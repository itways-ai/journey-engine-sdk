package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class StateStoreStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "STATE_STORE";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String variableName = config.getVariable();
            String operation = config.getOperation() != null ? config.getOperation().toUpperCase() : "SET";
            String sourceValue = engineUtils.replacePlaceholders(config.getSource() != null ? config.getSource() : "", context.getVariables());

            if (variableName == null || variableName.isEmpty()) {
                return StepResult.error("State variable name is missing");
            }

            log.info("💾 State Store Step: '{}' - {} {} with value: {}", step.getStepName(), operation, variableName, sourceValue);

            Object finalValue = sourceValue;

            switch (operation) {
                case "APPEND":
                    Object existing = context.getVariables().get(variableName);
                    List<Object> list = (existing instanceof List) ? (List<Object>) existing : new ArrayList<>();
                    list.add(sourceValue);
                    finalValue = list;
                    break;
                case "INCREMENT":
                    Object current = context.getVariables().get(variableName);
                    int val = (current instanceof Number) ? ((Number) current).intValue() : 0;
                    try {
                        val += Integer.parseInt(sourceValue);
                    } catch (Exception e) { val += 1; }
                    finalValue = val;
                    break;
                case "SET":
                default:
                    finalValue = sourceValue;
                    break;
            }

            context.setVariable(variableName, finalValue);
            return StepResult.success(finalValue, step.getMessage());
        } catch (Exception e) {
            log.error("❌ State Store failed", e);
            return StepResult.error("State Persistence Failure: " + e.getMessage());
        }
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
