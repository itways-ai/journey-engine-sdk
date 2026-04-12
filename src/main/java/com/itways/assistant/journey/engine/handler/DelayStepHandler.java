package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "DELAY";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = loadApiConfig(step.getApiConfig());
        Integer duration = config.getDuration() != null ? config.getDuration() : 5;
        String unit = config.getUnit() != null ? config.getUnit().toUpperCase() : "SECONDS";

        log.info("⏳ Delay Step: '{}' - Pausing for {} {}", step.getStepName(), duration, unit);

        // Check if we are resuming from a finished delay
        String delayFinishedKey = "delay_finished_" + step.getStepOrder();
        if (context.getVariables().containsKey(delayFinishedKey)) {
            context.getVariables().remove(delayFinishedKey);
            return StepResult.success(Map.of("waited", duration, "unit", unit), "Delay completed.");
        }

        // Otherwise, set a resume window and pause
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        
        LocalDateTime resumeAt = LocalDateTime.now();
        if (unit.equals("MINUTES")) resumeAt = resumeAt.plusMinutes(duration);
        else if (unit.equals("HOURS")) resumeAt = resumeAt.plusHours(duration);
        else resumeAt = resumeAt.plusSeconds(duration);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "TEMPORAL_PAUSE");
        metadata.put("resumeAt", resumeAt.toString());
        metadata.put("duration", duration);
        metadata.put("unit", unit);

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty()) 
            ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
            : "Sequence paused for " + duration + " " + unit;

        return StepResult.waiting(prompt, metadata);
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
