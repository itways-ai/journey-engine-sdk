package com.itways.assistant.journey.engine.handler;

import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final ObjectMapper objectMapper;
    private final AiService aiService;

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String query = engineUtils.replacePlaceholders(config.getQuery() != null ? config.getQuery() : "", context.getVariables());
            
            log.info("🔍 Knowledge Retrieval Step: '{}' with query: {}", step.getStepName(), query);

            // Construct RAG System Prompt
            List<AiMessage> messages = new ArrayList<>();
            messages.add(new AiMessage("system", "You are a Knowledge Retrieval Specialist. Find relevant information for the following query and return it as a concise summary. Query: " + query));
            
            AiChatRequest chatRequest = new AiChatRequest();
            chatRequest.setMessages(messages);
            
            AiResponse aiResponse = aiService.chat(chatRequest);
            String resultText = aiResponse.getContent();

            context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), resultText);
            context.setVariable("retrieved_knowledge", resultText);
            
            return StepResult.success(resultText, step.getMessage());
        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("RAG Failure: " + e.getMessage());
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
