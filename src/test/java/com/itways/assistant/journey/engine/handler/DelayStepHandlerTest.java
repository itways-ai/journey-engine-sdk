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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DELAY is a deadline gate, not a timer: the platform has no scheduler, so the
 * step parks the run and clears itself on the first resume after the deadline.
 * The deadline lives in engine internals as an ISO instant, which is also what
 * makes these tests deterministic — a past or future instant is pre-seeded
 * instead of sleeping.
 */
@DisplayName("DelayStepHandler")
class DelayStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final DelayStepHandler handler = new DelayStepHandler(
            new EngineUtils(objectMapper), variableContext, new StepOutputSchemaHelper(objectMapper));

    private static ExecutionContext freshContext() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        new VariableContext().ensureStructure(context);
        return context;
    }

    private static JourneyStep delayStep(String configJson) {
        return JourneyStep.builder().stepOrder(2).actionType("DELAY").apiConfig(configJson).build();
    }

    @Test
    @DisplayName("first entry parks the run and stores an absolute deadline")
    void firstEntryParks() {
        ExecutionContext context = freshContext();

        StepResult result = handler.execute(delayStep("{\"duration\":10,\"unit\":\"MINUTES\"}"), context);

        assertThat(result.getStatus()).isEqualTo("WAITING");
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        assertThat(result.getMetadata()).containsEntry("type", "TEMPORAL_PAUSE")
                .containsKeys("resumeAt");

        Instant deadline = Instant.parse(String.valueOf(context.getInternal("delayDeadline_2")));
        assertThat(deadline).isAfter(Instant.now().plusSeconds(9 * 60));
    }

    @Test
    @DisplayName("a resume before the deadline parks again on the same deadline")
    void resumeBeforeDeadlineStillWaits() {
        ExecutionContext context = freshContext();
        String future = Instant.now().plusSeconds(3600).toString();
        context.setInternal("delayDeadline_2", future);

        StepResult result = handler.execute(delayStep("{\"duration\":1,\"unit\":\"HOURS\"}"), context);

        assertThat(result.getStatus()).isEqualTo("WAITING");
        // The stored deadline is untouched — re-entry must not restart the clock.
        assertThat(context.getInternal("delayDeadline_2")).isEqualTo(future);
    }

    @Test
    @DisplayName("a resume after the deadline clears the gate and succeeds")
    void resumeAfterDeadlineClears() {
        ExecutionContext context = freshContext();
        context.setInternal("delayDeadline_2", Instant.now().minusSeconds(5).toString());

        StepResult result = handler.execute(delayStep("{\"duration\":5,\"unit\":\"SECONDS\"}"), context);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(context.getInternal("delayDeadline_2")).isNull();
        assertThat(variableContext.read(context, "steps.2.output")).isNotNull();
    }

    @Test
    @DisplayName("resumeOnEvent cuts the wait short on any re-entry, even before the deadline")
    void resumeOnEventShortCircuits() {
        ExecutionContext context = freshContext();
        context.setInternal("delayDeadline_2", Instant.now().plusSeconds(3600).toString());

        StepResult result = handler.execute(
                delayStep("{\"duration\":1,\"unit\":\"HOURS\",\"resumeOnEvent\":true}"), context);

        // Re-entry means something resumed this run — typically the user's next
        // message — which the author asked to break the delay early.
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(context.getInternal("delayDeadline_2")).isNull();
    }

    @Test
    @DisplayName("an unreadable stored deadline is treated as elapsed rather than trapping the run")
    void unreadableDeadlineDoesNotTrap() {
        ExecutionContext context = freshContext();
        context.setInternal("delayDeadline_2", "not-a-timestamp");

        StepResult result = handler.execute(delayStep("{\"duration\":5}"), context);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("missing config falls back to five seconds")
    void defaultDuration() {
        ExecutionContext context = freshContext();

        handler.execute(delayStep(null), context);

        Instant deadline = Instant.parse(String.valueOf(context.getInternal("delayDeadline_2")));
        // 5 SECONDS default: the deadline is near-term, not the MINUTES/HOURS scale.
        assertThat(deadline).isBefore(Instant.now().plusSeconds(60));
    }
}
