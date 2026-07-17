package com.itways.assistant.journey.engine.handler;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.AiFieldMappingService;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataMapStepHandler implements StepHandler {

	private final AiFieldMappingService aiFieldMappingService;
	private final EngineUtils engineUtils;

	@Override
	public String getType() {
		return "DATA_MAP";
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		if (step.getActionTarget() == null || step.getActionTarget().isBlank()) {
			return StepResult.error("DATA_MAP: field schema is required");
		}

		try {
			String text = (String) context.getVariable("text");
			String action = step.getActionTarget();
			action = engineUtils.replacePlaceholders(action, context.getVariables());

			log.info("---> DATA_MAP step: '{}' schema={}", step.getStepName(), action);

			Map<String, Object> mappedData = aiFieldMappingService.extractFieldsStrict(
					text, action, step.getRequiredParams(), context);

			log.info("<--- DATA_MAP step: '{}' extracted={}", step.getStepName(), mappedData);

			context.addStepResult(step.getStepOrder(), mappedData);
			context.setVariable("step" + step.getStepOrder(), mappedData);
			context.setVariable("input", mappedData);
			context.setVariable("lastStep", mappedData);
			if (step.getStepName() != null && !step.getStepName().isEmpty()) {
				context.setVariable(engineUtils.sanitizeKey(step.getStepName()), mappedData);
			}

			String resolvedMessage = step.getMessage() != null && !step.getMessage().isEmpty()
					? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
					: null;
			return StepResult.success(mappedData, resolvedMessage);
		} catch (Exception e) {
			log.error("❌ DATA_MAP step '{}' failed", step.getStepName(), e);
			return StepResult.error("Data Mapping Failed: " + e.getMessage());
		}
	}
}
