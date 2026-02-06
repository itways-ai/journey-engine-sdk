package com.itways.assistant.journey.engine.handler;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApiCallStepHandler implements StepHandler {

	private final EngineUtils engineUtils;
	private final RestTemplate restTemplate = new RestTemplate();
	private final ObjectMapper objectMapper;

	@Override
	public String getType() {
		return "API_CALL";
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		try {
			String url = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
			ApiConfig config = loadApiConfig(step.getApiConfig());

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			config.getHeaders()
					.forEach((k, v) -> headers.set(k, engineUtils.replacePlaceholders(v, context.getVariables())));

			HttpEntity<Object> entity = new HttpEntity<>(config.getBody(), headers);
			ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.valueOf(config.getMethod()), entity,
					Object.class);

			Object apiResult = response.getBody();

			context.addStepResult(step.getStepOrder(), apiResult);
			context.setVariable("step" + step.getStepOrder(), apiResult);
			if (step.getStepName() != null && !step.getStepName().isEmpty()) {
				context.setVariable(engineUtils.sanitizeKey(step.getStepName()), apiResult);
			}
			if (apiResult instanceof java.util.Map) {
				context.getVariables().putAll((java.util.Map<String, Object>) apiResult);
			}

			return StepResult.success(apiResult, step.getMessage());
		} catch (Exception e) {
			return StepResult.error("API Call Failed: " + e.getMessage());
		}
	}

	private ApiConfig loadApiConfig(String json) {
		try {
			if (json == null || json.isEmpty())
				return new ApiConfig();
			return objectMapper.readValue(json, ApiConfig.class);
		} catch (Exception e) {
			return new ApiConfig();
		}
	}
}
