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
public class HumanApprovalStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "HUMAN_APPROVAL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.humanApprovalDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("HUMAN_APPROVAL", "Approval Decision");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        Object answer = variableContext.getInputs(context).get("answer");
        if (answer != null) {
            variableContext.storeOutput(context, step, answer);
            variableContext.getInputs(context).remove("answer");
            return StepResult.success(answer, "Human approval granted: " + answer);
        }

        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "HUMAN_GOVERNANCE");
        metadata.put("stakeholders", engineUtils.replacePlaceholders(
                config.getStakeholders() != null ? config.getStakeholders() : "", context.getVariables()));
        metadata.put("instruction", engineUtils.replacePlaceholders(
                config.getInstruction() != null ? config.getInstruction() : "", context.getVariables()));
        metadata.put("awaiting", true);

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Awaiting human approval from: " + config.getStakeholders();

        return StepResult.waiting(prompt, metadata);
    }

}
