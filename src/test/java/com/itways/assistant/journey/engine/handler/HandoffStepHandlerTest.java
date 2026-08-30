package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HANDOFF is the platform's only escalation path, and the thing it has to get
 * right is stopping: a bot that announces "a colleague will take over" and then
 * asks its next scripted question has handed nothing over. It therefore ends
 * the run rather than parking it — a parked run would swallow the user's next
 * message straight back into the journey a person was supposed to take from.
 */
@DisplayName("HandoffStepHandler")
class HandoffStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineMessages messages = new EngineMessages();
    private final HandoffStepHandler handler = new HandoffStepHandler(
            new EngineUtils(objectMapper), variableContext,
            new StepOutputSchemaHelper(objectMapper), messages);

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .executionId("exec-1")
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("orderId", "A-42"));
        return context;
    }

    private static JourneyStep step(String message, String apiConfig) {
        return JourneyStep.builder()
                .stepOrder(3).stepName("Escalate").actionType("HANDOFF")
                .message(message).apiConfig(apiConfig).build();
    }

    @Nested
    @DisplayName("ending the conversation")
    class Ending {

        @Test
        @DisplayName("completes the run so no later step executes after a person takes over")
        void endsTheRun() {
            ExecutionContext context = context();

            StepResult result = handler.execute(step("Putting you through.", null), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            // Not WAITING_FOR_INPUT: that would resume this journey on the
            // user's next message, which is exactly what must not happen.
            assertThat(context.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("says the author's line, interpolated")
        void speaksTheAuthorsLine() {
            StepResult result = handler.execute(
                    step("Someone will pick up order {{inputs.entities.orderId}}.", null), context());

            assertThat(result.getMessage()).isEqualTo("Someone will pick up order A-42.");
        }

        @Test
        @DisplayName("with no authored line the engine speaks in the run's language")
        void fallsBackToEngineWording() {
            StepResult result = handler.execute(step(null, null), context());

            assertThat(result.getMessage())
                    .isEqualTo(messages.get(ConversationLanguage.ENGLISH, "step.handoff.waiting"));
        }
    }

    @Nested
    @DisplayName("what it hands over")
    class Payload {

        private static final String CONFIG =
                "{\"queue\":\"billing\",\"note\":\"Disputed order {{inputs.entities.orderId}}\","
                        + "\"timeoutMinutes\":30}";

        @Test
        @DisplayName("publishes queue and note as metadata — the host is what actually reaches a human")
        void publishesHandoffMetadata() {
            StepResult result = handler.execute(step("One moment.", CONFIG), context());

            assertThat(result.getMetadata())
                    .containsEntry("handoff", true)
                    .containsEntry("queue", "billing")
                    .containsEntry("subStatus", "HANDED_OFF")
                    .containsEntry("timeoutMinutes", 30)
                    // The note is what an agent reads first, so it interpolates.
                    .containsEntry("note", "Disputed order A-42");
        }

        @Test
        @DisplayName("stores handedOff and the queue so a later journey can branch on the escalation")
        void storesBranchableOutput() {
            ExecutionContext context = context();

            handler.execute(step("One moment.", CONFIG), context);

            assertThat(variableContext.read(context, "steps.3.output.handedOff")).isEqualTo(true);
            assertThat(variableContext.read(context, "steps.3.output.queue")).isEqualTo("billing");
        }

        @Test
        @DisplayName("an unconfigured step still hands off — queue and note are optional")
        void worksWithNoConfiguration() {
            StepResult result = handler.execute(step(null, null), context());

            assertThat(result.getMetadata()).containsEntry("handoff", true);
            assertThat(result.getMetadata()).containsEntry("queue", null);
            assertThat(result.getMetadata()).doesNotContainKey("timeoutMinutes");
        }

        @Test
        @DisplayName("a blank queue reads as none rather than as an empty routing label")
        void blankQueueIsNull() {
            StepResult result = handler.execute(step(null, "{\"queue\":\"   \"}"), context());

            assertThat(result.getMetadata()).containsEntry("queue", null);
        }
    }
}
