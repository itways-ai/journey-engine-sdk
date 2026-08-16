package com.itways.assistant.journey.engine.language;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    @Test
    @DisplayName("plain sentences resolve to their script")
    void detectsByScript() {
        assertThat(detector.detect("Where is my order?")).isEqualTo(ConversationLanguage.ENGLISH);
        assertThat(detector.detect("أين طلبي؟")).isEqualTo(ConversationLanguage.ARABIC);
    }

    @Test
    @DisplayName("an Arabic message keeps its language despite Latin fragments")
    void arabicSurvivesLatinFragments() {
        // The asymmetry the 0.3 threshold exists for: order ids, URLs and brand
        // names ride along in Arabic messages constantly, and a naive majority
        // vote hands these to English.
        assertThat(detector.detect("أين طلبي رقم ORD-99213 من متجر Acme Store؟"))
                .isEqualTo(ConversationLanguage.ARABIC);
        assertThat(detector.detect("مرحبا، هل يمكنني تتبع الشحنة عبر tracking link؟"))
                .isEqualTo(ConversationLanguage.ARABIC);
    }

    @Test
    @DisplayName("messages with no script signal express no opinion")
    void returnsNullWithoutSignal() {
        // Each of these is a real mid-journey answer. Returning ENGLISH for any of
        // them is how an Arabic conversation used to switch language on turn three.
        assertThat(detector.detect("123456")).isNull();
        assertThat(detector.detect("+962790000000")).isNull();
        assertThat(detector.detect("!!!")).isNull();
        assertThat(detector.detect("🙂")).isNull();
        assertThat(detector.detect("")).isNull();
        assertThat(detector.detect(null)).isNull();
    }

    @Test
    @DisplayName("short answers do not carry enough signal to change the language")
    void shortAnswersHaveNoSignal() {
        assertThat(detector.hasEnoughSignal("ok")).isFalse();
        assertThat(detector.hasEnoughSignal("y")).isFalse();
        assertThat(detector.hasEnoughSignal("3")).isFalse();

        assertThat(detector.hasEnoughSignal("yes")).isTrue();
        assertThat(detector.hasEnoughSignal("نعم")).isTrue();
    }

    @Test
    @DisplayName("an unsupported script expresses no opinion rather than guessing")
    void unsupportedScriptIsNotForced() {
        // Falls through to the rest of the precedence chain instead of being
        // pushed onto whichever supported language it resembles least.
        assertThat(detector.detect("配送状況を教えてください")).isNull();
    }

    @Test
    @DisplayName("presentation-form Arabic counts as Arabic")
    void handlesSupplementaryAndPresentationForms() {
        assertThat(detector.detect("ﺍﻟﺴﻼﻡ ﻋﻠﻴﻜﻢ ﻭﺭﺣﻤﺔ ﺍﻟﻠﻪ")).isEqualTo(ConversationLanguage.ARABIC);
    }
}
