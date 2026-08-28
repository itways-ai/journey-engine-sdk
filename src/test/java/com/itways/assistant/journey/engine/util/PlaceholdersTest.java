package com.itways.assistant.journey.engine.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code {{ path }}} is the only placeholder syntax journey authors have; this
 * class is what turns authored step text into live values. The type-preserving
 * lone-placeholder rule and the found-vs-null reporting contract are both relied
 * on by handlers (STATE_STORE stores 8, not "8") and by unresolved-variable
 * diagnostics, so both are pinned here.
 */
@DisplayName("Placeholders")
class PlaceholdersTest {

    private final Map<String, Object> root = buildRoot();

    private static Map<String, Object> buildRoot() {
        Map<String, Object> root = new HashMap<>();
        root.put("steps", Map.of("2", Map.of("output", Map.of("score", 8))));
        root.put("inputs", Map.of("text", "hi"));
        Map<String, Object> nullable = new HashMap<>();
        nullable.put("present", null);
        root.put("state", nullable);
        return root;
    }

    @AfterEach
    void closeAnyLeakedFrames() {
        // Diagnostics are a thread-local stack; a leaked frame would silently
        // couple this class to whichever test runs next on the same thread.
        VariableDiagnostics.reset();
    }

    @Nested
    @DisplayName("replace")
    class Replace {

        @Test
        @DisplayName("substitutes every placeholder inside mixed text")
        void mixedText() {
            assertThat(Placeholders.replace("score={{steps.2.output.score}} for {{ inputs.text }}", root))
                    .isEqualTo("score=8 for hi");
        }

        @Test
        @DisplayName("renders an absent path as an empty string")
        void absentPath() {
            assertThat(Placeholders.replace("[{{nowhere.at.all}}]", root)).isEqualTo("[]");
        }

        @Test
        @DisplayName("leaves text without placeholders untouched, including null")
        void noPlaceholders() {
            assertThat(Placeholders.replace("plain text", root)).isEqualTo("plain text");
            assertThat(Placeholders.replace(null, root)).isNull();
        }

        @Test
        @DisplayName("a replacement value containing $ or \\ is inserted literally")
        void regexSpecialCharactersInValue() {
            // Matcher.appendReplacement treats $ as a group reference unless quoted;
            // a payment amount like "$5" must survive substitution verbatim.
            Map<String, Object> money = Map.of("state", Map.of("price", "$5\\kg"));
            assertThat(Placeholders.replace("cost: {{state.price}}", money)).isEqualTo("cost: $5\\kg");
        }

        @Test
        @DisplayName("reports absent paths to an open diagnostics frame, but not present-null ones")
        void diagnosticsReporting() {
            VariableDiagnostics.open();
            Placeholders.replace("{{state.present}} and {{state.absent}}", root);
            List<String> unresolved = VariableDiagnostics.close();
            // `state.present` exists and holds null — a legitimate empty value.
            assertThat(unresolved).containsExactly("state.absent");
        }
    }

    @Nested
    @DisplayName("resolve (type preservation)")
    class ResolveTyped {

        @Test
        @DisplayName("a lone placeholder returns the raw value with its original type")
        void lonePlaceholderKeepsType() {
            assertThat(Placeholders.resolve("{{steps.2.output.score}}", root))
                    .isInstanceOf(Integer.class)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("surrounding whitespace still counts as a lone placeholder")
        void lonePlaceholderTrimmed() {
            assertThat(Placeholders.resolve("  {{steps.2.output.score}}  ", root)).isEqualTo(8);
        }

        @Test
        @DisplayName("mixed templates stringify")
        void mixedTemplateStringifies() {
            assertThat(Placeholders.resolve("score: {{steps.2.output.score}}", root))
                    .isEqualTo("score: 8");
        }

        @Test
        @DisplayName("a lone placeholder over an absent path yields null and a diagnostic")
        void loneAbsentPath() {
            VariableDiagnostics.open();
            assertThat(Placeholders.resolve("{{missing.path}}", root)).isNull();
            assertThat(VariableDiagnostics.close()).containsExactly("missing.path");
        }
    }

    @Nested
    @DisplayName("referenced and unresolved paths")
    class PathListing {

        @Test
        @DisplayName("lists each distinct path once, in order of appearance")
        void referencedPathsDeduplicated() {
            assertThat(Placeholders.referencedPaths("{{b}} {{a}} {{b}}"))
                    .containsExactly("b", "a");
        }

        @Test
        @DisplayName("unresolvedPaths returns only the paths absent from the root")
        void unresolvedOnly() {
            assertThat(Placeholders.unresolvedPaths("{{inputs.text}} {{gone}}", root))
                    .containsExactly("gone");
        }

        @Test
        @DisplayName("text without placeholders references nothing")
        void nothingReferenced() {
            assertThat(Placeholders.referencedPaths("no vars here")).isEmpty();
            assertThat(Placeholders.contains("no vars here")).isFalse();
        }
    }
}
