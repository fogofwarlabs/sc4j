package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Serves a window without platform input method: the default {@link TextInputMethod} off Windows and of offscreen runs and tests. Text arrives
 * as typed characters only; text input is never enabled and no composition events come.
 */
public final class NoTextInputMethod implements TextInputMethod {
    /**
     * Creates a {@link NoTextInputMethod} instance.
     */
    public NoTextInputMethod() {
    }

    /**
     * @return {@code true} if text input is enabled (the input method is attached to the window), {@code false} otherwise; always {@code false} without input method
     */
    @Override
    public boolean isEnabled() {
        return false;
    }

    /**
     * Enables or disables text input: attaches the input method to the window or detaches it. When the platform refuses the change, the state
     * stays as it was ({@link #isEnabled}).
     * <p>
     * Without input method nothing changes.
     *
     * @param enabled whether an edit control takes text
     */
    @Override
    public void setEnabled(boolean enabled) {
    }

    /**
     * Sets the receiver of the composition events.
     * <p>
     * Without input method no events come.
     *
     * @param listener the receiver, or {@code null} to drop the events
     */
    @Override
    public void setListener(@Nullable TextCompositionListener listener) {
    }

    /**
     * Returns the language the input method composes text in.
     *
     * @return {@code null}: no input method composes text
     */
    @Override
    public @Nullable Locale getCompositionLocale() {
        return null;
    }


    /**
     * Cancels the composition in progress, for example after the edit control refused one of its characters.
     * <p>
     * Without input method there is none.
     */
    @Override
    public void cancelComposition() {
    }
}
