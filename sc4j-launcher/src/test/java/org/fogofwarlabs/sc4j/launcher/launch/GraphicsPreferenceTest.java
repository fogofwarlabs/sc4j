package org.fogofwarlabs.sc4j.launcher.launch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the Windows graphics preference of a program through a recording stand-in for {@code reg}: a missing value is written, a value that
 * names the high-performance GPU stays, a value that names another GPU is replaced with its other entries kept, only the value the launcher
 * writes is removed, and nothing runs outside Windows.
 */
final class GraphicsPreferenceTest {
    private static final @NotNull String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
    private static final @NotNull Path PROGRAM = Path.of("SC4J", "java", "bin", "java.exe").toAbsolutePath();

    /**
     * Answers {@code reg query} with a value of the given data, or as for a missing value, answers every other command as done, and records
     * the commands without their program.
     */
    private static final class RecordingRegistry implements GraphicsPreference.CommandRunner {
        private final @Nullable String data;
        private final @NotNull List<@NotNull List<@NotNull String>> commands;

        private RecordingRegistry(@Nullable String data) {
            this.data     = data;
            this.commands = new ArrayList<>();
        }

        @Override
        public @Nullable String run(@NotNull List<@NotNull String> command) {
            this.commands.add(List.copyOf(command.subList(1, command.size())));

            if (!command.get(1).equals("query")) {
                return "The operation completed successfully.\r\n";
            }

            return this.data == null ? null : "\r\n" + KEY.replace("HKCU", "HKEY_CURRENT_USER") + "\r\n    " + PROGRAM + "    REG_SZ    " + this.data +
                                              "\r\n\r\n";
        }
    }

    private static @NotNull List<@NotNull String> createQuery() {
        return List.of("query", KEY, "/v", PROGRAM.toString());
    }

    private static @NotNull List<@NotNull String> createAdd(@NotNull String data) {
        return List.of("add", KEY, "/v", PROGRAM.toString(), "/t", "REG_SZ", "/d", data, "/f");
    }

    @Test
    void add_high_performance_should_write_a_missing_value() {
        RecordingRegistry registry = new RecordingRegistry(null);

        new GraphicsPreference(true, registry).addHighPerformance(PROGRAM);

        Assertions.assertEquals(List.of(createQuery(), createAdd("GpuPreference=2;")), registry.commands);
    }

    @Test
    void add_high_performance_should_keep_a_value_that_names_the_high_performance_gpu() {
        RecordingRegistry registry = new RecordingRegistry("GpuPreference=2;SwapEffectUpgradeEnable=1;");

        new GraphicsPreference(true, registry).addHighPerformance(PROGRAM);

        Assertions.assertEquals(List.of(createQuery()), registry.commands);
    }

    @Test
    void add_high_performance_should_replace_another_gpu_and_keep_the_other_entries() {
        RecordingRegistry registry = new RecordingRegistry("SwapEffectUpgradeEnable=1;GpuPreference=1;");

        new GraphicsPreference(true, registry).addHighPerformance(PROGRAM);

        Assertions.assertEquals(List.of(createQuery(), createAdd("GpuPreference=2;SwapEffectUpgradeEnable=1;")), registry.commands);
    }

    @Test
    void remove_high_performance_should_delete_only_the_value_the_launcher_writes() {
        RecordingRegistry written = new RecordingRegistry("GpuPreference=2;");
        RecordingRegistry changed = new RecordingRegistry("GpuPreference=2;SwapEffectUpgradeEnable=1;");
        RecordingRegistry missing = new RecordingRegistry(null);

        new GraphicsPreference(true, written).removeHighPerformance(PROGRAM);
        new GraphicsPreference(true, changed).removeHighPerformance(PROGRAM);
        new GraphicsPreference(true, missing).removeHighPerformance(PROGRAM);

        Assertions.assertEquals(List.of(createQuery(), List.of("delete", KEY, "/v", PROGRAM.toString(), "/f")), written.commands);
        Assertions.assertEquals(List.of(createQuery()), changed.commands, "a value the user changed stays");
        Assertions.assertEquals(List.of(createQuery()), missing.commands);
    }

    @Test
    void graphics_preference_runs_nothing_outside_windows() {
        RecordingRegistry registry = new RecordingRegistry(null);
        GraphicsPreference preference = new GraphicsPreference(false, registry);

        preference.addHighPerformance(PROGRAM);
        preference.removeHighPerformance(PROGRAM);

        Assertions.assertFalse(preference.isSupported());
        Assertions.assertEquals(List.of(), registry.commands);
    }
}
