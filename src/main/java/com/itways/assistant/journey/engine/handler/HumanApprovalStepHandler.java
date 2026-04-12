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

@Slf4j
@Component
@RequiredArgsConstructor
public class HumanApprovalStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() {
        return "HUMAN_APPROVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String safeName = engineUtils.sanitizeKey(step.getStepName() != null ? step.getStepName() : ("approval" + step.getStepOrder()));
        
        // If approval result already exists in context (resumed)
        if (context.getVariables().containsKey(safeName) || context.getVariables().containsKey("approvalResult")) {
            Object result = context.getVariables().containsKey(safeName) 
                ? context.getVariables().get(safeName) 
                : context.getVariables().get("approvalResult");
            
            context.getVariables().remove("approvalResult");
            return StepResult.success(result, "Human approval granted: " + result);
        }

        // Otherwise, pause and wait
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        ApiConfig config = loadApiConfig(step.getApiConfig());
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "HUMAN_GOVERNANCE");
        metadata.put("stakeholders", engineUtils.replacePlaceholders(config.getStakeholders() != null ? config.getStakeholders() : "", context.getVariables()));
        metadata.put("instruction", engineUtils.replacePlaceholders(config.getInstruction() != null ? config.getInstruction() : "", context.getVariables()));
        metadata.put("awaiting", true);

        log.info("🤝 Human Approval Step: '{}' - Journey Paused for Governance", step.getStepName());

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty()) 
            ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
            : "Awaiting human approval from: " + config.getStakeholders();

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
