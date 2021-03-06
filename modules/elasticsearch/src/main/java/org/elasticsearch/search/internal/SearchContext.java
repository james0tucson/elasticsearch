/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.analysis.AnalysisService;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.cache.filter.FilterCache;
import org.elasticsearch.index.cache.id.IdCache;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.search.nested.BlockJoinQuery;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.facet.SearchContextFacets;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.script.ScriptFieldsContext;
import org.elasticsearch.search.highlight.SearchContextHighlight;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.search.query.QuerySearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class SearchContext implements Releasable {

    private static ThreadLocal<SearchContext> current = new ThreadLocal<SearchContext>();

    public static void setCurrent(SearchContext value) {
        current.set(value);
    }

    public static void removeCurrent() {
        current.remove();
    }

    public static SearchContext current() {
        return current.get();
    }

    private final long id;

    private final SearchShardTarget shardTarget;

    private SearchType searchType;

    private final int numberOfShards;

    private final Engine.Searcher engineSearcher;

    private final ScriptService scriptService;

    private final IndexService indexService;

    private final ContextIndexSearcher searcher;

    private final DfsSearchResult dfsResult;

    private final QuerySearchResult queryResult;

    private final FetchSearchResult fetchResult;

    private final long nowInMillis;

    private final TimeValue timeout;

    private float queryBoost = 1.0f;


    private Scroll scroll;

    private boolean explain;

    private boolean version = false; // by default, we don't return versions

    private List<String> fieldNames;

    private int from = -1;

    private int size = -1;

    private String[] types;

    private Sort sort;

    private Float minimumScore;

    private boolean trackScores = false; // when sorting, track scores as well...

    private ParsedQuery originalQuery;

    private Query query;

    private Filter filter;

    private Filter aliasFilter;

    private int[] docIdsToLoad;

    private int docsIdsToLoadFrom;

    private int docsIdsToLoadSize;

    private SearchContextFacets facets;

    private SearchContextHighlight highlight;

    private ScriptFieldsContext scriptFields;

    private SearchLookup searchLookup;

    private boolean queryRewritten;

    private volatile long keepAlive;

    private volatile long lastAccessTime;

    private List<ScopePhase> scopePhases = null;

    private Map<String, BlockJoinQuery> nestedQueries;

    public SearchContext(long id, SearchShardTarget shardTarget, SearchType searchType, int numberOfShards, long nowInMillis, TimeValue timeout,
                         String[] types, Engine.Searcher engineSearcher, IndexService indexService, ScriptService scriptService) {
        this.id = id;
        this.nowInMillis = nowInMillis;
        this.searchType = searchType;
        this.shardTarget = shardTarget;
        this.numberOfShards = numberOfShards;
        this.timeout = timeout;
        this.types = types;
        this.engineSearcher = engineSearcher;
        this.scriptService = scriptService;
        this.dfsResult = new DfsSearchResult(id, shardTarget);
        this.queryResult = new QuerySearchResult(id, shardTarget);
        this.fetchResult = new FetchSearchResult(id, shardTarget);
        this.indexService = indexService;

        this.searcher = new ContextIndexSearcher(this, engineSearcher);
    }

    @Override public boolean release() throws ElasticSearchException {
        // clear and scope phase we  have
        if (scopePhases != null) {
            for (ScopePhase scopePhase : scopePhases) {
                scopePhase.clear();
            }
        }
        // we should close this searcher, since its a new one we create each time, and we use the IndexReader
        try {
            searcher.close();
        } catch (Exception e) {
            // ignore any exception here
        }
        engineSearcher.release();
        return true;
    }

    public long id() {
        return this.id;
    }

    public SearchType searchType() {
        return this.searchType;
    }

    public SearchContext searchType(SearchType searchType) {
        this.searchType = searchType;
        return this;
    }

    public SearchShardTarget shardTarget() {
        return this.shardTarget;
    }

    public int numberOfShards() {
        return this.numberOfShards;
    }

    public boolean hasTypes() {
        return types != null && types.length > 0;
    }

    public String[] types() {
        return types;
    }

    public float queryBoost() {
        return queryBoost;
    }

    public SearchContext queryBoost(float queryBoost) {
        this.queryBoost = queryBoost;
        return this;
    }

    public long nowInMillis() {
        return nowInMillis;
    }

    public Scroll scroll() {
        return this.scroll;
    }

    public SearchContext scroll(Scroll scroll) {
        this.scroll = scroll;
        return this;
    }

    public SearchContextFacets facets() {
        return facets;
    }

    public SearchContext facets(SearchContextFacets facets) {
        this.facets = facets;
        return this;
    }

    public SearchContextHighlight highlight() {
        return highlight;
    }

    public void highlight(SearchContextHighlight highlight) {
        this.highlight = highlight;
    }

    public boolean hasScriptFields() {
        return scriptFields != null;
    }

    public ScriptFieldsContext scriptFields() {
        if (scriptFields == null) {
            scriptFields = new ScriptFieldsContext();
        }
        return this.scriptFields;
    }

    public ContextIndexSearcher searcher() {
        return this.searcher;
    }

    public MapperService mapperService() {
        return indexService.mapperService();
    }

    public AnalysisService analysisService() {
        return indexService.analysisService();
    }

    public IndexQueryParserService queryParserService() {
        return indexService.queryParserService();
    }

    public SimilarityService similarityService() {
        return indexService.similarityService();
    }

    public ScriptService scriptService() {
        return scriptService;
    }

    public FilterCache filterCache() {
        return indexService.cache().filter();
    }

    public FieldDataCache fieldDataCache() {
        return indexService.cache().fieldData();
    }

    public IdCache idCache() {
        return indexService.cache().idCache();
    }

    public TimeValue timeout() {
        return timeout;
    }

    public SearchContext minimumScore(float minimumScore) {
        this.minimumScore = minimumScore;
        return this;
    }

    public Float minimumScore() {
        return this.minimumScore;
    }

    public SearchContext sort(Sort sort) {
        this.sort = sort;
        return this;
    }

    public Sort sort() {
        return this.sort;
    }

    public SearchContext trackScores(boolean trackScores) {
        this.trackScores = trackScores;
        return this;
    }

    public boolean trackScores() {
        return this.trackScores;
    }

    public SearchContext parsedFilter(Filter filter) {
        this.filter = filter;
        return this;
    }

    public Filter parsedFilter() {
        return this.filter;
    }

    public SearchContext aliasFilter(Filter aliasFilter) {
        this.aliasFilter = aliasFilter;
        return this;
    }

    public Filter aliasFilter() {
        return aliasFilter;
    }

    public SearchContext parsedQuery(ParsedQuery query) {
        queryRewritten = false;
        this.originalQuery = query;
        this.query = query.query();
        return this;
    }

    public ParsedQuery parsedQuery() {
        return this.originalQuery;
    }

    /**
     * The query to execute, might be rewritten.
     */
    public Query query() {
        return this.query;
    }

    /**
     * Has the query been rewritten already?
     */
    public boolean queryRewritten() {
        return queryRewritten;
    }

    /**
     * Rewrites the query and updates it. Only happens once.
     */
    public SearchContext updateRewriteQuery(Query rewriteQuery) {
        query = rewriteQuery;
        queryRewritten = true;
        return this;
    }

    public int from() {
        return from;
    }

    public SearchContext from(int from) {
        this.from = from;
        return this;
    }

    public int size() {
        return size;
    }

    public SearchContext size(int size) {
        this.size = size;
        return this;
    }

    public boolean hasFieldNames() {
        return fieldNames != null;
    }

    public List<String> fieldNames() {
        if (fieldNames == null) {
            fieldNames = Lists.newArrayList();
        }
        return fieldNames;
    }

    public void emptyFieldNames() {
        this.fieldNames = ImmutableList.of();
    }

    public boolean explain() {
        return explain;
    }

    public void explain(boolean explain) {
        this.explain = explain;
    }

    public boolean version() {
        return version;
    }

    public void version(boolean version) {
        this.version = version;
    }

    public int[] docIdsToLoad() {
        return docIdsToLoad;
    }

    public int docIdsToLoadFrom() {
        return docsIdsToLoadFrom;
    }

    public int docIdsToLoadSize() {
        return docsIdsToLoadSize;
    }

    public SearchContext docIdsToLoad(int[] docIdsToLoad, int docsIdsToLoadFrom, int docsIdsToLoadSize) {
        this.docIdsToLoad = docIdsToLoad;
        this.docsIdsToLoadFrom = docsIdsToLoadFrom;
        this.docsIdsToLoadSize = docsIdsToLoadSize;
        return this;
    }

    public void accessed(long accessTime) {
        this.lastAccessTime = accessTime;
    }

    public long lastAccessTime() {
        return this.lastAccessTime;
    }

    public long keepAlive() {
        return this.keepAlive;
    }

    public void keepAlive(long keepAlive) {
        this.keepAlive = keepAlive;
    }

    public SearchLookup lookup() {
        if (searchLookup == null) {
            searchLookup = new SearchLookup(mapperService(), fieldDataCache());
        }
        return searchLookup;
    }

    public DfsSearchResult dfsResult() {
        return dfsResult;
    }

    public QuerySearchResult queryResult() {
        return queryResult;
    }

    public FetchSearchResult fetchResult() {
        return fetchResult;
    }

    public List<ScopePhase> scopePhases() {
        return this.scopePhases;
    }

    public void addScopePhase(ScopePhase scopePhase) {
        if (this.scopePhases == null) {
            this.scopePhases = new ArrayList<ScopePhase>();
        }
        this.scopePhases.add(scopePhase);
    }

    public Map<String, BlockJoinQuery> nestedQueries() {
        return this.nestedQueries;
    }

    public void addNestedQuery(String scope, BlockJoinQuery query) {
        if (nestedQueries == null) {
            nestedQueries = new HashMap<String, BlockJoinQuery>();
        }
        nestedQueries.put(scope, query);
    }
}
