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
public class TemplateRenderHandler implements StepHandler {

//    private final TemplateRendererPort templateRenderer;
    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "TEMPLATE_RENDER";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            Long templateId = Long.parseLong(step.getActionTarget());
            // In a real implementation, we'd load the ApiConfig for allowMissingInputs flag
            // For now, simplicity:
//            templateRenderer.render(templateId, context.getVariables(), false);
            String rendered = "";
            context.addStepResult(step.getStepOrder(), rendered);
            context.setVariable("step" + step.getStepOrder(), rendered);
            if (step.getStepName() != null && !step.getStepName().isEmpty()) {
                context.setVariable(engineUtils.sanitizeKey(step.getStepName()), rendered);
            }

            return StepResult.success(rendered);
        } catch (Exception e) {
            return StepResult.error("Template Rendering Failed: " + e.getMessage());
        }
    }
}
