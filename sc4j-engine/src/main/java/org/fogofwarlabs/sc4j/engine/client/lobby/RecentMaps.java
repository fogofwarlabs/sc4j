package org.fogofwarlabs.sc4j.engine.client.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the maps a client hosted last, newest first, for the map choice of a create game screen. A map is named by the mod's own text, one line
 * each, for example its file path or its name. The list is kept in a text file of the client's own folder, written whole on every change; an
 * unreadable file starts an empty list and a failed write keeps the change for the run.
 */
public final class RecentMaps {
    /**
     * The most maps the list keeps.
     */
    public static final int MAXIMUM_COUNT = 10;

    private static final @NotNull Logger LOGGER = Logger.of(RecentMaps.class);
    private static final @NotNull String LINE_SEPARATOR = "\n";

    /**
     * Reads the list from its file.
     *
     * @param file the list file; a file that does not exist or cannot be read is an empty list
     * @return the read list, kept in the file
     */
    public static @NotNull RecentMaps read(@NotNull Path file) {
        RecentMaps maps = new RecentMaps(file);

        if (!Files.exists(file)) {
            return maps;
        }

        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.strip();

                if (!text.isEmpty() && maps.entries.size() < MAXIMUM_COUNT) {
                    maps.entries.add(text);
                }
            }
        } catch (IOException exception) {
            LOGGER.warning("RECENT MAPS NOT READ: {0}: {1}", file, exception.getMessage());
            maps.entries.clear();
        }

        return maps;
    }


    private final @NotNull Path file;
    private final @NotNull List<@NotNull String> entries;

    private RecentMaps(@NotNull Path file) {
        this.file    = file;
        this.entries = new ArrayList<>();
    }

    /**
     * @return the maps, newest first, unmodifiable
     */
    public @NotNull List<@NotNull String> getMaps() {
        return List.copyOf(this.entries);
    }


    /**
     * Puts a hosted map first, drops the oldest beyond {@value #MAXIMUM_COUNT} and writes the file.
     *
     * @param map the hosted map, one line of text
     * @throws IllegalArgumentException if the map's text is empty or spans lines
     */
    public void add(@NotNull String map) {
        String text = map.strip();

        if (text.isEmpty() || text.lines().count() != 1) {
            throw Exceptions.newIllegalArgumentException("Invalid recent map {0}", map);
        }

        this.entries.remove(text);
        this.entries.addFirst(text);

        while (this.entries.size() > MAXIMUM_COUNT) {
            this.entries.removeLast();
        }

        StringBuilder contents = new StringBuilder();

        for (String entry : this.entries) {
            contents.append(entry).append(LINE_SEPARATOR);
        }

        try {
            AtomicFileUtils.write(this.file, contents.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            LOGGER.warning("RECENT MAPS NOT SAVED: {0}: {1}", this.file, exception.getMessage());
        }
    }
}
