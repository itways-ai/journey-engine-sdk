package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

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
        Object switchVal = engineUtils.evaluateExpression(step.getConditionExpression(), context.getVariables());

        context.addStepResult(step.getStepOrder(), switchVal);
        context.setVariable("step" + step.getStepOrder(), switchVal);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), switchVal);
        }

        return StepResult.builder()
                .status("SUCCESS")
                .data(switchVal)
                .build();
    }
}
