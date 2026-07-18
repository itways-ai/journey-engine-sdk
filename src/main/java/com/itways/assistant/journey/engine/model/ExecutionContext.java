package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContext {
    /** Permanent UUID for this run (also used as WAITING resume key). */
    private String executionId;
    /** Immediate parent run UUID when this run was started via TRIGGER_JOURNEY. */
    private String parentExecutionId;
    /** Top-level run UUID for the nesting tree (equals executionId for top-level runs). */
    private String rootExecutionId;
    private Long journeyId;
    private String accountId;
    private int currentStepIndex;
    private ExecutionStatus status;
    private Date startedAt;

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
