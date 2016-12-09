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
package org.apache.lucene.facet;

import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.ThreadInterruptedException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class ParallelDrillSideways extends DrillSideways {

  private final ExecutorService executor;

  public ParallelDrillSideways(final ExecutorService executor, final IndexSearcher searcher, final FacetsConfig config,
          final SortedSetDocValuesReaderState state) {
    super(searcher, config, state);
    this.executor = executor;
  }

  public ParallelDrillSideways(final ExecutorService executor, final IndexSearcher searcher, final FacetsConfig config,
          final TaxonomyReader taxoReader) {
    super(searcher, config, taxoReader);
    this.executor = executor;
  }

  public ParallelDrillSideways(final ExecutorService executor, final IndexSearcher searcher, final FacetsConfig config,
          final TaxonomyReader taxoReader, final SortedSetDocValuesReaderState state) {
    super(searcher, config, taxoReader, state);
    this.executor = executor;
  }

  private DrillDownQuery getDrillDownQuery(final Query baseQuery, final List<FacetField> dimPathList,
          final String excludedDimension) {
    final DrillDownQuery drillDownQuery = new DrillDownQuery(config, baseQuery);
    boolean excluded = false;
    for (FacetField dimPath : dimPathList) {
      final String dim = dimPath.dim;
      if (dim.equals(excludedDimension))
        excluded = true;
      else
        drillDownQuery.add(dim, dimPath.path);
    }
    return excluded ? drillDownQuery : null;
  }

  private static class CallableCollector implements Callable<CallableResult> {

    private final int pos;
    private final IndexSearcher searcher;
    private final Query query;
    private final CollectorManager<?, ?> collectorManager;

    private CallableCollector(int pos, IndexSearcher searcher, Query query, CollectorManager<?, ?> collectorManager) {
      this.pos = pos;
      this.searcher = searcher;
      this.query = query;
      this.collectorManager = collectorManager;
    }

    @Override
    public CallableResult call() throws Exception {
      return new CallableResult(pos, searcher.search(query, collectorManager));
    }
  }

  private static class CallableResult {

    private final int pos;
    private final Object result;

    private CallableResult(int pos, Object result) {
      this.pos = pos;
      this.result = result;
    }
  }

  public <R> Result<R> search(final DrillDownQuery drillDownQuery, final Collection<String> facets,
          final List<FacetField> dimPathList, final CollectorManager<?, R> hitCollectorManager) throws IOException {

    // Extracts the dimensions
    final Set<String> dimensions = new HashSet<>();
    dimPathList.forEach(p -> dimensions.add(p.dim));

    final List<CallableCollector> callableCollectors = new ArrayList<>(facets.size() + 1);

    // Add the main DrillDownQuery
    callableCollectors.add(new CallableCollector(0, searcher, drillDownQuery,
            new MultiCollectorManager(new FacetsCollectorManager(), hitCollectorManager)));

    final Query baseQuery = drillDownQuery.getBaseQuery();

    // Build & run the drillsideways DrillDownQueries
    int i = 0;
    for (String facet : facets) {
      final DrillDownQuery ddq = getDrillDownQuery(baseQuery, dimPathList, facet);
      if (ddq != null)
        callableCollectors.add(new CallableCollector(i, searcher, ddq, new FacetsCollectorManager()));
      i++;
    }

    final FacetsCollector mainFacetsCollector;
    final FacetsCollector[] facetsCollectors = new FacetsCollector[facets.size()];
    final R collectorResult;

    try {
      // Run the query pool
      final List<Future<CallableResult>> futures = executor.invokeAll(callableCollectors);

      // Extract the results
      final Object[] mainResults = (Object[]) futures.get(0).get().result;
      mainFacetsCollector = (FacetsCollector) mainResults[0];
      collectorResult = (R) mainResults[1];
      for (i = 1; i < futures.size(); i++) {
        final CallableResult result = futures.get(i).get();
        facetsCollectors[result.pos] = (FacetsCollector) result.result;
      }
      // Fill the null results with the mainFacetsCollector
      for (i = 0; i < facetsCollectors.length; i++)
        if (facetsCollectors[i] == null)
          facetsCollectors[i] = mainFacetsCollector;

    } catch (InterruptedException e) {
      throw new ThreadInterruptedException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    // build the facets and return the result
    return new Result<>(
            buildFacetsResult(mainFacetsCollector, facetsCollectors, facets.toArray(new String[facets.size()])), null,
            collectorResult);
  }

  public static class Result<R> extends DrillSidewaysResult {

    public final R collectorResult;

    /**
     * Sole constructor.
     *
     * @param facets
     * @param hits
     */
    public Result(Facets facets, TopDocs hits, R collectorResult) {
      super(facets, hits);
      this.collectorResult = collectorResult;
    }
  }
}
