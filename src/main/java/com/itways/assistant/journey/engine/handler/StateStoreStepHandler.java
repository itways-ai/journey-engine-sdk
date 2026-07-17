package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;
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

    @Override
    public String getType() {
        return "STATE_STORE";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String variableName = config.getVariable();
            String operation = config.getOperation() != null ? config.getOperation().toUpperCase() : "SET";
            Object resolvedSource = engineUtils.resolveSourceValue(
                    config.getSource() != null ? config.getSource() : "", context.getVariables());

            if (variableName == null || variableName.isBlank()) {
                return StepResult.error("STATE_STORE: variable name is required");
            }

            log.info("STATE_STORE step '{}' — {} '{}' = {}", step.getStepName(), operation, variableName, resolvedSource);

            Object finalValue = resolvedSource;

            switch (operation) {
                case "APPEND":
                    Object existing = context.getVariables().get(variableName);
                    List<Object> list = (existing instanceof List)
                            ? new ArrayList<>((List<Object>) existing)
                            : new ArrayList<>();
                    list.add(resolvedSource);
                    finalValue = list;
                    break;
                case "INCREMENT":
                    Object current = context.getVariables().get(variableName);
                    int val = (current instanceof Number) ? ((Number) current).intValue() : 0;
                    try {
                        val += (resolvedSource instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(resolvedSource));
                    } catch (Exception e) {
                        val += 1;
                    }
                    finalValue = val;
                    break;
                case "SET":
                default:
                    finalValue = resolvedSource;
                    break;
            }

            context.setVariable(variableName, finalValue);
            context.setVariable("step" + step.getStepOrder(), finalValue);
            context.setVariable("lastStep", finalValue);

            String resolvedMessage = step.getMessage() != null && !step.getMessage().isBlank()
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : null;

            log.info("STATE_STORE step '{}' stored '{}' = {}", step.getStepName(), variableName, finalValue);

            return StepResult.success(finalValue, resolvedMessage);
        } catch (Exception e) {
            log.error("STATE_STORE failed", e);
            return StepResult.error("STATE_STORE: " + e.getMessage());
        }
    }

}
