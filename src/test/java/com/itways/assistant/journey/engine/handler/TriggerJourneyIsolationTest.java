package com.itways.assistant.journey.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.Journey;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

class TriggerJourneyIsolationTest {

    @Test
    @SuppressWarnings("unchecked")
    void childDoesNotOverwriteParentStepBuckets() {
        AtomicReference<Map<String, Object>> capturedChildParams = new AtomicReference<>();

        JourneyLookupPort lookup = (accountId, intent) -> {
            Journey child = new Journey();
            child.setId(99L);
            child.setTriggerIntent(intent);
            child.setSteps(List.of());
            return child;
        };

        JourneyEngine engine = new JourneyEngine() {
            @Override
            public Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams) {
                capturedChildParams.set(initialParams);
                // Simulate child writing steps.1 — must not touch parent buckets.
                Map<String, Object> steps = (Map<String, Object>) initialParams.get("steps");
                Map<String, Object> bucket = new HashMap<>();
                bucket.put("output", "We value your opinion!");
                steps.put("1", bucket);

                Map<String, Object> result = new HashMap<>();
                result.put("status", "FINISHED");
                result.put("stepResults", List.of());
                result.put("context", ExecutionContext.builder().variables(new HashMap<>(initialParams)).build());
                return result;
            }

            @Override
            public Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams) {
                throw new UnsupportedOperationException();
            }
        };

        ObjectMapper mapper = new ObjectMapper();
        TriggerJourneyStepHandler handler = new TriggerJourneyStepHandler(
                lookup,
                new EngineUtils(mapper),
                new VariableContext(),
                new StepOutputSchemaHelper(mapper),
                engine);

        ExecutionContext parent = new ExecutionContext();
        parent.setAccountId("acc-1");
        Map<String, Object> parentSteps = new HashMap<>();
        Map<String, Object> parentStep1 = new HashMap<>();
        parentStep1.put("output", "I can look up your order status...");
        parentSteps.put("1", parentStep1);

        Map<String, Object> parentInputs = new HashMap<>();
        parentInputs.put("text", "TRACK_ORDER");
        parentInputs.put("answer", "ORD-1");

        Map<String, Object> parentVars = new HashMap<>();
        parentVars.put("steps", parentSteps);
        parentVars.put("state", new HashMap<>(Map.of("order_status", "CANCELLED")));
        parentVars.put("runtime", new HashMap<>());
        parentVars.put("channel", new HashMap<>(Map.of("id", "web")));
        parentVars.put("inputs", parentInputs);
        parent.setVariables(parentVars);

        JourneyStep step = new JourneyStep();
        step.setStepOrder(11);
        step.setStepName("StartNpsFeedback");
        step.setActionTarget("CUSTOMER_FEEDBACK");

        StepResult result = handler.execute(step, parent);
        assertEquals("SUCCESS", result.getStatus());

        assertEquals("I can look up your order status...", parentStep1.get("output"),
                "parent steps.1.output must survive child write");
        assertEquals("CANCELLED", ((Map<?, ?>) parentVars.get("state")).get("order_status"));

        Map<String, Object> childParams = capturedChildParams.get();
        assertNotSame(parentSteps, childParams.get("steps"));
        assertNotSame(parentVars.get("state"), childParams.get("state"));
        assertTrue(((Map<?, ?>) childParams.get("steps")).containsKey("1"));
        assertEquals("We value your opinion!",
                ((Map<?, ?>) ((Map<?, ?>) childParams.get("steps")).get("1")).get("output"));
        assertTrue(((Map<?, ?>) childParams.get("state")).isEmpty());
        assertFalse(((Map<?, ?>) childParams.get("inputs")).containsKey("answer"));
        assertNull(((Map<?, ?>) childParams.get("inputs")).get("answer"));
        assertEquals("TRACK_ORDER", ((Map<?, ?>) childParams.get("inputs")).get("text"));
        assertEquals("web", ((Map<?, ?>) childParams.get("channel")).get("id"));
    }
}
