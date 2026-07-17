package com.itways.assistant.journey.engine.handler;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.MailDeliveryPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MailStepHandler implements StepHandler {

    private final ObjectMapper objectMapper;
    private final EngineUtils engineUtils;
    private final MailDeliveryPort mailDeliveryPort;

    @Override
    public String getType() {
        return "SEND_MAIL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            MailConfig mailConfig = parseMailConfig(step.getApiConfig());
            Map<String, Object> variables = context.getVariables();

            String to = engineUtils.replacePlaceholders(mailConfig.getTo(), variables);
            String subject = engineUtils.replacePlaceholders(mailConfig.getSubject(), variables);
            String body = engineUtils.replacePlaceholders(
                    mailConfig.getBody() != null ? mailConfig.getBody() : "", variables);

            if (StringUtils.hasText(mailConfig.getFrom())) {
                mailConfig.setFrom(engineUtils.replacePlaceholders(mailConfig.getFrom(), variables));
            }

            if (!StringUtils.hasText(to)) {
                return StepResult.error("SEND_MAIL: recipient (to) is required after resolving placeholders.");
            }
            if (!StringUtils.hasText(subject)) {
                return StepResult.error("SEND_MAIL: subject is required after resolving placeholders.");
            }
            if (!StringUtils.hasText(mailConfig.getSmtpHost())) {
                return StepResult.error("SEND_MAIL: SMTP host is required.");
            }
            if (!StringUtils.hasText(mailConfig.getUsername())) {
                return StepResult.error("SEND_MAIL: SMTP username is required.");
            }

            mailDeliveryPort.send(mailConfig, to.trim(), subject.trim(), body);

            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("to", to.trim());
            resultData.put("subject", subject.trim());
            resultData.put("status", "sent");

            context.addStepResult(step.getStepOrder(), resultData);

            String safeName = engineUtils.sanitizeKey(
                    step.getStepName() != null ? step.getStepName() : ("step" + step.getStepOrder()));
            context.setVariable("step" + step.getStepOrder(), resultData);
            context.setVariable("lastStep", resultData);
            context.setVariable(safeName, resultData);

            String successMessage = null;
            if (StringUtils.hasText(step.getMessage())) {
                successMessage = engineUtils.replacePlaceholders(step.getMessage(), variables);
            }

            return StepResult.success(resultData, successMessage);
        } catch (Exception e) {
            return StepResult.error("Mail Send Failed: " + e.getMessage());
        }
    }

    private MailConfig parseMailConfig(String configJson) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (configJson == null || configJson.isBlank()) {
            return new MailConfig();
        }
        return objectMapper.readValue(configJson, MailConfig.class);
    }
}
