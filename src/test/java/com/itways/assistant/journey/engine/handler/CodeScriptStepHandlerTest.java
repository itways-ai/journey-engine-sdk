package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
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
 * CODE_SCRIPT runs author-written JavaScript on the real GraalVM engine — no
 * stubbing, because the contract under test is precisely the bridge: variables
 * arrive as plain JS objects (the JSON round-trip is what makes
 * {@code steps['1'].output.score} work at all), the script's completion value
 * is the step's output, and whatever comes back crosses into plain Java maps
 * and lists so downstream steps can address it. A broken script must fail the
 * step, never the engine.
 */
@DisplayName("CodeScriptStepHandler")
class CodeScriptStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final CodeScriptStepHandler handler = new CodeScriptStepHandler(
            new EngineUtils(objectMapper), variableContext,
            new StepOutputSchemaHelper(objectMapper), objectMapper);

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("text", "hello"));
        variableContext.writeStepField(context,
                JourneyStep.builder().stepOrder(1).build(), "output", Map.of("score", 8));
        return context;
    }

    private JourneyStep scriptStep(String code) {
        String apiConfig;
        try {
            apiConfig = objectMapper.writeValueAsString(Map.of("code", code));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return JourneyStep.builder()
                .stepOrder(3).stepName("Compute").actionType("CODE_SCRIPT")
                .apiConfig(apiConfig).build();
    }

    @Nested
    @DisplayName("running a script")
    class Running {

        @Test
        @DisplayName("computes over journey variables and stores the last expression as the output")
        void computesFromContextVariables() {
            ExecutionContext context = context();

            StepResult result = handler.execute(scriptStep("steps['1'].output.score * 2"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(16);
            assertThat(variableContext.read(context, "steps.3.output")).isEqualTo(16);
        }

        @Test
        @DisplayName("exposes inputs as a plain JS object, not a Java map")
        void inputsAreliveJsObjects() {
            // Property access and String methods both fail on a raw Java Map;
            // the JSON round-trip is what this test protects.
            StepResult result = handler.execute(
                    scriptStep("inputs.text.toUpperCase()"), context());

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("returned objects cross the bridge as Java maps and lists")
        void objectsConvertToJavaTypes() {
            ExecutionContext context = context();

            StepResult result = handler.execute(scriptStep(
                    "var r = { name: 'x', count: 3, tags: ['a', 'b'] }; r"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(
                    Map.of("name", "x", "count", 3, "tags", List.of("a", "b")));
            // Downstream steps address into the converted structure by path.
            assertThat(variableContext.read(context, "steps.3.output.tags")).isEqualTo(List.of("a", "b"));
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a script that throws becomes a step ERROR carrying the script's own message")
        void throwingScriptBecomesStepError() {
            StepResult result = handler.execute(scriptStep("throw new Error('boom')"), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("CODE_SCRIPT execution failed").contains("boom");
        }

        @Test
        @DisplayName("a syntactically invalid script becomes a step ERROR, not a crashed run")
        void invalidSyntaxBecomesStepError() {
            StepResult result = handler.execute(scriptStep("function ("), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("CODE_SCRIPT execution failed");
        }

        @Test
        @DisplayName("an infinite loop is stopped by the statement limit, not left hanging the turn")
        void infiniteLoopIsStopped() {
            // Scripts run on the request thread; before resource limits existed a
            // while(true) parked the conversation forever. The statement limit
            // stops it deterministically, without waiting for the wall clock.
            StepResult result = handler.execute(scriptStep("while (true) {}"), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("execution limits");
        }

        @Test
        @DisplayName("scripts cannot reach Java host classes")
        void hostAccessIsClosed() {
            // HostAccess.NONE: the only thing a script may touch is its own
            // variables. `Java.type` is the interop door and must not exist.
            StepResult result = handler.execute(
                    scriptStep("typeof Java === 'undefined' ? 'sealed' : Java.type('java.lang.Runtime').toString()"),
                    context());

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("sealed");
        }

        @Test
        @DisplayName("a step with no code fails plainly instead of evaluating nothing")
        void missingCodeFails() {
            JourneyStep step = JourneyStep.builder()
                    .stepOrder(3).stepName("Compute").actionType("CODE_SCRIPT")
                    .apiConfig("{}").build();

            StepResult result = handler.execute(step, context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("script code is required");
        }
    }
}
