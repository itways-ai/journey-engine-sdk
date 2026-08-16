package com.itways.assistant.journey.engine.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.service.StepTextPort;
import com.itways.assistant.journey.engine.service.TextTranslator;

class StepLocalizerTest {

    private static final String ACCOUNT = "acc-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepLocalizer localizer;
    private RecordingPort port;

    @BeforeEach
    void setUp() {
        port = new RecordingPort();
        localizer = new StepLocalizer(MAPPER, new LanguageDetector(),
                provider(TextTranslator.NONE), provider(port));
    }

    @Test
    @DisplayName("a stored variant replaces the authored message")
    void appliesStoredTranslation() {
        JourneyStep step = step("USER_INPUT", "What is your order number?");
        Map<Long, StepText> translations = Map.of(1L, new StepText("ما هو رقم طلبك؟", null, null, null));

        JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(localized.getMessage()).isEqualTo("ما هو رقم طلبك؟");
    }

    @Test
    @DisplayName("the shared journey step is never mutated")
    void neverMutatesTheOriginal() {
        // The Journey is fetched once per turn and, on a resumed run, describes
        // steps other conversations are executing concurrently in other
        // languages. Mutating it leaks one conversation's language into another.
        JourneyStep step = step("USER_INPUT", "What is your order number?");
        Map<Long, StepText> translations = Map.of(1L, new StepText("ما هو رقم طلبك؟", null, null, null));

        JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(step.getMessage()).isEqualTo("What is your order number?");
        assertThat(localized).isNotSameAs(step);
    }

    @Test
    @DisplayName("actionTarget is translated on a RESPONSE step")
    void translatesResponseBody() {
        JourneyStep step = step("RESPONSE", null);
        step.setActionTarget("Your order is on its way.");
        Map<Long, StepText> translations = Map.of(1L, new StepText(null, "طلبك في الطريق إليك.", null, null));

        JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(localized.getActionTarget()).isEqualTo("طلبك في الطريق إليك.");
    }

    @Test
    @DisplayName("actionTarget is left alone on every other step type")
    void neverTranslatesNonResponseTargets() {
        // On these types actionTarget is a URL, a template id or an intent code.
        // Translating one does not produce a badly worded step, it produces a
        // broken one -- a REDIRECT to nowhere, a TRIGGER_JOURNEY that resolves
        // to no journey at all.
        for (String type : new String[] { "REDIRECT", "API_CALL", "TEMPLATE_RENDER", "TRIGGER_JOURNEY" }) {
            JourneyStep step = step(type, null);
            step.setActionTarget("https://example.com/track");
            Map<Long, StepText> translations = Map.of(1L, new StepText(null, "ترجمة خاطئة", null, null));

            JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

            assertThat(localized.getActionTarget())
                    .as("actionTarget on a %s step", type)
                    .isEqualTo("https://example.com/track");
        }
    }

    @Test
    @DisplayName("only the prose fields inside apiConfig are rewritten")
    void patchesOnlyProseInApiConfig() throws Exception {
        JourneyStep step = step("USER_INPUT", "Confirm?");
        step.setApiConfig("""
                {"method":"POST","inputMode":"INTERACTIVE","confirmationMessage":"Is this correct?",
                 "headers":{"X-Trace":"confirmationMessage"},"body":{"note":"Is this correct?"}}
                """);
        Map<Long, StepText> translations = Map.of(1L,
                new StepText(null, null, "هل هذا صحيح؟", null));

        JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

        var patched = MAPPER.readTree(localized.getApiConfig());
        assertThat(patched.get("confirmationMessage").asText()).isEqualTo("هل هذا صحيح؟");
        // A textual find-and-replace would have corrupted both of these.
        assertThat(patched.get("method").asText()).isEqualTo("POST");
        assertThat(patched.get("headers").get("X-Trace").asText()).isEqualTo("confirmationMessage");
        assertThat(patched.get("body").get("note").asText()).isEqualTo("Is this correct?");
    }

    @Test
    @DisplayName("an unparseable apiConfig loses the translation, not the step")
    void malformedApiConfigIsSurvivable() {
        JourneyStep step = step("USER_INPUT", "Confirm?");
        step.setApiConfig("{not json");
        Map<Long, StepText> translations = Map.of(1L, new StepText("أكد؟", null, "هل هذا صحيح؟", null));

        JourneyStep localized = localizer.localize(step, translations, ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(localized.getApiConfig()).isEqualTo("{not json");
        // The message still gets translated; only the config patch is dropped.
        assertThat(localized.getMessage()).isEqualTo("أكد؟");
    }

    @Test
    @DisplayName("text already in the run's language is never sent for translation")
    void skipsTranslationWhenAlreadyInTargetLanguage() {
        // The common case for a bilingual author, and it must cost nothing.
        CountingTranslator translator = new CountingTranslator();
        localizer = new StepLocalizer(MAPPER, new LanguageDetector(), provider(translator), provider(port));

        JourneyStep step = step("USER_INPUT", "ما هو رقم طلبك؟");

        JourneyStep localized = localizer.localize(step, Map.of(), ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(translator.calls).isZero();
        assertThat(localized).isSameAs(step);
    }

    @Test
    @DisplayName("a missing variant is machine translated and written back")
    void translatesAndPersistsWhenNoVariantExists() {
        CountingTranslator translator = new CountingTranslator();
        localizer = new StepLocalizer(MAPPER, new LanguageDetector(), provider(translator), provider(port));

        JourneyStep step = step("USER_INPUT", "What is your order number?");

        JourneyStep localized = localizer.localize(step, Map.of(), ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(localized.getMessage()).isEqualTo("[ar] What is your order number?");
        // Written back so the next run does not pay for the same call.
        assertThat(port.saved).containsKey(1L);
    }

    @Test
    @DisplayName("a translator failure leaves the step as authored")
    void translatorFailureIsNotFatal() {
        TextTranslator exploding = (accountId, text, from, to) -> {
            throw new IllegalStateException("provider down");
        };
        localizer = new StepLocalizer(MAPPER, new LanguageDetector(), provider(exploding), provider(port));

        JourneyStep step = step("USER_INPUT", "What is your order number?");

        JourneyStep localized = localizer.localize(step, Map.of(), ACCOUNT, ConversationLanguage.ARABIC);

        assertThat(localized.getMessage()).isEqualTo("What is your order number?");
        assertThat(port.saved).isEmpty();
    }

    private static JourneyStep step(String type, String message) {
        return JourneyStep.builder()
                .id(1L)
                .stepOrder(1)
                .stepName("Step")
                .actionType(type)
                .message(message)
                .build();
    }

    /** Marks the text rather than really translating, so assertions stay readable. */
    private static final class CountingTranslator implements TextTranslator {
        private int calls;

        @Override
        public String translate(String accountId, String text, ConversationLanguage from, ConversationLanguage to) {
            if (text == null || text.isBlank()) {
                return null;
            }
            calls++;
            return "[" + to.code() + "] " + text;
        }
    }

    private static final class RecordingPort implements StepTextPort {
        private final Map<Long, StepText> saved = new java.util.HashMap<>();

        @Override
        public Map<Long, StepText> forJourney(String accountId, Long journeyId, ConversationLanguage language) {
            return Map.of();
        }

        @Override
        public void saveMachineTranslation(String accountId, Long stepId, ConversationLanguage language,
                StepText text) {
            saved.put(stepId, text);
        }
    }

    /** Minimal ObjectProvider: the localizer only ever calls getIfAvailable. */
    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }
}
