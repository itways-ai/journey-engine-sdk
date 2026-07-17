package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateRenderHandler implements StepHandler {

    // private final TemplateRendererPort templateRenderer;
    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "TEMPLATE_RENDER";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            if (step.getActionTarget() == null || step.getActionTarget().isBlank()) {
                return StepResult.error("TEMPLATE_RENDER: a template must be selected");
            }

            Long templateId = Long.parseLong(step.getActionTarget());

            // Rendering is deferred until TemplateRendererPort is implemented.
            // templateRenderer.render(templateId, context.getVariables(), false);
            String rendered = "";

            String safeName = engineUtils.sanitizeKey(step.getStepName() != null ? step.getStepName() : ("step" + step.getStepOrder()));
            context.addStepResult(step.getStepOrder(), rendered);
            context.setVariable("step" + step.getStepOrder(), rendered);
            context.setVariable("lastStep", rendered);
            context.setVariable(safeName, rendered);

            log.info("✅ Template Render Step '{}' — templateId={} (rendering deferred)", step.getStepName(), templateId);

            String resolvedMessage = step.getMessage() != null && !step.getMessage().isEmpty()
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : null;

            return StepResult.success(rendered, resolvedMessage);
        } catch (NumberFormatException e) {
            return StepResult.error("TEMPLATE_RENDER: invalid template ID — " + step.getActionTarget());
        } catch (Exception e) {
            log.error("❌ Template Render Step '{}' failed", step.getStepName(), e);
            return StepResult.error("TEMPLATE_RENDER failed: " + e.getMessage());
        }
    }
}
