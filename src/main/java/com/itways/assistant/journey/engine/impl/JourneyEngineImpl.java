package com.itways.assistant.journey.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.handler.TriggerJourneyStepHandler;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.JourneyStepGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyEngineImpl implements JourneyEngine {

    private final StepHandlerRegistry handlerRegistry;
    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams) {
        Map<String, Object> variables = new HashMap<>(initialParams != null ? initialParams : Map.of());
        // Seed call stack so TRIGGER_JOURNEY can detect cycles without needing the parent Journey object
        if (!variables.containsKey(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK)
                && journey.getTriggerIntent() != null
                && !journey.getTriggerIntent().isBlank()) {
            variables.put(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK,
                    new ArrayList<>(List.of(journey.getTriggerIntent())));
        }

        ExecutionContext context = ExecutionContext.builder()
                .journeyId(journey.getId())
                .accountId(accountId)
                .currentStepIndex(-1)
                .status(ExecutionStatus.RUNNING)
                .variables(variables)
                .startedAt(new Date())
                .build();

        return execute(journey, context);
    }

    @Override
    public Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams) {
        Map<String, Object> pending = inputParams != null ? new HashMap<>(inputParams) : new HashMap<>();
        context.getVariables().putAll(pending);
        context.setVariable(TriggerJourneyStepHandler.PENDING_RESUME_INPUT, pending);
        context.setStatus(ExecutionStatus.RUNNING);
        Map<String, Object> result = execute(journey, context);
        context.getVariables().remove(TriggerJourneyStepHandler.PENDING_RESUME_INPUT);
        return result;
    }

    private Map<String, Object> execute(Journey journey, ExecutionContext context) {

        log.debug("START JOURNEY EXECUTION >> journeyId={} accountId={}", journey.getId(), context.getAccountId());
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        List<JourneyStep> steps = journey.getSteps();
        if (steps == null || steps.isEmpty()) {
            log.warn("Journey '{}' has no steps defined.", journey.getTriggerIntent());
            result.put("status", "FINISHED");
            result.put("message", "This journey has no steps configured.");
            result.put("context", context);
            return result;
        }

        List<JourneyStep> sortedSteps;
        try {
            sortedSteps = JourneyStepGraph.sortSteps(steps);
        } catch (IllegalStateException e) {
            log.error("❌ Journey '{}' has a cyclic step graph — aborting execution: {}",
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
                context.addStepResult(stepOrder, stepResult.getData());

                String safeName = engineUtils
                        .sanitizeKey(step.getStepName() != null ? step.getStepName() : ("step" + stepOrder));
                context.setVariable(safeName, stepResult.getData());

                i++;
            } else if ("WAITING".equals(stepResult.getStatus())) {
                if (!skipSelfView) {
                    viewResult.put("message", stepResult.getMessage());
                    mergeStepMetadata(viewResult, metadata);
                    stepResults.add(viewResult);
                }
                // Pause execution
                result.put("status", "WAITING");
                result.put("stepResults", stepResults);
                result.put("context", context);
                if (stepResult.getMessage() != null) {
                    result.put("message", stepResult.getMessage());
                }
                return result;
            } else if ("JUMP".equals(stepResult.getStatus())) {
                int targetOrder = (Integer) stepResult.getMetadata().get("targetOrder");

                // Clear `stepResults` sequencing >= targetOrder
                List<Integer> ordersToRemove = new java.util.ArrayList<>(context.getStepResults().keySet());
                for (Integer order : ordersToRemove) {
                    if (order >= targetOrder) {
                        context.getStepResults().remove(order);
                    }
                }

                // Keep variables alive if they were also created by a step < targetOrder
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
                    }
                }
                context.getVariables().keySet().removeIf(k -> k.startsWith("step") && ordersToRemove.stream().anyMatch(o -> o >= targetOrder && k.equals("step" + o)));

                context.setCurrentStepIndex(targetOrder - 1);
                i = 0; // Reset loop to run from start
                continue;
            } else {
                viewResult.put("message", stepResult.getMessage());
                mergeStepMetadata(viewResult, metadata);
                stepResults.add(viewResult);
                if (step.isContinueOnError()) {
                    context.addStepResult(stepOrder, "FAILED");
                    context.setCurrentStepIndex(stepOrder);

                    // Propagate "FAILED" status to variables so subsequent steps don't see nulls
                    context.setVariable("step" + stepOrder, "FAILED");
                    context.setVariable("lastStep", "FAILED");
                    if (step.getStepName() != null && !step.getStepName().isEmpty()) {
                        context.setVariable(engineUtils.sanitizeKey(step.getStepName()), "FAILED");
                    }
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
