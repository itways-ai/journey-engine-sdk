package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.TextTranslator;
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
    private static final double MIN_ABSOLUTE_THRESHOLD = 0.70;
    private static final double MIN_RELATIVE_GAP       = 0.04;

    private static final double SURE_MATCH_THRESHOLD = 0.85;

    private final EngineUtils        engineUtils;
    private final VariableContext    variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort  knowledgeBasePort;
    private final EngineMessages messages;
    private final TextTranslator translator;

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
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
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
            // Locale-scoped when the index has content in this language. Without it a
            // near-duplicate English chunk can outscore the correct Arabic one, since
            // the embedding space is shared across languages.
            List<EngineSearchResult> results = knowledgeBasePort.search(
                    accountId, indexName, queryVector, limit, context.resolvedLanguage().code());

            String fallbackMsg = messages.get(context.resolvedLanguage(), "step.knowledge.noAnswer");

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
                String cleanAnswerText = answerInRunLanguage(bestMatch, context);
                storeKnowledgeOutput(step, context, cleanAnswerText, true);
                return StepResult.success(cleanAnswerText, step.getMessage());
            }

//            // Guard 2
//            if (bestScore < SURE_MATCH_THRESHOLD && actualGap < MIN_RELATIVE_GAP) {
//                log.warn("⚠️ Ambiguous result cluster detected. Actual gap of {} is less than required {}. Forcing fallback to protect domain accuracy.", actualGap, MIN_RELATIVE_GAP);
//                return triggerFallback(step,context,fallbackMsg);
//            }

            // GUARD 3: The "Soft Match" / Cross-Lingual Zone
            if(actualGap < MIN_RELATIVE_GAP) {
                log.warn("⚠️ Ambiguous cluster detected (Gap {} < {}). Returning best scored answer.", actualGap, MIN_RELATIVE_GAP);
                String cleanAnswerText = answerInRunLanguage(bestMatch, context);
                storeKnowledgeOutput(step, context, cleanAnswerText, true);
                return StepResult.success(cleanAnswerText, step.getMessage());
            }

            String cleanAnswerText = answerInRunLanguage(bestMatch, context);
            storeKnowledgeOutput(step, context, cleanAnswerText, true);

            log.info("✅ Knowledge Retrieval complete (Direct Answer).");
            return StepResult.success(cleanAnswerText, step.getMessage());

        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("Knowledge Retrieval failed: " + e.getMessage());
        }
    }

    /**
     * The matched answer, translated if it is stored in another language.
     *
     * <p>
     * Only reached when the index had nothing in the run's language -- the search
     * itself prefers matching-locale chunks, so a properly tagged bilingual
     * corpus never gets here and never pays for a translation.
     *
     * <p>
     * An untagged chunk (null locale) is returned as stored. Guessing its
     * language and translating on that guess risks mangling an answer that was
     * already correct, and untagged corpora are the ones most likely to be mixed.
     */
    private String answerInRunLanguage(EngineSearchResult match, ExecutionContext context) {
        String answer = match.answer();
        ConversationLanguage stored = ConversationLanguage.parse(match.locale());
        ConversationLanguage target = context.resolvedLanguage();

        if (answer == null || answer.isBlank() || stored == null || stored == target) {
            return answer;
        }

        try {
            String translated = translator.translate(context.getAccountId(), answer, stored, target);
            if (translated != null && !translated.isBlank()) {
                log.info("Translated knowledge answer from {} to {}", stored.code(), target.code());
                return translated;
            }
        } catch (Exception e) {
            log.warn("Could not translate knowledge answer: {}", e.getMessage());
        }

        // A correct answer in the wrong language beats no answer at all.
        return answer;
    }

    private StepResult triggerFallback(JourneyStep step, ExecutionContext context, String fallbackMsg) {
        storeKnowledgeOutput(step, context, fallbackMsg, false);
        return StepResult.success(fallbackMsg, step.getMessage());
    }

    private void storeKnowledgeOutput(JourneyStep step, ExecutionContext context, String answer, boolean found) {
        // Namespaced only: {{steps.<order>.output}} and {{steps.<order>.found}}.
        // The flat knowledge_found / step<N>_found / <name>_found flags are gone.
        variableContext.storeOutput(context, step, answer);
        variableContext.writeStepField(context, step, "found", found);
    }
}
