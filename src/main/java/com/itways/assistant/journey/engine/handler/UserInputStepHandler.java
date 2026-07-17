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

            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode()) && answer instanceof String) {
                String confirmKey = "runtime.userInput_" + step.getStepOrder() + "_confirmed";
                @SuppressWarnings("unchecked")
                Map<String, Object> runtime = (Map<String, Object>) context.getVariables().get("runtime");
                if (!Boolean.TRUE.equals(runtime.get(confirmKey))) {
                    context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                    Map<String, Object> metadata = prepareMetadata(step, uiConfig);
                    metadata.put("subStatus", "CONFIRMATION_REQUIRED");
                    metadata.put("parsedData", answer);
                    return StepResult.waiting(
                            "I've analyzed your input. Please verify the details below to ensure neural accuracy.",
                            metadata);
                }
            }

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
