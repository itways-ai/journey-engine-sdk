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
public class ConditionStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "CONDITION";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String expression = step.getConditionExpression();
        if (!StringUtils.hasText(expression)) {
            return StepResult.error("CONDITION: conditionExpression is required");
        }

        boolean cond = engineUtils.evaluateCondition(expression, context.getVariables());

        // Raw Boolean required for JourneyEngineImpl.isEligible() branch routing
        context.addStepResult(step.getStepOrder(), cond);
        context.setVariable("step" + step.getStepOrder(), cond);
        context.setVariable("lastStep", cond);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), cond);
        }

        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("result", cond);
        resultData.put("branch", cond ? "MATCH" : "ELSE");

        String successMessage = null;
        if (StringUtils.hasText(step.getMessage())) {
            successMessage = engineUtils.replacePlaceholders(step.getMessage(), context.getVariables());
        }

        return StepResult.success(resultData, successMessage);
    }
}
