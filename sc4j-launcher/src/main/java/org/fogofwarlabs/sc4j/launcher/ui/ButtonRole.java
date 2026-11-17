package org.fogofwarlabs.sc4j.launcher.ui;

/**
 * Lists the button roles: one primary action per screen, secondary for the rest, danger for destructive actions.
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
    DANGER
}
