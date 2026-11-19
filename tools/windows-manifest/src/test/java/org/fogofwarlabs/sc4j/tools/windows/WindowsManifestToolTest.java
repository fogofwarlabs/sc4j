package org.fogofwarlabs.sc4j.tools.windows;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the UTF-8 active code page setting added to the application manifest of a Windows program.
 */
final class WindowsManifestToolTest {
    private static final @NotNull String SETTING = "<activeCodePage xmlns=\"http://schemas.microsoft.com/SMI/2019/WindowsSettings\">" +
                                                   "UTF-8</activeCodePage>";

    @Test
    void add_utf8_code_page_should_add_the_setting_to_the_windows_settings() {
        String manifest = "<assembly><asmv3:application><asmv3:windowsSettings><dpiAware>true</dpiAware></asmv3:windowsSettings>" +
                          "</asmv3:application></assembly>";

        Assertions.assertEquals("<assembly><asmv3:application><asmv3:windowsSettings><dpiAware>true</dpiAware>" + SETTING +
                                "</asmv3:windowsSettings></asmv3:application></assembly>", WindowsManifestTool.addUtf8CodePage(manifest));
    }

    @Test
    void add_utf8_code_page_should_add_an_application_when_the_manifest_has_no_windows_settings() {
        String manifest = "<assembly><trustInfo/></assembly>";

        Assertions.assertEquals("<assembly><trustInfo/><application xmlns=\"urn:schemas-microsoft-com:asm.v3\"><windowsSettings>" + SETTING +
                                "</windowsSettings></application></assembly>", WindowsManifestTool.addUtf8CodePage(manifest));
    }

    @Test
    void add_utf8_code_page_should_keep_a_manifest_with_an_active_code_page() {
        String manifest = "<assembly><application><windowsSettings>" + SETTING + "</windowsSettings></application></assembly>";

        Assertions.assertSame(manifest, WindowsManifestTool.addUtf8CodePage(manifest));
    }

    @Test
    void add_utf8_code_page_should_refuse_a_text_without_an_assembly() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> WindowsManifestTool.addUtf8CodePage("<manifest/>"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void set_utf8_code_page_should_change_the_manifest_of_a_program_once(@TempDir @NotNull Path directory) throws IOException {
        Path program = directory.resolve("java.exe");

        Files.copy(Path.of(System.getProperty("java.home"), "bin", "java.exe"), program);

        String manifest = WindowsManifestTool.readManifest(program);

        Assertions.assertTrue(WindowsManifestTool.setUtf8CodePage(program));
        Assertions.assertEquals(WindowsManifestTool.addUtf8CodePage(manifest), WindowsManifestTool.readManifest(program));
        Assertions.assertFalse(WindowsManifestTool.setUtf8CodePage(program));
    }
}
