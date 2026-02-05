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

        if (context.getVariables().containsKey(safeName)) {
            Object val = context.getVariables().get(safeName);
            // Re-sync to context if it was added as a flat variable
            context.addStepResult(step.getStepOrder(), val);
            return StepResult.success(val);
        } else {
            // Pause execution
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("stepName", step.getStepName());
            metadata.put("formConfig", transformUserInputConfig(uiConfig));

            return StepResult.waiting("Waiting for input: " + step.getStepName(), metadata);
        }
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

    private Map<String, Object> transformUserInputConfig(ApiConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("allowResubmit", config.isAllowResubmit());
        return map;
    }
}
