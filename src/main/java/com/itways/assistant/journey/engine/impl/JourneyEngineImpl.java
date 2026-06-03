package com.itways.assistant.journey.engine.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
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

    @Override
    public Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams) {
        ExecutionContext context = ExecutionContext.builder()
                .journeyId(journey.getId())
                .accountId(accountId)
                .currentStepIndex(-1)
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>(initialParams))
                .startedAt(new Date())
                .build();

        return execute(journey, context);
    }

    @Override
    public Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams) {
        context.getVariables().putAll(inputParams);
        context.setStatus(ExecutionStatus.RUNNING);
        return execute(journey, context);
    }

    private Map<String, Object> execute(Journey journey, ExecutionContext context) {

        System.out.println("START JOURNEY EXECUTION>>");
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

        List<JourneyStep> sortedSteps = sortSteps(steps);

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

            // check branch eligibility
            if (!isEligible(step, context)) {
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
                        viewResult.put("outputPayload", new ObjectMapper().writeValueAsString(stepResult.getData()));
                    } catch(Exception ignored){}
                }
                stepResults.add(viewResult);
                context.setCurrentStepIndex(stepOrder);
                context.addStepResult(stepOrder, stepResult.getData());

                String safeName = engineUtils
                        .sanitizeKey(step.getStepName() != null ? step.getStepName() : ("step" + stepOrder));
                context.setVariable(safeName, stepResult.getData());
                
                i++;
            } else if ("WAITING".equals(stepResult.getStatus())) {
                viewResult.put("message", stepResult.getMessage());
                viewResult.putAll(stepResult.getMetadata());
                stepResults.add(viewResult);
                // Pause execution
                result.put("status", "WAITING");
                result.put("stepResults", stepResults);
                result.put("context", context);
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

    private List<Integer> resolveInboundParents(JourneyStep step) {
        if (step.getParentOrders() != null && !step.getParentOrders().isEmpty()) {
            return step.getParentOrders();
        }
        if (step.getParentOrder() != null && step.getParentOrder() > 0) {
            return List.of(step.getParentOrder());
        }
        return Collections.emptyList();
    }

    private boolean isRejoinStep(JourneyStep step) {
        return step.getParentOrders() != null && !step.getParentOrders().isEmpty();
    }

    private List<JourneyStep> sortSteps(List<JourneyStep> steps) {
        Map<Integer, JourneyStep> stepMap = new HashMap<>();
        Map<Integer, List<Integer>> adj = new HashMap<>();
        Map<Integer, Integer> inDegree = new HashMap<>();

        for (JourneyStep s : steps) {
            stepMap.put(s.getStepOrder(), s);
            inDegree.put(s.getStepOrder(), 0);
        }

        for (JourneyStep s : steps) {
            for (Integer parent : resolveInboundParents(s)) {
                if (parent == null || parent <= 0 || !stepMap.containsKey(parent)) {
                    continue;
                }
                adj.computeIfAbsent(parent, k -> new ArrayList<>()).add(s.getStepOrder());
                inDegree.merge(s.getStepOrder(), 1, Integer::sum);
            }
        }

        Queue<Integer> queue = new LinkedList<>();
        for (JourneyStep s : steps) {
            if (inDegree.getOrDefault(s.getStepOrder(), 0) == 0) {
                queue.add(s.getStepOrder());
            }
        }

        List<Integer> sortedOrders = new ArrayList<>();
        while (!queue.isEmpty()) {
            int node = queue.poll();
            sortedOrders.add(node);
            for (int child : adj.getOrDefault(node, Collections.emptyList())) {
                int deg = inDegree.merge(child, -1, Integer::sum);
                if (deg == 0) {
                    queue.add(child);
                }
            }
        }

        for (JourneyStep s : steps) {
            if (!sortedOrders.contains(s.getStepOrder())) {
                sortedOrders.add(s.getStepOrder());
            }
        }

        List<JourneyStep> result = new ArrayList<>();
        for (int order : sortedOrders) {
            result.add(stepMap.get(order));
        }
        return result;
    }

    private boolean isEligible(JourneyStep step, ExecutionContext context) {
        List<Integer> inbound = resolveInboundParents(step);
        if (inbound.isEmpty()) {
            return true;
        }

        if (isRejoinStep(step)) {
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

        if (parentResult instanceof Boolean) {
            boolean boolResult = (Boolean) parentResult;
            return (requiredBranch.equalsIgnoreCase("true") && boolResult)
                    || (requiredBranch.equalsIgnoreCase("false") && !boolResult);
        } else {
            String parentValStr = String.valueOf(parentResult);
            return requiredBranch.equalsIgnoreCase(parentValStr) || "DEFAULT".equalsIgnoreCase(requiredBranch);
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
