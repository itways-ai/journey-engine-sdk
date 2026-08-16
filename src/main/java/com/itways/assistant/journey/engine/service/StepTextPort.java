package com.itways.assistant.journey.engine.service;

import java.util.Map;

import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.StepText;

/**
 * Supplies a journey's authored text in a given language.
 *
 * <p>
 * A port because the engine has no database: journey-service owns
 * {@code journey_step_translations}, and speech-service wires the two together.
 * Same shape as {@link KnowledgeBasePort} and {@link TemplateRenderPort}.
 *
 * <p>
 * Implementations must never throw. A translation lookup that fails should
 * return an empty map — the run then proceeds in the authored language, which
 * is the behaviour the platform had before translations existed. A conversation
 * in the wrong language is a poor experience; a conversation that dies because
 * a translation table was unreachable is a worse one.
 */
public interface StepTextPort {

    /**
     * Every translated step in one journey, keyed by step id.
     *
     * <p>
     * Batched per journey rather than fetched per step because a run touches
     * most of its steps and each would otherwise cost a round trip inside the
     * step loop.
     *
     * @return possibly empty, never null. A missing entry means "no variant in
     *         this language", which is different from an entry whose fields are
     *         all null
     */
    Map<Long, StepText> forJourney(String accountId, Long journeyId, ConversationLanguage language);

    /**
     * Records a machine translation so the next run does not pay for it again.
     *
     * <p>
     * Best-effort and must not throw: failing to cache a translation is not a
     * reason to fail the turn that produced it.
     */
    default void saveMachineTranslation(String accountId, Long stepId, ConversationLanguage language, StepText text) {
        // Default no-op: a deployment without a translation store still runs,
        // it just re-translates each time.
    }

    /** Port that knows nothing, for tests and for engine embeddings with no store. */
    StepTextPort NONE = (accountId, journeyId, language) -> Map.of();
}
