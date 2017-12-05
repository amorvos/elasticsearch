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


import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Counter;
import org.elasticsearch.action.search.SearchTask;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.AbstractRefCounted;
import org.elasticsearch.common.util.concurrent.RefCounted;
import org.elasticsearch.common.util.iterable.Iterables;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.lookup.SearchLookup;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class encapsulates the state needed to execute a search. It holds a reference to the
 * shards point in time snapshot (IndexReader / ContextIndexSearcher) and allows passing on
 * state from one query / fetch phase to another.
 *
 * This class also implements {@link RefCounted} since in some situations like in org.elasticsearch.search.SearchService
 * a SearchContext can be closed concurrently due to independent events ie. when an index gets removed. To prevent accessing closed
 * IndexReader / IndexSearcher instances the SearchContext can be guarded by a reference count and fail if it's been closed by
 * an external event.
 */
// For reference why we use RefCounted here see #20095
public abstract class SearchContext extends AbstractRefCounted implements Releasable {

    public static final int DEFAULT_TERMINATE_AFTER = 0;
    private Map<Lifetime, List<Releasable>> clearables = null;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected SearchContext() {
        super("search_context");
    }

    public abstract void setTask(SearchTask task);

    public abstract SearchTask getTask();

    public abstract boolean isCancelled();

    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) { // prevent double closing
            decRef();
        }
    }

    @Override
    protected final void closeInternal() {
        try {
            clearReleasables(Lifetime.CONTEXT);
        } finally {
            doClose();
        }
    }

    @Override
    protected void alreadyClosed() {
        throw new IllegalStateException("search context is already closed can't increment refCount current count [" + refCount() + "]");
    }

    protected abstract void doClose();

    /**
     * Should be called before executing the main query and after all other parameters have been set.
     * @param rewrite if the set query should be rewritten against the searcher returned from {@link #searcher()}
     */
    public abstract void preProcess(boolean rewrite);

    /** Automatically apply all required filters to the given query such as
     *  alias filters, types filters, etc. */
    public abstract Query buildFilteredQuery(Query query);

    public abstract long id();

    public abstract String source();

    public abstract SearchType searchType();

    public abstract SearchShardTarget shardTarget();

    public abstract int numberOfShards();

    public abstract float queryBoost();

    public abstract long getOriginNanoTime();


    /**
     * A shortcut function to see whether there is a fetchSourceContext and it says the source is requested.
     */
    public abstract boolean sourceRequested();

    public abstract boolean hasFetchSourceContext();

    public abstract FetchSourceContext fetchSourceContext();

    public abstract SearchContext fetchSourceContext(FetchSourceContext fetchSourceContext);

    public abstract ContextIndexSearcher searcher();

    public abstract IndexShard indexShard();

    public abstract MapperService mapperService();

    public abstract BigArrays bigArrays();

    public abstract BitsetFilterCache bitsetFilterCache();

    public abstract IndexFieldDataService fieldData();

    public abstract TimeValue timeout();

    public abstract void timeout(TimeValue timeout);

    public abstract int terminateAfter();

    public abstract void terminateAfter(int terminateAfter);

    /**
     * Indicates if the current index should perform frequent low level search cancellation check.
     *
     * Enabling low-level checks will make long running searches to react to the cancellation request faster. However,
     * since it will produce more cancellation checks it might slow the search performance down.
     */
    public abstract boolean lowLevelCancellation();

    public abstract SearchContext minimumScore(float minimumScore);

    public abstract Float minimumScore();

    public abstract SearchContext trackScores(boolean trackScores);

    public abstract boolean trackScores();

    public abstract SearchContext searchAfter(FieldDoc searchAfter);

    public abstract FieldDoc searchAfter();


    public abstract Query aliasFilter();

    /**
     * The query to execute, might be rewritten.
     */
    public abstract Query query();

    public abstract int from();

    public abstract SearchContext from(int from);

    public abstract int size();

    public abstract SearchContext size(int size);

    public abstract boolean hasStoredFields();

    public abstract boolean hasStoredFieldsContext();

    /**
     * A shortcut function to see whether there is a storedFieldsContext and it says the fields are requested.
     */
    public abstract boolean storedFieldsRequested();

    public abstract boolean explain();

    public abstract void explain(boolean explain);

    @Nullable
    public abstract List<String> groupStats();

    public abstract void groupStats(List<String> groupStats);

    public abstract boolean version();

    public abstract void version(boolean version);

    public abstract int[] docIdsToLoad();

    public abstract int docIdsToLoadFrom();

    public abstract int docIdsToLoadSize();

    public abstract SearchContext docIdsToLoad(int[] docIdsToLoad, int docsIdsToLoadFrom, int docsIdsToLoadSize);

    public abstract void accessed(long accessTime);

    public abstract long lastAccessTime();

    public abstract long keepAlive();

    public abstract void keepAlive(long keepAlive);

    public SearchLookup lookup() {
        return getQueryShardContext().lookup();
    }

    public abstract DfsSearchResult dfsResult();

    /**
     * Schedule the release of a resource. The time when {@link Releasable#close()} will be called on this object
     * is function of the provided {@link Lifetime}.
     */
    public void addReleasable(Releasable releasable, Lifetime lifetime) {
        if (clearables == null) {
            clearables = new EnumMap<>(Lifetime.class);
        }
        List<Releasable> releasables = clearables.get(lifetime);
        if (releasables == null) {
            releasables = new ArrayList<>();
            clearables.put(lifetime, releasables);
        }
        releasables.add(releasable);
    }

    public void clearReleasables(Lifetime lifetime) {
        if (clearables != null) {
            List<List<Releasable>>releasables = new ArrayList<>();
            for (Lifetime lc : Lifetime.values()) {
                if (lc.compareTo(lifetime) > 0) {
                    break;
                }
                List<Releasable> remove = clearables.remove(lc);
                if (remove != null) {
                    releasables.add(remove);
                }
            }
            Releasables.close(Iterables.flatten(releasables));
        }
    }

    /**
     * @return true if the request contains only suggest
     */
    public final boolean hasOnlySuggest() {
        return false;
    }

    /**
     * Looks up the given field, but does not restrict to fields in the types set on this context.
     */
    public abstract MappedFieldType smartNameFieldType(String name);

    public abstract ObjectMapper getObjectMapper(String name);

    public abstract Counter timeEstimateCounter();

    /** Return a view of the additional query collectors that should be run for this context. */
    public abstract Map<Class<?>, Collector> queryCollectors();

    /**
     * The life time of an object that is used during search execution.
     */
    public enum Lifetime {
        /**
         * This life time is for objects that only live during collection time.
         */
        COLLECTION,
        /**
         * This life time is for objects that need to live until the end of the current search phase.
         */
        PHASE,
        /**
         * This life time is for objects that need to live until the search context they are attached to is destroyed.
         */
        CONTEXT
    }

    public abstract QueryShardContext getQueryShardContext();

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder().append(shardTarget());
        if (searchType() != SearchType.DEFAULT) {
            result.append("searchType=[").append(searchType()).append("]");
        }
        result.append(" query=[").append(query()).append("]");
        return result.toString();
    }
}
