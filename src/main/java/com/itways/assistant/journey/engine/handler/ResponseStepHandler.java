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
public class ResponseStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "RESPONSE";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String resp = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());

        context.addStepResult(step.getStepOrder(), resp);
        context.setVariable("step" + step.getStepOrder(), resp);
        context.setVariable("lastStep", resp);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), resp);
        }

        return StepResult.builder()
                .status("SUCCESS")
                .message(resp)
                .data(resp)
                .build();
    }
}
