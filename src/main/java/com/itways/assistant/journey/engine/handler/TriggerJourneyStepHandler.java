package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.Journey;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs another journey inline by trigger intent, then lets the parent continue.
 * Nested WAITING state is stored on the parent context under {@link #ACTIVE_TRIGGERED_JOURNEY}.
 */
@Slf4j
@Component
public class TriggerJourneyStepHandler implements StepHandler {

    public static final String ACTIVE_TRIGGERED_JOURNEY = "_activeTriggeredJourney";
    public static final String TRIGGERED_JOURNEY_STACK = "_triggeredJourneyStack";
    public static final String PENDING_RESUME_INPUT = "_pendingResumeInput";
    public static final String META_NESTED_STEP_RESULTS = "nestedStepResults";
    public static final String META_SKIP_SELF_VIEW = "skipSelfView";

    private static final int MAX_TRIGGER_DEPTH = 5;

    private final JourneyLookupPort journeyLookupPort;
    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final JourneyEngine journeyEngine;

    public TriggerJourneyStepHandler(JourneyLookupPort journeyLookupPort, EngineUtils engineUtils,
                                     VariableContext variableContext, StepOutputSchemaHelper schemaHelper,
                                     @Lazy JourneyEngine journeyEngine) {
        this.journeyLookupPort = journeyLookupPort;
        this.engineUtils = engineUtils;
        this.variableContext = variableContext;
        this.schemaHelper = schemaHelper;
        this.journeyEngine = journeyEngine;
    }

    @Override
    public String getType() {
        return "TRIGGER_JOURNEY";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.triggerJourneyDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("TRIGGER_JOURNEY", "Triggered Journey Result");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        Object activeRaw = context.getVariable(ACTIVE_TRIGGERED_JOURNEY);
        Map<String, Object> childResult;
        String intent;

        if (activeRaw instanceof Map<?, ?> activeMap) {
            intent = String.valueOf(activeMap.get("intent"));
            ExecutionContext childContext = deserializeChildContext(activeMap.get("childContext"));
            if (childContext == null) {
                context.getVariables().remove(ACTIVE_TRIGGERED_JOURNEY);
                return StepResult.error("TRIGGER_JOURNEY: corrupt nested execution state");
            }

            Journey childJourney = journeyLookupPort.findByTriggerIntent(context.getAccountId(), intent);
            if (childJourney == null) {
                context.getVariables().remove(ACTIVE_TRIGGERED_JOURNEY);
                return StepResult.error("TRIGGER_JOURNEY: journey not found for intent '" + intent + "'");
            }

            Map<String, Object> pending = context.getVariable(PENDING_RESUME_INPUT) instanceof Map<?, ?> p
                    ? new HashMap<>((Map<String, Object>) p)
                    : new HashMap<>();
            log.info("Resuming triggered journey '{}'", intent);
            childResult = journeyEngine.resume(childJourney, childContext, pending);
        } else {
            intent = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
            if (intent == null || intent.isBlank()) {
                return StepResult.error("TRIGGER_JOURNEY: actionTarget (journey intent) is required");
            }
            intent = intent.trim();

            List<String> stack = resolveTriggerStack(context);
            if (stack.contains(intent)) {
                return StepResult.error(
                        "TRIGGER_JOURNEY: cannot trigger '" + intent + "' — already on the call stack " + stack);
            }
            if (stack.size() >= MAX_TRIGGER_DEPTH) {
                return StepResult.error(
                        "TRIGGER_JOURNEY: max nesting depth (" + MAX_TRIGGER_DEPTH + ") exceeded");
            }

            Journey childJourney = journeyLookupPort.findByTriggerIntent(context.getAccountId(), intent);
            if (childJourney == null) {
                return StepResult.error("TRIGGER_JOURNEY: journey not found for intent '" + intent + "'");
            }

            Map<String, Object> childParams = copyVariablesForChild(context.getVariables());
            List<String> childStack = new ArrayList<>(stack);
            childStack.add(intent);
            childParams.put(TRIGGERED_JOURNEY_STACK, childStack);

            log.info("Starting triggered journey '{}'", intent);
            childResult = journeyEngine.start(childJourney, context.getAccountId(), childParams);
        }

        String childStatus = (String) childResult.get("status");
        List<Map<String, Object>> childSteps = childResult.get("stepResults") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();

        if ("WAITING".equals(childStatus)) {
            ExecutionContext childCtx = (ExecutionContext) childResult.get("context");
            Map<String, Object> active = new HashMap<>();
            active.put("intent", intent);
            if (childCtx != null && childCtx.getJourneyId() != null) {
                active.put("journeyId", childCtx.getJourneyId());
            }
            active.put("childContext", serializeChildContext(childCtx));
            context.setVariable(ACTIVE_TRIGGERED_JOURNEY, active);
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_NESTED_STEP_RESULTS, childSteps);
            metadata.put(META_SKIP_SELF_VIEW, true);

            String message = childResult.get("message") != null
                    ? String.valueOf(childResult.get("message"))
                    : null;
            return StepResult.waiting(message, metadata);
        }

        context.getVariables().remove(ACTIVE_TRIGGERED_JOURNEY);

        if ("ERROR".equals(childStatus)) {
            String errMsg = childResult.get("message") != null
                    ? String.valueOf(childResult.get("message"))
                    : "Triggered journey '" + intent + "' failed";
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_NESTED_STEP_RESULTS, childSteps);
            return StepResult.builder()
                    .status("ERROR")
                    .message(errMsg)
                    .metadata(metadata)
                    .build();
        }

        // FINISHED
        Object outputData = intent;
        if (childResult.get("context") instanceof ExecutionContext finishedChild) {
            Object last = finishedChild.getVariable("lastStep");
            if (last != null) {
                outputData = last;
            }
        }

        String message = step.getMessage() != null
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Triggered journey: " + intent;

        variableContext.storeOutput(context, step, outputData);
        context.setStatus(ExecutionStatus.RUNNING);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_NESTED_STEP_RESULTS, childSteps);

        return StepResult.builder()
                .status("SUCCESS")
                .data(outputData)
                .message(message)
                .actionTarget(intent)
                .metadata(metadata)
                .build();
    }

    private List<String> resolveTriggerStack(ExecutionContext context) {
        List<String> stack = new ArrayList<>();
        Object raw = context.getVariable(TRIGGERED_JOURNEY_STACK);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    stack.add(String.valueOf(item));
                }
            }
        }
        return stack;
    }

    private Map<String, Object> copyVariablesForChild(Map<String, Object> parentVars) {
        Map<String, Object> copy = new HashMap<>();
        if (parentVars == null) {
            return copy;
        }
        for (Map.Entry<String, Object> e : parentVars.entrySet()) {
            String key = e.getKey();
            if (ACTIVE_TRIGGERED_JOURNEY.equals(key)
                    || PENDING_RESUME_INPUT.equals(key)
                    || TRIGGERED_JOURNEY_STACK.equals(key)) {
                continue;
            }
            copy.put(key, e.getValue());
        }
        return copy;
    }

    private Map<String, Object> serializeChildContext(ExecutionContext childCtx) {
        if (childCtx == null) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("journeyId", childCtx.getJourneyId());
        map.put("accountId", childCtx.getAccountId());
        map.put("currentStepIndex", childCtx.getCurrentStepIndex());
        map.put("status", childCtx.getStatus() != null ? childCtx.getStatus().name() : ExecutionStatus.WAITING_FOR_INPUT.name());
        map.put("variables", childCtx.getVariables() != null ? new HashMap<>(childCtx.getVariables()) : new HashMap<>());
        map.put("stepResults", childCtx.getStepResults() != null ? new HashMap<>(childCtx.getStepResults()) : new HashMap<>());
        map.put("startedAt", childCtx.getStartedAt());
        map.put("executionId", childCtx.getExecutionId());
        return map;
    }

    @SuppressWarnings("unchecked")
    private ExecutionContext deserializeChildContext(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            Map<String, Object> variables = map.get("variables") instanceof Map<?, ?> v
                    ? new HashMap<>((Map<String, Object>) v)
                    : new HashMap<>();

            Map<Integer, Object> stepResults = new HashMap<>();
            Object rawResults = map.get("stepResults");
            if (rawResults instanceof Map<?, ?> rm) {
                for (Map.Entry<?, ?> entry : rm.entrySet()) {
                    stepResults.put(Integer.valueOf(String.valueOf(entry.getKey())), entry.getValue());
                }
            }

            Long journeyId = map.get("journeyId") != null ? Long.valueOf(String.valueOf(map.get("journeyId"))) : null;
            String statusName = map.get("status") != null ? String.valueOf(map.get("status")) : ExecutionStatus.WAITING_FOR_INPUT.name();
            int currentStepIndex = -1;
            if (map.get("currentStepIndex") instanceof Number n) {
                currentStepIndex = n.intValue();
            } else if (map.get("currentStepIndex") != null) {
                currentStepIndex = Integer.parseInt(String.valueOf(map.get("currentStepIndex")));
            }

            Date startedAt = null;
            if (map.get("startedAt") instanceof Date d) {
                startedAt = d;
            } else if (map.get("startedAt") instanceof Number n) {
                startedAt = new Date(n.longValue());
            }

            return ExecutionContext.builder()
                    .executionId(map.get("executionId") != null ? String.valueOf(map.get("executionId")) : null)
                    .journeyId(journeyId)
                    .accountId(map.get("accountId") != null ? String.valueOf(map.get("accountId")) : null)
                    .currentStepIndex(currentStepIndex)
                    .status(ExecutionStatus.valueOf(statusName))
                    .variables(variables)
                    .stepResults(stepResults)
                    .startedAt(startedAt)
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialize nested journey context", e);
            return null;
        }
    }
}
