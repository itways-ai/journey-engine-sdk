package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TriggerIntentStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "TRIGGER_INTENT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.triggerIntentDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("TRIGGER_INTENT", "Triggered Intent");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String action = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
        variableContext.writeStepOutput(context, step, action);
        context.addStepResult(step.getStepOrder(), action);

        String defaultMessage = "Triggering intent: " + action;
        return StepResult.builder()
                .status("SUCCESS")
                .actionTarget(action)
                .message(step.getMessage() != null ? step.getMessage() : defaultMessage)
                .data(action)
                .build();
    }
}
