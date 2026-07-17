package com.itways.assistant.journey.engine.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.dto.AiWrappedFile;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.config.TemplateRender;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.util.DataMapped;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFieldMappingService {

    private static final String DEFAULT_FILL_SYSTEM_PROMPT =
            "You are a data mapping AI. Your goal is to fill a JSON template using information from the user's input. "
                    + "Return ONLY valid JSON, starting with { and ending with }. Do not add any conversational text before or after.";

    private final AiService aiService;
    private final ObjectMapper objectMapper;
    private final TemplateRender templateRender;
    private final AiConfigProvider aiConfigProvider;

    /**
     * Maps free-text user input to field keys using the DataMapped prompt.
     * Returns an empty map on failure (never throws).
     */
    public Map<String, Object> extractFields(String userText, String jsonTemplate, String instructions,
            ExecutionContext context) {
        try {
            return extractFieldsStrict(userText, jsonTemplate, instructions, context);
        } catch (Exception e) {
            log.warn("AI field extraction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> extractFieldsStrict(String userText, String jsonTemplate, String instructions,
            ExecutionContext context) throws Exception {
        Map<String, Object> model = new HashMap<>();
        model.put("userText", userText != null ? userText : "");
        model.put("executionContext", objectMapper.writeValueAsString(context.getVariables()));
        model.put("jsonTemplate", jsonTemplate);
        model.put("instructions", instructions != null ? instructions : "");

        String userPrompt = templateRender.renderFromString(DataMapped.PROMPT, model);

        @SuppressWarnings("unchecked")
        List<AiWrappedFile> files = (List<AiWrappedFile>) context.getVariable("files");

        AiRequestConfig aiRequestConfig = aiConfigProvider.getConfig(context.getAccountId());

        AiChatRequest chatRequest = AiChatRequest.builder()
                .messages(List.of(
                        AiMessage.system(DEFAULT_FILL_SYSTEM_PROMPT),
                        AiMessage.user(userPrompt)))
                .files(files)
                .config(aiRequestConfig)
                .build();

        AiResponse nlpResult = aiService.chat(chatRequest);
        String cleanedContent = stripMarkdownCodeBlocks(nlpResult.getContent());
        Object mappedData = objectMapper.readValue(cleanedContent, Object.class);

        if (mappedData instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> flat = unflattenMap((Map<String, Object>) rawMap);
            return flat;
        }
        return Map.of();
    }

    String stripMarkdownCodeBlocks(String content) {
        if (content == null) {
            return "{}";
        }

        String trimmed = content.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastTicks = trimmed.lastIndexOf("```");
            if (lastTicks > 0) {
                trimmed = trimmed.substring(0, lastTicks);
            }
            trimmed = trimmed.trim();
        } else {
            int startInd = trimmed.indexOf('{');
            int endInd = trimmed.lastIndexOf('}');
            if (startInd >= 0 && endInd > startInd) {
                trimmed = trimmed.substring(startInd, endInd + 1);
            }
        }

        return trimmed.isEmpty() ? "{}" : trimmed;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> unflattenMap(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (key.contains(".")) {
                String[] parts = key.split("\\.");
                Map<String, Object> curr = result;
                for (int i = 0; i < parts.length - 1; i++) {
                    curr = (Map<String, Object>) curr.computeIfAbsent(parts[i], k -> new HashMap<String, Object>());
                }
                curr.put(parts[parts.length - 1], val);
            } else {
                result.put(key, val);
            }
        }
        return result;
    }
}
