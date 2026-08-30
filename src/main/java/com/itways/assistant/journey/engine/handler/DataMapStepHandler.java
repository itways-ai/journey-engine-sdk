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
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.DataMapped;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataMapStepHandler implements StepHandler {
	private static final String DEFAULT_FILL_SYSTEM_PROMPT = "You are a data mapping AI. Your goal is to fill a JSON template using information from the user's input. Return ONLY valid JSON, starting with { and ending with }. Do not add any conversational text before or after.";
	private final AiService aiService;
	private final ObjectMapper objectMapper;
	private final EngineUtils engineUtils;
	private final VariableContext variableContext;
	private final StepOutputSchemaHelper schemaHelper;
	private final TemplateRender templateRender;
	private final AiConfigProvider aiConfigProvider;

	@Override
	public String getType() {
		return "DATA_MAP";
	}

	@Override
	public StepDefinition describe() {
		return schemaHelper.dataMapDefinition();
	}

	@Override
	public StepOutputSchema describeOutputs(JourneyStep step) {
		return schemaHelper.dataMapSchema(step);
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		try {
			// Extract text from context (usually it's stored in 'text' variable)
			String text = (String) variableContext.getInputs(context).get("text");
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
			List<com.itways.assistant.ai.dto.AiWrappedFile> files = (List<com.itways.assistant.ai.dto.AiWrappedFile>) variableContext
					.getInputs(context).get("files");

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

			mappedData = repairMissingFields(mappedData, action, userPrompt, files, aiRequestConfig, step);

			variableContext.storeOutput(context, step, mappedData);

			return StepResult.success(mappedData, step.getMessage());
		} catch (Exception e) {
			return StepResult.error("Data Mapping Failed: " + e.getMessage());
		}
	}

	/**
	 * Asks once more for the fields the model left out, then gives up.
	 *
	 * <p>
	 * The author declares a shape — {@code {"briefing": ""}} — and downstream
	 * steps address it by name. When the model answers with prose, an empty
	 * string, or a differently-named key, every {@code {{steps.N.output.briefing}}}
	 * downstream silently resolves to nothing and the journey delivers a blank
	 * message. Two of the demo catalogue's journeys failed exactly this way,
	 * intermittently, with no error anywhere: the step reported SUCCESS.
	 *
	 * <p>
	 * One retry, not a loop. A model that ignores an explicit field list twice
	 * is not going to comply on the third ask, and each attempt costs a call.
	 * Nothing is fabricated to fill a gap that survives the retry — an invented
	 * value would be worse than a visible blank, and the unresolved-variable
	 * diagnostic stays attached to the step so the failure is inspectable.
	 */
	@SuppressWarnings("unchecked")
	private Object repairMissingFields(Object mappedData, String jsonTemplate, String userPrompt,
			List<com.itways.assistant.ai.dto.AiWrappedFile> files, AiRequestConfig aiRequestConfig,
			JourneyStep step) {
		if (!(mappedData instanceof Map)) {
			return mappedData;
		}
		Map<String, Object> mapped = (Map<String, Object>) mappedData;
		List<String> missing = missingDeclaredFields(jsonTemplate, mapped);
		if (missing.isEmpty()) {
			return mappedData;
		}

		log.warn("DATA_MAP step '{}' returned no value for {} — asking once more", step.getStepName(), missing);
		try {
			String repairPrompt = userPrompt
					+ "\n\nYour previous answer left these fields empty or missing: " + String.join(", ", missing)
					+ ".\nReturn the same JSON object again with every one of those fields present and filled in"
					+ " from the information available. Do not return an empty string for them.";

			AiResponse retry = aiService.chat(AiChatRequest.builder()
					.messages(List.of(AiMessage.system(DEFAULT_FILL_SYSTEM_PROMPT), AiMessage.user(repairPrompt)))
					.files(files)
					.config(aiRequestConfig).build());

			Object reparsed = objectMapper.readValue(stripMarkdownCodeBlocks(retry.getContent()), Object.class);
			if (reparsed instanceof Map) {
				Map<String, Object> second = unflattenMap((Map<String, Object>) reparsed);
				// Only the gaps are taken from the retry. Everything the first
				// answer got right stands — a second pass is not a second opinion.
				for (String field : missing) {
					Object value = second.get(field);
					if (!isBlank(value)) {
						mapped.put(field, value);
					}
				}
			}
		} catch (Exception e) {
			log.warn("DATA_MAP step '{}' repair attempt failed: {}", step.getStepName(), e.getMessage());
			return mapped;
		}

		List<String> stillMissing = missingDeclaredFields(jsonTemplate, mapped);
		if (!stillMissing.isEmpty()) {
			log.error("DATA_MAP step '{}' still has no value for {} after a retry; downstream references to them"
					+ " will resolve to nothing", step.getStepName(), stillMissing);
		}
		return mapped;
	}

	/** Top-level keys the author's template declares that the answer did not fill. */
	@SuppressWarnings("unchecked")
	private List<String> missingDeclaredFields(String jsonTemplate, Map<String, Object> mapped) {
		if (jsonTemplate == null || jsonTemplate.isBlank()) {
			return List.of();
		}
		Map<String, Object> declared;
		try {
			Object parsed = objectMapper.readValue(jsonTemplate, Object.class);
			if (!(parsed instanceof Map)) {
				return List.of();
			}
			declared = (Map<String, Object>) parsed;
		} catch (Exception e) {
			// Not a JSON shape (some steps carry free-form instructions instead);
			// there is nothing declared to check against.
			return List.of();
		}
		List<String> missing = new java.util.ArrayList<>();
		for (String field : declared.keySet()) {
			if (isBlank(mapped.get(field))) {
				missing.add(field);
			}
		}
		return missing;
	}

	private static boolean isBlank(Object value) {
		return value == null || (value instanceof String text && text.isBlank());
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
