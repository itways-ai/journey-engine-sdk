package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalStepHandler implements StepHandler {

    private static final int    DEFAULT_LIMIT     = 5;
    private static final double MIN_ABSOLUTE_THRESHOLD = 0.75;
    private static final double MIN_RELATIVE_GAP       = 0.04;

    private static final double SURE_MATCH_THRESHOLD= 0.85;

//    private static final String SYNTHESIS_SYSTEM_PROMPT =
//            "You are a Knowledge Assistant. You have been given relevant excerpts from a knowledge base. " +
//            "Use ONLY the provided excerpts to answer the user's question. " +
//            "If the excerpts do not contain enough information to answer, say so clearly. " +
//            "Do not make up information.";
    private final EngineUtils        engineUtils;
    private final VariableContext    variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final ObjectMapper       objectMapper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort  knowledgeBasePort;

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.knowledgeDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.knowledgeRetrievalSchema();
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
                    : "{{inputs.text}}";

            String query = engineUtils.replacePlaceholders(rawQuery, context.getVariables());

            if (query.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: query is empty");
            }

            String indexName = config.getIndexName();
            if (indexName == null || indexName.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: indexName is required");
            }

            int    limit     = config.getLimit()     != null ? config.getLimit()     : DEFAULT_LIMIT;

            log.info("🔍 Knowledge Scored Retrieval: index='{}', query='{}'", indexName, query);

            float[] queryVector = embeddingEngine.embed(query);

            // Vector search via port (implemented in journey-service)
            List<EngineSearchResult> results = knowledgeBasePort.search(
                    accountId, indexName, queryVector, limit);

            String fallbackMsg = "I don't have information about this.";

            if (results.isEmpty()) {
                log.warn("⚠️ Database query returned 0 rows for index: '{}'", indexName);
                return triggerFallback(step,context,fallbackMsg);
            }

            EngineSearchResult bestMatch = results.get(0);
            log.info("🎯 Evaluated top match score: {} | Text: '{}'", bestMatch.similarity(), bestMatch.answer());

            double bestScore = bestMatch.similarity();
            double secondScore = results.size() > 1 ? results.get(1).similarity() : 0.0;
            double actualGap = bestScore - secondScore;

            log.info(
                    "Top score={}, Second score={}, Gap={}",
                    bestScore,
                    secondScore,
                    actualGap
            );

            log.info("📊 Confidence gap check -> Best: {}, Second: {}, Computed Gap: {}", bestScore, secondScore, actualGap);
            // GUARD 1: Absolute Floor (Protects against total hallucinations)
            if(bestScore < MIN_ABSOLUTE_THRESHOLD) {
                log.warn("❌ Top score {} rejected below absolute requirement of {}", bestMatch.similarity(), MIN_ABSOLUTE_THRESHOLD);
                return triggerFallback(step,context,fallbackMsg);
            }

            // GUARD 2: The "Sure Match" Bypass (For exact Arabic-to-Arabic matches)
            if(bestScore >= SURE_MATCH_THRESHOLD){
                log.info("🌟 Sure Match bypassed gap check! Score: {}", bestScore);
                String cleanAnswerText = bestMatch.answer();
                storeKnowledgeOutput(step, context, cleanAnswerText);
                return StepResult.success(cleanAnswerText, step.getMessage());
            }

//            // Guard 2
//            if (bestScore < SURE_MATCH_THRESHOLD && actualGap < MIN_RELATIVE_GAP) {
//                log.warn("⚠️ Ambiguous result cluster detected. Actual gap of {} is less than required {}. Forcing fallback to protect domain accuracy.", actualGap, MIN_RELATIVE_GAP);
//                return triggerFallback(step,context,fallbackMsg);
//            }

           // GUARD 3: The "Soft Match" / Cross-Lingual Zone
            if(actualGap < MIN_RELATIVE_GAP) {
                log.warn("⚠️ Ambiguous cluster detected (Gap {} < {}). Resolving via Context Aggregation.", actualGap, MIN_RELATIVE_GAP);
                // Because the top scores are strong (>0.78) but the gap is small,
                // the user's intent matches multiple FAQs.
                // We combine the top 2 or 3 results into a single context block.

                StringBuilder aggregatedResult = new StringBuilder();

                for(int i=0 ;i<Math.min(results.size(),3);i++){
                    EngineSearchResult current = results.get(i);
                    // Only include chunks that are mathematically close to the top score
                    if((bestScore -current.similarity()) <= MIN_RELATIVE_GAP) {
                        aggregatedResult.append(current.answer());
                    }
                }
                // Pass this aggregated block to your Chatbot LLM node
              return handleSoftMatch(step,context,aggregatedResult.toString());
            }
            // if between 0.78 and 0.85 return best answer
            String cleanAnswerText = bestMatch.answer();
            storeKnowledgeOutput(step, context, cleanAnswerText);

            log.info("✅ Knowledge Retrieval complete (Direct Answer).");
            return StepResult.success(cleanAnswerText, step.getMessage());

        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("Knowledge Retrieval failed: " + e.getMessage());
        }
    }

    private StepResult triggerFallback(JourneyStep step, ExecutionContext context, String fallbackMsg) {
        storeKnowledgeOutput(step, context, fallbackMsg);
        return StepResult.success(fallbackMsg, step.getMessage());
    }

    private StepResult handleSoftMatch(JourneyStep step, ExecutionContext context, String answer) {
        storeKnowledgeOutput(step, context, answer);
        return StepResult.success(answer, step.getMessage());
    }

    private void storeKnowledgeOutput(JourneyStep step, ExecutionContext context, String answer) {
        variableContext.writeStepOutput(context, step, answer);
        context.addStepResult(step.getStepOrder(), answer);
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
