package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TemplateRenderHandler implements StepHandler {

    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "TEMPLATE_RENDER";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.templateRenderDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("TEMPLATE_RENDER", "Rendered Output");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            Long.parseLong(step.getActionTarget());
            String rendered = "";
            variableContext.storeOutput(context, step, rendered);
            return StepResult.success(rendered, step.getMessage());
        } catch (Exception e) {
            return StepResult.error("Template Rendering Failed: " + e.getMessage());
        }
    }
}
