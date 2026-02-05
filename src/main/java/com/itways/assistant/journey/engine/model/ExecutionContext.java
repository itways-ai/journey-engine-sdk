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
public class ExecutionContext {
    private String executionId;
    private Long journeyId;
    private String accountId;
    private int currentStepIndex;
    private ExecutionStatus status;

    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    @Builder.Default
    private Map<Integer, Object> stepResults = new HashMap<>();

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public void addStepResult(int stepOrder, Object result) {
        stepResults.put(stepOrder, result);
    }
}
