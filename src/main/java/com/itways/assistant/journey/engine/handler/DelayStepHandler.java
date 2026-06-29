package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "DELAY";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.delayDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("DELAY", "Delay Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        Integer duration = config.getDuration() != null ? config.getDuration() : 5;
        String unit = config.getUnit() != null ? config.getUnit().toUpperCase() : "SECONDS";

        String delayFinishedKey = "delay_finished_" + step.getStepOrder();
        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) context.getVariables().computeIfAbsent("runtime", k -> new HashMap<>());
        if (Boolean.TRUE.equals(runtime.get(delayFinishedKey))) {
            runtime.remove(delayFinishedKey);
            Map<String, Object> result = Map.of("waited", duration, "unit", unit);
            variableContext.storeOutput(context, step, result);
            return StepResult.success(result, "Delay completed.");
        }

        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        java.time.LocalDateTime resumeAt = java.time.LocalDateTime.now();
        if (unit.equals("MINUTES")) {
            resumeAt = resumeAt.plusMinutes(duration);
        } else if (unit.equals("HOURS")) {
            resumeAt = resumeAt.plusHours(duration);
        } else {
            resumeAt = resumeAt.plusSeconds(duration);
        }

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

}
