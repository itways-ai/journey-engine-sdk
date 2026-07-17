package com.itways.assistant.journey.engine.handler;



import java.util.HashMap;

import java.util.Map;



import org.springframework.stereotype.Component;



import com.itways.assistant.journey.engine.model.ApiConfig;

import com.itways.assistant.journey.engine.model.ExecutionContext;

import com.itways.assistant.journey.engine.model.ExecutionStatus;

import com.itways.assistant.journey.engine.model.JourneyStep;

import com.itways.assistant.journey.engine.model.StepResult;

import com.itways.assistant.journey.engine.service.AiFieldMappingService;

import com.itways.assistant.journey.engine.service.StepHandler;

import com.itways.assistant.journey.engine.util.EngineUtils;

import com.itways.assistant.journey.engine.util.FormFieldTemplateBuilder;



import lombok.RequiredArgsConstructor;



@Component

@RequiredArgsConstructor

public class UserInputStepHandler implements StepHandler {

    private static final String DEFAULT_CONFIRMATION_MESSAGE = "Please verify the details below and confirm.";

    private final EngineUtils engineUtils;

    private final AiFieldMappingService aiFieldMappingService;

    private final FormFieldTemplateBuilder formFieldTemplateBuilder;



    @Override

    public String getType() {

        return "USER_INPUT";

    }



    @Override

    public StepResult execute(JourneyStep step, ExecutionContext context) {

        String safeName = engineUtils

                .sanitizeKey(step.getStepName() != null ? step.getStepName() : ("step" + step.getStepOrder()));

        String draftKey = safeName + "_draft";

        ApiConfig uiConfig = engineUtils.parseApiConfig(step.getApiConfig());



        // allowResubmit=true: clear existing answer to force re-asking even if already answered

        if (uiConfig.isAllowResubmit()

                && context.getVariables().containsKey(safeName)

                && !context.getVariables().containsKey("userInputAnswer")) {

            context.getVariables().remove(safeName);

            context.getVariables().remove(draftKey);

        }



        if (context.getVariables().containsKey(safeName) || context.getVariables().containsKey("userInputAnswer")) {

            Object val = context.getVariables().containsKey(safeName)

                    ? context.getVariables().get(safeName)

                    : context.getVariables().get("userInputAnswer");



            // --- INTERACTIVE MODE: free text → AI parse → confirmation form ---

            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode()) && val instanceof String userText) {

                String captureText = userText;

                Object textVar = context.getVariable("text");

                if ((captureText == null || captureText.isBlank()) && textVar instanceof String tv) {

                    captureText = tv;

                }



                String jsonTemplate = formFieldTemplateBuilder.buildJsonTemplate(uiConfig.getFields());

                Map<String, Object> parsedFormData = aiFieldMappingService.extractFields(

                        captureText, jsonTemplate, step.getRequiredParams(), context);



                context.getVariables().put(draftKey, parsedFormData);

                context.getVariables().remove("userInputAnswer");



                context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

                Map<String, Object> metadata = prepareMetadata(step, uiConfig);

                metadata.put("subStatus", "CONFIRMATION_REQUIRED");

                metadata.put("rawCapture", captureText);

                metadata.put("parsedFormData", parsedFormData);



                String confirmPrompt = resolveConfirmationMessage(uiConfig, context);

                return StepResult.waiting(confirmPrompt, metadata);

            }



            // --- INTERACTIVE MODE: confirmed form submission ---

            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode()) && val instanceof Map<?, ?> confirmed) {

                @SuppressWarnings("unchecked")

                Map<String, Object> confirmedMap = (Map<String, Object>) confirmed;

                context.getVariables().put(safeName, confirmedMap);
                promoteFormFields(confirmedMap, context);

                context.getVariables().remove(draftKey);

                context.getVariables().remove("userInputAnswer");

                context.addStepResult(step.getStepOrder(), confirmedMap);



                return StepResult.success(confirmedMap, null);

            }



            // --- FREE_TEXT / STRUCTURED ---

            context.addStepResult(step.getStepOrder(), val);



            if (!context.getVariables().containsKey(safeName)) {

                context.getVariables().put(safeName, val);

            }

            if (val instanceof Map<?, ?> formMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) formMap;
                promoteFormFields(fields, context);
            }



            context.getVariables().remove("userInputAnswer");

            context.getVariables().remove(draftKey);



            return StepResult.success(val, null);

        } else {

            // Pause execution — waiting for first answer

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

    }



    private void promoteFormFields(Map<String, Object> formMap, ExecutionContext context) {
        formMap.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                context.setVariable(key, value);
            }
        });
    }

    private String resolveConfirmationMessage(ApiConfig uiConfig, ExecutionContext context) {
        String template = uiConfig.getConfirmationMessage();
        if (template == null || template.isBlank()) {
            return DEFAULT_CONFIRMATION_MESSAGE;
        }
        return engineUtils.replacePlaceholders(template, context.getVariables());
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


