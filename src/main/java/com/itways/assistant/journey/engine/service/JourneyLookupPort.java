package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.Journey;

/**
 * Port for resolving a journey by its trigger intent at runtime.
 * Implemented by the host (e.g. speech-service) so the engine can run
 * TRIGGER_JOURNEY steps without depending on journey-service directly.
 */
public interface JourneyLookupPort {

    /**
     * Finds a journey by trigger intent for the given account.
     *
     * @param accountId account that owns the journey
     * @param intent    trigger intent name (e.g. {@code ORDER_STATUS})
     * @return the journey including steps, or {@code null} if not found
     */
    Journey findByTriggerIntent(String accountId, String intent);
}
