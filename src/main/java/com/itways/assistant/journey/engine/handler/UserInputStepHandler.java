package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
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

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

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
                        "I've analyzed your input. Please verify the details below to ensure neural accuracy.",
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
                : "Waiting for input: " + step.getStepName();

        return StepResult.waiting(prompt, metadata);
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
