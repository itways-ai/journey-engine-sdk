package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EngineUtils is the expression surface CONDITION and SWITCH steps evaluate
 * through. The SpEL-then-VariablePath fallback exists because SpEL cannot parse
 * numeric path segments like {@code steps.1.output}; losing the fallback breaks
 * every branch that references a step output.
 */
@DisplayName("EngineUtils")
class EngineUtilsTest {

    private final EngineUtils engineUtils = new EngineUtils(new ObjectMapper());

    private final Map<String, Object> context = buildContext();

    private static Map<String, Object> buildContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("state", new HashMap<>(Map.of("count", 3, "name", "Amal")));
        context.put("steps", Map.of("1", Map.of("output", Map.of("score", 8))));
        return context;
    }

    @AfterEach
    void cleanDiagnostics() {
        VariableDiagnostics.reset();
    }

    @Nested
    @DisplayName("evaluateExpression")
    class Expressions {

        @Test
        @DisplayName("evaluates real SpEL over the variable map")
        void spelExpression() {
            assertThat(engineUtils.evaluateExpression("state.count > 2", context)).isEqualTo(true);
            assertThat(engineUtils.evaluateExpression("state.name == 'Amal'", context)).isEqualTo(true);
        }

        @Test
        @DisplayName("strips a surrounding {{ }} before evaluating")
        void placeholderWrappedExpression() {
            assertThat(engineUtils.evaluateExpression("{{ state.count > 2 }}", context)).isEqualTo(true);
        }

        @Test
        @DisplayName("falls back to path resolution for numeric segments SpEL cannot parse")
        void numericSegmentFallback() {
            assertThat(engineUtils.evaluateExpression("steps.1.output.score", context)).isEqualTo(8);
        }

        @Test
        @DisplayName("an expression that resolves nowhere yields null and reports its identifiers")
        void unresolvedExpressionReported() {
            VariableDiagnostics.open();
            assertThat(engineUtils.evaluateExpression("steps.9.output.missing", context)).isNull();
            List<String> unresolved = VariableDiagnostics.close();
            assertThat(unresolved).contains("steps.9.output.missing");
        }

        @Test
        @DisplayName("string literals inside a failing expression are not mistaken for paths")
        void literalsNotReported() {
            VariableDiagnostics.open();
            engineUtils.evaluateExpression("steps.9.output == 'steps.everywhere'", context);
            assertThat(VariableDiagnostics.close()).doesNotContain("steps.everywhere");
        }

        @Test
        @DisplayName("null and empty expressions evaluate to null")
        void degenerateInput() {
            assertThat(engineUtils.evaluateExpression(null, context)).isNull();
            assertThat(engineUtils.evaluateExpression("", context)).isNull();
        }

        @Test
        @DisplayName("instance methods on values still work — conditions rely on contains/startsWith")
        void instanceMethodsAllowed() {
            assertThat(engineUtils.evaluateExpression("state.name.contains('Am')", context)).isEqualTo(true);
            assertThat(engineUtils.evaluateExpression("state.name.startsWith('X')", context)).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("expression sandbox")
    class Sandbox {

        // Condition expressions are author-supplied text. The Standard SpEL
        // context exposed T(), constructors and bean references — a straight
        // line from "can edit a journey" to Runtime.exec. These pin the seal.

        @Test
        @DisplayName("T() type references cannot reach Java classes")
        void typeReferencesBlocked() {
            assertThat(engineUtils.evaluateExpression(
                    "T(java.lang.Runtime).getRuntime()", context)).isNull();
        }

        @Test
        @DisplayName("constructors cannot be invoked")
        void constructorsBlocked() {
            assertThat(engineUtils.evaluateExpression(
                    "new java.lang.ProcessBuilder('cmd').start()", context)).isNull();
        }

        @Test
        @DisplayName("static methods cannot be invoked through a Class reference")
        void staticMethodsBlocked() {
            assertThat(engineUtils.evaluateExpression(
                    "''.getClass().forName('java.lang.Runtime')", context)).isNull();
        }
    }

    @Nested
    @DisplayName("evaluateCondition")
    class Conditions {

        @Test
        @DisplayName("only Boolean TRUE counts as true — truthy strings and numbers do not")
        void strictBooleanSemantics() {
            assertThat(engineUtils.evaluateCondition("state.count > 2", context)).isTrue();
            assertThat(engineUtils.evaluateCondition("state.count", context)).isFalse();
            assertThat(engineUtils.evaluateCondition("state.name", context)).isFalse();
            assertThat(engineUtils.evaluateCondition("nowhere.at.all", context)).isFalse();
        }
    }

    @Nested
    @DisplayName("parseApiConfig")
    class ApiConfigParsing {

        @Test
        @DisplayName("parses well-formed config JSON")
        void wellFormed() {
            ApiConfig config = engineUtils.parseApiConfig(
                    "{\"method\":\"POST\",\"variable\":\"count\",\"operation\":\"INCREMENT\"}");
            assertThat(config.getMethod()).isEqualTo("POST");
            assertThat(config.getVariable()).isEqualTo("count");
            assertThat(config.getOperation()).isEqualTo("INCREMENT");
        }

        @Test
        @DisplayName("malformed, empty, and null JSON all fall back to an empty config, never an exception")
        void malformedFallsBack() {
            // Handlers call this on author-supplied JSON at run time; throwing
            // would turn a config typo into a crashed run instead of a step error.
            assertThat(engineUtils.parseApiConfig("{not json").getMethod()).isEqualTo("GET");
            assertThat(engineUtils.parseApiConfig("").getMethod()).isEqualTo("GET");
            assertThat(engineUtils.parseApiConfig(null).getMethod()).isEqualTo("GET");
        }
    }

    @Test
    @DisplayName("resolveSourceValue keeps the original type for a lone placeholder")
    void sourceValueTypePreserved() {
        assertThat(engineUtils.resolveSourceValue("{{steps.1.output.score}}", context))
                .isInstanceOf(Integer.class).isEqualTo(8);
        assertThat(engineUtils.resolveSourceValue("score {{steps.1.output.score}}", context))
                .isEqualTo("score 8");
    }
}
