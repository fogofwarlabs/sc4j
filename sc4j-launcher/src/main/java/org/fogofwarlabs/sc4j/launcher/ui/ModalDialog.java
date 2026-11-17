package org.fogofwarlabs.sc4j.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * Builds a dialog shown inside the launcher window over a dimmed screen: a panel with a title, a body, a message line for a problem, a progress
 * bar for long work and the action buttons in its footer. A screen shows it with {@link #getRoot()} on top of its content and removes it to
 * close it. Its primary button is the default button (Enter) and its first other button the cancel button (Escape). Slow work runs on a
 * thread of its own with its progress shown ({@link #runWork}).
 */
public final class ModalDialog {
    private static final double GROWING_CONTENT_HEIGHT = 200;
    private static final double GROWING_CONTENT_MINIMUM_HEIGHT = 120;
    private static final @NotNull String WORK_THREAD_NAME = "launcher-dialog-work";
    private static final @NotNull String SCRIM_STYLE_CLASS = "modal-scrim";
    private static final @NotNull String TALL_STYLE_CLASS = "tall";

    /**
     * Lists the widths of a dialog.
     */
    public enum Width {
        /**
         * 460 pixels, the common width.
         */
        REGULAR(460),
        /**
         * 580 pixels, for wider content such as a list beside a list or release notes.
         */
        WIDE(580);

        private final double width;

        /**
         * Creates a {@link Width} instance.
         *
         * @param width the width of the panel in pixels
         */
        Width(double width) {
            this.width = width;
        }

        /**
         * @return the width of the panel in pixels
         */
        public double getWidth() {
            return this.width;
        }
    }

    /**
     * Lists the heights of a dialog.
     */
    public enum Height {
        /**
         * As tall as its content; growing content ({@link #addGrowingContent}) takes a common height.
         */
        CONTENT,
        /**
         * The height of the launcher, with a wider margin above and below; growing content takes the room the other content leaves.
         */
        TALL
    }

    /**
     * Carries out the slow part of a dialog's work away from the user interface thread, without changing the files the dialog is about.
     *
     * @param <T> the type of the work's result
     */
    @FunctionalInterface
    public interface Work<T> {
        /**
         * Carries out the work.
         *
         * @param progress receives the share of the work done, from {@code 0} to {@code 1}, from any thread
         * @return the work's result, or {@code null} if it was cancelled
         * @throws IOException if the work failed
         */
        @Nullable T run(@NotNull DoubleConsumer progress) throws IOException;
    }

    /**
     * Takes the result of a dialog's work on the user interface thread, where Cancel runs too, so that a cancelled dialog saves nothing.
     *
     * @param <T> the type of the work's result
     */
    @FunctionalInterface
    public interface WorkResult<T> {
        /**
         * Saves the result and goes on with it, such as by closing the dialog.
         *
         * @param result the work's result
         * @throws IOException if the result cannot be saved
         */
        void accept(@NotNull T result) throws IOException;
    }

    private static void setShown(@NotNull Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }


    private final @NotNull VBox body;
    private final @NotNull Label message;
    private final @NotNull Label progressText;
    private final @NotNull ProgressBar progress;
    private final @NotNull VBox progressRow;
    private final @NotNull HBox footer;
    private final @NotNull StackPane root;

    /**
     * Creates a {@link ModalDialog} instance without buttons, of the common width, as tall as its content.
     *
     * @param title the title, shown in capitals by the stylesheet
     */
    public ModalDialog(@NotNull String title) {
        this(title, Width.REGULAR, Height.CONTENT);
    }

    /**
     * Creates a {@link ModalDialog} instance without buttons, of the common width and a given height.
     *
     * @param title  the title, shown in capitals by the stylesheet
     * @param height the height
     */
    public ModalDialog(@NotNull String title, @NotNull Height height) {
        this(title, Width.REGULAR, height);
    }

    /**
     * Creates a {@link ModalDialog} instance without buttons, of a given width, as tall as its content.
     *
     * @param title the title, shown in capitals by the stylesheet
     * @param width the width
     */
    public ModalDialog(@NotNull String title, @NotNull Width width) {
        this(title, width, Height.CONTENT);
    }

    /**
     * Creates a {@link ModalDialog} instance without buttons.
     *
     * @param title  the title, shown in capitals by the stylesheet
     * @param width  the width
     * @param height the height
     */
    public ModalDialog(@NotNull String title, @NotNull Width width, @NotNull Height height) {
        VBox panel;

        this.body         = ComponentUtils.panelBody();
        this.message      = ComponentUtils.paragraph("", "field-message");
        this.progressText = ComponentUtils.label("", "text-caption");
        this.progress     = new ProgressBar(0);
        this.progressRow  = ComponentUtils.column(Spacing.XS, this.progressText, this.progress);
        this.footer       = ComponentUtils.panelFooter();
        this.root         = new StackPane();

        this.progress.setMaxWidth(Double.MAX_VALUE);
        this.body.getChildren().addAll(this.message, this.progressRow);
        setShown(this.message, false);
        setShown(this.progressRow, false);
        this.footer.setAlignment(Pos.CENTER_RIGHT);

        panel = ComponentUtils.panelWithFooter(title, this.body, this.footer);
        panel.setPrefWidth(width.getWidth());
        panel.setMaxWidth(width.getWidth());
        panel.setMaxHeight(height == Height.TALL ? Double.MAX_VALUE : Region.USE_PREF_SIZE);

        this.root.getStyleClass().add(SCRIM_STYLE_CLASS);

        if (height == Height.TALL) {
            this.root.getStyleClass().add(TALL_STYLE_CLASS);
        }

        this.root.getChildren().add(panel);
        StackPane.setAlignment(panel, Pos.CENTER);
    }

    /**
     * @return the dialog's root: the dimming layer with the centred panel, to be laid over a screen
     */
    public @NotNull StackPane getRoot() {
        return this.root;
    }

    /**
     * Adds content to the body, above the message line and the progress bar.
     *
     * @param content the content
     */
    public void addContent(@NotNull Node @NotNull ... content) {
        int index = this.body.getChildren().indexOf(this.message);

        this.body.getChildren().addAll(index, List.of(content));
    }

    /**
     * Adds content that grows with the dialog to the body, above the message line and the progress bar, such as a list or a long text. In a
     * dialog as tall as its content it takes a common height; in a tall dialog the room the other content leaves.
     *
     * @param content the content
     */
    public void addGrowingContent(@NotNull Region content) {
        content.setMinHeight(GROWING_CONTENT_MINIMUM_HEIGHT);
        content.setPrefHeight(GROWING_CONTENT_HEIGHT);
        VBox.setVgrow(content, Priority.ALWAYS);
        this.addContent(content);
    }

    /**
     * Sets the footer buttons from left to right, after a spacer: the cancel button (Escape) and the action button (Enter).
     *
     * @param cancel the button that closes the dialog without acting, or {@code null} for none
     * @param action the button that acts, or {@code null} for none
     */
    public void setButtons(@Nullable Button cancel, @Nullable Button action) {
        this.setButtons(List.of(), cancel, action);
    }

    /**
     * Sets the footer buttons from left to right: the buttons at the left end, then after a spacer the cancel button (Escape) and the action
     * button (Enter).
     *
     * @param leading the buttons for other choices, such as skipping what the dialog offers or acting on a selected list item
     * @param cancel  the button that closes the dialog without acting, or {@code null} for none
     * @param action  the button that acts, or {@code null} for none
     */
    public void setButtons(@NotNull List<@NotNull Button> leading, @Nullable Button cancel, @Nullable Button action) {
        this.footer.getChildren().setAll(leading);
        this.footer.getChildren().add(ComponentUtils.growingSpacer());

        if (cancel != null) {
            cancel.setCancelButton(true);
            this.footer.getChildren().add(cancel);
        }

        if (action != null) {
            action.setDefaultButton(true);
            this.footer.getChildren().add(action);
        }
    }

    /**
     * Shows a problem in one line under the content, or hides the line.
     *
     * @param text the problem, or {@code null} to hide the line
     */
    public void showMessage(@Nullable String text) {
        this.message.setText(text == null ? "" : text);
        setShown(this.message, text != null);
    }

    /**
     * Shows the progress of long work under the content, or hides it.
     *
     * @param text  the text over the bar, or {@code null} to hide the progress
     * @param share the share done, from {@code 0} to {@code 1}, or {@link ProgressBar#INDETERMINATE_PROGRESS} when it is not known
     */
    public void showProgress(@Nullable String text, double share) {
        this.progressText.setText(text == null ? "" : text);
        this.progress.setProgress(share);
        setShown(this.progressRow, text != null);
    }

    /**
     * Runs the dialog's work on a thread of its own and shows its progress under a text that does not change.
     *
     * @param progressText the text over the progress bar
     * @param cancelled    set by the dialog's Cancel
     * @param work         the work
     * @param done         takes the result
     * @param describe     gives the message of a failure
     * @param failed       called after a failure, such as to enable the dialog's inputs again
     * @param <T>          the type of the work's result
     * @return the started thread of the work, which Cancel may interrupt to stop the work early
     */
    public <T> @NotNull Thread runWork(@NotNull String progressText, @NotNull AtomicBoolean cancelled, @NotNull Work<T> work,
                                       @NotNull WorkResult<T> done, @NotNull Function<@NotNull Exception, @NotNull String> describe,
                                       @NotNull Runnable failed) {
        return this.runWork(share -> progressText, cancelled, work, done, describe, failed);
    }

    /**
     * Runs the dialog's work on a thread of its own and shows its progress. The result is passed on on the user interface thread, and only
     * while the work was not cancelled: Cancel, which runs on that thread too, either comes first and the result is dropped, or the result
     * was taken already. A failure of the work or of taking its result hides the progress and shows the reason in the message line, unless
     * the work was cancelled.
     *
     * @param progressText gives the text over the progress bar from the share done, which is {@link ProgressBar#INDETERMINATE_PROGRESS}
     *                     while it is not known
     * @param cancelled    set by the dialog's Cancel
     * @param work         the work
     * @param done         takes the result
     * @param describe     gives the message of a failure
     * @param failed       called after a failure, such as to enable the dialog's inputs again
     * @param <T>          the type of the work's result
     * @return the started thread of the work, which Cancel may interrupt to stop the work early
     */
    public <T> @NotNull Thread runWork(@NotNull DoubleFunction<@NotNull String> progressText, @NotNull AtomicBoolean cancelled,
                                       @NotNull Work<T> work, @NotNull WorkResult<T> done,
                                       @NotNull Function<@NotNull Exception, @NotNull String> describe, @NotNull Runnable failed) {
        AtomicReference<Double> latest = new AtomicReference<>(null);
        Consumer<Exception> fail = exception -> {
            if (cancelled.get()) {
                return;
            }

            this.showProgress(null, 0);
            this.showMessage(describe.apply(exception));
            failed.run();
        };

        this.showMessage(null);
        this.showProgress(progressText.apply(ProgressBar.INDETERMINATE_PROGRESS), ProgressBar.INDETERMINATE_PROGRESS);

        DoubleConsumer progress = share -> {
            if (latest.getAndSet(share) == null) {
                Platform.runLater(() -> {
                    Double value = latest.getAndSet(null);

                    if (value != null && !cancelled.get()) {
                        this.showProgress(progressText.apply(value), value);
                    }
                });
            }
        };
        Thread thread = new Thread(() -> {
            try {
                T result = work.run(progress);

                Platform.runLater(() -> {
                    if (result == null || cancelled.get()) {
                        return;
                    }

                    try {
                        this.showProgress(null, 0);
                        done.accept(result);
                    } catch (IOException | RuntimeException exception) {
                        fail.accept(exception);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                Platform.runLater(() -> fail.accept(exception));
            }
        }, WORK_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();

        return thread;
    }
}
