package com.itways.assistant.journey.engine.language;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Language tag parsing and the reserved-param lift. The parse contract that
 * "no opinion" (null) and "explicitly English" are different values is what the
 * whole precedence chain in LanguageResolver is built on; collapsing them would
 * make a missing hint outrank a real signal.
 */
@DisplayName("ConversationLanguage and LanguageParams")
class LanguageParamsAndParsingTest {

    private static ExecutionContext freshContext() {
        return ExecutionContext.builder().variables(new HashMap<>()).build();
    }

    @Nested
    @DisplayName("ConversationLanguage.parse")
    class Parsing {

        @Test
        @DisplayName("regional and underscore variants land on the primary language")
        void regionalVariants() {
            assertThat(ConversationLanguage.parse("ar-SA")).isEqualTo(ConversationLanguage.ARABIC);
            assertThat(ConversationLanguage.parse("ar_EG")).isEqualTo(ConversationLanguage.ARABIC);
            assertThat(ConversationLanguage.parse("EN-us")).isEqualTo(ConversationLanguage.ENGLISH);
        }

        @Test
        @DisplayName("'auto', blank, and null all mean no opinion — not the default")
        void noOpinionInputs() {
            assertThat(ConversationLanguage.parse("auto")).isNull();
            assertThat(ConversationLanguage.parse("  ")).isNull();
            assertThat(ConversationLanguage.parse(null)).isNull();
        }

        @Test
        @DisplayName("an unsupported language parses to null, and parseOrDefault falls back")
        void unsupportedLanguage() {
            assertThat(ConversationLanguage.parse("fr")).isNull();
            assertThat(ConversationLanguage.parseOrDefault("fr")).isEqualTo(ConversationLanguage.DEFAULT);
        }

        @Test
        @DisplayName("direction and codes are stable facts the UI builds on")
        void directionFacts() {
            assertThat(ConversationLanguage.ARABIC.isRightToLeft()).isTrue();
            assertThat(ConversationLanguage.ARABIC.direction().toString()).isEqualTo("rtl");
            assertThat(ConversationLanguage.ENGLISH.direction().toString()).isEqualTo("ltr");
            assertThat(ConversationLanguage.ARABIC.code()).isEqualTo("ar");
            assertThat(ConversationLanguage.ENGLISH.englishName()).isEqualTo("English");
        }
    }

    @Nested
    @DisplayName("LanguageParams.lift")
    class Lift {

        @Test
        @DisplayName("moves a supplied language onto the context and strips the reserved key")
        void liftsAndStrips() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put(LanguageParams.PARAM_LANGUAGE, "ar");

            LanguageParams.lift(context, params);

            assertThat(context.getLanguage()).isEqualTo(ConversationLanguage.ARABIC);
            assertThat(params).doesNotContainKey(LanguageParams.PARAM_LANGUAGE);
            assertThat(context.getVariables()).doesNotContainKey(LanguageParams.PARAM_LANGUAGE);
        }

        @Test
        @DisplayName("accepts an already-typed ConversationLanguage value")
        void typedValue() {
            ExecutionContext context = freshContext();
            Map<String, Object> params = new HashMap<>();
            params.put(LanguageParams.PARAM_LANGUAGE, ConversationLanguage.ARABIC);

            LanguageParams.lift(context, params);

            assertThat(context.getLanguage()).isEqualTo(ConversationLanguage.ARABIC);
        }

        @Test
        @DisplayName("a silent turn leaves a previously resolved language in place")
        void silentTurnKeepsLanguage() {
            // This no-op is what makes a resumed run keep the language it was
            // started in when the caller says nothing this turn.
            ExecutionContext context = freshContext();
            context.setLanguage(ConversationLanguage.ARABIC);

            LanguageParams.lift(context, new HashMap<>());

            assertThat(context.getLanguage()).isEqualTo(ConversationLanguage.ARABIC);
        }

        @Test
        @DisplayName("an unparseable tag strips the key without changing the language")
        void unparseableTag() {
            ExecutionContext context = freshContext();
            context.setLanguage(ConversationLanguage.ENGLISH);
            Map<String, Object> params = new HashMap<>();
            params.put(LanguageParams.PARAM_LANGUAGE, "klingon");

            LanguageParams.lift(context, params);

            assertThat(context.getLanguage()).isEqualTo(ConversationLanguage.ENGLISH);
            assertThat(params).doesNotContainKey(LanguageParams.PARAM_LANGUAGE);
        }

        @Test
        @DisplayName("inherit hands the parent's resolved language to a child run")
        void inheritForChildRun() {
            ExecutionContext parent = freshContext();
            parent.setLanguage(ConversationLanguage.ARABIC);
            Map<String, Object> childParams = new HashMap<>();

            LanguageParams.inherit(childParams, parent);

            assertThat(childParams).containsEntry(LanguageParams.PARAM_LANGUAGE, "ar");
        }

        @Test
        @DisplayName("resolvedLanguage never returns null")
        void resolvedLanguageDefault() {
            assertThat(freshContext().resolvedLanguage()).isEqualTo(ConversationLanguage.DEFAULT);
        }
    }
}
