package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
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
    private static final double DEFAULT_THRESHOLD = 0.72;
//    private static final String SYNTHESIS_SYSTEM_PROMPT =
//            "You are a Knowledge Assistant. You have been given relevant excerpts from a knowledge base. " +
//            "Use ONLY the provided excerpts to answer the user's question. " +
//            "If the excerpts do not contain enough information to answer, say so clearly. " +
//            "Do not make up information.";
    private final EngineUtils        engineUtils;
    private final ObjectMapper       objectMapper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort  knowledgeBasePort;

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = loadApiConfig(step.getApiConfig());
            String accountId = context.getAccountId();


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

            float[] queryVector = embeddingEngine.embed(query);
            log.info("✅ Query embedded, dimensions: {}", queryVector.length);

            // Vector search via port (implemented in journey-service)
            List<String> chunks = knowledgeBasePort.search(
                    accountId, indexName, queryVector, limit, threshold);

            if (chunks.isEmpty()) {
                log.warn("⚠️ No relevant database context found for query: '{}'", query);
                String fallbackMsg = "I don't have information about this.";
                context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), fallbackMsg);
                context.setVariable("retrieved_knowledge", fallbackMsg);
                return StepResult.success(fallbackMsg, step.getMessage());
            }

            log.info("✅ Retrieved {} chunks from knowledge base", chunks.size());

            String  bestAnswer = chunks.get(0);

            // Store direct exact answer in context
            context.setVariable(engineUtils.sanitizeKey(step.getStepName() + "_result"), bestAnswer);
            context.setVariable("retrieved_knowledge", bestAnswer);

            log.info("✅ Knowledge Retrieval complete (Direct Answer).");
            return  StepResult.success(bestAnswer, step.getMessage());

        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("Knowledge Retrieval failed: " + e.getMessage());
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

//    private String determineCategoryWithAi(String userQuery,
//                                           List<String> categories,
//                                           String accountId){
//        try{
//            String categoriesListString = String.join("\n-", categories);
//            String systemPrompt = """
//                أنت مصنف نصوص محترف وخبير في فرز الاستفسارات الإدارية.
//                مهمتك هي قراءة استفسار المستخدم واختيار التصنيف الأكثر ملاءمة له من القائمة الديناميكية المتوفرة فقط.
//
//                القائمة المتاحة حالياً في النظام:
//                - %s
//
//                شروط صارمة:
//                1. اختر تصنيفاً واحداً وبنفس اللفظ الدقيق والمطابق تماماً للقائمة المذكورة أعلاه.
//                2. إذا كان السؤال عاماً، ترحيبياً، أو خارج نطاق دلالات هذه التصنيفات تماماً، أجب بكلمة واحدة فقط: OTHER.
//                3. ممنوع نهائياً كتابة أي مقدمات، تفسيرات، علامات ترقيم، أو جمل إضافية. أجب باسم التصنيف أو كلمة OTHER فقط.
//                """.formatted(categoriesListString);
//
//            AiRequestConfig config = aiConfigProvider.getConfig(accountId);
//
//            AiChatRequest chatRequest = AiChatRequest.builder()
//                    .messages(List.of(AiMessage.system(systemPrompt),AiMessage.user(userQuery)))
//                    .config(config)
//                    .build();
//
//            AiResponse response = aiService.chat(chatRequest);
//            String result = response.getContent() != null ? response.getContent().trim() : "OTHER";
//
//            return categories.contains(result) ? result : null ;
//        } catch (Exception e) {
//            log.error("⚠️ Dynamic category classification lookup encountered an error: {}", e.getMessage());
//            return null ;
//        }
//    }
}
