package com.itways.assistant.journey.engine.model;

// DUPLICATE: com.itways.assistant.journey.domain.dto.EngineSearchResult in journey-service
// mirrors this record. Keep both in sync, or replace with a shared journey-model module.

public record EngineSearchResult(
        String answer,
        double similarity
) {
}
