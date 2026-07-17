package com.itways.assistant.journey.engine.handler;

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
public class HumanApprovalStepHandler implements StepHandler {

    private final EngineUtils engineUtils;

    @Override
    public String getType() {
        return "HUMAN_APPROVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String safeName = engineUtils.sanitizeKey(step.getStepName() != null ? step.getStepName() : ("approval" + step.getStepOrder()));

        // If approval result already exists in context (resumed after human action)
        if (context.getVariables().containsKey(safeName) || context.getVariables().containsKey("approvalResult")) {
            Object result = context.getVariables().containsKey(safeName)
                    ? context.getVariables().get(safeName)
                    : context.getVariables().get("approvalResult");

            context.getVariables().remove("approvalResult");

            boolean rejected = isRejected(result);
            String decision = rejected ? "REJECTED" : "APPROVED";

            // Store the normalised decision so downstream CONDITION steps can branch on it
            context.setVariable(safeName + "_decision", decision);
            context.setVariable("approval_decision", decision);
            context.addStepResult(step.getStepOrder(), result);

            if (rejected) {
                log.warn("HUMAN_APPROVAL step '{}' — REJECTED (raw result: {})", step.getStepName(), result);
                return StepResult.error("HUMAN_APPROVAL: request was not confirmed. Decision: " + result);
            }

            context.setVariable("step" + step.getStepOrder(), decision);
            context.setVariable("lastStep", decision);

            log.info("HUMAN_APPROVAL step '{}' — APPROVED (raw result: {})", step.getStepName(), result);

            String resolvedMessage = step.getMessage() != null && !step.getMessage().isBlank()
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : "Human approval granted.";

            return StepResult.success(decision, resolvedMessage);
        }

        // Otherwise, pause and wait for human input
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        String approvalMode = resolveApprovalMode(config);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("interactionType", "SELF_CONFIRM".equals(approvalMode) ? "HUMAN_CONFIRM" : "HUMAN_GOVERNANCE");
        metadata.put("approvalMode", approvalMode);
        if ("STAKEHOLDER".equals(approvalMode) && config.getStakeholders() != null && !config.getStakeholders().isBlank()) {
            metadata.put("stakeholders", engineUtils.replacePlaceholders(config.getStakeholders(), context.getVariables()));
        }
        metadata.put("instruction", engineUtils.replacePlaceholders(
                config.getInstruction() != null ? config.getInstruction() : "", context.getVariables()));
        metadata.put("awaiting", true);

        log.info("HUMAN_APPROVAL step '{}' — paused, awaiting {} decision", step.getStepName(), approvalMode);

        String prompt;
        if (step.getMessage() != null && !step.getMessage().isBlank()) {
            prompt = engineUtils.replacePlaceholders(step.getMessage(), context.getVariables());
        } else if ("SELF_CONFIRM".equals(approvalMode)) {
            prompt = "Please confirm to continue.";
        } else {
            prompt = "Awaiting human approval from: " + config.getStakeholders();
        }

        return StepResult.waiting(prompt, metadata);
    }

    private static String resolveApprovalMode(ApiConfig config) {
        if (config.getApprovalMode() == null || config.getApprovalMode().isBlank()) {
            return "SELF_CONFIRM";
        }
        return "STAKEHOLDER".equalsIgnoreCase(config.getApprovalMode()) ? "STAKEHOLDER" : "SELF_CONFIRM";
    }

    /**
     * Returns true if the approval result represents a rejection.
     * Handles Boolean false, strings "REJECTED"/"DENIED"/"NO"/"FALSE",
     * and Maps that carry a "decision" key.
     */
    private boolean isRejected(Object result) {
        if (result instanceof Boolean) {
            return !(Boolean) result;
        }
        if (result instanceof String) {
            String s = ((String) result).trim().toUpperCase();
            return s.equals("REJECTED") || s.equals("DENIED") || s.equals("NO") || s.equals("FALSE");
        }
        if (result instanceof Map<?, ?> map) {
            return isRejected(map.get("decision"));
        }
        return false;
    }
}

