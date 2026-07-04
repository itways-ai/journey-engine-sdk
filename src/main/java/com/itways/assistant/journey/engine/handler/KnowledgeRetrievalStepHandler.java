package com.itways.assistant.journey.engine.handler;

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

    private static final int DEFAULT_LIMIT = 5;
    private static final double MIN_ABSOLUTE_THRESHOLD = 0.70;
    private static final double MIN_RELATIVE_GAP = 0.04;
    private static final double SURE_MATCH_THRESHOLD = 0.85;

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort knowledgeBasePort;

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

            int limit = config.getLimit() != null ? config.getLimit() : DEFAULT_LIMIT;
            double minimumScore = resolveThreshold(config.getThreshold());

            KnowledgeBasePort.EmbeddingMetadata embeddingMetadata =
                    knowledgeBasePort.getEmbeddingMetadata(accountId, indexName);

            log.info("Knowledge Scored Retrieval: index='{}', query='{}', embeddingModel='{}', dimension={}, threshold={}",
                    indexName, query, embeddingMetadata.embeddingModel(), embeddingMetadata.embeddingDimension(), minimumScore);

            float[] queryVector = embeddingEngine.embed(query, embeddingMetadata.embeddingModel());

            List<EngineSearchResult> results = knowledgeBasePort.search(
                    accountId,
                    indexName,
                    queryVector,
                    limit,
                    embeddingMetadata.embeddingModel(),
                    embeddingMetadata.embeddingDimension());

            String fallbackMsg = "I don't have information about this.";

            if (results.isEmpty()) {
                log.warn("Database query returned 0 rows for index '{}'", indexName);
                return triggerFallback(step, context, fallbackMsg);
            }

            EngineSearchResult bestMatch = results.get(0);
            double bestScore = bestMatch.similarity();
            double secondScore = results.size() > 1 ? results.get(1).similarity() : 0.0;
            double actualGap = bestScore - secondScore;

            log.info("Top score={}, second score={}, gap={}, answer='{}'",
                    bestScore, secondScore, actualGap, bestMatch.answer());

            if (bestScore < minimumScore) {
                log.warn("Top score {} rejected below configured threshold {}", bestScore, minimumScore);
                return triggerFallback(step, context, fallbackMsg);
            }

            if (bestScore >= SURE_MATCH_THRESHOLD) {
                log.info("Sure match bypassed gap check. Score={}", bestScore);
                String cleanAnswerText = bestMatch.answer();
                storeKnowledgeOutput(step, context, cleanAnswerText);
                return StepResult.success(cleanAnswerText, step.getMessage());
            }

            if (actualGap < MIN_RELATIVE_GAP) {
                log.warn("Ambiguous cluster detected. Gap {} < {}. Returning best scored answer.",
                        actualGap, MIN_RELATIVE_GAP);
                String cleanAnswerText = bestMatch.answer();
                storeKnowledgeOutput(step, context, cleanAnswerText);
                return StepResult.success(cleanAnswerText, step.getMessage());
            }

            String cleanAnswerText = bestMatch.answer();
            storeKnowledgeOutput(step, context, cleanAnswerText);

            log.info("Knowledge Retrieval complete.");
            return StepResult.success(cleanAnswerText, step.getMessage());
        } catch (Exception e) {
            log.error("Knowledge Retrieval failed", e);
            return StepResult.error("Knowledge Retrieval failed: " + e.getMessage());
        }
    }

    private StepResult triggerFallback(JourneyStep step, ExecutionContext context, String fallbackMsg) {
        storeKnowledgeOutput(step, context, fallbackMsg);
        return StepResult.success(fallbackMsg, step.getMessage());
    }

    private void storeKnowledgeOutput(JourneyStep step, ExecutionContext context, String answer) {
        variableContext.storeOutput(context, step, answer);
    }

    private double resolveThreshold(Double configuredThreshold) {
        if (configuredThreshold == null) {
            return MIN_ABSOLUTE_THRESHOLD;
        }
        if (configuredThreshold < 0 || configuredThreshold > 1) {
            log.warn("Invalid knowledge threshold {}. Using default {}", configuredThreshold, MIN_ABSOLUTE_THRESHOLD);
            return MIN_ABSOLUTE_THRESHOLD;
        }
        return configuredThreshold;
    }
}
