/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jbellis.jvector.graph;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import com.github.jbellis.jvector.util.Bits;
import com.github.jbellis.jvector.vector.VectorEncoding;
import com.github.jbellis.jvector.vector.VectorSimilarityFunction;

/**
 * Builder for Concurrent GraphIndex. See {@link GraphIndex} for a high level overview, and the
 * comments to `addGraphNode` for details on the concurrent building approach.
 *
 * @param <T> the type of vector
 */
public class GraphIndexBuilder<T> {
  /** Default number of maximum connections per node */
  public static final int DEFAULT_MAX_CONN = 16;

  /**
   * Default number of the size of the queue maintained while searching during a graph construction.
   */
  public static final int DEFAULT_BEAM_WIDTH = 100;

  private final int beamWidth;
  private final ThreadLocal<NeighborArray> naturalScratch;
  private final ThreadLocal<NeighborArray> concurrentScratch;

  private final VectorSimilarityFunction similarityFunction;
  private final float neighborOverflow;
  private final VectorEncoding vectorEncoding;
  private final ThreadLocal<RandomAccessVectorValues<T>> vectors;
  private final ThreadLocal<GraphSearcher> graphSearcher;
  private final ThreadLocal<NeighborQueue> beamCandidates;

  final OnHeapGraphIndex graph;
  private final ConcurrentSkipListSet<Integer> insertionsInProgress =
          new ConcurrentSkipListSet<>();

  // we need two sources of vectors in order to perform diversity check comparisons without
  // colliding
  private final ThreadLocal<RandomAccessVectorValues<T>> vectorsCopy;

  /**
   * Reads all the vectors from vector values, builds a graph connecting them by their dense
   * ordinals, using the given hyperparameter settings, and returns the resulting graph.
   *
   * @param vectorValues the vectors whose relations are represented by the graph - must provide a
   *     different view over those vectors than the one used to add via addGraphNode.
   * @param M – the maximum number of connections a node can have
   * @param beamWidth the size of the beam search to use when finding nearest neighbors.
   * @param neighborOverflow the ratio of extra neighbors to allow temporarily when inserting a
   *     node. larger values will build more efficiently, but use more memory.
   * @param alpha how aggressive pruning diverse neighbors should be.  Set alpha &gt; 1.0 to
   *        allow longer edges.  If alpha = 1.0 then the equivalent of the lowest level of
   *        an HNSW graph will be created, which is usually not what you want.
   */
  public GraphIndexBuilder(
          RandomAccessVectorValues<T> vectorValues,
          VectorEncoding vectorEncoding,
          VectorSimilarityFunction similarityFunction,
          int M,
          int beamWidth,
          float neighborOverflow,
          float alpha) {
    this.vectors = ThreadLocal.withInitial(vectorValues::copy);
    this.vectorsCopy = ThreadLocal.withInitial(vectorValues::copy);
    this.vectorEncoding = Objects.requireNonNull(vectorEncoding);
    this.similarityFunction = Objects.requireNonNull(similarityFunction);
    this.neighborOverflow = neighborOverflow;
    if (M <= 0) {
      throw new IllegalArgumentException("maxConn must be positive");
    }
    if (beamWidth <= 0) {
      throw new IllegalArgumentException("beamWidth must be positive");
    }
    this.beamWidth = beamWidth;

    NeighborSimilarity similarity =
            new NeighborSimilarity() {
              @Override
              public float score(int node1, int node2) {
                return scoreBetween(
                        vectors.get().vectorValue(node1), vectorsCopy.get().vectorValue(node2));
              }

              @Override
              public ScoreFunction scoreProvider(int node1) {
                T v1;
                v1 = vectors.get().vectorValue(node1);
                return node2 -> {
                  return scoreBetween(v1, vectorsCopy.get().vectorValue(node2));
                };
              }
            };
    this.graph =
            new OnHeapGraphIndex(
                    M, (node, m) -> new ConcurrentNeighborSet(node, m, similarity, alpha));
    this.graphSearcher =
            ThreadLocal.withInitial(
                    () -> new GraphSearcher.Builder(graph.getView()).withConcurrentUpdates().build());
    // in scratch we store candidates in reverse order: worse candidates are first
    this.naturalScratch =
            ThreadLocal.withInitial(() -> new NeighborArray(Math.max(beamWidth, M + 1), true));
    this.concurrentScratch =
            ThreadLocal.withInitial(() -> new NeighborArray(Math.max(beamWidth, M + 1), true));
    this.beamCandidates =
            ThreadLocal.withInitial(() -> new NeighborQueue(beamWidth, false));
  }

