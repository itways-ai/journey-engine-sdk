package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.model.*;
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

    private static final int    DEFAULT_LIMIT            = 5;
    private static final double MIN_ABSOLUTE_THRESHOLD   = 0.78;
    private static final double MIN_RELATIVE_GAP         = 0.04;
    private static final double SURE_MATCH_THRESHOLD     = 0.85;

    private final EngineUtils          engineUtils;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort    knowledgeBasePort;

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config    = engineUtils.parseApiConfig(step.getApiConfig());
            String    accountId = context.getAccountId();

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

            int    limit             = DEFAULT_LIMIT;
            double absoluteThreshold = (config.getThreshold() != null && config.getThreshold() > 0)
                    ? config.getThreshold()
                    : MIN_ABSOLUTE_THRESHOLD;

            String resolvedMessage = step.getMessage() != null
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : null;

            log.info("🔍 Knowledge Scored Retrieval: index='{}', query='{}'", indexName, query);

            float[] queryVector = embeddingEngine.embed(query);

            List<EngineSearchResult> results = knowledgeBasePort.search(
                    accountId, indexName, queryVector, limit);

            String fallbackMsg = "I don't have information about this.";

            if (results.isEmpty()) {
                log.warn("⚠️ Database query returned 0 rows for index: '{}'", indexName);
                return triggerFallback(step, context, fallbackMsg, resolvedMessage);
            }

            EngineSearchResult bestMatch   = results.get(0);
            double             bestScore   = bestMatch.similarity();
            double             secondScore = results.size() > 1 ? results.get(1).similarity() : 0.0;
            double             actualGap   = bestScore - secondScore;

            log.info("🎯 Top score={}, Second score={}, Gap={}", bestScore, secondScore, actualGap);

            // GUARD 1: Absolute Floor
            if (bestScore < absoluteThreshold) {
                log.warn("❌ Top score {} rejected below absolute threshold of {}", bestScore, absoluteThreshold);
                return triggerFallback(step, context, fallbackMsg, resolvedMessage);
            }

            // GUARD 2: Sure Match Bypass
            if (bestScore >= SURE_MATCH_THRESHOLD) {
                log.info("🌟 Sure Match bypassed gap check! Score: {}", bestScore);
                String answer = bestMatch.answer();
                setContextVariables(step, context, answer, true);
                return StepResult.success(answer, resolvedMessage);
            }

            // GUARD 3: Soft Match / Cross-Lingual Zone
            if (actualGap < MIN_RELATIVE_GAP) {
                log.warn("⚠️ Ambiguous cluster detected (Gap {} < {}). Resolving via Context Aggregation.", actualGap, MIN_RELATIVE_GAP);
                StringBuilder aggregatedResult = new StringBuilder();
                for (int i = 0; i < Math.min(results.size(), 3); i++) {
                    EngineSearchResult current = results.get(i);
                    if ((bestScore - current.similarity()) <= MIN_RELATIVE_GAP) {
                        aggregatedResult.append(current.answer());
                    }
                }
                return handleSoftMatch(step, context, aggregatedResult.toString(), resolvedMessage);
            }

            // Direct answer (0.78 – 0.85 with sufficient gap)
            String answer = bestMatch.answer();
            setContextVariables(step, context, answer, true);
            log.info("✅ Knowledge Retrieval complete (Direct Answer).");
            return StepResult.success(answer, resolvedMessage);

        } catch (Exception e) {
            log.error("❌ Knowledge Retrieval failed", e);
            return StepResult.error("KNOWLEDGE_RETRIEVAL: " + e.getMessage());
        }
    }

    private void setContextVariables(JourneyStep step, ExecutionContext context, String answer, boolean found) {
        context.addStepResult(step.getStepOrder(), answer);
        context.setVariable("step" + step.getStepOrder(), answer);
        context.setVariable("lastStep", answer);
        context.setVariable("retrieved_knowledge", answer);
        context.setVariable("knowledge_found", found);
        context.setVariable("step" + step.getStepOrder() + "_found", found);
        if (step.getStepName() != null && !step.getStepName().isEmpty()) {
            String safeName = engineUtils.sanitizeKey(step.getStepName());
            context.setVariable(safeName + "_result", answer);
            context.setVariable(safeName + "_found", found);
        }
    }

    private StepResult triggerFallback(JourneyStep step, ExecutionContext context, String fallbackMsg, String resolvedMessage) {
        setContextVariables(step, context, fallbackMsg, false);
        return StepResult.success(fallbackMsg, resolvedMessage);
    }

    private StepResult handleSoftMatch(JourneyStep step, ExecutionContext context, String answer, String resolvedMessage) {
        setContextVariables(step, context, answer, true);
        return StepResult.success(answer, resolvedMessage);
    }
}


