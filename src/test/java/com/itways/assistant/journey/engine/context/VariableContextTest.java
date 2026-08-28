package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * VariableContext owns the five variable namespaces and the state-provenance
 * bookkeeping that lets a backward JUMP roll back exactly what it replays.
 * These are the invariants the whole engine leans on.
 */
@DisplayName("VariableContext")
class VariableContextTest {

    private final VariableContext variableContext = new VariableContext();

    private static ExecutionContext freshContext() {
        return ExecutionContext.builder().variables(new HashMap<>()).build();
    }

    private static JourneyStep stepAt(int order) {
        return JourneyStep.builder().stepOrder(order).build();
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        @Test
        @DisplayName("ensureStructure creates all five namespaces, including on a null map")
        void createsNamespaces() {
            ExecutionContext context = ExecutionContext.builder().variables(null).build();
            variableContext.ensureStructure(context);
            assertThat(context.getVariables())
                    .containsKeys("inputs", "steps", "state", "runtime", "channel");
        }

        @Test
        @DisplayName("ensureStructure never clobbers namespaces that already hold data")
        void idempotent() {
            ExecutionContext context = freshContext();
            variableContext.ensureStructure(context);
            variableContext.getState(context).put("kept", 1);
            variableContext.ensureStructure(context);
            assertThat(variableContext.getState(context)).containsEntry("kept", 1);
        }
    }

    @Nested
    @DisplayName("mergeInputs")
    class MergeInputs {

        @Test
        @DisplayName("routes text, files, and answer into inputs, everything else into inputs.entities")
        void routing() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put("text", "hello");
            params.put("answer", "yes");
            params.put("orderId", "A-17");

            variableContext.mergeInputs(context, params);

            Map<String, Object> inputs = variableContext.getInputs(context);
            assertThat(inputs).containsEntry("text", "hello").containsEntry("answer", "yes");
            assertThat(inputs.get("orderId")).isNull();
            assertThat(inputs.get("entities")).asInstanceOf(MAP).containsEntry("orderId", "A-17");
        }

        @Test
        @DisplayName("reserved keys never leak into entities")
        void reservedKeysExcluded() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put("text", "t");
            params.put("forceIntent", "greet");
            params.put("language", "ar");
            params.put("channel", Map.of("user", Map.of("id", "u1")));

            variableContext.mergeInputs(context, params);

