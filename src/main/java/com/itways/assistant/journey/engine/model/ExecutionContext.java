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

    /**
     * Engine bookkeeping — nested-journey call stack, paused child context,
     * pending resume input. Deliberately separate from {@link #variables}: these
     * are not addressable by journey authors and must not reach run history, the
     * variable picker, the CODE_SCRIPT sandbox, or DATA_MAP's LLM prompt (all of
     * which serialise {@code variables} wholesale).
     */
    @Builder.Default
    private Map<String, Object> internal = new HashMap<>();

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public void addStepResult(int stepOrder, Object result) {
        stepResults.put(stepOrder, result);
    }

    public void setInternal(String key, Object value) {
        internal.put(key, value);
    }

    public Object getInternal(String key) {
        return internal.get(key);
    }

    public void removeInternal(String key) {
        internal.remove(key);
    }
}
