package com.itways.assistant.journey.engine.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VariablePath is the single traversal implementation for every journey
 * variable reference — placeholders, SpEL fallbacks and step bindings all end
 * here. A regression in parsing or descent silently breaks variable resolution
 * platform-wide, so each supported syntax from the class contract is pinned.
 */
@DisplayName("VariablePath")
class VariablePathTest {

    private final Map<String, Object> root = buildRoot();

    private static Map<String, Object> buildRoot() {
        Map<String, Object> root = new HashMap<>();
        root.put("inputs", Map.of(
                "text", "hello",
                "entities", new HashMap<>(Map.of("email", "a@b.c"))));
        // `steps` is keyed by String; `stepResults` by Integer. Both shapes exist
        // in a live ExecutionContext and both must traverse with the same path.
        root.put("steps", Map.of("3", Map.of("output", Map.of(
                "email", "x@y.z",
                "items", List.of(Map.of("name", "first"), Map.of("name", "second")),
                "array", new Object[] { "a0", "a1" },
                "odd-key", "odd"))));
        Map<Integer, Object> intKeyed = new HashMap<>();
        intKeyed.put(7, "seven");
        root.put("byInt", intKeyed);
        Map<String, Object> nullable = new HashMap<>();
        nullable.put("present", null);
        root.put("nullable", nullable);
        return root;
    }

    @Nested
    @DisplayName("resolution")
    class Resolve {

        @Test
        @DisplayName("resolves a plain dotted path")
        void plainDottedPath() {
            assertThat(VariablePath.resolve(root, "inputs.text")).isEqualTo("hello");
        }

        @Test
        @DisplayName("descends numeric map keys written as dotted segments")
        void numericSegmentIntoMap() {
            assertThat(VariablePath.resolve(root, "steps.3.output.email")).isEqualTo("x@y.z");
        }

        @Test
        @DisplayName("matches Integer-keyed maps by rendered key, as stepResults requires")
        void integerKeyedMap() {
            // stepResults is Map<Integer, Object>; the path arrives as a string.
            assertThat(VariablePath.resolve(root, "byInt.7")).isEqualTo("seven");
        }

        @Test
        @DisplayName("indexes lists with bracket syntax")
        void listBracketIndex() {
            assertThat(VariablePath.resolve(root, "steps.3.output.items[0].name")).isEqualTo("first");
        }

        @Test
        @DisplayName("indexes lists with a bare numeric segment, equivalent to brackets")
        void listBareNumericSegment() {
            assertThat(VariablePath.resolve(root, "steps.3.output.items.1.name")).isEqualTo("second");
        }

        @Test
        @DisplayName("indexes Object[] arrays like lists")
        void arrayIndex() {
            assertThat(VariablePath.resolve(root, "steps.3.output.array[1]")).isEqualTo("a1");
        }

        @Test
        @DisplayName("reaches keys containing dashes through bracket-quoted segments")
        void bracketQuotedKey() {
            assertThat(VariablePath.resolve(root, "steps.3.output['odd-key']")).isEqualTo("odd");
            assertThat(VariablePath.resolve(root, "steps.3.output[\"odd-key\"]")).isEqualTo("odd");
        }

        @Test
        @DisplayName("returns null for an out-of-bounds or non-numeric list index")
        void badListIndexes() {
            assertThat(VariablePath.resolve(root, "steps.3.output.items[9].name")).isNull();
            assertThat(VariablePath.resolve(root, "steps.3.output.items[-1]")).isNull();
            assertThat(VariablePath.resolve(root, "steps.3.output.items[x]")).isNull();
        }

        @Test
        @DisplayName("returns null when descending into a scalar")
        void descendIntoScalar() {
            assertThat(VariablePath.resolve(root, "inputs.text.length")).isNull();
        }
    }

    @Nested
    @DisplayName("found vs null distinction")
    class FoundVsNull {

        @Test
        @DisplayName("a key holding null is found — a legitimate empty value, not a missing path")
        void presentButNull() {
            // This distinction is what separates "unresolved variable" diagnostics
            // from ordinary empty values in step output.
            VariablePath.Resolution resolution = VariablePath.lookup(root, "nullable.present");
            assertThat(resolution.found()).isTrue();
            assertThat(resolution.value()).isNull();
        }

        @Test
        @DisplayName("an absent key is MISSING")
        void absentKey() {
            VariablePath.Resolution resolution = VariablePath.lookup(root, "nullable.absent");
            assertThat(resolution.found()).isFalse();
            assertThat(resolution.value()).isNull();
        }

        @Test
        @DisplayName("null, blank, and malformed paths are MISSING rather than an exception")
        void degeneratePaths() {
            assertThat(VariablePath.lookup(root, null).found()).isFalse();
            assertThat(VariablePath.lookup(root, "   ").found()).isFalse();
            // Unclosed bracket parses to no segments at all.
            assertThat(VariablePath.lookup(root, "steps.3.output[unclosed").found()).isFalse();
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("splits dots and brackets into the same segment stream")
        void mixedSyntax() {
            assertThat(VariablePath.parse("a.b[0]['c.d'].e"))
                    .containsExactly("a", "b", "0", "c.d", "e");
        }

        @Test
        @DisplayName("trims whitespace inside segments and brackets")
        void whitespaceTrimming() {
            // No trailing space after the final ']': characters after a closing
            // bracket flush as an extra (empty) segment. Harmless in practice —
            // the placeholder regex trims before parse ever sees the path.
            assertThat(VariablePath.parse(" a . b [ 'k' ]")).containsExactly("a", "b", "k");
        }

        @Test
        @DisplayName("a bracket pair with nothing inside invalidates the whole path")
        void emptyBrackets() {
            assertThat(VariablePath.parse("a[]")).isEmpty();
        }

        @Test
        @DisplayName("a quoted segment keeps a ] inside the quotes")
        void quotedClosingBracket() {
            assertThat(VariablePath.parse("a[']']")).containsExactly("a", "]");
        }
    }
}
