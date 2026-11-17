package org.fogofwarlabs.sc4j.launcher.ui.run;

import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.launch.RunReport;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeProcess;
import org.fogofwarlabs.sc4j.launcher.launch.RuntimeRun;
import org.fogofwarlabs.sc4j.launcher.ui.ButtonRole;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Spacing;
import org.fogofwarlabs.sc4j.launcher.ui.Tone;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Shows the Run screen: the state of the last run (running, ended, failed), the failure report with the last output lines
 * when the runtime ended with an error, and the whole output with the command line and the settings file in tabs. The
 * output grows while the runtime runs.
 */
public final class RunScreen {
    private static final @NotNull DateTimeFormatter START_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final @NotNull String LINE_SEPARATOR = "\n";
    private static final double MILLISECONDS_PER_SECOND = 1000.0;
    private static final @NotNull String DURATION_FORMAT = "%.1f";
    /**
     * The number of lines the log may hold beyond the run's output capacity before it is cut back to the kept output.
     */
    private static final int LOG_TRIM_MARGIN = RuntimeRun.OUTPUT_CAPACITY / 10;

    /**
     * Receives the actions of the Run screen.
     */
    public interface Actions {
        /**
         * Called when the user opens the run's log folder.
         *
         * @param run the run
         */
        void onOpenLogFolder(@NotNull RuntimeRun run);

        /**
         * Called when the user copies the run's report.
         *
         * @param text the report text
         */
        void onCopy(@NotNull String text);

        /**
         * Called when the user stops the running runtime.
         *
         * @param run the run
         */
        void onStop(@NotNull RuntimeRun run);

        /**
         * Called when the user wants to run the mod again; the launcher shows its settings.
         *
         * @param run the run
         */
        void onRunAgain(@NotNull RuntimeRun run);
    }


    private final @Nullable RuntimeRun run;
    private final @NotNull LauncherTexts texts;
    private final @NotNull Actions actions;

    private final @NotNull VBox root;
    private final @NotNull VBox summarySlot;
    private final @NotNull VBox reportSlot;
    private final @NotNull TextArea log;

    private int shownLineCount;

    /**
     * Creates a {@link RunScreen} instance.
     *
     * @param run     the last run, or {@code null} when nothing ran yet
     * @param texts   the launcher texts of the display language
     * @param actions receives the actions
     */
    public RunScreen(@Nullable RuntimeRun run, @NotNull LauncherTexts texts, @NotNull Actions actions) {
        this.run     = run;
        this.texts   = texts;
        this.actions = actions;

        this.root        = ComponentUtils.column(Spacing.L);
        this.summarySlot = new VBox();
        this.reportSlot  = new VBox();
        this.log         = ComponentUtils.codeArea(run == null ? "" : String.join(LINE_SEPARATOR, run.getOutput().getLines()), "log-area");

        this.shownLineCount = run == null ? 0 : run.getOutput().getLines().size();

        if (run == null) {
            HBox notice = ComponentUtils.notice(Tone.ACCENT, texts.get("run.none"));

            this.root.getChildren().add(ComponentUtils.panel(ComponentUtils.panelBody(notice)));

            return;
        }

        this.root.getChildren().addAll(this.summarySlot, this.reportSlot, this.createOutputPanel(run));
        this.update();
        this.log.positionCaret(this.log.getLength());
    }

    /**
     * @return the screen's root node
     */
    public @NotNull VBox getRoot() {
        return this.root;
    }

    /**
     * Adds a line of the running runtime's output to the log.
     *
     * @param line the line, already recorded in the run
     */
    public void appendOutput(@NotNull String line) {
        if (this.run != null && this.shownLineCount >= RuntimeRun.OUTPUT_CAPACITY + LOG_TRIM_MARGIN) {
            List<String> kept = this.run.getOutput().getLines();

            this.log.setText(String.join(LINE_SEPARATOR, kept));
            this.shownLineCount = kept.size();

            return;
        }

        if (this.log.getLength() > 0) {
            this.log.appendText(LINE_SEPARATOR);
        }

        this.log.appendText(line);
        this.shownLineCount++;
    }

    /**
     * Shows the run's current state again: after it started, ended or failed to start.
     */
    public void update() {
        if (this.run == null) {
            return;
        }

        this.summarySlot.getChildren().setAll(this.createSummary(this.run));
        this.reportSlot.getChildren().setAll(this.createReport(this.run));
    }

