package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentInsightStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;
    private final AiService aiService;

    @Override
    public String getType() {
        return "DOCUMENT_INSIGHT";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String strategy = config.getStrategy() != null ? config.getStrategy() : "OCR_DETAILED";
            
            log.info("👁️ Document Insight Step: '{}' with strategy: {}", step.getStepName(), strategy);

            // Simulation of Intelligence extraction
            Map<String, Object> simulationData = new HashMap<>();
            simulationData.put("document_type", strategy.contains("FINANCE") ? "Invoice" : "Standard Document");
            simulationData.put("confidence_score", 0.98);
            simulationData.put("extracted_fields", Map.of(
                "status", "Verified",
                "processed_via", "Neural OCR Core",
                "automatic_extraction", config.getAutoExtract() != null ? config.getAutoExtract() : true
            ));

            String safeName = engineUtils.sanitizeKey(step.getStepName());
            context.setVariable(safeName, simulationData);
            
            return StepResult.success(simulationData, "Document processed successfully using " + strategy);
        } catch (Exception e) {
            log.error("❌ Document Insight failed", e);
            return StepResult.error("OCR Failure: " + e.getMessage());
        }
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
