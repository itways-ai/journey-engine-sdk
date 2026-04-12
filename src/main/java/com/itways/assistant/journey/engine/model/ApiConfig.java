package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiConfig {
    @Builder.Default
    private String method = "GET";
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();
    @Builder.Default
    private Map<String, String> queryParams = new HashMap<>();
    private Object body;
    private boolean allowMissingInputs = false;
    private boolean allowResubmit = false;
    @Builder.Default
    private String inputMode = "FREE_TEXT"; // FREE_TEXT, STRUCTURED, INTERACTIVE
    private Object fields;
    private Object rules;

    // Elite: Knowledge Retrieval
    private String query;
    private String indexName;
    private Integer limit;
    private Double threshold;

    // Elite: Human Approval
    private String stakeholders;
    private String instruction;
    private Integer timeout;

    // Elite: Logic Script
    private String code;
    private String language;

    // Elite: State Persistence
    private String variable;
    private String operation;
    private String source;

    // Elite: Timing
    private Integer duration;
    private String unit;

    // Elite: OCR / Document Insight
    private String strategy;
    private Boolean autoExtract;
    private String languageHint;
    private String pages;
}
