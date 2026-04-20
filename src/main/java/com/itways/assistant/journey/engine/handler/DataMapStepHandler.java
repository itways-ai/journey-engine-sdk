package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.itways.assistant.journey.engine.service.AiConfigProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.config.TemplateRender;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.DataMapped;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DataMapStepHandler implements StepHandler {
	private static final String DEFAULT_FILL_SYSTEM_PROMPT = "You are a data mapping AI. Your goal is to fill a JSON template using information from the user's input. Return ONLY valid JSON, starting with { and ending with }. Do not add any conversational text before or after.";
	// private static final String DEFAULT_FILL_SYSTEM_PROMPT = "You are a data
	// mapping AI. Your goal is to fill a JSON template using information from the
	// user's input (text and files). Follow the instructions strictly and return
	// ONLY the resulting JSON.";

	private final AiService aiService;
	private final ObjectMapper objectMapper;
	private final EngineUtils engineUtils;
	private final TemplateRender templateRender;
//	private final AiRequestConfig aiRequestConfig;
	private final AiConfigProvider aiConfigProvider;


	@Override
	public String getType() {
		return "DATA_MAP";
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		try {
			// Extract text from context (usually it's stored in 'text' variable)
			String text = (String) context.getVariable("text");
			String action = step.getActionTarget();

			// Resolve placeholders in action target if any
			action = engineUtils.replacePlaceholders(action, context.getVariables());

			Map<String, Object> model = new HashMap<>();
			model.put("userText", text);
			model.put("executionContext", objectMapper.writeValueAsString(context.getVariables()));
			model.put("jsonTemplate", action);
			model.put("instructions", step.getRequiredParams());

			String userPrompt = templateRender.renderFromString(DataMapped.PROMPT, model);

			@SuppressWarnings("unchecked")
			List<com.itways.assistant.ai.dto.AiWrappedFile> files = (List<com.itways.assistant.ai.dto.AiWrappedFile>) context
					.getVariable("files");

			// get the configuration from the provider
			AiRequestConfig aiRequestConfig = aiConfigProvider.getConfig(context.getAccountId());

			AiChatRequest chatRequest = AiChatRequest.builder()
					.messages(List.of(AiMessage.system(DEFAULT_FILL_SYSTEM_PROMPT), AiMessage.user(userPrompt)))
					.files(files)
					.config(aiRequestConfig).build();

			AiResponse nlpResult = aiService.chat(chatRequest);

			// Strip markdown code blocks if present (AI often returns ```json ... ```)
			String cleanedContent = stripMarkdownCodeBlocks(nlpResult.getContent());

			Object mappedData = objectMapper.readValue(cleanedContent, Object.class);

			if (mappedData instanceof Map) {
				mappedData = unflattenMap((Map<String, Object>) mappedData);
			}

			context.addStepResult(step.getStepOrder(), mappedData);
			context.setVariable("input", mappedData);
			if (step.getStepName() != null && !step.getStepName().isEmpty()) {
				context.setVariable(engineUtils.sanitizeKey(step.getStepName()), mappedData);
			}

			return StepResult.success(mappedData, step.getMessage());
		} catch (Exception e) {
			return StepResult.error("Data Mapping Failed: " + e.getMessage());
		}
	}

	/**
	 * Strips markdown code block formatting from AI responses.
	 * Handles formats like: ```json {...} ```, ``` {...} ```, or plain JSON
	 */
	private String stripMarkdownCodeBlocks(String content) {
		if (content == null) {
			return content;
		}

		// Trim whitespace
		String trimmed = content.trim();

		// Check if wrapped in code blocks (```json or ``` at start)
		// If it has markdown blocks, try to extract just the json part
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
			// Extract just the part between { and } if there is conversational text
			int startInd = trimmed.indexOf('{');
			int endInd = trimmed.lastIndexOf('}');
			if (startInd >= 0 && endInd > startInd) {
				trimmed = trimmed.substring(startInd, endInd + 1);
			}
		}

		return trimmed;
	}

	private Map<String, Object> unflattenMap(Map<String, Object> source) {
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
