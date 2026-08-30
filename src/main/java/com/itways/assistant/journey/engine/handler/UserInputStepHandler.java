package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.DecisionWords;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserInputStepHandler implements StepHandler {

    /** Engine-internal marker: this step has asked for confirmation and is awaiting it. */
    static final String AWAITING_CONFIRM_PREFIX = "userInputAwaitingConfirm_";

    /**
     * Engine-internal: the value offered for confirmation, held while we wait.
     *
     * <p>
     * Held rather than re-derived, because the confirming turn's answer is "yes"
     * — and storing <em>that</em> as the step's output is the obvious bug this
     * design has to avoid. The user agrees to a value; the value is what gets
     * stored.
     */
    static final String PREFILL_PENDING_PREFIX = "userInputPrefill_";

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineMessages messages;
    private final DecisionWords decisionWords;

    @Override
    public String getType() {
        return "USER_INPUT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.userInputDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.userInputSchema(step);
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig uiConfig = engineUtils.parseApiConfig(step.getApiConfig());
        Map<String, Object> inputs = variableContext.getInputs(context);
        Object answer = inputs.get("answer");

        // Pre-fill: an answer this step already has, offered back for one yes.
        //
        // Ordered before the normal answer path because a pending offer changes
        // what "yes" means. Outside that, this whole block is inert unless the
        // author wrote fillFrom, so no existing journey changes behaviour.
        String prefillKey = PREFILL_PENDING_PREFIX + step.getStepOrder();
        Object offered = context.getInternal(prefillKey);
        if (offered != null) {
            StepResult settled = settlePrefill(step, context, uiConfig, inputs, answer, offered, prefillKey);
            if (settled != null) {
                return settled;
            }
            // Neither yes nor no: the reply is the user answering the original
            // question in their own words, so fall through and treat it as one.
        } else if (answer == null) {
            Object candidate = prefillCandidate(uiConfig, inputs);
            if (candidate != null) {
                return offerPrefill(step, context, uiConfig, candidate, prefillKey);
            }
        }

        if (answer != null) {
            variableContext.storeOutput(context, step, answer);
            inputs.remove("answer");

            // INTERACTIVE mode asks the user to verify a free-text answer once.
            // The awaiting-confirmation marker is what makes the second pass
            // through this step count as the confirmation; without it a plain-text
            // reply (every channel user) re-triggered the prompt forever.
            String awaitingKey = AWAITING_CONFIRM_PREFIX + step.getStepOrder();
            boolean awaitingConfirmation = Boolean.TRUE.equals(context.getInternal(awaitingKey));

            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode())
                    && answer instanceof String
                    && !awaitingConfirmation) {
                context.setInternal(awaitingKey, true);
                context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                Map<String, Object> metadata = prepareMetadata(step, uiConfig);
                metadata.put("subStatus", "CONFIRMATION_REQUIRED");
                metadata.put("parsedData", answer);
                return StepResult.waiting(
                        messages.get(context.resolvedLanguage(), "step.userInput.confirm"),
                        metadata);
            }

            context.removeInternal(awaitingKey);

            String successPrompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : step.getMessage();

            return StepResult.success(answer, successPrompt);
        }

        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
            metadata.put("subStatus", "DIRECT_FORM");
        }

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : messages.get(context.resolvedLanguage(), "step.userInput.waiting", step.getStepName());

        return StepResult.waiting(prompt, metadata);
    }

    /**
     * A value the conversation already established that could answer this step.
     *
     * <p>
     * Opt-in via {@code fillFrom}, naming an entity the intent classifier
     * extracted — {@code inputs.entities.task} for
     * {@code fillFrom: "task"}. Explicit rather than matched by name, because the
     * model chooses its own entity names: asked to complete a task it returned
     * {@code task}, while the step collecting that value is called
     * {@code taskName}. A name-matching version would work often enough to look
     * finished and fail without a reason anyone could see.
     *
     * <p>
     * Only scalars qualify. A structured step wants a whole form filled and a
     * single extracted string is not that, so STRUCTURED is left alone; there is
     * nothing to confirm one field of.
     */
    private Object prefillCandidate(ApiConfig uiConfig, Map<String, Object> inputs) {
        String from = uiConfig.getFillFrom();
        if (from == null || from.isBlank() || "STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
            return null;
        }
        Object entities = inputs.get("entities");
        if (!(entities instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(from.trim());
        if (value == null || value instanceof Map || value instanceof Iterable) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** Parks the run on "is this right?" rather than answering for the user. */
    private StepResult offerPrefill(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                    Object candidate, String prefillKey) {
        context.setInternal(prefillKey, candidate);
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        // A distinct subStatus, not INTERACTIVE's CONFIRMATION_REQUIRED. That one
        // means "here is the form, correct it and submit"; this one means "answer
        // yes or no", and a client that renders a form for it would submit the
        // form's contents as a decision.
        metadata.put("subStatus", "PREFILL_CONFIRMATION");
        metadata.put("prefill", candidate);
        metadata.put("expectedAnswers", Map.of("confirm", "yes", "reject", "no"));

        return StepResult.waiting(
                messages.get(context.resolvedLanguage(), "step.userInput.prefillConfirm", candidate),
                metadata);
    }

    /**
     * Resolves a pending offer, or returns null when the reply was neither
     * yes nor no.
     *
     * <p>
     * That third case is the one worth spelling out: told "Use 'board deck'?" a
     * user may simply type the task they actually meant. Treating that as
     * unclear and re-asking would be maddening, so the caller falls through and
     * takes the reply as the answer — which is what the step would have done had
     * it never offered anything.
     */
    private StepResult settlePrefill(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                     Map<String, Object> inputs, Object answer, Object offered,
                                     String prefillKey) {
        if (answer == null) {
            // Resumed with nothing new — re-offer rather than silently accepting.
            return offerPrefill(step, context, uiConfig, offered, prefillKey);
        }

        Boolean decision = decisionWords.interpret(answer);
        if (decision == null) {
            context.removeInternal(prefillKey);
            return null;
        }

        inputs.remove("answer");
        context.removeInternal(prefillKey);

        if (!decision) {
            // Declined. Ask the author's original question, with no offer this
            // time — repeating one the user just refused is not a conversation.
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
            Map<String, Object> metadata = prepareMetadata(step, uiConfig);
            if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
                metadata.put("subStatus", "DIRECT_FORM");
            }
            return StepResult.waiting(prompt(step, context), metadata);
        }

        // Agreed. The offered value is stored, never the "yes" that agreed to it.
        variableContext.storeOutput(context, step, offered);
        return StepResult.success(offered, prompt(step, context));
    }

    /** The author's own wording where there is any, the engine's default otherwise. */
    private String prompt(JourneyStep step, ExecutionContext context) {
        return (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : messages.get(context.resolvedLanguage(), "step.userInput.waiting", step.getStepName());
    }

    private Map<String, Object> prepareMetadata(JourneyStep step, ApiConfig uiConfig) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stepName", step.getStepName());
        metadata.put("inputMode", uiConfig.getInputMode());
        metadata.put("formConfig", Map.of(
                "fields", uiConfig.getFields() != null ? uiConfig.getFields() : new Object[] {},
                "rules", uiConfig.getRules() != null ? uiConfig.getRules() : new Object[] {}));
        metadata.put("allowResubmit", uiConfig.isAllowResubmit());
        return metadata;
    }

}
