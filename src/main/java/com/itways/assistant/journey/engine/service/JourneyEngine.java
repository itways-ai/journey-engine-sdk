package com.itways.assistant.journey.engine.service;

import java.util.Map;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.Journey;

public interface JourneyEngine {
	/**
	 * Starts a new journey execution.
	 */
	Map<String, Object> start(Journey journey, String accountId, Map<String, Object> initialParams);

	/**
	 * Resumes an existing journey execution.
	 */
	Map<String, Object> resume(Journey journey, ExecutionContext context, Map<String, Object> inputParams);
}
