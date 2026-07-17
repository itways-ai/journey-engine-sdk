package com.itways.assistant.journey.engine.handler;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SwitchStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "SWITCH";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String expression = step.getConditionExpression();
        if (!StringUtils.hasText(expression)) {
            return StepResult.error("SWITCH: conditionExpression is required");
        }

        Object switchVal = engineUtils.evaluateExpression(expression, context.getVariables());

        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("value", switchVal);

        context.addStepResult(step.getStepOrder(), resultData);
        context.setVariable("step" + step.getStepOrder(), switchVal);
        context.setVariable("lastStep", switchVal);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), switchVal);
        }

        String successMessage = null;
        if (StringUtils.hasText(step.getMessage())) {
            successMessage = engineUtils.replacePlaceholders(step.getMessage(), context.getVariables());
        }

        return StepResult.success(resultData, successMessage);
    }
}
