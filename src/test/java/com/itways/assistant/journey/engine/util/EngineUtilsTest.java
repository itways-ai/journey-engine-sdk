package com.itways.assistant.journey.engine.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineUtilsTest {

    private EngineUtils engineUtils;
    private Map<String, Object> context;

    @BeforeEach
    void setUp() {
        engineUtils = new EngineUtils();
        context = new HashMap<>();
        context.put("inputs", Map.of("text", "hello"));
        context.put("steps", Map.of("1", Map.of("output", Map.of("email", "test@example.com"))));
        context.put("channel", Map.of("user", Map.of("displayName", "Yazan")));
    }

    @Test
    void resolveValue_nestedPath() {
        assertEquals("hello", engineUtils.resolveValue("inputs.text", context));
        assertEquals("test@example.com", engineUtils.resolveValue("steps.1.output.email", context));
        assertEquals("Yazan", engineUtils.resolveValue("channel.user.displayName", context));
    }

    @Test
    void replacePlaceholders_dotPathOnly() {
        String result = engineUtils.replacePlaceholders(
                "Email: {{steps.1.output.email}}, User: {{channel.user.displayName}}", context);
        assertEquals("Email: test@example.com, User: Yazan", result);
    }

    @Test
    void replacePlaceholders_missingResolvesEmpty() {
        String result = engineUtils.replacePlaceholders("{{steps.99.output}}", context);
        assertEquals("", result);
    }

    @Test
    void evaluateCondition_spelOnNestedMap() {
        assertTrue(engineUtils.evaluateCondition("channel.user.displayName == 'Yazan'", context));
    }
}
