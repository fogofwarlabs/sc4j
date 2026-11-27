package org.fogofwarlabs.sc4j.runtime.cli;

import org.fogofwarlabs.sc4j.translate.service.model.TranslationDevice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Tests parsing the runtime command line: every option, the repeatable seeds, the launcher link, the translation preset and device, the mod
 * arguments after {@code --} and the rejected forms.
 */
final class RuntimeCommandLineTest {
    @Test
    void parse_should_read_every_option_and_the_mod_arguments() {
        RuntimeCommandLine commandLine = RuntimeCommandLine.parse(new String[]{
                "--mod", "mods/my-mod.jar", "--install", "install", "--settings", "my-mod.json", "--storage", "storage", "--log", "logs",
                "--account", "accounts/0123456789abcdef", "--seeds", "seeds.json", "--seed", "127.0.0.1:7455", "--seed", "[::1]:7456",
                "--peers", "peers.json", "--no-port-mapping", "--launcher-link", "userdata/run/launcher-1234.sock",
                "--translation-preset", "index-translate-2b", "--translation-device", "cpu",
                "--headless", "--replay", "game.sc4r", "--join", "192.0.2.2:6121", "--", "--skirmish", "--mod", "x"
        });

        Assertions.assertEquals(Path.of("mods/my-mod.jar").toAbsolutePath(), commandLine.getMod());
        Assertions.assertEquals(Path.of("install").toAbsolutePath(), commandLine.getInstall());
        Assertions.assertEquals(Path.of("my-mod.json").toAbsolutePath(), commandLine.getSettings());
        Assertions.assertEquals(Path.of("storage").toAbsolutePath(), commandLine.getStorage());
        Assertions.assertEquals(Path.of("logs").toAbsolutePath(), commandLine.getLog());
        Assertions.assertEquals(Path.of("accounts/0123456789abcdef").toAbsolutePath(), commandLine.getAccount());
        Assertions.assertEquals(Path.of("seeds.json").toAbsolutePath(), commandLine.getSeedsFile());
        Assertions.assertEquals(List.of("127.0.0.1:7455", "[::1]:7456"), commandLine.getSeeds().stream().map(Object::toString).toList());
        Assertions.assertEquals(Path.of("peers.json").toAbsolutePath(), commandLine.getPeersFile());
        Assertions.assertTrue(commandLine.hasSeedSource());
        Assertions.assertFalse(commandLine.isPortMapping());
        Assertions.assertEquals(Path.of("userdata/run/launcher-1234.sock").toAbsolutePath(), commandLine.getLauncherLink());
        Assertions.assertEquals("index-translate-2b", commandLine.getTranslationPreset());
        Assertions.assertEquals(TranslationDevice.CPU, commandLine.getTranslationDevice());
        Assertions.assertTrue(commandLine.isHeadless());
        Assertions.assertEquals(Path.of("game.sc4r").toAbsolutePath(), commandLine.getReplay());
        Assertions.assertEquals("192.0.2.2:6121", commandLine.getJoin());
        Assertions.assertEquals(List.of("--skirmish", "--mod", "x"), commandLine.getModArguments());
        Assertions.assertFalse(commandLine.isHelp());
    }

    @Test
    void parse_should_leave_defaults_to_the_install_layout_and_offer_no_beacon_link() {
        RuntimeCommandLine commandLine = RuntimeCommandLine.parse(new String[]{"--mod", "my-mod.jar"});

        Assertions.assertNull(commandLine.getInstall());
        Assertions.assertNull(commandLine.getSettings());
        Assertions.assertNull(commandLine.getStorage());
        Assertions.assertNull(commandLine.getLog());
        Assertions.assertNull(commandLine.getAccount());
        Assertions.assertNull(commandLine.getSeedsFile());
        Assertions.assertEquals(List.of(), commandLine.getSeeds());
        Assertions.assertNull(commandLine.getPeersFile());
        Assertions.assertFalse(commandLine.hasSeedSource(), "a run without a seed source offers no Beacon.Link");
        Assertions.assertTrue(commandLine.isPortMapping(), "Beacon.Link sessions map their socket unless told not to");
        Assertions.assertNull(commandLine.getLauncherLink(), "a run without a launcher has no link");
        Assertions.assertNull(commandLine.getTranslationPreset());
        Assertions.assertEquals(TranslationDevice.AUTOMATIC, commandLine.getTranslationDevice());
        Assertions.assertFalse(commandLine.isHeadless());
        Assertions.assertEquals(List.of(), commandLine.getModArguments());
        Assertions.assertTrue(RuntimeCommandLine.parse(new String[]{"--help"}).isHelp());
    }

    @Test
    void parse_should_reject_missing_mod_unknown_repeated_and_valueless_options() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--headless"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--skirmish"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--mod", "b.jar"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--log"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--seed", "node"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--peers", "a.json", "--peers", "b.json"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--launcher-link"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--translation-preset"}));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> RuntimeCommandLine.parse(new String[]{"--mod", "a.jar", "--translation-device", "gpu"}));
    }
}
