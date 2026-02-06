package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MailStepHandler implements StepHandler {

    // private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;
    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "MAIL_SEND";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            String configJson = step.getApiConfig();
            MailConfig mailConfig = objectMapper.readValue(configJson, MailConfig.class);

            // notificationPort.sendMail(mailConfig, context.getVariables());

            String result = "Mail sent to " + mailConfig.getTo();
            context.addStepResult(step.getStepOrder(), result);
            return StepResult.success(result, step.getMessage() != null ? step.getMessage() : result);
        } catch (Exception e) {
            return StepResult.error("Mail Send Failed: " + e.getMessage());
        }
    }
}
