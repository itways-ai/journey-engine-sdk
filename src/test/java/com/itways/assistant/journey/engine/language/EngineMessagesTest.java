package com.itways.assistant.journey.engine.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EngineMessagesTest {

    private final EngineMessages messages = new EngineMessages();

    @Test
    @DisplayName("every locale defines exactly the keys the base bundle does")
    void bundlesAgreeOnKeys() throws IOException {
        // The failure this prevents is not a crash: a key missing from ar falls
        // back to the base bundle and emits one English sentence into an Arabic
        // conversation, which reads as a glitch and is easy to miss in review.
        Set<Object> base = load("messages/engine.properties").keySet();
        for (ConversationLanguage language : ConversationLanguage.values()) {
            Set<Object> localised = load("messages/engine_" + language.code() + ".properties").keySet();
            assertThat(localised)
                    .as("keys in engine_%s.properties", language.code())
                    .containsExactlyInAnyOrderElementsOf(base);
        }
    }

    @Test
    @DisplayName("Arabic strings resolve as Arabic, not as the key or as English")
    void arabicResolves() {
        String prompt = messages.get(ConversationLanguage.ARABIC, "step.approval.prompt");
        assertThat(prompt).isNotEqualTo("step.approval.prompt");
        assertThat(prompt).containsAnyOf("يرجى", "أجب");
    }

    @Test
    @DisplayName("message arguments are substituted")
    void formatsArguments() {
        assertThat(messages.get(ConversationLanguage.ENGLISH, "step.userInput.waiting", "Email"))
                .isEqualTo("Waiting for input: Email");
        assertThat(messages.get(ConversationLanguage.ARABIC, "step.userInput.waiting", "البريد"))
                .contains("البريد");
    }

    @Test
    @DisplayName("an unknown key returns itself rather than throwing mid-run")
    void unknownKeyIsNotFatal() {
        assertThat(messages.get(ConversationLanguage.ENGLISH, "no.such.key")).isEqualTo("no.such.key");
    }

    @Test
    @DisplayName("no word counts as both approval and rejection in any locale")
    void decisionWordsAreDisjoint() {
        // HumanApprovalStepHandler drops any overlap at startup, so an overlap here
        // silently removes a word from the vocabulary instead of failing loudly.
        Set<String> approve = new HashSet<>();
        Set<String> reject = new HashSet<>();
        for (ConversationLanguage language : ConversationLanguage.values()) {
            approve.addAll(words(messages.get(language, "step.approval.approveWords")));
            reject.addAll(words(messages.get(language, "step.approval.rejectWords")));
        }
        assertThat(approve).isNotEmpty();
        assertThat(reject).isNotEmpty();
        assertThat(new HashSet<>(approve)).doesNotContainAnyElementsOf(reject);
    }

    private static Set<String> words(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EngineMessagesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource %s", resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
