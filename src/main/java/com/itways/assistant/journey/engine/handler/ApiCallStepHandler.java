package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiCallStepHandler implements StepHandler {

	private final EngineUtils engineUtils;
	private final VariableContext variableContext;
	private final StepOutputSchemaHelper schemaHelper;

	private static final RestTemplate restTemplate = new RestTemplate(
			new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));

	@Override
	public String getType() {
		return "API_CALL";
	}

	@Override
	public StepDefinition describe() {
		return schemaHelper.apiCallDefinition();
	}

	@Override
	public StepOutputSchema describeOutputs(JourneyStep step) {
		return schemaHelper.apiCallSchema(step);
	}

	@Override
	public StepResult execute(JourneyStep step, ExecutionContext context) {
		try {
			String url = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
			ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			config.getHeaders()
					.forEach((k, v) -> headers.set(k, engineUtils.replacePlaceholders(v, context.getVariables())));

			Object processedBody = processBody(config.getBody(), context.getVariables());

			log.info("---> API_CALL Step: '{}'", step.getStepName());
			log.info("Request URL    : {} {}", config.getMethod(), url);

			HttpEntity<Object> entity = new HttpEntity<>(processedBody, headers);
			ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.valueOf(config.getMethod()), entity,
					Object.class);

			Object apiResult = response.getBody();
			log.info("<--- API_CALL Step: '{}' status={}", step.getStepName(), response.getStatusCode());

		variableContext.storeOutput(context, step, apiResult);
		variableContext.writeStepField(context, step, "status", response.getStatusCode().value());
		variableContext.writeStepField(context, step, "headers", response.getHeaders().toSingleValueMap());

			return StepResult.success(apiResult, step.getMessage());
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			log.error("API_CALL step '{}' status={} error={}", step.getStepName(), e.getStatusCode(),
					e.getResponseBodyAsString());
			String bodyMsg = e.getResponseBodyAsString();
			return StepResult.error("API Call Failed [" + e.getStatusCode() + "]: " +
					(bodyMsg != null && !bodyMsg.isBlank() ? bodyMsg : e.getMessage()));
		} catch (Exception e) {
		 log.error("API_CALL step '{}' unexpected error", step.getStepName(), e);
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

}
