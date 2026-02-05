package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

/**
 * Handler for TRIGGER_INTENT steps.
 * Signals the frontend to trigger a specific intent/action.
 */
@Component
@RequiredArgsConstructor
public class TriggerIntentStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "TRIGGER_INTENT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        // Resolve intent name (actionTarget) from the step definition, supporting
        // placeholders
        String action = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());

        // Update context
        context.addStepResult(step.getStepOrder(), action);
        context.setVariable("step" + step.getStepOrder(), action);
        context.setVariable("lastStep", action);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), action);
        }

        return StepResult.builder()
                .status("SUCCESS")
                .actionTarget(action)
                .message("Triggering intent: " + action)
                .data(action)
                .build();
    }
}
