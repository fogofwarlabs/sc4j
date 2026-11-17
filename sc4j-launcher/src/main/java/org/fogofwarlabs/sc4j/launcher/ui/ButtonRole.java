package org.fogofwarlabs.sc4j.launcher.ui;

/**
 * Lists the button roles: one primary action per screen, secondary for the rest, danger for destructive actions and text only for an action
 * that stays in the background.
 */
public enum ButtonRole {
    /**
     * The main action of a screen.
     */
    PRIMARY,
    /**
     * Any other action.
     */
    SECONDARY,
    /**
     * A destructive action.
     */
    DANGER,
    /**
     * An action shown as its text only, without border or background, beside a stronger action.
     */
    TEXT_ONLY
}
