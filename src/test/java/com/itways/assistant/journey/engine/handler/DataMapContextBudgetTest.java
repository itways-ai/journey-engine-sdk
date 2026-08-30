package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much of a run DATA_MAP is allowed to put in a prompt.
 *
 * <p>
 * The incident: this step serialized the entire variable map into every call.
 * On a journey that had just fetched a project's whole task list that ran past
 * the provider's per-minute token limit, the call came back 413, and because a
 * failed call is returned as ordinary content and these steps are authored
 * {@code continueOnError}, the journey carried on and delivered an empty
 * message with nothing in run history explaining it.
 */
@DisplayName("DataMapStepHandler context budget")
class DataMapContextBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataMapStepHandler handler = new DataMapStepHandler(
            null, objectMapper, new EngineUtils(objectMapper), new VariableContext(),
            new StepOutputSchemaHelper(objectMapper), null, null);

    private static final JourneyStep STEP = JourneyStep.builder()
            .stepOrder(5).stepName("Narrate").actionType("DATA_MAP").build();

    private void budget(int chars) {
        ReflectionTestUtils.setField(handler, "contextBudgetChars", chars);
    }

    /** A step output big enough to matter, keyed by order like the engine writes them. */
    private static Map<String, Object> variablesWithSteps(int count, int sizeEach) {
        Map<String, Object> steps = new LinkedHashMap<>();
        for (int order = 1; order <= count; order++) {
            steps.put(String.valueOf(order), Map.of("output", "x".repeat(sizeEach)));
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("inputs", Map.of("text", "give me a briefing"));
        variables.put("steps", steps);
        variables.put("state", Map.of());
        return variables;
    }

    @Nested
    @DisplayName("within budget")
    class WithinBudget {

        @Test
        @DisplayName("a small run is serialized whole, exactly as before")
        void smallRunIsUntouched() throws Exception {
            budget(8000);
            Map<String, Object> variables = variablesWithSteps(2, 20);

            String result = handler.boundedContext(variables, STEP);

            assertThat(result).isEqualTo(objectMapper.writeValueAsString(variables));
            assertThat(result).doesNotContain("omitted");
        }
    }

    @Nested
    @DisplayName("over budget")
    class OverBudget {

        @Test
        @DisplayName("older step outputs are dropped first — a DATA_MAP summarises what just happened")
        void dropsOldestSteps() throws Exception {
            budget(600);

            String result = handler.boundedContext(variablesWithSteps(6, 200), STEP);

            assertThat(result.length()).isLessThan(objectMapper
                    .writeValueAsString(variablesWithSteps(6, 200)).length());
            // The newest step survives; the oldest is gone.
            assertThat(result).contains("\"6\"");
            assertThat(result).doesNotContain("\"1\":");
        }

        @Test
        @DisplayName("what was dropped is named, so the model cannot answer from context it never saw")
        void namesWhatItDropped() throws Exception {
            budget(600);

            String result = handler.boundedContext(variablesWithSteps(6, 200), STEP);

            assertThat(result).contains("omitted to fit").contains("steps ");
        }

        @Test
        @DisplayName("the buckets an author references by name survive the trim")
        void keepsReservedBuckets() throws Exception {
            budget(600);

            String result = handler.boundedContext(variablesWithSteps(6, 200), STEP);

            assertThat(result).contains("inputs").contains("give me a briefing").contains("state");
        }

        @Test
        @DisplayName("one enormous step output is kept rather than leaving the model nothing at all")
        void neverEmptiesStepsEntirely() throws Exception {
            budget(100);

            String result = handler.boundedContext(variablesWithSteps(3, 500), STEP);

            // Trimming to nothing would be worse than overrunning: the step
            // exists to read a step output.
            assertThat(result).contains("\"3\"");
        }
    }
}
