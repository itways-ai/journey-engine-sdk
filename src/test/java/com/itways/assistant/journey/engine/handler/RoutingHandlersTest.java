package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing primitives — CONDITION, SWITCH, JUMP, RESPONSE. Their outputs
 * are what the engine's eligibility check branches on, so the stored shape
 * (raw Boolean, raw value, jump metadata) is contract, not implementation.
 */
@DisplayName("Routing step handlers")
class RoutingHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EngineUtils engineUtils = new EngineUtils(objectMapper);
    private final VariableContext variableContext = new VariableContext();
    private final StepOutputSchemaHelper schemaHelper = new StepOutputSchemaHelper(objectMapper);

    private static ExecutionContext contextWithState(Map<String, Object> state) {
        ExecutionContext context = ExecutionContext.builder().variables(new HashMap<>()).build();
        new VariableContext().ensureStructure(context);
        context.getVariables().put("state", new HashMap<>(state));
        return context;
    }

    @Nested
    @DisplayName("CONDITION")
    class Condition {

        private final ConditionStepHandler handler =
                new ConditionStepHandler(engineUtils, variableContext, schemaHelper);

        @Test
        @DisplayName("stores and returns the evaluated boolean")
        void evaluatesToBoolean() {
            ExecutionContext context = contextWithState(Map.of("count", 5));
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(2).actionType("CONDITION")
                    .conditionExpression("state.count > 3").build();

            StepResult result = handler.execute(step, context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(true);
            // The engine's eligibility check reads this exact stored value.
            assertThat(context.getStepResults().get(2)).isEqualTo(true);
        }

        @Test
        @DisplayName("an unresolvable expression is false, never an exception")
        void unresolvableIsFalse() {
            ExecutionContext context = contextWithState(Map.of());
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(2).actionType("CONDITION")
                    .conditionExpression("state.missing > 3").build();

            assertThat(handler.execute(step, context).getData()).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("SWITCH")
    class SwitchCase {

        private final SwitchStepHandler handler =
                new SwitchStepHandler(engineUtils, variableContext, schemaHelper);

        @Test
        @DisplayName("stores the evaluated value for sibling case matching")
        void storesValue() {
            ExecutionContext context = contextWithState(Map.of("tier", "gold"));
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(1).actionType("SWITCH")
                    .conditionExpression("state.tier").build();

            StepResult result = handler.execute(step, context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("gold");
            assertThat(context.getStepResults().get(1)).isEqualTo("gold");
        }
    }

    @Nested
    @DisplayName("JUMP")
    class Jump {

        private final JumpHandler handler = new JumpHandler(schemaHelper);

        @Test
        @DisplayName("a numeric target produces a JUMP result with the target in metadata")
        void numericTarget() {
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(5).actionType("JUMP").actionTarget("2").build();

            StepResult result = handler.execute(step, ExecutionContext.builder().build());

            assertThat(result.getStatus()).isEqualTo("JUMP");
            assertThat(result.getMetadata()).containsEntry("targetOrder", 2);
        }

        @Test
        @DisplayName("a non-numeric target is a step error naming the bad value")
        void nonNumericTarget() {
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(5).actionType("JUMP").actionTarget("start").build();

            StepResult result = handler.execute(step, ExecutionContext.builder().build());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("start");
        }

        @Test
        @DisplayName("the authored message rides along, with a fallback when absent")
        void messagePropagation() {
            JourneyStep authored = JourneyStep.builder()
                    .stepOrder(5).actionType("JUMP").actionTarget("2").message("looping back").build();
            assertThat(handler.execute(authored, ExecutionContext.builder().build()).getMessage())
                    .isEqualTo("looping back");

            JourneyStep bare = JourneyStep.builder()
                    .stepOrder(5).actionType("JUMP").actionTarget("2").build();
            assertThat(handler.execute(bare, ExecutionContext.builder().build()).getMessage())
                    .contains("2");
        }
    }

    @Nested
    @DisplayName("RESPONSE")
    class Response {

        private final ResponseStepHandler handler =
                new ResponseStepHandler(engineUtils, variableContext, schemaHelper);

        @Test
        @DisplayName("interpolates placeholders from live variables into the reply")
        void interpolatesPlaceholders() {
            ExecutionContext context = contextWithState(Map.of("name", "Amal"));
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(3).actionType("RESPONSE")
                    .actionTarget("Hello {{state.name}}!").build();

            StepResult result = handler.execute(step, context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("Hello Amal!");
            // RESPONSE speaks its output: data and message are the same text.
            assertThat(result.getMessage()).isEqualTo("Hello Amal!");
            assertThat(context.getStepResults().get(3)).isEqualTo("Hello Amal!");
        }
    }

    @Nested
    @DisplayName("STATE_STORE")
    class StateStore {

        private final StateStoreStepHandler handler =
                new StateStoreStepHandler(engineUtils, variableContext, schemaHelper);

        private JourneyStep stateStep(int order, String configJson) {
            return JourneyStep.builder().stepOrder(order).actionType("STATE_STORE")
                    .apiConfig(configJson).build();
        }

        @Test
        @DisplayName("SET keeps the source value's original type")
        void setPreservesType() {
            ExecutionContext context = contextWithState(Map.of());
            context.getVariables().put("steps",
                    new HashMap<>(Map.of("2", Map.of("output", Map.of("score", 8)))));

            StepResult result = handler.execute(stateStep(3,
                    "{\"variable\":\"score\",\"operation\":\"SET\",\"source\":\"{{steps.2.output.score}}\"}"),
                    context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            // 8, not "8" — the lone-placeholder type-preservation contract.
            assertThat(variableContext.getState(context).get("score"))
                    .isInstanceOf(Integer.class).isEqualTo(8);
        }

        @Test
        @DisplayName("INCREMENT adds to the existing number, starting from zero")
        void increment() {
            ExecutionContext context = contextWithState(Map.of("counter", 2));

            handler.execute(stateStep(1,
                    "{\"variable\":\"counter\",\"operation\":\"INCREMENT\",\"source\":\"3\"}"), context);

            assertThat(variableContext.getState(context).get("counter")).isEqualTo(5);
        }

        @Test
        @DisplayName("INCREMENT with a blank or non-numeric source steps by one")
        void incrementDefaultStep() {
            ExecutionContext context = contextWithState(Map.of());

            handler.execute(stateStep(1,
                    "{\"variable\":\"visits\",\"operation\":\"INCREMENT\",\"source\":\"\"}"), context);
            handler.execute(stateStep(2,
                    "{\"variable\":\"visits\",\"operation\":\"INCREMENT\",\"source\":\"lots\"}"), context);

            assertThat(variableContext.getState(context).get("visits")).isEqualTo(2);
        }

        @Test
        @DisplayName("APPEND grows a list, converting a scalar start into a fresh list")
        void appendBuildsList() {
            ExecutionContext context = contextWithState(Map.of());

            handler.execute(stateStep(1,
                    "{\"variable\":\"items\",\"operation\":\"APPEND\",\"source\":\"a\"}"), context);
            handler.execute(stateStep(2,
                    "{\"variable\":\"items\",\"operation\":\"APPEND\",\"source\":\"b\"}"), context);

            assertThat(variableContext.getState(context).get("items"))
                    .isEqualTo(List.of("a", "b"));
        }

        @Test
        @DisplayName("a missing variable name is a step error, not a silent write")
        void missingVariableName() {
            ExecutionContext context = contextWithState(Map.of());
            StepResult result = handler.execute(stateStep(1, "{\"operation\":\"SET\",\"source\":\"x\"}"), context);
            assertThat(result.getStatus()).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("state writes record provenance so a backward JUMP can roll them back")
        void provenanceRecorded() {
            ExecutionContext context = contextWithState(Map.of());
            handler.execute(stateStep(4,
                    "{\"variable\":\"k\",\"operation\":\"SET\",\"source\":\"v\"}"), context);

            variableContext.clearStateWrittenFrom(context, 4);

            assertThat(variableContext.getState(context)).doesNotContainKey("k");
        }
    }
}
