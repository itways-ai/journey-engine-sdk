package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
public class RedirectStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "REDIRECT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String raw = step.getActionTarget();
        if (raw == null || raw.isBlank()) {
            return StepResult.error("REDIRECT: actionTarget (URL) is required");
        }

        String resolvedUrl = engineUtils.replacePlaceholders(raw, context.getVariables());
        if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
            return StepResult.error("REDIRECT: invalid URL — must start with http:// or https://");
        }

        context.addStepResult(step.getStepOrder(), resolvedUrl);
        context.setVariable("step" + step.getStepOrder(), resolvedUrl);
        context.setVariable("lastStep", resolvedUrl);
        context.setVariable("redirect_url", resolvedUrl);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            context.setVariable(engineUtils.sanitizeKey(step.getStepName()), resolvedUrl);
        }

        log.info("↪️ REDIRECT step '{}' → {}", step.getStepName(), resolvedUrl);

        String successMessage = null;
        if (StringUtils.hasText(step.getMessage())) {
            successMessage = engineUtils.replacePlaceholders(step.getMessage(), context.getVariables());
        }

        return StepResult.builder()
                .status("SUCCESS")
                .data(resolvedUrl)
                .message(successMessage)
                .actionTarget(resolvedUrl)
                .build();
    }
}
