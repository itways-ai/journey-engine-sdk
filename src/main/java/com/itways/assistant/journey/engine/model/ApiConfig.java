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
}
