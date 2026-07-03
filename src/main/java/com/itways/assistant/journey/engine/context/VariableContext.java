package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class VariableContext {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "text", "files", "entities", "answer", "forceIntent",
            "channel", "channelId", "channelType", "channelUser", "userInputAnswer");

    public void ensureStructure(ExecutionContext context) {
        Map<String, Object> vars = context.getVariables();
        if (vars == null) {
            vars = new HashMap<>();
            context.setVariables(vars);
        }
        vars.putIfAbsent("inputs", new HashMap<String, Object>());
        vars.putIfAbsent("steps", new HashMap<String, Object>());
        vars.putIfAbsent("state", new HashMap<String, Object>());
        vars.putIfAbsent("runtime", new HashMap<String, Object>());
        vars.putIfAbsent("channel", new HashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    public void mergeInputs(ExecutionContext context, Map<String, Object> flatParams) {
        ensureStructure(context);
        if (flatParams == null || flatParams.isEmpty()) {
            return;
        }

        Map<String, Object> inputs = (Map<String, Object>) context.getVariables().get("inputs");

        if (flatParams.containsKey("text")) {
            inputs.put("text", flatParams.get("text"));
        }
        if (flatParams.containsKey("files")) {
            inputs.put("files", flatParams.get("files"));
        }
        if (flatParams.containsKey("answer")) {
            inputs.put("answer", flatParams.get("answer"));
        }
        if (flatParams.containsKey("userInputAnswer")) {
            inputs.put("answer", flatParams.get("userInputAnswer"));
        }
        if (flatParams.containsKey("entities")) {
            mergeEntities(inputs, flatParams.get("entities"));
        }
        if (flatParams.containsKey("channel") && flatParams.get("channel") instanceof Map) {
            mergeChannel(context, (Map<String, Object>) flatParams.get("channel"));
        }
        normalizeLegacyChannel(context, flatParams);

        Map<String, Object> entities = getOrCreateEntities(inputs);
        for (Map.Entry<String, Object> entry : flatParams.entrySet()) {
            String key = entry.getKey();
            if (!RESERVED_KEYS.contains(key)) {
                entities.put(key, entry.getValue());
            }
        }

        if (flatParams.containsKey("forceIntent")) {
            context.getVariables().put("forceIntent", flatParams.get("forceIntent"));
        }
    }

    @SuppressWarnings("unchecked")
    public void mergeChannel(ExecutionContext context, Map<String, Object> channelData) {
        ensureStructure(context);
        if (channelData == null || channelData.isEmpty()) {
            return;
        }
        Map<String, Object> channel = (Map<String, Object>) context.getVariables().get("channel");
        channel.putAll(channelData);
    }

    @SuppressWarnings("unchecked")
    private void normalizeLegacyChannel(ExecutionContext context, Map<String, Object> flatParams) {
        if (flatParams.containsKey("channelId") || flatParams.containsKey("channelType")
                || flatParams.containsKey("channelUser")) {
            Map<String, Object> channel = new HashMap<>();
            if (flatParams.containsKey("channelId")) {
                channel.put("id", String.valueOf(flatParams.get("channelId")));
            }
            if (flatParams.containsKey("channelType")) {
                channel.put("type", flatParams.get("channelType"));
            }
            if (flatParams.get("channelUser") instanceof Map) {
                channel.put("user", flatParams.get("channelUser"));
            }
            mergeChannel(context, channel);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeEntities(Map<String, Object> inputs, Object entitiesObj) {
        Map<String, Object> entities = getOrCreateEntities(inputs);
        if (entitiesObj instanceof Map) {
            entities.putAll((Map<String, Object>) entitiesObj);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateEntities(Map<String, Object> inputs) {
        Object existing = inputs.get("entities");
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> entities = new HashMap<>();
        inputs.put("entities", entities);
        return entities;
    }

    @SuppressWarnings("unchecked")
    public void writeStepOutput(ExecutionContext context, JourneyStep step, Object output) {
        writeStepField(context, step, "output", output);
    }

    public void storeOutput(ExecutionContext context, JourneyStep step, Object output) {
        writeStepOutput(context, step, output);
        context.addStepResult(step.getStepOrder(), output);
    }

    @SuppressWarnings("unchecked")
    public void writeStepField(ExecutionContext context, JourneyStep step, String field, Object value) {
        ensureStructure(context);
        Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
        String orderKey = String.valueOf(step.getStepOrder());
        Map<String, Object> stepBucket = (Map<String, Object>) steps.computeIfAbsent(orderKey, k -> new HashMap<>());
        stepBucket.put(field, value);
    }

    @SuppressWarnings("unchecked")
    public void writeState(ExecutionContext context, String key, Object value) {
        ensureStructure(context);
        Map<String, Object> state = (Map<String, Object>) context.getVariables().get("state");
        state.put(key, value);
    }

    public Object read(ExecutionContext context, String dotPath) {
        if (dotPath == null || dotPath.isEmpty()) {
            return null;
        }
        String[] parts = dotPath.split("\\.");
        Object current = context.getVariables();
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public String resolveForTemplate(ExecutionContext context, String text) {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{([a-zA-Z0-9_\\.]+)\\}\\}");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object val = read(context, matcher.group(1));
            String replacement = val != null ? val.toString() : "";
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public void clearStepOutputsFromOrder(ExecutionContext context, int fromOrder) {
        ensureStructure(context);
        Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
        steps.keySet().removeIf(k -> {
            try {
                return Integer.parseInt(k) >= fromOrder;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        context.getStepResults().keySet().removeIf(order -> order >= fromOrder);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getInputs(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("inputs");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getChannel(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("channel");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("state");
    }
}
