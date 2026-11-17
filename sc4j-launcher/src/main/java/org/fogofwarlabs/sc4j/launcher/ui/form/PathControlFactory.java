package org.fogofwarlabs.sc4j.launcher.ui.form;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.fogofwarlabs.sc4j.launcher.form.FormField;
import org.fogofwarlabs.sc4j.launcher.i18n.LauncherTexts;
import org.fogofwarlabs.sc4j.launcher.ui.ButtonRole;
import org.fogofwarlabs.sc4j.launcher.ui.ComponentUtils;
import org.fogofwarlabs.sc4j.launcher.ui.Spacing;
import org.fogofwarlabs.sc4j.manifest.settings.FileSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the control of a {@code directory} or {@code file} setting: a path text field and a Browse button that opens the
 * system's folder or file chooser (a file chooser filtered by the setting's {@code extensions}).
 */
public final class PathControlFactory implements FieldControlFactory {
    /**
     * The factory of {@code directory} settings.
     */
    public static final @NotNull PathControlFactory DIRECTORY = new PathControlFactory(true);

    /**
     * The factory of {@code file} settings.
     */
    public static final @NotNull PathControlFactory FILE = new PathControlFactory(false);

    private static final @NotNull String EXTENSION_PATTERN_PREFIX = "*.";

    /**
     * Holds the text field and the Browse button of one path field.
     */
    private final class PathControl implements FieldControl {
        private final @NotNull FormField field;
        private final @NotNull TextField text;
        private final @NotNull HBox node;

        /**
         * Creates a {@link PathControl} instance.
         *
         * @param field    the field
         * @param texts    the launcher texts of the display language
         * @param onChange called after a changed value was passed to the field
         */
        private PathControl(@NotNull FormField field, @NotNull LauncherTexts texts, @NotNull Runnable onChange) {
            Button browse = ComponentUtils.button(texts.get("form.browse"), ButtonRole.SECONDARY);

            this.field = field;
            this.text  = new TextField(field.getText());
            this.node  = ComponentUtils.row(Spacing.S, this.text, browse);

            this.text.setPromptText(texts.get(PathControlFactory.this.directory ? "form.choose.folder" : "form.choose.file"));
            this.text.textProperty().addListener((observable, previous, current) -> {
                field.setInput(current);
                onChange.run();
            });
            HBox.setHgrow(this.text, Priority.ALWAYS);
            browse.setOnAction(event -> this.browse(texts.localize(field.getDefinition().getLabel())));
        }

        /**
         * @return the node the form places in the field, below the label
         */
        @Override
        public @NotNull Node getNode() {
            return this.node;
        }

        /**
         * @return the input node that shows the {@code :invalid} state when the value fails validation
         */
        @Override
        public @NotNull Node getInput() {
            return this.text;
        }

        /**
         * @return {@code true} if the control shows the setting's label itself (a check box) instead of the form showing it above
         * the control, {@code false} otherwise
         */
        @Override
        public boolean isLabelled() {
            return false;
        }

        /**
         * Shows the field's current value again, after the form changed it (for example a reset to the defaults).
         */
        @Override
        public void showValue() {
            this.text.setText(this.field.getText());
        }

        private void browse(@NotNull String title) {
            Window owner = this.text.getScene() == null ? null : this.text.getScene().getWindow();
            File initialDirectory = this.findInitialDirectory();
            File chosen;

            if (owner == null) {
                return;
            }

            if (PathControlFactory.this.directory) {
                DirectoryChooser chooser = new DirectoryChooser();

                chooser.setTitle(title);
                chooser.setInitialDirectory(initialDirectory);
                chosen = chooser.showDialog(owner);
            } else {
                FileChooser chooser = new FileChooser();
                List<String> patterns = new ArrayList<>();

                if (this.field.getDefinition() instanceof FileSetting fileSetting) {
                    for (String extension : fileSetting.getExtensions()) {
                        patterns.add(EXTENSION_PATTERN_PREFIX + extension);
                    }
                }

                if (!patterns.isEmpty()) {
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(String.join(", ", patterns), patterns));
                }

                chooser.setTitle(title);
                chooser.setInitialDirectory(initialDirectory);
                chosen = chooser.showOpenDialog(owner);
            }

            if (chosen != null) {
                this.text.setText(chosen.getAbsolutePath());
            }
        }

        private @Nullable File findInitialDirectory() {
            Path path;

            try {
                path = Path.of(this.text.getText().strip());
            } catch (InvalidPathException exception) {
                return null;
            }

            while (path != null && !Files.isDirectory(path)) {
                path = path.getParent();
            }

            return path == null || !path.isAbsolute() ? null : path.toFile();
        }
    }


    private final boolean directory;

    /**
     * Creates a {@link PathControlFactory} instance.
     *
     * @param directory whether the setting names a folder ({@code directory}) rather than a file ({@code file})
     */
    private PathControlFactory(boolean directory) {
        this.directory = directory;
    }

    /**
     * Creates the control of a field.
     *
     * @param field    the field, whose setting has this factory's type
     * @param texts    the launcher texts of the display language
     * @param onChange called after the control passed a changed value to the field
     * @return the created control, showing the field's current value
     */
    @Override
    public @NotNull FieldControl create(@NotNull FormField field, @NotNull LauncherTexts texts, @NotNull Runnable onChange) {
        return new PathControl(field, texts, onChange);
    }
}
