package org.fogofwarlabs.sc4j.engine.client.lobby;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the recently hosted maps: a hosted map goes first, a hosted map again moves first, the oldest give way beyond the most kept, the
 * list is read back from its file, and a map's text must be one line.
 */
final class RecentMapsTest {
    @Test
    void add_should_put_the_hosted_map_first_and_keep_the_list_in_its_file(@TempDir @NotNull Path directory) {
        Path file = directory.resolve("recent-maps.txt");
        List<String> maps = new ArrayList<>();

        for (int index = 0; index <= RecentMaps.MAXIMUM_COUNT; index++) {
            maps.add("Map " + index);
        }

        RecentMaps recent = RecentMaps.read(file);

        Assertions.assertEquals(List.of(), recent.getMaps(), "no file is an empty list");

        for (String map : maps) {
            recent.add(map);
        }

        recent.add(maps.get(maps.size() - 2));

        List<String> listed = RecentMaps.read(file).getMaps();

        Assertions.assertEquals(RecentMaps.MAXIMUM_COUNT, listed.size());
        Assertions.assertEquals(maps.get(maps.size() - 2), listed.getFirst());
        Assertions.assertEquals(maps.getLast(), listed.get(1));
        Assertions.assertFalse(listed.contains(maps.getFirst()), "the oldest gives way");
        Assertions.assertThrows(IllegalArgumentException.class, () -> recent.add("two\nlines"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> recent.add(" "));
    }
}
