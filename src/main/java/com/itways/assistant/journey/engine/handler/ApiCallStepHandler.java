package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCallStepHandler implements StepHandler {

	private final EngineUtils engineUtils;
	private final ObjectMapper objectMapper;

	// Shared properly configured RestTemplate (buffered for response re-reads)
	private static final RestTemplate restTemplate = new RestTemplate(
			new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));

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

			Object processedBody = processBody(config.getBody(), context.getVariables());

			// Detailed Logging for debugging
			log.info("---> API_CALL Step: '{}'", step.getStepName());
			log.info("Request URL    : {} {}", config.getMethod(), url);
			log.info("Request Headers: {}", headers);
			if (processedBody != null) {
				try {
					log.info("Request Body   : {}", objectMapper.writeValueAsString(processedBody));
				} catch (Exception e) {
					log.info("Request Body   : {}", processedBody);
				}
			}

			HttpEntity<Object> entity = new HttpEntity<>(processedBody, headers);
			ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.valueOf(config.getMethod()), entity,
					Object.class);

			Object apiResult = response.getBody();
			log.info("<--- API_CALL Step: '{}' status={}", step.getStepName(), response.getStatusCode());
			if (apiResult != null) {
				try {
					log.debug("Response Body  : {}", objectMapper.writeValueAsString(apiResult));
				} catch (Exception e) {
					log.debug("Response Body  : {}", apiResult);
				}
			}

			context.addStepResult(step.getStepOrder(), apiResult);
			context.setVariable("step" + step.getStepOrder(), apiResult);
			if (step.getStepName() != null && !step.getStepName().isEmpty()) {
				context.setVariable(engineUtils.sanitizeKey(step.getStepName()), apiResult);
			}
			if (apiResult instanceof java.util.Map) {
				context.getVariables().putAll((java.util.Map<String, Object>) apiResult);
			}

			return StepResult.success(apiResult, step.getMessage());
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			log.error("❌ API_CALL step '{}' status={} error={}", step.getStepName(), e.getStatusCode(),
					e.getResponseBodyAsString());
			String bodyMsg = e.getResponseBodyAsString();
			return StepResult.error("API Call Failed [" + e.getStatusCode() + "]: " +
					(bodyMsg != null && !bodyMsg.isBlank() ? bodyMsg : e.getMessage()));
		} catch (Exception e) {
			log.error("❌ API_CALL step '{}' unexpected error", step.getStepName(), e);
			return StepResult.error("API Call Failed: " + e.getMessage());
		}
	}

	private Object processBody(Object body, Map<String, Object> variables) {
		if (body instanceof String) {
			return engineUtils.replacePlaceholders((String) body, variables);
		} else if (body instanceof Map) {
			Map<String, Object> bodyMap = (Map<String, Object>) body;
			Map<String, Object> processedMap = new HashMap<>();
			for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
				processedMap.put(entry.getKey(), processBody(entry.getValue(), variables));
			}
			return processedMap;
		} else if (body instanceof List) {
			List<Object> bodyList = (List<Object>) body;
			List<Object> processedList = new ArrayList<>();
			for (Object item : bodyList) {
				processedList.add(processBody(item, variables));
			}
			return processedList;
		}
		return body;
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
