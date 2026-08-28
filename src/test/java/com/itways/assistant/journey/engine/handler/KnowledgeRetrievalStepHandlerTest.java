package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.EngineSearchResult;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.ExecutionStatus;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KNOWLEDGE_RETRIEVAL's job is refusing to answer as much as answering: the
 * absolute floor (0.70) is what stands between a weak vector match and a
 * hallucinated answer served as fact. The embedding engine is a concrete class
 * from ai-engine-sdk, subclassed here rather than mocked — its constructor only
 * records configuration (langchain4j builders never connect at build time), so
 * overriding embed() is safe and keeps Ollama out of the tests entirely.
 */
@DisplayName("KnowledgeRetrievalStepHandler")
class KnowledgeRetrievalStepHandlerTest {

    private static final String ACCOUNT = "acc-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VariableContext variableContext = new VariableContext();
    private final EngineMessages messages = new EngineMessages();
    private final StubEmbeddingEngine embedding = new StubEmbeddingEngine();
    private final StubKnowledgePort port = new StubKnowledgePort();

    private KnowledgeRetrievalStepHandler handler(TextTranslator translator) {
        return new KnowledgeRetrievalStepHandler(
                new EngineUtils(objectMapper), variableContext,
                new StepOutputSchemaHelper(objectMapper), embedding, port, messages, translator);
    }

    private final KnowledgeRetrievalStepHandler handler = handler(TextTranslator.NONE);

    private String noAnswerMessage() {
        return messages.get(ConversationLanguage.ENGLISH, "step.knowledge.noAnswer");
    }

    private ExecutionContext context() {
        ExecutionContext context = ExecutionContext.builder()
                .accountId(ACCOUNT)
                .variables(new HashMap<>()).status(ExecutionStatus.RUNNING).build();
        variableContext.ensureStructure(context);
        variableContext.mergeInputs(context, Map.of("text", "How do I reset my password?"));
        return context;
    }

    private static JourneyStep step(String apiConfig) {
        return JourneyStep.builder()
                .stepOrder(2).stepName("FAQ lookup").actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig(apiConfig).build();
    }

    private static EngineSearchResult hit(String answer, double similarity, String locale) {
        return new EngineSearchResult(answer, similarity, locale);
    }

    @Nested
    @DisplayName("building the query")
    class Query {

        @Test
        @DisplayName("the user's message is the query, scoped to the account, index and run language")
        void usesTheUsersMessageAsTheQuery() {
            port.results = List.of(hit("Use the reset link.", 0.91, "en"));

            handler.execute(step("{\"indexName\":\"faq\"}"), context());

            assertThat(embedding.embeddedText).isEqualTo("How do I reset my password?");
            assertThat(port.accountId).isEqualTo(ACCOUNT);
            assertThat(port.indexName).isEqualTo("faq");
            assertThat(port.limit).isEqualTo(5);
            // Locale-scoped search is what stops a near-duplicate English chunk
            // outscoring the correct Arabic one on a shared embedding space.
            assertThat(port.locale).isEqualTo("en");
        }

        @Test
        @DisplayName("an authored query template overrides the user's message")
        void authoredQueryOverrides() {
            port.results = List.of(hit("Answer.", 0.91, "en"));
            ExecutionContext context = context();
            variableContext.mergeInputs(context, Map.of("topic", "billing"));

            handler.execute(step("{\"indexName\":\"faq\",\"query\":\"explain {{inputs.entities.topic}}\"}"),
                    context);

            assertThat(embedding.embeddedText).isEqualTo("explain billing");
        }

        @Test
        @DisplayName("an empty query fails before touching the embedding engine")
        void emptyQueryFails() {
            ExecutionContext context = context();
            variableContext.getInputs(context).remove("text");

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("query is empty");
            assertThat(embedding.embeddedText).isNull();
        }

        @Test
        @DisplayName("a missing index name fails plainly")
        void missingIndexFails() {
            StepResult result = handler.execute(step("{}"), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("indexName is required");
        }
    }

    @Nested
    @DisplayName("confidence guards")
    class ConfidenceGuards {

        @Test
        @DisplayName("a sure match (>= 0.85) is answered directly and flagged as found")
        void sureMatchAccepted() {
            port.results = List.of(hit("Use the reset link.", 0.91, "en"),
                    hit("Contact support.", 0.90, "en"));
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("Use the reset link.");
            assertThat(variableContext.read(context, "steps.2.output")).isEqualTo("Use the reset link.");
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(true);
        }

        @Test
        @DisplayName("a top score below the absolute floor (0.70) falls back rather than guessing")
        void belowFloorRejected() {
            port.results = List.of(hit("Something vaguely related.", 0.69, "en"));
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            // The fallback is a SUCCESS carrying the localized refusal, not an
            // ERROR — "I don't know" is a valid answer, not a broken step.
            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(noAnswerMessage());
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(false);
        }

