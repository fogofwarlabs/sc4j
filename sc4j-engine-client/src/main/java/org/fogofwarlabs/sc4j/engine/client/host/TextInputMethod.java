package org.fogofwarlabs.sc4j.engine.client.host;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Connects a {@link HostWindow} to the platform's input method (IME), which composes text such as Korean, Japanese or Chinese from several key
 * strokes. While text input is enabled the window shows no composition window of its own: the composition goes to a
 * {@link TextCompositionListener} that shows it in the client's edit control, and the composed result arrives there too. While text input is
 * disabled (no edit control has the focus) the input method is detached from the window, so keys reach the client as plain keys.
 * <p>
 * A window's input method ({@link HostWindow#getTextInputMethod}) is the platform's on Windows and a {@link NoTextInputMethod} elsewhere. It is
 * used on the thread that owns the window.
 */
public interface TextInputMethod {
    /**
     * @return {@code true} if text input is enabled (the input method is attached to the window), {@code false} otherwise
     */
    boolean isEnabled();

    /**
     * Enables or disables text input: attaches the input method to the window or detaches it. When the platform refuses the change, the state
     * stays as it was ({@link #isEnabled}).
     *
     * @param enabled whether an edit control takes text
     */
    void setEnabled(boolean enabled);

    /**
     * Sets the receiver of the composition events.
     *
     * @param listener the receiver, or {@code null} to drop the events
     */
    void setListener(@Nullable TextCompositionListener listener);

    /**
     * Returns the language the input method composes text in: the locale of the window's keyboard layout while text input is enabled and the
     * input method converts to the language's own characters.
     *
     * @return the locale, or {@code null} while text input is disabled, in alphanumeric mode or without input method
     */
    @Nullable Locale getCompositionLocale();


    /**
     * Cancels the composition in progress, for example after the edit control refused one of its characters.
     */
    void cancelComposition();
}
