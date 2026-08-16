package com.itways.assistant.journey.engine.language;

import java.util.Locale;

/**
 * Whether an assistant follows the user's language or holds its own.
 *
 * <p>
 * {@link #MIRROR_USER} is the right default and what most products want. The
 * escape hatch exists for copy that is not free to be reworded: regulated
 * disclosures, contractual terms, price and legal notices that were approved in
 * one language and must be delivered in that language even to a user writing in
 * another. Translating those on the fly is a compliance problem wearing a UX
 * costume, so an assistant can decline to.
 *
 * <p>
 * Note there is no "mirror only within a supported set" option:
 * {@link ConversationLanguage} is already a closed set, so a message in a
 * language the product does not ship simply yields no detection and falls
 * through to the assistant's default. That behaviour is {@link #MIRROR_USER}.
 */
public enum LanguagePolicy {

    /** Answer in whatever language the user writes in. */
    MIRROR_USER,

    /**
     * Always answer in the assistant's default language, whatever the user
     * writes. Detection still runs and is still recorded, so the console can
     * show that a user wrote in a language the assistant declined to adopt.
     */
    FIXED;

    public static final LanguagePolicy DEFAULT = MIRROR_USER;

    public static LanguagePolicy parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