    private @NotNull Region createSummary(@NotNull RuntimeRun shown) {
        RuntimeProcess process = shown.getProcess();
        RunReport report = shown.getReport();
        LocalTime startTime = LocalTime.ofInstant(shown.getStartTime(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
        String processText = process == null ? "" : this.texts.format("run.process", process.getPid());
        String duration = String.format(Locale.ROOT, DURATION_FORMAT, shown.getDuration(Instant.now()).toMillis() / MILLISECONDS_PER_SECOND);
        String captionText = this.texts.format("run.caption", shown.getModId(), shown.getModVersion(), START_TIME_FORMAT.format(startTime), duration,
                                               processText);
        Label caption = ComponentUtils.label(captionText, "text-caption");
        Label badge;
        Button openLog = ComponentUtils.button(this.texts.get("run.open.log"), ButtonRole.SECONDARY);
        Button copy = ComponentUtils.button(this.texts.get("run.copy.report"), ButtonRole.SECONDARY);
        Button action;

        if (shown.isRunning()) {
            badge  = ComponentUtils.badge(this.texts.get("run.state.running"), Tone.ACCENT);
            action = ComponentUtils.button(this.texts.get("run.stop"), ButtonRole.DANGER);
            action.setOnAction(event -> this.actions.onStop(shown));
        } else {
            if (report == null) {
                badge = ComponentUtils.badge(this.texts.get("run.state.not.started"), Tone.DANGER);
            } else if (report.isFailure()) {
                badge = ComponentUtils.badge(this.texts.format("run.state.exit", report.getStatus()), Tone.DANGER);
            } else {
                badge = ComponentUtils.badge(this.texts.get("run.state.ended"), Tone.SUCCESS);
            }

            action = ComponentUtils.button(this.texts.get("run.again"), ButtonRole.PRIMARY);
            action.setOnAction(event -> this.actions.onRunAgain(shown));
        }

        openLog.setOnAction(event -> this.actions.onOpenLogFolder(shown));
        copy.setOnAction(event -> this.actions.onCopy(this.formatReport(shown)));

        VBox heading = ComponentUtils.column(Spacing.XS, ComponentUtils.label(shown.getModName(), "text-title"), caption);

        return ComponentUtils.panel(ComponentUtils.panelBody(ComponentUtils.row(Spacing.L, heading, badge,
                                                                                ComponentUtils.growingSpacer(), openLog, copy, action)));
    }

    private @NotNull List<@NotNull Region> createReport(@NotNull RuntimeRun shown) {
        RunReport report = shown.getReport();
        String startFailure = shown.getStartFailure();

        if (startFailure != null) {
            HBox headline = ComponentUtils.row(Spacing.S, ComponentUtils.statusMarker(Tone.DANGER),
                                               ComponentUtils.label(this.texts.get("run.start.failed"), "text-subtitle", "text-danger"));

            return List.of(ComponentUtils.dangerPanel(headline, ComponentUtils.paragraph(startFailure, "text-caption")));
        }

        if (report == null) {
            return List.of();
        }

        if (!report.isFailure()) {
            return List.of(ComponentUtils.notice(Tone.SUCCESS, report.getHeadline(this.texts)));
        }

        VBox excerpt = new VBox();
        HBox headline = ComponentUtils.row(Spacing.S, ComponentUtils.statusMarker(Tone.DANGER),
                                           ComponentUtils.label(report.getHeadline(this.texts), "text-subtitle", "text-danger"));
        VBox failure = ComponentUtils.dangerPanel(headline, ComponentUtils.paragraph(report.getExplanation(this.texts), "text-body"), excerpt);

        excerpt.getStyleClass().add("log-excerpt");

        for (String line : report.getLastLines()) {
            excerpt.getChildren().add(ComponentUtils.label(line));
        }

        return List.of(failure);
    }

    private @NotNull VBox createOutputPanel(@NotNull RuntimeRun shown) {
        TabPane tabs = new TabPane(new Tab(this.texts.get("run.tab.log"), this.log),
                                   new Tab(this.texts.get("run.tab.command"), ComponentUtils.codeArea(shown.getCommandLine().describe(), "code-area")),
                                   new Tab(this.texts.get("run.tab.settings"), ComponentUtils.codeArea(shown.getSettingsText(), "code-area")));
        VBox panel;

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        panel = ComponentUtils.panel(this.texts.get("run.output"), ComponentUtils.panelBody(tabs));
        VBox.setVgrow(panel, Priority.ALWAYS);
        HBox.setHgrow(panel, Priority.ALWAYS);

        return panel;
    }

    private @NotNull String formatReport(@NotNull RuntimeRun shown) {
        RunReport report = shown.getReport();
        String startFailure = shown.getStartFailure();
        StringBuilder text = new StringBuilder();

        text.append(shown.getModName()).append(LINE_SEPARATOR).append(shown.getCommandLine().describe()).append(LINE_SEPARATOR);

        if (startFailure != null) {
            text.append(this.texts.get("run.start.failed")).append(LINE_SEPARATOR).append(startFailure);
        } else if (report != null) {
            text.append(report.format(this.texts));
        } else {
            text.append(this.texts.get("run.state.running"));
        }

        return text.toString();
    }
}
