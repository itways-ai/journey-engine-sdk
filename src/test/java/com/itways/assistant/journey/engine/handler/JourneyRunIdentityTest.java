package com.itways.assistant.journey.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.impl.JourneyEngineImpl;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.Journey;
import com.itways.assistant.journey.engine.model.JourneyRunLifecycleEvent;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.service.JourneyRunLifecyclePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

class JourneyRunIdentityTest {

    @Test
    void startAssignsUniqueExecutionIdAndRootSelf() {
        List<JourneyRunLifecycleEvent> events = new ArrayList<>();
        JourneyEngineImpl engine = buildEngine(
                List.of(events::add),
                List.of(responseHandler()));

        Journey journey = simpleJourney(1L, "TRACK_ORDER", responseStep(1));
        Map<String, Object> result = engine.start(journey, "acc-1", Map.of());

        assertEquals("FINISHED", result.get("status"));
        assertNotNull(result.get("executionId"));
        assertEquals(result.get("executionId"), result.get("rootExecutionId"));
        assertNull(result.get("parentExecutionId"));

        ExecutionContext ctx = (ExecutionContext) result.get("context");
        assertEquals(result.get("executionId"), ctx.getExecutionId());
        assertEquals(result.get("executionId"), ctx.getRootExecutionId());

        assertTrue(events.stream().anyMatch(e -> JourneyRunLifecycleEvent.STATUS_RUNNING.equals(e.getStatus())));
        assertTrue(events.stream().anyMatch(e -> JourneyRunLifecycleEvent.STATUS_COMPLETED.equals(e.getStatus())));
    }

    @Test
    void nestedChildGetsOwnIdWithParentAndRootLinkage() {
        List<JourneyRunLifecycleEvent> events = new ArrayList<>();
        AtomicReference<Map<String, Object>> childStartParams = new AtomicReference<>();

        JourneyLookupPort lookup = (accountId, intent) -> simpleJourney(99L, intent, responseStep(1));

        JourneyEngine nestedEngine = new JourneyEngine() {
            @Override
            public Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams) {
                childStartParams.set(new HashMap<>(initialParams));
                JourneyEngineImpl inner = buildEngine(List.of(events::add), List.of(responseHandler()));
                return inner.start(journey, accountId, initialParams);
            }

            @Override
            public Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams) {
                throw new UnsupportedOperationException();
            }
        };

        ObjectMapper mapper = new ObjectMapper();
        TriggerJourneyStepHandler trigger = new TriggerJourneyStepHandler(
                lookup,
                new EngineUtils(mapper),
                new VariableContext(),
                new StepOutputSchemaHelper(mapper),
                nestedEngine);

        JourneyEngineImpl parentEngine = buildEngine(List.of(events::add), List.of(responseHandler(), trigger));

        Journey parent = new Journey();
        parent.setId(1L);
        parent.setTriggerIntent("TRACK_ORDER");
        JourneyStep r1 = responseStep(1);
        JourneyStep t = new JourneyStep();
        t.setStepOrder(2);
        t.setStepName("StartNps");
        t.setActionType("TRIGGER_JOURNEY");
        t.setActionTarget("CUSTOMER_FEEDBACK");
        parent.setSteps(List.of(r1, t));

        Map<String, Object> result = parentEngine.start(parent, "acc-1", Map.of());
        assertEquals("FINISHED", result.get("status"));
        String parentId = (String) result.get("executionId");
        assertNotNull(parentId);

        Map<String, Object> params = childStartParams.get();
        assertEquals(parentId, params.get(TriggerJourneyStepHandler.PARENT_EXECUTION_ID));
        assertEquals(parentId, params.get(TriggerJourneyStepHandler.ROOT_EXECUTION_ID));

        JourneyRunLifecycleEvent childCompleted = events.stream()
                .filter(e -> JourneyRunLifecycleEvent.STATUS_COMPLETED.equals(e.getStatus()))
                .filter(e -> "CUSTOMER_FEEDBACK".equals(e.getTriggerIntent()))
                .findFirst()
                .orElseThrow();
        assertNotEquals(parentId, childCompleted.getExecutionId());
        assertEquals(parentId, childCompleted.getParentExecutionId());
        assertEquals(parentId, childCompleted.getRootExecutionId());
    }

    private static StepHandler responseHandler() {
        return new StepHandler() {
            @Override
            public String getType() {
                return "RESPONSE";
            }

            @Override
            public StepResult execute(JourneyStep step, ExecutionContext context) {
                return StepResult.success(step.getActionTarget(), step.getActionTarget());
            }
        };
    }

    private static JourneyStep responseStep(int order) {
        JourneyStep step = new JourneyStep();
        step.setStepOrder(order);
        step.setStepName("Step" + order);
        step.setActionType("RESPONSE");
        step.setActionTarget("hello");
        step.setClientVisible(true);
        return step;
    }

    private static Journey simpleJourney(Long id, String intent, JourneyStep... steps) {
        Journey journey = new Journey();
        journey.setId(id);
        journey.setTriggerIntent(intent);
        journey.setSteps(List.of(steps));
        return journey;
    }

    private static JourneyEngineImpl buildEngine(List<JourneyRunLifecyclePort> ports, List<StepHandler> handlers) {
        return new JourneyEngineImpl(
                new StepHandlerRegistry(handlers),
                new EngineUtils(new ObjectMapper()),
                new VariableContext(),
                new ObjectMapper(),
                ports);
    }
}
