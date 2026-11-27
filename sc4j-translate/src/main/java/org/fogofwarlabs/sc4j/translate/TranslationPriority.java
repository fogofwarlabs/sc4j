package org.fogofwarlabs.sc4j.translate;

/**
 * Lists the priorities of translation requests; a translator answers {@link #CHAT} requests before {@link #BATCH} ones.
 */
public enum TranslationPriority {
    /**
     * A message a player is reading now.
     */
    CHAT,
    /**
     * Background work, such as texts translated ahead of their display.
     */
    BATCH
}
