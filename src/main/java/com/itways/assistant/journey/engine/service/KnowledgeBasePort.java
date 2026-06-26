package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.EngineSearchResult;

import java.util.List;

/**
 * Port interface for knowledge base vector search.
 * Implemented by journey-service using pgvector.
 * Kept here in the SDK so KnowledgeRetrievalStepHandler can depend on it
 * without knowing anything about JPA or PostgreSQL.
 */
public interface KnowledgeBasePort {

    /**
     * Searches for the most similar chunks to the given query vector.
     *
     * @param accountId  the account that owns the knowledge base
     * @param indexName  the named knowledge base to search (e.g. "products", "faq")
     * @param queryVector the embedding vector of the user's query
     * @param limit      maximum number of results to return
     * @return list of matching text chunks ordered by similarity (most similar first)
     */
    List<EngineSearchResult> search(String accountId,
                                    String indexName,
                                    float[] queryVector,
                                    int limit);
}
