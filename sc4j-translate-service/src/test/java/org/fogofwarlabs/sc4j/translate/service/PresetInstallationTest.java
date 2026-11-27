package org.fogofwarlabs.sc4j.translate.service;

import com.sun.net.httpserver.HttpServer;
import org.fogofwarlabs.sc4j.translate.service.model.PresetFile;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests the preset installation against a local HTTP server: downloads checked by size and checksum, progress, files kept between
 * installations, cancellation and deletion.
 */
final class PresetInstallationTest {
    private static final int LARGE_INPUT_LIMIT = 512;
    private static final int LARGE_FILE_SIZE = 1024 * 1024;

    private @NotNull Map<@NotNull String, byte @NotNull []> served;
    private @NotNull AtomicInteger requests;
    private @NotNull HttpServer server;

    @TempDir
    Path folder;

    @BeforeEach
    void startServer() throws IOException {
        this.served   = new ConcurrentHashMap<>();
        this.requests = new AtomicInteger();
        this.server   = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange -> {
            byte[] contents = this.served.get(exchange.getRequestURI().getPath());

            this.requests.incrementAndGet();

            if (contents == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, contents.length);

                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(contents);
                }
            }

            exchange.close();
        });
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    @Test
    void install_should_download_check_and_keep_the_files() throws IOException {
        byte[] first = "first file".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second file".getBytes(StandardCharsets.UTF_8);
        PresetInstallation installation = this.createInstallation(List.of(this.serve("first.bin", first, first), this.serve("second.bin", second, second)));
        List<long[]> progress = new ArrayList<>();

        Assertions.assertFalse(installation.isInstalled());

        installation.install((installed, total) -> progress.add(new long[]{installed, total}));

        Assertions.assertTrue(installation.isInstalled());
        Assertions.assertArrayEquals(first, Files.readAllBytes(installation.getFolder().resolve("first.bin")));
        Assertions.assertArrayEquals(second, Files.readAllBytes(installation.getFolder().resolve("second.bin")));
        Assertions.assertArrayEquals(new long[]{first.length + second.length, installation.getTotalSize()}, progress.getLast());
        Assertions.assertEquals(2, this.requests.get());

        installation.install((installed, total) -> {
        });

        Assertions.assertEquals(2, this.requests.get(), "installed files are not downloaded again");
    }

    @Test
    void install_should_refuse_a_file_that_does_not_match_its_checksum() throws IOException {
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "changed!".getBytes(StandardCharsets.UTF_8);
        PresetInstallation installation = this.createInstallation(List.of(this.serve("model.bin", expected, changed)));

        Assertions.assertThrows(IOException.class, () -> installation.install((installed, total) -> {
        }));
        Assertions.assertFalse(installation.isInstalled());
        Assertions.assertFalse(Files.exists(installation.getFolder().resolve("model.bin")));
        Assertions.assertFalse(Files.exists(installation.getFolder().resolve("model.bin.part")));
    }

    @Test
    void install_should_fail_when_a_file_is_missing_on_the_server() {
        byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
        PresetFile missing = new PresetFile("missing.bin", this.address("missing.bin"), contents.length, sha256(contents));
        PresetInstallation installation = this.createInstallation(List.of(missing));

        Assertions.assertThrows(IOException.class, () -> installation.install((installed, total) -> {
        }));
        Assertions.assertFalse(installation.isInstalled());
    }

    @Test
    void install_should_stop_when_cancelled_and_leave_no_partial_file() {
        byte[] contents = new byte[LARGE_FILE_SIZE];
        PresetInstallation installation = this.createInstallation(List.of(this.serve("large.bin", contents, contents)));

        Assertions.assertThrows(CancellationException.class, () -> installation.install((installed, total) -> installation.cancel()));
        Assertions.assertFalse(installation.isInstalled());
        Assertions.assertFalse(Files.exists(installation.getFolder().resolve("large.bin")));
        Assertions.assertFalse(Files.exists(installation.getFolder().resolve("large.bin.part")));
    }

    @Test
    void install_should_take_a_file_that_another_preset_folder_holds_instead_of_downloading_it() throws IOException {
        byte[] shared = "shared file".getBytes(StandardCharsets.UTF_8);
        PresetFile file = this.serve("shared.bin", shared, shared);
        PresetInstallation first = this.createInstallation(List.of(file));

        first.install((installed, total) -> {
        });

        PresetInstallation second = new PresetInstallation(first.getPreset(), first.getFolder().resolveSibling("other"));
        List<long[]> progress = new ArrayList<>();

        second.install((installed, total) -> progress.add(new long[]{installed, total}));

        Assertions.assertTrue(second.isInstalled());
        Assertions.assertArrayEquals(shared, Files.readAllBytes(second.getFolder().resolve("shared.bin")));
        Assertions.assertArrayEquals(new long[]{shared.length, shared.length}, progress.getLast());
        Assertions.assertEquals(1, this.requests.get(), "the file of the other preset folder is not downloaded again");

        first.delete();

        Assertions.assertArrayEquals(shared, Files.readAllBytes(second.getFolder().resolve("shared.bin")), "deleting one preset keeps the other's file");
    }

    @Test
    void install_should_download_a_file_whose_copy_in_another_preset_folder_does_not_match_its_checksum() throws IOException {
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "changed!".getBytes(StandardCharsets.UTF_8);
        PresetInstallation installation = this.createInstallation(List.of(this.serve("model.bin", expected, expected)));
        Path other = installation.getFolder().resolveSibling("other");

        Files.createDirectories(other);
        Files.write(other.resolve("model.bin"), changed);

        installation.install((installed, total) -> {
        });

        Assertions.assertArrayEquals(expected, Files.readAllBytes(installation.getFolder().resolve("model.bin")));
        Assertions.assertEquals(1, this.requests.get());
    }

    @Test
    void delete_should_remove_the_files_and_the_empty_folder() throws IOException {
        byte[] contents = "contents".getBytes(StandardCharsets.UTF_8);
        PresetInstallation installation = this.createInstallation(List.of(this.serve("model.bin", contents, contents)));

        installation.install((installed, total) -> {
        });
        Files.writeString(installation.getFolder().resolve("model.bin.part"), "partial");

        installation.delete();

        Assertions.assertFalse(installation.isInstalled());
        Assertions.assertFalse(Files.exists(installation.getFolder()));
    }

    private static @NotNull String sha256(byte @NotNull [] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Serves contents under a name and describes the file with the size and checksum of the expected contents.
     */
    private @NotNull PresetFile serve(@NotNull String name, byte @NotNull [] expected, byte @NotNull [] contents) {
        this.served.put("/" + name, contents);

        return new PresetFile(name, this.address(name), expected.length, sha256(expected));
    }

    private @NotNull URI address(@NotNull String name) {
        return URI.create("http://" + this.server.getAddress().getHostString() + ":" + this.server.getAddress().getPort() + "/" + name);
    }

    private @NotNull PresetInstallation createInstallation(@NotNull List<@NotNull PresetFile> files) {
        FakeTranslationPreset preset = new FakeTranslationPreset(files, LARGE_INPUT_LIMIT);

        return new PresetInstallation(preset, TranslationStorage.of(this.folder).getPresetFolder(preset));
    }
}