            // `language` in entities would look authoritative to an author while
            // changing nothing — the exact trap RESERVED_KEYS exists to prevent.
            assertThat(variableContext.getInputs(context).get("entities")).asInstanceOf(MAP)
                    .doesNotContainKeys("text", "forceIntent", "language", "channel");
        }

        @Test
        @DisplayName("an explicit entities map merges with loose parameters")
        void entitiesMapMerges() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put("entities", Map.of("email", "a@b.c"));
            params.put("phone", "0790");

            variableContext.mergeInputs(context, params);

            assertThat(variableContext.getInputs(context).get("entities")).asInstanceOf(MAP)
                    .containsEntry("email", "a@b.c")
                    .containsEntry("phone", "0790");
        }

        @Test
        @DisplayName("channel data merges into the channel namespace, and forceIntent sits at the root")
        void channelAndForceIntent() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put("channel", Map.of("type", "TELEGRAM"));
            params.put("forceIntent", "order-status");

            variableContext.mergeInputs(context, params);

            assertThat(variableContext.getChannel(context)).containsEntry("type", "TELEGRAM");
            assertThat(context.getVariables()).containsEntry("forceIntent", "order-status");
        }

        @Test
        @DisplayName("null and empty parameter maps are a no-op that still builds structure")
        void nullParams() {
            ExecutionContext context = freshContext();
            variableContext.mergeInputs(context, null);
            assertThat(context.getVariables()).containsKeys("inputs", "steps", "state");
        }
    }

    @Nested
    @DisplayName("step outputs")
    class StepOutputs {

        @Test
        @DisplayName("storeOutput writes both the variable bucket and the stepResults map")
        void storeOutputWritesBoth() {
            ExecutionContext context = freshContext();
            variableContext.storeOutput(context, stepAt(3), "value");

            assertThat(variableContext.read(context, "steps.3.output")).isEqualTo("value");
            assertThat(context.getStepResults()).containsEntry(3, "value");
        }

        @Test
        @DisplayName("writeStepField adds fields without clobbering siblings")
        void fieldsCoexist() {
            ExecutionContext context = freshContext();
            JourneyStep step = stepAt(2);
            variableContext.writeStepOutput(context, step, "out");
            variableContext.writeStepField(context, step, "contentType", "text/html");

            assertThat(variableContext.read(context, "steps.2.output")).isEqualTo("out");
            assertThat(variableContext.read(context, "steps.2.contentType")).isEqualTo("text/html");
        }

        @Test
        @DisplayName("clearStepOutputsFromOrder removes outputs and stepResults at and after the order")
        void clearFromOrder() {
            ExecutionContext context = freshContext();
            variableContext.storeOutput(context, stepAt(1), "one");
            variableContext.storeOutput(context, stepAt(2), "two");
            variableContext.storeOutput(context, stepAt(3), "three");

            variableContext.clearStepOutputsFromOrder(context, 2);

            assertThat(variableContext.read(context, "steps.1.output")).isEqualTo("one");
            assertThat(variableContext.read(context, "steps.2.output")).isNull();
            assertThat(variableContext.read(context, "steps.3.output")).isNull();
            assertThat(context.getStepResults()).containsOnlyKeys(1);
        }
    }

    @Nested
    @DisplayName("state provenance")
    class StateProvenance {

        @Test
        @DisplayName("clearStateWrittenFrom removes only entries written by replayed steps")
        void rollbackIsExact() {
            // The JUMP contract: without provenance-based rollback, an INCREMENT
            // written by a replayed step compounds on every pass through the loop.
            ExecutionContext context = freshContext();
            variableContext.writeState(context, stepAt(1), "keep", "early");
            variableContext.writeState(context, stepAt(4), "drop", "late");

            variableContext.clearStateWrittenFrom(context, 3);

            assertThat(variableContext.getState(context))
                    .containsEntry("keep", "early")
                    .doesNotContainKey("drop");
        }

        @Test
        @DisplayName("a later write by an earlier step re-owns the key")
        void latestWriterOwnsKey() {
            ExecutionContext context = freshContext();
            variableContext.writeState(context, stepAt(4), "counter", 1);
            variableContext.writeState(context, stepAt(2), "counter", 2);

            // Step 2 wrote last, so a rollback from order 3 keeps the key.
            variableContext.clearStateWrittenFrom(context, 3);

            assertThat(variableContext.getState(context)).containsEntry("counter", 2);
        }

        @Test
        @DisplayName("provenance lives in engine internals, invisible to journey authors")
        void provenanceIsInternal() {
            ExecutionContext context = freshContext();
            variableContext.writeState(context, stepAt(1), "k", "v");
            assertThat(context.getVariables()).doesNotContainKey("_stateWrites");
            assertThat(context.getInternal("_stateWrites")).isNotNull();
        }

        @Test
        @DisplayName("rollback with no recorded writes is a no-op")
        void emptyProvenance() {
            ExecutionContext context = freshContext();
            variableContext.getState(context).put("preexisting", 1);
            variableContext.clearStateWrittenFrom(context, 1);
            assertThat(variableContext.getState(context)).containsEntry("preexisting", 1);
        }
    }

    @Test
    @DisplayName("resolveForTemplate substitutes placeholders against the live variables")
    void resolveForTemplate() {
        ExecutionContext context = freshContext();
        variableContext.storeOutput(context, stepAt(1), Map.of("name", "Amal"));
        assertThat(variableContext.resolveForTemplate(context, "Hi {{steps.1.output.name}}"))
                .isEqualTo("Hi Amal");
    }
}
