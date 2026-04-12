package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserInputStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "USER_INPUT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String safeName = engineUtils
                .sanitizeKey(step.getStepName() != null ? step.getStepName() : ("step" + step.getStepOrder()));
        ApiConfig uiConfig = loadApiConfig(step.getApiConfig());

        if (context.getVariables().containsKey(safeName) || context.getVariables().containsKey("userInputAnswer")) {
            Object val = context.getVariables().containsKey(safeName) 
                ? context.getVariables().get(safeName) 
                : context.getVariables().get("userInputAnswer");
            
            // Re-sync to context if it was added as a flat variable
            context.addStepResult(step.getStepOrder(), val);
            
            // If it was a generic 'userInputAnswer' consumption, sync it to the safe name
            if (!context.getVariables().containsKey(safeName)) {
                context.getVariables().put(safeName, val);
            }

            // --- INTERACTIVE MODE LOGIC ---
            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode()) && val instanceof String) {
                // Mock AI Logic: In a real scenario, we'd call an LLM here to parse fields.
                // If the value is a String, it means we got capture text, not a form submit.
                
                // Let's check if we've already done the interactive confirmation
                if (!context.getVariables().containsKey(safeName + "_confirmed")) {
                    context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                    Map<String, Object> metadata = prepareMetadata(step, uiConfig);
                    metadata.put("subStatus", "CONFIRMATION_REQUIRED");
                    metadata.put("parsedData", val); // In real: pre-filled fields
                    
                    String prompt = "I've analyzed your input. Please verify the details below to ensure neural accuracy.";
                    return StepResult.waiting(prompt, metadata);
                }
            }
            
            // Clean up
            context.getVariables().remove("userInputAnswer");

            String successPrompt = (step.getMessage() != null && !step.getMessage().isEmpty()) 
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables()) // Render !
                : step.getMessage();

            return StepResult.success(val, successPrompt);
        } else {
            // Pause execution
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

            Map<String, Object> metadata = prepareMetadata(step, uiConfig);
            
            // If mode is STRUCTURED, we tell the frontend to show the form immediately
            if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
                metadata.put("subStatus", "DIRECT_FORM");
            }

            // Render the step message (the question) with current context variables
            String prompt = (step.getMessage() != null && !step.getMessage().isEmpty()) 
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Waiting for input: " + step.getStepName();

            return StepResult.waiting(prompt, metadata);
        }
    }

    private Map<String, Object> prepareMetadata(JourneyStep step, ApiConfig uiConfig) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stepName", step.getStepName());
        metadata.put("inputMode", uiConfig.getInputMode());
        metadata.put("formConfig", Map.of(
            "fields", uiConfig.getFields() != null ? uiConfig.getFields() : new Object[]{},
            "rules", uiConfig.getRules() != null ? uiConfig.getRules() : new Object[]{}
        ));
        metadata.put("allowResubmit", uiConfig.isAllowResubmit());
        return metadata;
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty())
                return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }


}
