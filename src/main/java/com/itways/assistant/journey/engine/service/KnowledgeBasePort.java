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

    String DEFAULT_EMBEDDING_MODEL = "granite-embedding:278m";
    int DEFAULT_EMBEDDING_DIMENSION = 768;

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
                                    int limit,
                                    String embeddingModel,
                                    int embeddingDimension);

    default List<EngineSearchResult> search(String accountId,
                                            String indexName,
                                            float[] queryVector,
                                            int limit) {
        return search(
                accountId,
                indexName,
                queryVector,
                limit,
                DEFAULT_EMBEDDING_MODEL,
                DEFAULT_EMBEDDING_DIMENSION);
    }

    default EmbeddingMetadata getEmbeddingMetadata(String accountId, String indexName) {
        return new EmbeddingMetadata(DEFAULT_EMBEDDING_MODEL, DEFAULT_EMBEDDING_DIMENSION);
    }

    record EmbeddingMetadata(String embeddingModel, int embeddingDimension) {}
}