        @Test
        @DisplayName("no results at all falls back the same way")
        void noResultsFallsBack() {
            port.results = List.of();
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo(noAnswerMessage());
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(false);
        }

        @Test
        @DisplayName("a mid-band score with a clear gap over the runner-up is answered")
        void midBandWithClearGapAccepted() {
            port.results = List.of(hit("Use the reset link.", 0.80, "en"),
                    hit("Contact support.", 0.70, "en"));
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("Use the reset link.");
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(true);
        }

        @Test
        @DisplayName("an ambiguous cluster (gap < 0.04) still returns the best-scored answer")
        void ambiguousClusterStillAnswers() {
            // NOTE: possible defect — the source carries a commented-out guard
            // that forced a fallback for exactly this cluster, and the live
            // branch logs the ambiguity but answers anyway. Pinned as current
            // behavior; if the fallback guard is restored this test must flip.
            port.results = List.of(hit("Use the reset link.", 0.80, "en"),
                    hit("Contact support.", 0.79, "en"));
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("Use the reset link.");
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(true);
        }

        @Test
        @DisplayName("a knowledge base failure is a step ERROR, not a crashed run")
        void portFailureIsAStepError() {
            port.failure = new RuntimeException("pgvector down");

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context());

            assertThat(result.getStatus()).isEqualTo("ERROR");
            assertThat(result.getMessage()).contains("pgvector down");
        }
    }

    @Nested
    @DisplayName("answer language")
    class AnswerLanguage {

        @Test
        @DisplayName("a chunk stored in another language is translated into the run's language")
        void crossLingualAnswerIsTranslated() {
            RecordingTranslator translator = new RecordingTranslator();
            port.results = List.of(hit("استخدم رابط إعادة التعيين", 0.9, "ar"));

            StepResult result = handler(translator).execute(step("{\"indexName\":\"faq\"}"), context());

            assertThat(result.getData()).isEqualTo("[en] استخدم رابط إعادة التعيين");
            assertThat(translator.from).isEqualTo(ConversationLanguage.ARABIC);
            assertThat(translator.to).isEqualTo(ConversationLanguage.ENGLISH);
        }

        @Test
        @DisplayName("without a translator the stored answer is returned verbatim — wrong language beats no answer")
        void crossLingualAnswerReturnedAsStoredWithoutTranslator() {
            port.results = List.of(hit("استخدم رابط إعادة التعيين", 0.9, "ar"));
            ExecutionContext context = context();

            StepResult result = handler.execute(step("{\"indexName\":\"faq\"}"), context);

            assertThat(result.getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getData()).isEqualTo("استخدم رابط إعادة التعيين");
            assertThat(variableContext.read(context, "steps.2.found")).isEqualTo(true);
        }

        @Test
        @DisplayName("an untagged chunk is never translated — guessing risks mangling a correct answer")
        void untaggedChunkLeftAlone() {
            RecordingTranslator translator = new RecordingTranslator();
            port.results = List.of(hit("Use the reset link.", 0.9, null));

            StepResult result = handler(translator).execute(step("{\"indexName\":\"faq\"}"), context());

            assertThat(result.getData()).isEqualTo("Use the reset link.");
            assertThat(translator.called).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * The concrete engine with the model call overridden. The super constructor
     * only builds a client configuration — nothing connects until embed() runs,
     * which this class never lets happen.
     */
    private static final class StubEmbeddingEngine extends LocalEmbeddingEngine {

        private String embeddedText;

        private StubEmbeddingEngine() {
            super("http://localhost:9");
        }

        @Override
        public float[] embed(String text) {
            embeddedText = text;
            return new float[] { 0.1f, 0.2f, 0.3f };
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }

    private static final class StubKnowledgePort implements KnowledgeBasePort {

        private List<EngineSearchResult> results = List.of();
        private RuntimeException failure;

        private String accountId;
        private String indexName;
        private int limit;
        private String locale;

        @Override
        public List<EngineSearchResult> search(String accountId, String indexName,
                float[] queryVector, int limit, String locale) {
            this.accountId = accountId;
            this.indexName = indexName;
            this.limit = limit;
            this.locale = locale;
            if (failure != null) {
                throw failure;
            }
            return results;
        }
    }

    private static final class RecordingTranslator implements TextTranslator {

        private boolean called;
        private ConversationLanguage from;
        private ConversationLanguage to;

        @Override
        public String translate(String accountId, String text,
                ConversationLanguage from, ConversationLanguage to) {
            this.called = true;
            this.from = from;
            this.to = to;
            return "[" + to.code() + "] " + text;
        }
    }
}
