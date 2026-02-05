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
public class ConditionStepHandler implements StepHandler {

	private final EngineUtils engineUtils;

	@Override
	public String getType() {
		return "CONDITION";
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		boolean cond = engineUtils.evaluateCondition(step.getConditionExpression(), context.getVariables());

		context.addStepResult(step.getStepOrder(), cond);
		// Sync to context variables for placeholder rendering
		context.setVariable("step" + step.getStepOrder(), cond);
		if (step.getStepName() != null && !step.getStepName().isEmpty()) {
			context.setVariable(engineUtils.sanitizeKey(step.getStepName()), cond);
		}

		return StepResult.builder().status("SUCCESS").data(cond).build();
	}
}
