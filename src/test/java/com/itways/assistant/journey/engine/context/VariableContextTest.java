package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableContextTest {

    private VariableContext variableContext;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        variableContext = new VariableContext();
        context = ExecutionContext.builder()
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>())
                .build();
    }

    @Test
    void mergeInputs_structuresInputsAndEntities() {
        Map<String, Object> params = new HashMap<>();
        params.put("text", "hello");
        params.put("customKey", "customVal");

        variableContext.mergeInputs(context, params);

        assertEquals("hello", variableContext.getInputs(context).get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) variableContext.getInputs(context).get("entities");
        assertEquals("customVal", entities.get("customKey"));
    }

    @Test
    void mergeChannel_nestedAndLegacy() {
        Map<String, Object> channel = Map.of(
                "id", "uuid-1",
                "label", "Support",
                "user", Map.of("displayName", "Yazan"));
        variableContext.mergeInputs(context, Map.of("channel", channel));

        assertEquals("Support", variableContext.getChannel(context).get("label"));
        assertEquals("Yazan", variableContext.read(context, "channel.user.displayName"));

        variableContext.mergeInputs(context, Map.of(
                "channelId", "legacy-id",
                "channelType", "WHATSAPP_TWILIO"));
        assertEquals("legacy-id", variableContext.getChannel(context).get("id"));
    }

    @Test
    void writeStepOutput_andRead() {
        JourneyStep step = JourneyStep.builder().stepOrder(2).build();
        variableContext.writeStepOutput(context, step, Map.of("email", "a@b.com"));

        assertEquals("a@b.com", variableContext.read(context, "steps.2.output.email"));
    }

    @Test
    void clearStepOutputsFromOrder() {
        JourneyStep step1 = JourneyStep.builder().stepOrder(1).build();
        JourneyStep step2 = JourneyStep.builder().stepOrder(2).build();
        variableContext.writeStepOutput(context, step1, "a");
        variableContext.writeStepOutput(context, step2, "b");
        context.addStepResult(1, "a");
        context.addStepResult(2, "b");

        variableContext.clearStepOutputsFromOrder(context, 2);

        assertNull(variableContext.read(context, "steps.2.output"));
        assertNotNull(variableContext.read(context, "steps.1.output"));
        assertFalse(context.getStepResults().containsKey(2));
    }

    @Test
    void resolveForTemplate() {
        variableContext.mergeInputs(context, Map.of("text", "world"));
        String result = variableContext.resolveForTemplate(context, "Hello {{inputs.text}}!");
        assertEquals("Hello world!", result);
    }
}
