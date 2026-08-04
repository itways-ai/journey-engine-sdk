package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.itways.assistant.journey.engine.context.VariableContext;
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
    private final VariableContext variableContext;

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

        // Namespaced only — reachable as {{steps.<order>.output}}. This was the last
        // handler writing flat root variables (step<N>, lastStep, redirect_url).
        variableContext.storeOutput(context, step, resolvedUrl);

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
