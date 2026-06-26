package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StateStoreStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "STATE_STORE";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.stateStoreDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.stateStoreSchema(step);
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String variableName = config.getVariable();
            String operation = config.getOperation() != null ? config.getOperation().toUpperCase() : "SET";
            String sourceValue = engineUtils.replacePlaceholders(
                    config.getSource() != null ? config.getSource() : "", context.getVariables());

            if (variableName == null || variableName.isEmpty()) {
                return StepResult.error("State variable name is missing");
            }

            Object finalValue = sourceValue;
            switch (operation) {
                case "APPEND" -> {
                    Object existing = variableContext.getState(context).get(variableName);
                    List<Object> list = (existing instanceof List) ? (List<Object>) existing : new ArrayList<>();
                    list.add(sourceValue);
                    finalValue = list;
                }
                case "INCREMENT" -> {
                    Object current = variableContext.getState(context).get(variableName);
                    int val = (current instanceof Number) ? ((Number) current).intValue() : 0;
                    try {
                        val += Integer.parseInt(sourceValue);
                    } catch (Exception e) {
                        val += 1;
                    }
                    finalValue = val;
                }
                default -> finalValue = sourceValue;
            }

            variableContext.writeState(context, variableName, finalValue);
            variableContext.writeStepOutput(context, step, finalValue);
            return StepResult.success(finalValue, step.getMessage());
        } catch (Exception e) {
            log.error("State Store failed", e);
            return StepResult.error("State Persistence Failure: " + e.getMessage());
        }
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) {
                return new ApiConfig();
            }
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
