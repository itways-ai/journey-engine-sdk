package com.itways.assistant.journey.engine.model;

// DUPLICATE: com.itways.assistant.journey.domain.dto.EngineSearchResult in journey-service
// mirrors this record. Keep both in sync, or replace with a shared journey-model module.

/**
 * One knowledge base hit.
 *
 * @param answer     the stored answer, verbatim
 * @param similarity cosine similarity to the query vector, 0..1
 * @param locale     the language the answer is stored in, or null when the
 *                   chunk was never tagged. Null is meaningfully different from
 *                   a tagged locale: an untagged corpus is assumed usable in any
 *                   language and is left alone, while a chunk tagged in the
 *                   wrong language is a candidate for translation on output
 */
public record EngineSearchResult(
        String answer,
        double similarity,
        String locale
) {
}
