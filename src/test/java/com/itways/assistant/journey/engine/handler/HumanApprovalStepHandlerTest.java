package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HUMAN_APPROVAL is a governance gate. The three contracts pinned here each
 * cover a real past failure mode: any non-null answer used to count as
 * approval (so "no" granted what it refused), a rejection used to let the run
 * walk into the protected step, and an expired gate used to wait forever.
 */
@DisplayName("HumanApprovalStepHandler")
class HumanApprovalStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private HumanApprovalStepHandler handler;

    @BeforeEach
    void buildHandler() {
        handler = new HumanApprovalStepHandler(
                new EngineUtils(objectMapper), variableContext,
                new StepOutputSchemaHelper(objectMapper), new EngineMessages());
        // @PostConstruct does not run outside Spring; without this call both
        // vocabularies are empty and every answer reads as "unclear".
        handler.loadDecisionVocabulary();
    }

    private static ExecutionContext contextWithAnswer(Object answer) {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        VariableContext variables = new VariableContext();
        variables.ensureStructure(context);
        if (answer != null) {
            variables.getInputs(context).put("answer", answer);
        }
        return context;
    }

    private static JourneyStep approvalStep() {
        return JourneyStep.builder().stepOrder(3).actionType("HUMAN_APPROVAL").build();
    }

    @Nested
    @DisplayName("asking for a decision")
    class Asking {

        @Test
        @DisplayName("with no answer, the step parks the run and opens the timeout window")
        void parksAndOpensWindow() {
            ExecutionContext context = contextWithAnswer(null);

            StepResult result = handler.execute(approvalStep(), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
            assertThat(result.getMetadata())
                    .containsEntry("type", "HUMAN_GOVERNANCE")
                    .containsEntry("approvalMode", "SELF_CONFIRM")
                    .containsEntry("awaiting", true);
            // Default window: 24 hours from now, stored as an absolute instant.
            Instant deadline = Instant.parse(String.valueOf(context.getInternal("approvalDeadline_3")));
            assertThat(deadline).isAfter(Instant.now().plusSeconds(23 * 3600));
        }

        @Test
        @DisplayName("an unclear reply is consumed and re-prompted while the deadline keeps running")
        void unclearReplyReprompts() {
            ExecutionContext context = contextWithAnswer("maybe later");
            String existingDeadline = Instant.now().plusSeconds(600).toString();
            context.setInternal("approvalDeadline_3", existingDeadline);

            StepResult result = handler.execute(approvalStep(), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            // The answer must be consumed — otherwise the same unclear text
            // re-triggers this branch forever on every resume.
            assertThat(variableContext.getInputs(context)).doesNotContainKey("answer");
            // And the window is not restarted by an unclear reply.
            assertThat(context.getInternal("approvalDeadline_3")).isEqualTo(existingDeadline);
        }
    }

    @Nested
    @DisplayName("deciding")
    class Deciding {

        @Test
        @DisplayName("an approval word succeeds and stores TRUE for downstream branching")
        void approval() {
            ExecutionContext context = contextWithAnswer("yes");

            StepResult result = handler.execute(approvalStep(), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(context.getStepResults().get(3)).isEqualTo(Boolean.TRUE);
            assertThat(context.getInternal("approvalDeadline_3")).isNull();
        }

        @Test
        @DisplayName("trailing punctuation does not stop a word counting as a decision")
        void punctuationStripped() {
            assertThat(handler.execute(approvalStep(), contextWithAnswer("yes!")).getStatus())
                    .isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("a rejection halts the run — and still stores FALSE for an explicit rejection path")
        void rejectionHalts() {
            ExecutionContext context = contextWithAnswer("no");

            StepResult result = handler.execute(approvalStep(), context);

            // Success here would let the engine walk into the very step the
            // gate protects: answering "reject" would perform the refused action.
            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getUserMessage()).isNotBlank();
            assertThat(context.getStepResults().get(3)).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("structured boolean answers decide directly")
        void booleanAnswers() {
            assertThat(handler.execute(approvalStep(), contextWithAnswer(Boolean.TRUE)).getStatus())
                    .isEqualTo("SUCCESS");
            assertThat(handler.execute(approvalStep(), contextWithAnswer(Boolean.FALSE)).getStatus())
                    .isEqualTo("ERROR");
        }

        @Test
        @DisplayName("Arabic decision words work in an English conversation — vocabulary is pooled")
        void pooledVocabulary() {
            // People answer in whatever is fastest to type; refusing "نعم" because
            // the conversation is in English would be a regression dressed up as
            // correctness.
            assertThat(handler.execute(approvalStep(), contextWithAnswer("نعم")).getStatus())
                    .isEqualTo("SUCCESS");
        }
    }

    @Nested
    @DisplayName("timeout")
    class Timeout {

        @Test
        @DisplayName("an elapsed deadline auto-rejects — nothing is approved by inaction")
        void elapsedDeadlineAutoRejects() {
            ExecutionContext context = contextWithAnswer(null);
            context.setInternal("approvalDeadline_3", Instant.now().minusSeconds(60).toString());

            StepResult result = handler.execute(approvalStep(), context);

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(context.getStepResults().get(3)).isEqualTo(Boolean.FALSE);
            assertThat(context.getInternal("approvalDeadline_3")).isNull();
        }

        @Test
        @DisplayName("an unreadable deadline is ignored rather than trapping or auto-rejecting the run")
        void unreadableDeadlineIgnored() {
            ExecutionContext context = contextWithAnswer(null);
            context.setInternal("approvalDeadline_3", "garbage");

            StepResult result = handler.execute(approvalStep(), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
        }
    }
}
