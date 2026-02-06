package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {
    private String status;
    private Object data;
    private String message;
    private Map<String, Object> metadata;
    private String actionTarget;

    public static StepResult success(Object data) {
        return StepResult.builder()
                .status("SUCCESS")
                .data(data)
                .build();
    }

    public static StepResult success(Object data, String message) {
        return StepResult.builder()
                .status("SUCCESS")
                .data(data)
                .message(message)
                .build();
    }

    public static StepResult error(String message) {
        return StepResult.builder()
                .status("ERROR")
                .message(message)
                .build();
    }

    public static StepResult waiting(String message, Map<String, Object> metadata) {
        return StepResult.builder()
                .status("WAITING")
                .message(message)
                .metadata(metadata)
                .build();
    }
}