  public OnHeapGraphIndex build() {
    IntStream.range(0, vectors.get().size()).parallel().forEach(i -> {
      addGraphNode(i, vectors.get());
    });
    complete();
    return graph;
  }

  public void complete() {
    IntStream.range(0, graph.size()).parallel().forEach(i -> {
      graph.getNeighbors(i).cleanup();
    });
  }

  /**
   * Adds a node to the graph, with the vector at the same ordinal in the given provider.
   *
   * <p>See {@link #addGraphNode(int, Object)} for more details.
   */
  public long addGraphNode(int node, RandomAccessVectorValues<T> values) {
    return addGraphNode(node, values.vectorValue(node));
  }

  public OnHeapGraphIndex getGraph() {
    return graph;
  }

  /** Number of inserts in progress, across all threads. */
  public int insertsInProgress() {
    return insertionsInProgress.size();
  }

  /**
   * Inserts a doc with vector value to the graph.
   *
   * <p>To allow correctness under concurrency, we track in-progress updates in a
   * ConcurrentSkipListSet. After adding ourselves, we take a snapshot of this set, and consider all
   * other in-progress updates as neighbor candidates.
   *
   * @param node the node ID to add
   * @param value the vector value to add
   * @return an estimate of the number of extra bytes used by the graph after adding the given node
   */
  public long addGraphNode(int node, T value) {
    // do this before adding to in-progress, so a concurrent writer checking
    // the in-progress set doesn't have to worry about uninitialized neighbor sets
    graph.addNode(node);

    insertionsInProgress.add(node);
    ConcurrentSkipListSet<Integer> inProgressBefore = insertionsInProgress.clone();
    try {
      // find ANN of the new node by searching the graph
      int ep = graph.entry();
      var gs = new GraphSearcher.Builder(graph.getView()).withConcurrentUpdates().build(); // TODO cache these (but not with the same View)
      NeighborSimilarity.ScoreFunction scoreFunction = (i) -> scoreBetween(
              vectors.get().vectorValue(i), value);

      var bits = new ExcludingBits(node);
      NeighborQueue candidates = beamCandidates.get();
      candidates.clear();
      // find best "natural" candidates with a beam search
      gs.searchInternal(scoreFunction, candidates, beamWidth, ep, bits, Integer.MAX_VALUE);

      // Update neighbors with these candidates.
      var natural = getNaturalCandidates(candidates);
      var concurrent = getConcurrentCandidates(node, inProgressBefore);
      updateNeighbors(node, natural, concurrent);
      graph.markComplete(node);
    } finally {
      insertionsInProgress.remove(node);
    }

    return graph.ramBytesUsedOneNode(0);
  }

  private void updateNeighbors(int node, NeighborArray natural, NeighborArray concurrent) {
    ConcurrentNeighborSet neighbors = graph.getNeighbors(node);
    neighbors.insertDiverse(natural, concurrent);
    neighbors.backlink(graph::getNeighbors, neighborOverflow);
  }

  private NeighborArray getNaturalCandidates(NeighborQueue candidates) {
    NeighborArray scratch = this.naturalScratch.get();
    scratch.clear();
    int candidateCount = candidates.size();
    for (int i = candidateCount - 1; i >= 0; i--) {
      float score = candidates.topScore();
      int node = candidates.pop();
      scratch.node()[i] = node;
      scratch.score()[i] = score;
      scratch.size = candidateCount;
    }
    return scratch;
  }

  private NeighborArray getConcurrentCandidates(int newNode, Set<Integer> inProgress) {
    NeighborArray scratch = this.concurrentScratch.get();
    scratch.clear();
    for (var n : inProgress) {
      if (n != newNode) {
        scratch.insertSorted(
                n,
                scoreBetween(vectors.get().vectorValue(newNode), vectorsCopy.get().vectorValue(n)));
      }
    }
    return scratch;
  }

  protected float scoreBetween(T v1, T v2) {
    return scoreBetween(vectorEncoding, similarityFunction, v1, v2);
  }

  static <T> float scoreBetween(
          VectorEncoding encoding, VectorSimilarityFunction similarityFunction, T v1, T v2) {
    switch (encoding) {
      case BYTE:
        return similarityFunction.compare((byte[]) v1, (byte[]) v2);
      case FLOAT32:
        return similarityFunction.compare((float[]) v1, (float[]) v2);
      default:
        throw new IllegalArgumentException();
    }
  }

  private static class ExcludingBits implements Bits {
    private final int excluded;

    public ExcludingBits(int excluded) {
        this.excluded = excluded;
    }

    @Override
    public boolean get(int index) {
      return index != excluded;
    }

    @Override
    public int length() {
      throw new UnsupportedOperationException();
    }
  }
}
