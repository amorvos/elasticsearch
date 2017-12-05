/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.internal;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryCache;
import org.apache.lucene.search.QueryCachingPolicy;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.index.engine.Engine;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Context-aware extension of {@link IndexSearcher}.
 */
public class ContextIndexSearcher extends IndexSearcher implements Releasable {

    /** The wrapped {@link IndexSearcher}. The reason why we sometimes prefer delegating to this searcher instead of <tt>super</tt> is that
     *  this instance may have more assertions, for example if it comes from MockInternalEngine which wraps the IndexSearcher into an
     *  AssertingIndexSearcher. */
    private final IndexSearcher in;

    private final Engine.Searcher engineSearcher;

    private Runnable checkCancelled;

    public ContextIndexSearcher(Engine.Searcher searcher,
            QueryCache queryCache, QueryCachingPolicy queryCachingPolicy) {
        super(searcher.reader());
        in = searcher.searcher();
        engineSearcher = searcher;
        setSimilarity(searcher.searcher().getSimilarity(true));
        setQueryCache(queryCache);
        setQueryCachingPolicy(queryCachingPolicy);
    }

    @Override
    public void close() {
    }

    /**
     * Set a {@link Runnable} that will be run on a regular basis while
     * collecting documents.
     */
    public void setCheckCancelled(Runnable checkCancelled) {
        this.checkCancelled = checkCancelled;
    }

    @Override
    public Query rewrite(Query original) throws IOException {
        return in.rewrite(original);
    }

    @Override
    public Weight createNormalizedWeight(Query query, boolean needsScores) throws IOException {
        return in.createNormalizedWeight(query, needsScores);
    }

    @Override
    public Weight createWeight(Query query, boolean needsScores) throws IOException {
        // needs to be 'super', not 'in' in order to use aggregated DFS
        return super.createWeight(query, needsScores);
    }

    @Override
    protected void search(List<LeafReaderContext> leaves, Weight weight, Collector collector) throws IOException {
        final Weight cancellableWeight;
        if (checkCancelled != null) {
            cancellableWeight = new Weight(weight.getQuery()) {

                @Override
                public void extractTerms(Set<Term> terms) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Scorer scorer(LeafReaderContext context) throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public float getValueForNormalization() throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void normalize(float norm, float boost) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public BulkScorer bulkScorer(LeafReaderContext context) throws IOException {
                    BulkScorer in = weight.bulkScorer(context);
                    if (in != null) {
                        return new CancellableBulkScorer(in, checkCancelled);
                    } else {
                        return null;
                    }
                }
            };
        } else {
            cancellableWeight = weight;
        }
        super.search(leaves, cancellableWeight, collector);
    }

    @Override
    public Explanation explain(Query query, int doc) throws IOException {
        return in.explain(query, doc);
    }

    public DirectoryReader getDirectoryReader() {
        return engineSearcher.getDirectoryReader();
    }
}
