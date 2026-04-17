package com.itways.assistant.journey.engine.util;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EngineUtils {

    private final ExpressionParser parser = new SpelExpressionParser();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}|\\{([a-zA-Z0-9_\\.]+)\\}");

    public Object evaluateExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        try {
            String clean = expression.trim();
            if (clean.startsWith("{{") && clean.endsWith("}}")) {
                clean = clean.substring(2, clean.length() - 2);
            } else if (clean.startsWith("{") && clean.endsWith("}")) {
                clean = clean.substring(1, clean.length() - 1);
            }
            StandardEvaluationContext evalContext = new StandardEvaluationContext(context);
            evalContext.addPropertyAccessor(new org.springframework.context.expression.MapAccessor());
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
        if (text == null || !text.contains("{")) {
            return text;
        }
        String result = text;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Object val = resolveValue(key, context);
            String strVal = (val != null) ? val.toString() : "null";
            result = result.replace(fullMatch, strVal);
        }
        return result;
    }

    public Object resolveValue(String path, Map<String, Object> context) {
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
}
