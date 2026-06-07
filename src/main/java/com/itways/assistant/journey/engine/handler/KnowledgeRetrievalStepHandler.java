package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiEmbeddingRequest;
import com.itways.assistant.ai.dto.AiEmbeddingResponse;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.AiConfigProvider;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalStepHandler implements StepHandler {

    private static final int    DEFAULT_LIMIT     = 5;
    private static final double DEFAULT_THRESHOLD = 0.70;

    private static final String SYNTHESIS_SYSTEM_PROMPT =
            "You are a Knowledge Assistant. You have been given relevant excerpts from a knowledge base. " +
            "Use ONLY the provided excerpts to answer the user's question. " +
            "If the excerpts do not contain enough information to answer, say so clearly. " +
            "Do not make up information.";

    private final EngineUtils        engineUtils;
    private final ObjectMapper       objectMapper;
    private final AiService          aiService;
    private final AiConfigProvider   aiConfigProvider;
    private final KnowledgeBasePort  knowledgeBasePort;

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());

            // 1. Query = user's message (context variable "text").
            //    Optionally overridable via apiConfig.query with {{placeholder}} syntax,
            //    but for standard FAQ flows the user's input IS the query.
            String rawQuery = (config.getQuery() != null && !config.getQuery().isBlank())
                    ? config.getQuery()
                    : "{{text}}";

            String query = engineUtils.replacePlaceholders(rawQuery, context.getVariables());

            if (query.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: query is empty");
            }

            String indexName = config.getIndexName();
            if (indexName == null || indexName.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: indexName is required");
            }

            int    limit     = config.getLimit()     != null ? config.getLimit()     : DEFAULT_LIMIT;
            double threshold = config.getThreshold() != null ? config.getThreshold() : DEFAULT_THRESHOLD;

            log.info("🔍 Knowledge Retrieval: index='{}', query='{}', limit={}, threshold={}",
                    indexName, query, limit, threshold);

            // 2. Get AI config for this account (provider + apiKey)
            AiRequestConfig aiConfig = aiConfigProvider.getConfig(context.getAccountId());

            // 3. Embed the query
            AiEmbeddingRequest embeddingRequest = AiEmbeddingRequest.builder()
                    .input(query)
                    .config(aiConfig)
                    .build();

            AiEmbeddingResponse embeddingResponse = aiService.embed(embeddingRequest);
            float[] queryVector = embeddingResponse.getVector();

            log.info("✅ Query embedded, dimensions: {}", queryVector.length);

            // 4. Vector search via port (implemented in journey-service)
            List<String> chunks = knowledgeBasePort.search(
                    context.getAccountId(), indexName, queryVector, limit, threshold);

            if (chunks.isEmpty()) {
                log.warn("⚠️ No matching chunks found for query: '{}'", query);
                String noResultMsg = "No relevant information found in the knowledge base for: " + query;
                context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), noResultMsg);
                context.setVariable("retrieved_knowledge", noResultMsg);
                return StepResult.success(noResultMsg, step.getMessage());
            }

            log.info("✅ Retrieved {} chunks from knowledge base", chunks.size());

//            // 5. Store raw chunks in context for downstream steps
//            context.setVariable("retrieved_chunks", chunks);
//
//            // 6. Synthesize a natural language answer using the LLM
//            String chunksContext = buildChunksContext(chunks);
//            String userPrompt    = "Knowledge Base Answers:\n" + chunksContext +
//                                   "\n\nUser Question: " + query;
//
//            AiChatRequest chatRequest = AiChatRequest.builder()
//                    .messages(List.of(
//                            AiMessage.system(SYNTHESIS_SYSTEM_PROMPT),
//                            AiMessage.user(userPrompt)))
//                    .config(aiConfig)
//                    .build();
//
//            AiResponse synthesisResponse = aiService.chat(chatRequest);
//            String answer = synthesisResponse.getContent();
//
//            // 7. Store result in context
//            context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), answer);
//            context.setVariable("retrieved_knowledge", answer);
//
//            log.info("✅ Knowledge Retrieval complete for step '{}'", step.getStepName());
//            return StepResult.success(answer, step.getMessage());
            // the first item in chunks is already the best exact answer
            String  bestAnswer = chunks.get(0);

            // 5. store result in context directly (NO AI CHAT CALL)
            context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), bestAnswer);
            context.setVariable("retrieved_knowledge", bestAnswer);

            log.info("✅ Knowledge Retrieval complete (Direct Answer). Bypassed LLM generation.");
            return  StepResult.success(bestAnswer, step.getMessage());

        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("Knowledge Retrieval failed: " + e.getMessage());
        }
    }

    private String buildChunksContext(List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(chunks.get(i)).append("\n");
        }
        return sb.toString();
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
