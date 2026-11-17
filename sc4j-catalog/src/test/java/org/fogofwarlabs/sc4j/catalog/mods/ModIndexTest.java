package org.fogofwarlabs.sc4j.catalog.mods;

import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.catalog.test.ReleaseServerScenario;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tests the mod catalog: its entries in file order with localized names, invalid entries left out, and files that are no catalog.
 */
final class ModIndexTest {
    private static final @NotNull String CATALOG = """
                                                   {
                                                     "format": 1,
                                                     "mods": [
                                                       {
                                                         "name": {"en": "First Mod", "ko": "첫 모드"},
                                                         "repository": "owner/first",
                                                         "description": {"en": "The first.", "ko": "첫째."}
                                                       },
                                                       {"name": {"en": "No Repository"}},
                                                       {"name": {"en": " "}, "repository": "owner/blank"},
                                                       {"name": "Plain Name", "repository": "owner/plain"},
                                                       {"name": {"EN": "Bad Language"}, "repository": "owner/language"},
                                                       {"name": {"en": "Bad Text"}, "repository": "owner/text", "description": "Plain."},
                                                       {"name": {"en": "Bad Repository"}, "repository": "not a repository"},
                                                       "not an entry",
                                                       {"name": {"en": "Second Mod"}, "repository": "https://github.com/owner/second"}
                                                     ]
                                                   }
                                                   """;

    @Test
    void parse_should_keep_the_valid_entries_in_file_order() throws IOException {
        List<ModIndexEntry> entries = ModIndex.parse(CATALOG.getBytes(StandardCharsets.UTF_8));
        LocalizedText firstDescription = entries.get(0).getDescription();

        Assertions.assertEquals(2, entries.size());
        Assertions.assertEquals("First Mod", entries.get(0).getName().get("en"));
        Assertions.assertEquals("첫 모드", entries.get(0).getName().get("ko"));
        Assertions.assertEquals("owner/first", entries.get(0).getRepository().toString());
        Assertions.assertNotNull(firstDescription);
        Assertions.assertEquals("첫째.", firstDescription.get("ko-KR"));
        Assertions.assertEquals("Second Mod", entries.get(1).getName().get("ko"));
        Assertions.assertEquals("owner/second", entries.get(1).getRepository().toString());
        Assertions.assertNull(entries.get(1).getDescription());
    }

    @Test
    void parse_should_refuse_a_file_that_is_no_catalog() {
        for (String text : List.of("not json", "[]", "{\"format\": 2, \"mods\": []}", "{\"format\": 1}", "{\"mods\": []}")) {
            Assertions.assertThrows(IOException.class, () -> ModIndex.parse(text.getBytes(StandardCharsets.UTF_8)), text);
        }
    }

    @Test
    void read_should_fetch_the_catalog_from_its_address() throws IOException {
        try (ReleaseServerScenario server = ReleaseServerScenario.start()) {
            ReleaseClient client = new ReleaseClient(server.getApi());
            ModIndex index = new ModIndex(client, server.addFile("mods.json", CATALOG.getBytes(StandardCharsets.UTF_8)));
            ModIndex missing = new ModIndex(client, server.getApi().resolve("missing.json"));
            ReleaseException exception = Assertions.assertThrows(ReleaseException.class, missing::read);

            Assertions.assertEquals(2, index.read().size());
            Assertions.assertEquals(ReleaseProblem.NOT_FOUND, exception.getProblem());
        }
    }
}
