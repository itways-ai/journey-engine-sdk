package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.DecisionWords;
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
 * USER_INPUT is the step that parks a run on the user, so what it publishes
 * while waiting is a client-facing contract: the prompt, the form metadata and
 * the resubmit flag are what every channel renders. The other contract pinned
 * here is INTERACTIVE's one-shot confirmation loop — its awaiting marker is
 * what makes the second pass count as the confirmation; without it a
 * plain-text reply (every channel user) re-triggered the prompt forever.
 */
@DisplayName("UserInputStepHandler")
class UserInputStepHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineMessages messages = new EngineMessages();
    private final DecisionWords decisionWords = decisionWords();
    private final UserInputStepHandler handler = new UserInputStepHandler(
            new EngineUtils(objectMapper), variableContext,
            new StepOutputSchemaHelper(objectMapper), messages, decisionWords);

    /** @PostConstruct does not run outside Spring, so load() is called by hand. */
    private static DecisionWords decisionWords() {
        DecisionWords words = new DecisionWords(new EngineMessages());
        words.load();
        return words;
    }

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("name", "Sarah"));
        return context;
    }

    private ExecutionContext contextWithAnswer(Object answer) {
        ExecutionContext context = context();
        variableContext.getInputs(context).put("answer", answer);
        return context;
    }

    private static JourneyStep step(String message, String apiConfig) {
        return JourneyStep.builder()
                .stepOrder(4).stepName("Collect colour").actionType("USER_INPUT")
                .message(message).apiConfig(apiConfig).build();
    }

    @Nested
    @DisplayName("asking for input")
    class Asking {

        @Test
        @DisplayName("with no answer, the step parks the run with the interpolated authored prompt")
        void parksWithAuthoredPrompt() {
            ExecutionContext context = context();

            StepResult result = handler.execute(
                    step("What colour, {{inputs.entities.name}}?", null), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getMessage()).isEqualTo("What colour, Sarah?");
            assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        }

        @Test
        @DisplayName("publishes the form contract the client renders while waiting")
        void publishesFormMetadata() {
            StepResult result = handler.execute(step("Pick one", null), context());

            assertThat(result.getMetadata())
                    .containsEntry("stepName", "Collect colour")
                    .containsEntry("inputMode", "FREE_TEXT")
                    .containsEntry("allowResubmit", false)
                    .containsKey("formConfig")
                    // FREE_TEXT is the plain prompt; no form sub-status.
                    .doesNotContainKey("subStatus");
            @SuppressWarnings("unchecked")
            Map<String, Object> formConfig = (Map<String, Object>) result.getMetadata().get("formConfig");
            assertThat(formConfig).containsOnlyKeys("fields", "rules");
        }

        @Test
        @DisplayName("with no authored prompt, the engine speaks for the step in the run's language")
        void fallsBackToEnginePrompt() {
            StepResult result = handler.execute(step(null, null), context());

            assertThat(result.getMessage()).isEqualTo(
                    messages.get(ConversationLanguage.ENGLISH, "step.userInput.waiting", "Collect colour"));
        }

        @Test
        @DisplayName("STRUCTURED mode announces a direct form and carries the authored fields")
        void structuredModeOpensDirectForm() {
            StepResult result = handler.execute(step("Fill this in",
                    "{\"inputMode\":\"STRUCTURED\",\"allowResubmit\":true,"
                            + "\"fields\":[{\"name\":\"colour\"}]}"), context());

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getMetadata())
                    .containsEntry("subStatus", "DIRECT_FORM")
                    .containsEntry("inputMode", "STRUCTURED")
                    .containsEntry("allowResubmit", true);
            @SuppressWarnings("unchecked")
            Map<String, Object> formConfig = (Map<String, Object>) result.getMetadata().get("formConfig");
            assertThat(formConfig.get("fields")).asString().contains("colour");
        }
    }

    @Nested
    @DisplayName("receiving an answer")
    class Answering {

        @Test
        @DisplayName("consumes the answer exactly once and stores it as the step's output")
        void consumesAnswerAndStoresOutput() {
            ExecutionContext context = contextWithAnswer("blue");

            StepResult result = handler.execute(step("Thanks {{inputs.entities.name}}", null), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("blue");
            assertThat(result.getMessage()).isEqualTo("Thanks Sarah");
            assertThat(variableContext.read(context, "steps.4.output")).isEqualTo("blue");
            // The answer must be consumed — leaving it in inputs would make the
            // next USER_INPUT step in the journey swallow it as its own.
            assertThat(variableContext.getInputs(context)).doesNotContainKey("answer");
        }
    }

    @Nested
    @DisplayName("INTERACTIVE confirmation loop")
    class Interactive {

        private static final String CONFIG = "{\"inputMode\":\"INTERACTIVE\"}";

        @Test
        @DisplayName("a free-text answer is parsed back to the user once for confirmation")
        void firstPassAsksForConfirmation() {
            ExecutionContext context = contextWithAnswer("blue dress, size M");

            StepResult result = handler.execute(step(null, CONFIG), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
            assertThat(result.getMessage()).isEqualTo(
                    messages.get(ConversationLanguage.ENGLISH, "step.userInput.confirm"));
            assertThat(result.getMetadata())
                    .containsEntry("subStatus", "CONFIRMATION_REQUIRED")
                    .containsEntry("parsedData", "blue dress, size M");
            // The marker is the whole mechanism: it is what makes the next pass
            // count as the confirmation instead of a brand-new answer.
            assertThat(context.getInternal(UserInputStepHandler.AWAITING_CONFIRM_PREFIX + 4))
                    .isEqualTo(true);
            assertThat(variableContext.getInputs(context)).doesNotContainKey("answer");
        }

        @Test
        @DisplayName("the second pass counts as the confirmation and clears the marker")
        void secondPassConfirms() {
            ExecutionContext context = contextWithAnswer("yes");
            context.setInternal(UserInputStepHandler.AWAITING_CONFIRM_PREFIX + 4, true);

            StepResult result = handler.execute(step(null, CONFIG), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            // A stale marker would turn every later pass through this step into
            // a silent auto-confirm.
            assertThat(context.getInternal(UserInputStepHandler.AWAITING_CONFIRM_PREFIX + 4)).isNull();
            // NOTE: possible defect — the confirmation reply overwrites the
            // parsed first-pass answer: steps.4.output is now "yes", not the
            // data the user was asked to confirm. Pinned as current behavior.
            assertThat(result.getData()).isEqualTo("yes");
            assertThat(variableContext.read(context, "steps.4.output")).isEqualTo("yes");
        }

        @Test
        @DisplayName("a structured answer skips confirmation — it was not free text needing verification")
        void structuredAnswerSkipsConfirmation() {
            ExecutionContext context = contextWithAnswer(Map.of("colour", "blue"));

            StepResult result = handler.execute(step(null, CONFIG), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(Map.of("colour", "blue"));
            assertThat(context.getInternal(UserInputStepHandler.AWAITING_CONFIRM_PREFIX + 4)).isNull();
        }
    }

    /**
     * Using an answer the conversation already established, instead of asking
     * for it again.
     *
     * <p>
     * The case this exists for: a user searches for a task, then says "mark it
     * done". The classifier resolves the reference and extracts
     * {@code entities.task}, and without this the next journey asked "which
     * task?" anyway — having just been told.
     *
     * <p>
     * It confirms rather than assumes, deliberately. The steps that most want a
     * pre-fill are the ones that complete, delegate and archive things, and the
     * author wrote that question as a checkpoint. One "yes" keeps the checkpoint
     * and still saves the user retyping what they just said.
     */
    @Nested
    @DisplayName("pre-filling from an extracted entity")
    class PreFilling {

        private static final String FILL_TASK = "{\"inputMode\":\"FREE_TEXT\",\"fillFrom\":\"task\"}";

        private ExecutionContext contextWithEntity(String key, Object value) {
            ExecutionContext context = context();
            @SuppressWarnings("unchecked")
            Map<String, Object> entities =
                    (Map<String, Object>) variableContext.getInputs(context).get("entities");
            entities.put(key, value);
            return context;
        }

        @Test
        @DisplayName("without fillFrom nothing changes, so journeys authored before this are untouched")
        void inertWithoutOptIn() {
            ExecutionContext context = contextWithEntity("task", "board deck");

            StepResult result = handler.execute(step("Which task?", null), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getMessage()).isEqualTo("Which task?");
            assertThat(result.getMetadata()).doesNotContainKey("prefill");
        }

        @Test
        @DisplayName("offers the entity back for a yes instead of asking cold")
        void offersTheValue() {
            ExecutionContext context = contextWithEntity("task", "board deck");

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getMessage()).contains("board deck");
            assertThat(result.getMetadata())
                    .containsEntry("subStatus", "PREFILL_CONFIRMATION")
                    .containsEntry("prefill", "board deck");
            assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        }

        @Test
        @DisplayName("a distinct sub-status, so a client cannot render it as the INTERACTIVE form")
        void doesNotReuseInteractiveSubStatus() {
            // CONFIRMATION_REQUIRED means "correct this form and submit it"; a
            // client that showed a form here would submit its contents as a
            // yes/no decision.
            StepResult result = handler.execute(
                    step("Which task?", FILL_TASK), contextWithEntity("task", "board deck"));

            assertThat(result.getMetadata()).doesNotContainEntry("subStatus", "CONFIRMATION_REQUIRED");
        }

        @Test
        @DisplayName("\"yes\" stores the offered value, never the word yes")
        void yesStoresTheValue() {
            // The trap this design exists to avoid, and one INTERACTIVE still
            // falls into two nested classes above: the confirming reply is "yes",
            // and storing *that* as the answer would hand the journey the string
            // "yes" where it expected a task.
            ExecutionContext context = contextWithEntity("task", "board deck");
            handler.execute(step("Which task?", FILL_TASK), context);
            variableContext.getInputs(context).put("answer", "yes");

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("board deck");
            assertThat(variableContext.read(context, "steps.4.output")).isEqualTo("board deck");
            assertThat(context.getInternal(UserInputStepHandler.PREFILL_PENDING_PREFIX + 4)).isNull();
        }

        @Test
        @DisplayName("an affirmative in any supported language works, sharing HUMAN_APPROVAL's vocabulary")
        void affirmativeIsMultilingual() {
            ExecutionContext context = contextWithEntity("task", "board deck");
            handler.execute(step("Which task?", FILL_TASK), context);
            variableContext.getInputs(context).put("answer", "موافق");

            assertThat(handler.execute(step("Which task?", FILL_TASK), context).getData())
                    .isEqualTo("board deck");
        }

        @Test
        @DisplayName("\"no\" falls back to the author's question, and does not offer again")
        void noAsksTheOriginalQuestion() {
            ExecutionContext context = contextWithEntity("task", "board deck");
            handler.execute(step("Which task?", FILL_TASK), context);
            variableContext.getInputs(context).put("answer", "no");

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getStatus()).isEqualTo("WAITING");
            assertThat(result.getMessage()).isEqualTo("Which task?");
            // Re-offering a value the user just refused is not a conversation.
            assertThat(result.getMetadata()).doesNotContainKey("prefill");
            assertThat(context.getInternal(UserInputStepHandler.PREFILL_PENDING_PREFIX + 4)).isNull();
        }

        @Test
        @DisplayName("a reply that is neither yes nor no is taken as the answer itself")
        void otherRepliesAnswerTheQuestion() {
            // Told "Use 'board deck'?", a user may simply type the task they
            // actually meant. Re-prompting for a clearer yes/no would be
            // maddening, so the reply is treated as the answer.
            ExecutionContext context = contextWithEntity("task", "board deck");
            handler.execute(step("Which task?", FILL_TASK), context);
            variableContext.getInputs(context).put("answer", "the sitemap audit");

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("the sitemap audit");
        }

        @Test
        @DisplayName("a missing or blank entity just asks, as if nothing had been declared")
        void missingEntityAsksNormally() {
            assertThat(handler.execute(step("Which task?", FILL_TASK), context()).getMessage())
                    .isEqualTo("Which task?");
            assertThat(handler.execute(step("Which task?", FILL_TASK), contextWithEntity("task", "  "))
                    .getMessage()).isEqualTo("Which task?");
        }

        @Test
        @DisplayName("a non-scalar entity is ignored — a form is not answerable by one string")
        void nonScalarEntitiesAreIgnored() {
            ExecutionContext context = contextWithEntity("task", Map.of("id", 1));

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getMetadata()).doesNotContainKey("prefill");
        }

        @Test
        @DisplayName("STRUCTURED steps never pre-fill: one entity is not a filled form")
        void structuredStepsOptOut() {
            String structured = "{\"inputMode\":\"STRUCTURED\",\"fillFrom\":\"task\","
                    + "\"fields\":[{\"name\":\"task\"}]}";

            StepResult result = handler.execute(
                    step("Fill this in", structured), contextWithEntity("task", "board deck"));

            assertThat(result.getMetadata())
                    .containsEntry("subStatus", "DIRECT_FORM")
                    .doesNotContainKey("prefill");
        }

        @Test
        @DisplayName("an explicit answer wins over an offer, so a resumed form is never overridden")
        void explicitAnswerTakesPrecedence() {
            ExecutionContext context = contextWithEntity("task", "board deck");
            variableContext.getInputs(context).put("answer", "the sitemap audit");

            StepResult result = handler.execute(step("Which task?", FILL_TASK), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("the sitemap audit");
        }
    }
}
