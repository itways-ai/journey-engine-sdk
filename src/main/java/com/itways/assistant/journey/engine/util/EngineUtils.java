package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class EngineUtils {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}|\\{([a-zA-Z0-9_\\.]+)\\}|<%([a-zA-Z0-9_\\.]+)%>");
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}$");

    /**
     * Resolves a source template preserving the original type when it is a single
     * placeholder (e.g. {@code {{lastStep}}} → Integer 10). Mixed templates still
     * use string substitution via {@link #replacePlaceholders}.
     */
    public Object resolveSourceValue(String source, Map<String, Object> context) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher single = SINGLE_PLACEHOLDER.matcher(source.trim());
        if (single.matches()) {
            return resolveValue(single.group(1), context);
        }
        return replacePlaceholders(source, context);
    }

    public Object evaluateExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        try {
            String clean = expression.trim();
            if (clean.startsWith("{{") && clean.endsWith("}}")) {
                clean = clean.substring(2, clean.length() - 2);
            }
            StandardEvaluationContext evalContext = new StandardEvaluationContext(context);
            evalContext.addPropertyAccessor(new MapAccessor());
            return parser.parseExpression(clean).getValue(evalContext);
        } catch (Exception e) {
            return resolveValue(expression, context);
        }
    }

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        Object res = evaluateExpression(expression, context);
        return Boolean.TRUE.equals(res);
    }

    public String replacePlaceholders(String text, Map<String, Object> context) {
        if (text == null) {
            return null;
        }
        if (!text.contains("{{") && !text.contains("{") && !text.contains("<%")) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1)
                    : (matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            Object val = resolveValue(key, context);
            String strVal = val != null ? val.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(strVal));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public Object resolveValue(String path, Map<String, Object> context) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public String sanitizeKey(String name) {
        return name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9]", "");
    }

    public ApiConfig parseApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) return new ApiConfig();
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
