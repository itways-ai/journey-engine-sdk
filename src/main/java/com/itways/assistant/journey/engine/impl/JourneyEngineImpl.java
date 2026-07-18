package com.itways.assistant.journey.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.handler.TriggerJourneyStepHandler;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyRunLifecyclePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.JourneyStepGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class JourneyEngineImpl implements JourneyEngine {

    private final StepHandlerRegistry handlerRegistry;
    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final ObjectMapper objectMapper;
    private final List<JourneyRunLifecyclePort> lifecyclePorts;

    public JourneyEngineImpl(StepHandlerRegistry handlerRegistry, EngineUtils engineUtils,
                             VariableContext variableContext, ObjectMapper objectMapper,
                             List<JourneyRunLifecyclePort> lifecyclePorts) {
        this.handlerRegistry = handlerRegistry;
        this.engineUtils = engineUtils;
        this.variableContext = variableContext;
        this.objectMapper = objectMapper;
        this.lifecyclePorts = lifecyclePorts != null ? lifecyclePorts : List.of();
    }

    @Override
    public Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams) {
        Map<String, Object> params = initialParams != null ? new HashMap<>(initialParams) : new HashMap<>();

        String executionId = UUID.randomUUID().toString();
        String parentExecutionId = stringOrNull(params.remove(TriggerJourneyStepHandler.PARENT_EXECUTION_ID));
        String rootExecutionId = stringOrNull(params.remove(TriggerJourneyStepHandler.ROOT_EXECUTION_ID));
        if (rootExecutionId == null || rootExecutionId.isBlank()) {
            rootExecutionId = executionId;
        }

        ExecutionContext context = ExecutionContext.builder()
                .executionId(executionId)
                .parentExecutionId(parentExecutionId)
                .rootExecutionId(rootExecutionId)
                .journeyId(journey.getId())
                .accountId(accountId)
                .currentStepIndex(-1)
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>())
                .startedAt(new Date())
                .build();

        if (isStructuredVariableMap(params)) {
            context.setVariables(shallowCopyVariables(params));
            variableContext.ensureStructure(context);
        } else {
            variableContext.mergeInputs(context, params);
        }

        // Seed call stack so TRIGGER_JOURNEY can detect cycles without needing the parent Journey object
        if (!context.getVariables().containsKey(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK)
                && journey.getTriggerIntent() != null
                && !journey.getTriggerIntent().isBlank()) {
            context.setVariable(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK,
                    new ArrayList<>(List.of(journey.getTriggerIntent())));
        }

        // Durable RUNNING row before any business step; failure aborts the run.
        emitLifecycle(buildLifecycleEvent(journey, context, JourneyRunLifecycleEvent.STATUS_RUNNING, null, null));

        return finalizeResult(journey, context, execute(journey, context));
    }

    private boolean isStructuredVariableMap(Map<String, Object> params) {
        return params.get("inputs") instanceof Map && params.get("steps") instanceof Map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shallowCopyVariables(Map<String, Object> source) {
        return (Map<String, Object>) deepCopyValue(source);
    }

    @SuppressWarnings("unchecked")
    private Object deepCopyValue(Object val) {
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                copy.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return val;
    }

    @Override
    public Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams) {
        Map<String, Object> pending = inputParams != null ? new HashMap<>(inputParams) : new HashMap<>();
        variableContext.mergeInputs(context, pending);
        context.setVariable(TriggerJourneyStepHandler.PENDING_RESUME_INPUT, pending);
        context.setStatus(ExecutionStatus.RUNNING);
        // Ensure lineage fields are present after deserialization.
        if (context.getRootExecutionId() == null || context.getRootExecutionId().isBlank()) {
            context.setRootExecutionId(context.getExecutionId());
        }
        Map<String, Object> result = execute(journey, context);
        context.getVariables().remove(TriggerJourneyStepHandler.PENDING_RESUME_INPUT);
        return finalizeResult(journey, context, result);
    }

    private Map<String, Object> execute(Journey journey, ExecutionContext context) {

        log.debug("START JOURNEY EXECUTION >> journeyId={} accountId={} executionId={}",
                journey.getId(), context.getAccountId(), context.getExecutionId());
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        List<JourneyStep> steps = journey.getSteps();
        if (steps == null || steps.isEmpty()) {
            log.warn("Journey '{}' has no steps defined.", journey.getTriggerIntent());
            result.put("status", "FINISHED");
            result.put("message", "This journey has no steps configured.");
            result.put("context", context);
            result.put("stepResults", stepResults);
            return result;
        }

        List<JourneyStep> sortedSteps;
        try {
            sortedSteps = JourneyStepGraph.sortSteps(steps);
        } catch (IllegalStateException e) {
            log.error("Journey '{}' has a cyclic step graph - aborting execution: {}",
                    journey.getTriggerIntent(), e.getMessage());
            context.setStatus(ExecutionStatus.ERROR);
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
            result.put("context", context);
            result.put("stepResults", stepResults);
            return result;
        }

        int i = 0;
        while (i < sortedSteps.size() && context.getStatus() == ExecutionStatus.RUNNING) {
            JourneyStep step = sortedSteps.get(i);
            int stepOrder = step.getStepOrder();
            int startIndex = context.getCurrentStepIndex();

            // Skip already completed steps (Resumption logic)
            if (stepOrder <= startIndex) {
                i++;
                continue;
            }

            if (!isEligible(step, context, sortedSteps)) {
                i++;
                continue;
            }

            StepHandler handler = handlerRegistry.getHandler(step.getActionType());
            if (handler == null) {
                stepResults.add(createErrorResult(step, "No handler found for type: " + step.getActionType()));
                if (step.isContinueOnError()) {
                    context.addStepResult(stepOrder, "FAILED");
                    context.setCurrentStepIndex(stepOrder);
                } else {
                    context.setStatus(ExecutionStatus.ERROR);
                    break;
                }
                i++;
                continue;
            }

            long stepStart = System.currentTimeMillis();
            StepResult stepResult;
            try {
                stepResult = handler.execute(step, context);
            } catch (Exception e) {
                log.error("Unhandled exception in handler for type: {}", step.getActionType(), e);
                stepResult = StepResult.error("Internal Handler Error: " + e.getMessage());
            }
            long stepEnd = System.currentTimeMillis();

            Map<String, Object> metadata = stepResult.getMetadata();
            appendNestedStepResults(stepResults, metadata);
            boolean skipSelfView = metadata != null
                    && Boolean.TRUE.equals(metadata.get(TriggerJourneyStepHandler.META_SKIP_SELF_VIEW));

            Map<String, Object> viewResult = new HashMap<>();
            viewResult.put("type", step.getActionType());
            viewResult.put("id", step.getId());
            viewResult.put("stepName", step.getStepName());
            viewResult.put("clientVisible", step.isClientVisible());
            viewResult.put("status", stepResult.getStatus());
            viewResult.put("startedAt", new Date(stepStart));
            viewResult.put("completedAt", new Date(stepEnd));
            viewResult.put("durationMs", stepEnd - stepStart);

            if ("SUCCESS".equals(stepResult.getStatus())) {
                viewResult.put("message", stepResult.getMessage());
                if (stepResult.getData() != null) {
                    viewResult.put("data", stepResult.getData());
                    try {
                        viewResult.put("outputPayload", objectMapper.writeValueAsString(stepResult.getData()));
                    } catch (Exception ignored) {
                    }
                }
                mergeStepMetadata(viewResult, metadata);
                stepResults.add(viewResult);
                context.setCurrentStepIndex(stepOrder);
                // Handlers store output via VariableContext.storeOutput; ensure stepResults map is set
                if (context.getStepResults().get(stepOrder) == null && stepResult.getData() != null) {
                    context.addStepResult(stepOrder, stepResult.getData());
                }
                i++;
            } else if ("WAITING".equals(stepResult.getStatus())) {
                if (!skipSelfView) {
                    viewResult.put("message", stepResult.getMessage());
                    mergeStepMetadata(viewResult, metadata);
                    stepResults.add(viewResult);
                }
                result.put("status", "WAITING");
                result.put("stepResults", stepResults);
                result.put("context", context);
                if (stepResult.getMessage() != null) {
                    result.put("message", stepResult.getMessage());
                }
                return result;
            } else if ("JUMP".equals(stepResult.getStatus())) {
                int targetOrder = (Integer) stepResult.getMetadata().get("targetOrder");
                variableContext.clearStepOutputsFromOrder(context, targetOrder);

                // Clear legacy flat step* / sanitized name vars for steps >= targetOrder
                java.util.Set<String> safeNamesToKeep = steps.stream()
                        .filter(s -> s.getStepOrder() < targetOrder)
                        .map(s -> engineUtils.sanitizeKey(s.getStepName() != null ? s.getStepName() : ("step" + s.getStepOrder())))
                        .collect(java.util.stream.Collectors.toSet());

                for (JourneyStep s : steps) {
                    if (s.getStepOrder() >= targetOrder) {
                        String name = engineUtils.sanitizeKey(s.getStepName() != null ? s.getStepName() : ("step" + s.getStepOrder()));
                        if (!safeNamesToKeep.contains(name)) {
                            context.getVariables().remove(name);
                        }
                        context.getVariables().remove("step" + s.getStepOrder());
                    }
                }

                context.setCurrentStepIndex(targetOrder - 1);
                i = 0;
                continue;
            } else {
                viewResult.put("message", stepResult.getMessage());
                mergeStepMetadata(viewResult, metadata);
                stepResults.add(viewResult);
                if (step.isContinueOnError()) {
                    context.addStepResult(stepOrder, "FAILED");
                    context.setCurrentStepIndex(stepOrder);
                    variableContext.writeStepOutput(context, step, "FAILED");
                } else {
                    context.setStatus(ExecutionStatus.ERROR);
                    break;
                }
                i++;
            }
        }

        if (context.getStatus() == ExecutionStatus.RUNNING) {
            context.setStatus(ExecutionStatus.COMPLETED);
            result.put("status", "FINISHED");
        } else if (context.getStatus() == ExecutionStatus.ERROR) {
            result.put("status", "ERROR");
        }

        result.put("stepResults", stepResults);
        result.put("context", context);

        // Final message extraction (last success message)
        for (int idx = stepResults.size() - 1; idx >= 0; idx--) {
            Map<String, Object> r = stepResults.get(idx);
            if ("SUCCESS".equals(r.get("status")) && r.containsKey("message") && r.get("message") != null) {
                result.put("message", r.get("message"));
                break;
            }
        }

        return result;
    }

    private Map<String, Object> finalizeResult(Journey journey, ExecutionContext context, Map<String, Object> result) {
        putIdentity(result, context);
        String status = (String) result.get("status");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepLogs = result.get("stepResults") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        String message = result.get("message") != null ? String.valueOf(result.get("message")) : null;

        if ("WAITING".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, JourneyRunLifecycleEvent.STATUS_WAITING,
                    stepLogs, message));
        } else if ("ERROR".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, JourneyRunLifecycleEvent.STATUS_ERROR,
                    stepLogs, message));
        } else if ("FINISHED".equals(status) || "COMPLETED".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, JourneyRunLifecycleEvent.STATUS_COMPLETED,
                    stepLogs, message));
        }
        return result;
    }

    private void putIdentity(Map<String, Object> result, ExecutionContext context) {
        result.put("executionId", context.getExecutionId());
        result.put("parentExecutionId", context.getParentExecutionId());
        result.put("rootExecutionId", context.getRootExecutionId());
    }

    private JourneyRunLifecycleEvent buildLifecycleEvent(Journey journey, ExecutionContext context,
                                                         String status, List<Map<String, Object>> stepLogs,
                                                         String message) {
        Date completedAt = null;
        Long durationMs = null;
        if (!JourneyRunLifecycleEvent.STATUS_RUNNING.equals(status)) {
            completedAt = new Date();
            if (context.getStartedAt() != null) {
                durationMs = completedAt.getTime() - context.getStartedAt().getTime();
            }
        }

        Map<String, Object> stepResultsMap = new HashMap<>();
        if (context.getStepResults() != null) {
            for (Map.Entry<Integer, Object> e : context.getStepResults().entrySet()) {
                stepResultsMap.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        String userId = context.getVariable("userId") != null
                ? String.valueOf(context.getVariable("userId"))
                : null;

        return JourneyRunLifecycleEvent.builder()
                .executionId(context.getExecutionId())
                .parentExecutionId(context.getParentExecutionId())
                .rootExecutionId(context.getRootExecutionId())
                .journeyId(context.getJourneyId() != null ? context.getJourneyId() : journey.getId())
                .accountId(context.getAccountId())
                .triggerIntent(journey.getTriggerIntent())
                .status(status)
                .startedAt(context.getStartedAt())
                .completedAt(completedAt)
                .durationMs(durationMs)
                .userId(userId)
                .message(message)
                .stepLogs(stepLogs != null ? stepLogs : List.of())
                .variables(context.getVariables() != null ? new HashMap<>(context.getVariables()) : Map.of())
                .stepResults(stepResultsMap)
                .build();
    }

    private void emitLifecycle(JourneyRunLifecycleEvent event) {
        for (JourneyRunLifecyclePort port : lifecyclePorts) {
            port.onLifecycleEvent(event);
        }
    }

    private void emitLifecycleSoft(JourneyRunLifecycleEvent event) {
        for (JourneyRunLifecyclePort port : lifecyclePorts) {
            try {
                port.onLifecycleEvent(event);
            } catch (Exception e) {
                log.error("Lifecycle update failed for executionId={} status={}",
                        event.getExecutionId(), event.getStatus(), e);
            }
        }
    }

    private static String stringOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private void appendNestedStepResults(List<Map<String, Object>> stepResults, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Object nested = metadata.get(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
        if (nested instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    stepResults.add((Map<String, Object>) map);
                }
            }
        }
    }

    private boolean isEligible(JourneyStep step, ExecutionContext context, List<JourneyStep> allSteps) {
        List<Integer> inbound = JourneyStepGraph.resolveInboundParents(step);
        if (inbound.isEmpty()) {
            return true;
        }

        if (JourneyStepGraph.isRejoinStep(step)) {
            for (Integer parent : inbound) {
                if (context.getStepResults().get(parent) != null) {
                    return true;
                }
            }
            return false;
        }

        Integer parentOrder = inbound.get(0);
        Object parentResult = context.getStepResults().get(parentOrder);

        if (parentResult == null) {
            return false;
        }

        String requiredBranch = step.getBranchName();

        if (requiredBranch == null) {
            return true;
        }

        // CONDITION: boolean true/false routing (raw Boolean or Map.result)
        if (parentResult instanceof Boolean) {
            boolean boolResult = (Boolean) parentResult;
            return (requiredBranch.equalsIgnoreCase("true") && boolResult)
                    || (requiredBranch.equalsIgnoreCase("false") && !boolResult);
        }
        if (parentResult instanceof Map<?, ?> parentMap) {
            Object resultKey = parentMap.get("result");
            if (resultKey instanceof Boolean boolResult) {
                return (requiredBranch.equalsIgnoreCase("true") && boolResult)
                        || (requiredBranch.equalsIgnoreCase("false") && !boolResult);
            }
        }

        // SWITCH (and other value-based parents): named case or DEFAULT fallback
        String parentValStr = resolveSwitchValue(parentResult);
        if ("DEFAULT".equalsIgnoreCase(requiredBranch)) {
            return !hasMatchingNamedCase(parentOrder, parentValStr, allSteps);
        }
        return parentValStr != null && requiredBranch.equalsIgnoreCase(parentValStr);
    }

    /**
     * Extracts the switch/case value from a parent step result.
     * SWITCH stores {@code { "value": ... }}; scalars are stringified as-is.
     */
    private String resolveSwitchValue(Object parentResult) {
        if (parentResult instanceof Map<?, ?> parentMap && parentMap.containsKey("value")) {
            Object valueKey = parentMap.get("value");
            return valueKey != null ? String.valueOf(valueKey) : null;
        }
        return parentResult != null ? String.valueOf(parentResult) : null;
    }

    /**
     * Returns true when a sibling under {@code parentOrder} has a non-DEFAULT
     * branchName that matches {@code switchValue} (case-insensitive).
     */
    private boolean hasMatchingNamedCase(Integer parentOrder, String switchValue, List<JourneyStep> allSteps) {
        if (switchValue == null || allSteps == null) {
            return false;
        }
        for (JourneyStep sibling : allSteps) {
            if (JourneyStepGraph.isRejoinStep(sibling)) {
                continue;
            }
            List<Integer> siblingParents = JourneyStepGraph.resolveInboundParents(sibling);
            if (siblingParents.isEmpty() || !parentOrder.equals(siblingParents.get(0))) {
                continue;
            }
            String caseName = sibling.getBranchName();
            if (caseName == null || "DEFAULT".equalsIgnoreCase(caseName)) {
                continue;
            }
            if (caseName.equalsIgnoreCase(switchValue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges handler metadata into the client-facing step view without overwriting
     * core fields already set on {@code viewResult} (e.g. action {@code type}).
     * Engine-reserved nested-journey keys are excluded.
     */
    private void mergeStepMetadata(Map<String, Object> viewResult, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS.equals(key)
                    || TriggerJourneyStepHandler.META_SKIP_SELF_VIEW.equals(key)) {
                continue;
            }
            if (!viewResult.containsKey(key)) {
                viewResult.put(key, entry.getValue());
            }
        }
    }

    private Map<String, Object> createErrorResult(JourneyStep step, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("type", step.getActionType());
        res.put("id", step.getId());
        res.put("stepName", step.getStepName());
        res.put("status", "ERROR");
        res.put("message", message);
        return res;
    }
}