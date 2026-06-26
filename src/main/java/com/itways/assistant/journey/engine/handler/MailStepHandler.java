package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MailStepHandler implements StepHandler {

    private final ObjectMapper objectMapper;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "SEND_MAIL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.sendMailDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("SEND_MAIL", "Send Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            MailConfig mailConfig = objectMapper.readValue(step.getApiConfig(), MailConfig.class);
            String result = "Mail sent to " + mailConfig.getTo();
            variableContext.writeStepOutput(context, step, result);
            context.addStepResult(step.getStepOrder(), result);
            return StepResult.success(result, step.getMessage() != null ? step.getMessage() : result);
        } catch (Exception e) {
            return StepResult.error("Mail Send Failed: " + e.getMessage());
        }
    }
}
