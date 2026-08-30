package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rehearsal flag's one hard rule: a journey must never be able to see it.
 *
 * <p>
 * Everything in the variable map is serialised into run history, the builder's
 * variable picker, the CODE_SCRIPT sandbox and the DATA_MAP prompt. If
 * {@code simulate} survived there, an author could branch on being under test —
 * and a journey that behaves differently in rehearsal is a journey the
 * rehearsal proves nothing about.
 */
@DisplayName("Simulation")
class SimulationTest {

    private final VariableContext variableContext = new VariableContext();

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        return context;
    }

    @Nested
    @DisplayName("lifting the flag")
    class Lifting {

        @Test
        @DisplayName("a boolean true from the console marks the run and leaves the variables clean")
        void booleanTrue() {
            ExecutionContext context = context();
            Map<String, Object> params = new HashMap<>();
            params.put(Simulation.PARAM_SIMULATE, true);

            Simulation.lift(context, params);

            assertThat(Simulation.isActive(context)).isTrue();
            assertThat(context.getVariables()).doesNotContainKey(Simulation.PARAM_SIMULATE);
            assertThat(params).doesNotContainKey(Simulation.PARAM_SIMULATE);
        }

        @Test
        @DisplayName("the string form is accepted — a channel or hand-written request sends text")
        void stringTrue() {
            ExecutionContext context = context();
            Map<String, Object> params = new HashMap<>();
            params.put(Simulation.PARAM_SIMULATE, "true");

            Simulation.lift(context, params);

            assertThat(Simulation.isActive(context)).isTrue();
        }

        @Test
        @DisplayName("anything else runs for real — defaulting to live is the safe direction")
        void anythingElseIsReal() {
            for (Object value : new Object[] { null, false, "false", "yes", 1, "" }) {
                ExecutionContext context = context();
                Map<String, Object> params = new HashMap<>();
                params.put(Simulation.PARAM_SIMULATE, value);

                Simulation.lift(context, params);

                assertThat(Simulation.isActive(context))
                        .withFailMessage("value %s should not have started a simulation", value)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("an ordinary run is untouched")
        void noFlagIsANoOp() {
            ExecutionContext context = context();

            Simulation.lift(context, new HashMap<>());

            assertThat(Simulation.isActive(context)).isFalse();
            assertThat(context.getInternal(Simulation.INTERNAL_SIMULATE)).isNull();
        }

        @Test
        @DisplayName("the flag never reaches the variable map, whichever way it arrived")
        void neverReachesVariables() {
            // The whole reason it lives in `internal`: a journey that can read
            // {{simulate}} can behave differently under test.
            ExecutionContext context = context();
            context.getVariables().put(Simulation.PARAM_SIMULATE, true);

            Simulation.lift(context, null);

            assertThat(Simulation.isActive(context)).isTrue();
            assertThat(context.getVariables()).doesNotContainKey(Simulation.PARAM_SIMULATE);
        }
    }

    @Nested
    @DisplayName("surviving a turn")
    class Surviving {

        @Test
        @DisplayName("a resumed rehearsal stays a rehearsal even when the turn does not re-send the flag")
        void survivesWithoutBeingResent() {
            // Multi-turn simulation: the flag rides `internal`, which is
            // persisted with the parked context.
            ExecutionContext context = context();
            Simulation.lift(context, new HashMap<>(Map.of(Simulation.PARAM_SIMULATE, true)));

            Simulation.lift(context, new HashMap<>(Map.of("answer", "blue")));

            assertThat(Simulation.isActive(context)).isTrue();
        }
    }
}
