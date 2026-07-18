package com.itways.assistant.journey.engine.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

class CodeScriptBridgeTest {

    @Test
    void nestedStepsPropertyAccessReadsStatus() {
        ObjectMapper mapper = new ObjectMapper();
        CodeScriptStepHandler handler = new CodeScriptStepHandler(
                new EngineUtils(mapper),
                new VariableContext(),
                new StepOutputSchemaHelper(mapper),
                mapper);

        ExecutionContext ctx = new ExecutionContext();
        ctx.setVariables(mutableVars(
                Map.of("3", mutableBucket(Map.of(
                        "orderNumber", "ORD-1",
                        "status", "CANCELLED",
                        "orderStatus", "CANCELLED")))));

        JourneyStep step = new JourneyStep();
        step.setStepOrder(4);
        step.setStepName("ExtractOrderStatus");
        step.setApiConfig(
                "{\"code\":\"var data = steps['3'].output;\\nvar status = 'DEFAULT';\\nif (data != null && typeof data === 'object') {\\n  status = String(data.status || data.orderStatus || 'DEFAULT').toUpperCase();\\n} else if (data != null) {\\n  status = String(data).toUpperCase();\\n}\\nstatus;\",\"language\":\"js\"}");

        StepResult result = handler.execute(step, ctx);
        assertEquals("SUCCESS", result.getStatus(), () -> "unexpected: " + result.getMessage());
        assertNotEquals("TRANSATE_ERROR", result.getData());
        assertEquals("CANCELLED", result.getData());
    }

    @Test
    void npsScoreFromInteractiveForm() {
        ObjectMapper mapper = new ObjectMapper();
        CodeScriptStepHandler handler = new CodeScriptStepHandler(
                new EngineUtils(mapper),
                new VariableContext(),
                new StepOutputSchemaHelper(mapper),
                mapper);

        ExecutionContext ctx = new ExecutionContext();
        ctx.setVariables(mutableVars(
                Map.of("2", mutableBucket(Map.of("score", "9", "comment", "")))));

        JourneyStep step = new JourneyStep();
        step.setStepOrder(3);
        step.setStepName("ClassifyNPS");
        step.setApiConfig(
                "{\"code\":\"var ans = steps['2'].output;\\nvar s = 0;\\nif (ans != null && typeof ans === 'object') {\\n  s = parseInt(ans.score) || 0;\\n} else {\\n  s = parseInt(ans) || 0;\\n}\\nvar segment = 'DETRACTOR';\\nif (s >= 9) segment = 'PROMOTER';\\nelse if (s >= 7) segment = 'PASSIVE';\\nsegment;\",\"language\":\"js\"}");

        StepResult result = handler.execute(step, ctx);
        assertEquals("SUCCESS", result.getStatus(), () -> "unexpected: " + result.getMessage());
        assertEquals("PROMOTER", result.getData());
    }

    private static Map<String, Object> mutableVars(Map<String, Object> steps) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("inputs", new HashMap<>());
        vars.put("state", new HashMap<>());
        vars.put("channel", new HashMap<>());
        vars.put("runtime", new HashMap<>());
        vars.put("steps", new HashMap<>(steps));
        return vars;
    }

    private static Map<String, Object> mutableBucket(Map<String, Object> output) {
        Map<String, Object> bucket = new HashMap<>();
        bucket.put("output", new HashMap<>(output));
        return bucket;
    }
}
