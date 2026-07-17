package com.itways.assistant.journey.engine.handler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "DELAY";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        Integer duration = config.getDuration() != null ? config.getDuration() : 60;
        String unit = config.getUnit() != null ? config.getUnit().toUpperCase() : "SECONDS";

        String resumeAtKey = "delay_resume_at_" + step.getStepOrder();

        // On resume: check whether the delay window has actually elapsed
        if (context.getVariables().containsKey(resumeAtKey)) {
            // resumeOnEvent = true means any incoming message breaks the delay early
            boolean resumeOnEvent = Boolean.TRUE.equals(config.getResumeOnEvent());
            LocalDateTime resumeAt = LocalDateTime.parse((String) context.getVariables().get(resumeAtKey));
            if (!resumeOnEvent && LocalDateTime.now().isBefore(resumeAt)) {
                long secondsLeft = java.time.Duration.between(LocalDateTime.now(), resumeAt).getSeconds();
                log.info("⏳ Delay Step '{}' — still waiting, {} second(s) remaining", step.getStepName(), secondsLeft);
                String stillWaiting = "Still waiting: " + secondsLeft + " second(s) remaining";
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("interactionType", "TEMPORAL_PAUSE");
                metadata.put("resumeAt", resumeAt.toString());
                metadata.put("secondsRemaining", secondsLeft);
                return StepResult.waiting(stillWaiting, metadata);
            }
            // Delay has elapsed — clean up and proceed
            context.getVariables().remove(resumeAtKey);
            log.info("✅ Delay Step '{}' complete — {} {} elapsed", step.getStepName(), duration, unit);

            Map<String, Object> resultData = Map.of("waited", duration, "unit", unit);
            context.addStepResult(step.getStepOrder(), resultData);
            context.setVariable("step" + step.getStepOrder(), resultData);
            context.setVariable("lastStep", resultData);
            String safeName = engineUtils.sanitizeKey(
                    step.getStepName() != null ? step.getStepName() : ("step" + step.getStepOrder()));
            context.setVariable(safeName, resultData);

            String successMessage = step.getMessage() != null && !step.getMessage().isEmpty()
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : "Delay completed.";
            return StepResult.success(resultData, successMessage);
        }

        // First encounter: compute resumeAt, persist it in context variables, and pause
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        LocalDateTime resumeAt = LocalDateTime.now();
        if ("MINUTES".equals(unit))     resumeAt = resumeAt.plusMinutes(duration);
        else if ("HOURS".equals(unit))  resumeAt = resumeAt.plusHours(duration);
        else                            resumeAt = resumeAt.plusSeconds(duration);

        // Store in variables so it survives context serialisation/deserialisation
        context.setVariable(resumeAtKey, resumeAt.toString());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("interactionType", "TEMPORAL_PAUSE");
        metadata.put("resumeAt", resumeAt.toString());
        metadata.put("duration", duration);
        metadata.put("unit", unit);

        log.info("⏳ Delay Step '{}' — pausing for {} {}, resume after {}", step.getStepName(), duration, unit, resumeAt);

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Sequence paused for " + duration + " " + unit;

        return StepResult.waiting(prompt, metadata);
    }
}

