package org.fogofwarlabs.sc4j.catalog.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for GitHub on 127.0.0.1: the REST API's latest release of a repository ({@code /api/repos/<owner>/<name>/releases/latest}) and
 * its list of releases, newest first ({@code /api/repos/<owner>/<name>/releases}), the release files, which are served through a redirect as
 * GitHub serves them, and other files such as a mod catalog. A release file can be paused halfway, so that its download stays in progress
 * until the test resumes it.
 */
public final class ReleaseServerScenario implements AutoCloseable {
    /**
     * The checksum file extension of a release file.
     */
    public static final @NotNull String CHECKSUM_EXTENSION = ".sha256";

    private static final @NotNull String API_PATH = "/api/";
    private static final @NotNull String LATEST_RELEASE_PATH = API_PATH + "repos/{0}/releases/latest";
    private static final @NotNull String RELEASES_PATH = API_PATH + "repos/{0}/releases";
    private static final @NotNull String DOWNLOAD_PATH = "/download/{0}/{1}/{2}";
    private static final @NotNull String FILE_PATH = "/files/";
    private static final @NotNull String LOCATION_HEADER = "Location";
    private static final @NotNull String ENTITY_TAG_HEADER = "ETag";
    private static final @NotNull String IF_NONE_MATCH_HEADER = "If-None-Match";
    private static final int OK_STATUS = 200;
    private static final int FOUND_STATUS = 302;
    private static final int NOT_MODIFIED_STATUS = 304;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int NO_BODY = -1;
    private static final int HALF = 2;
    private static final long PAUSE_TIMEOUT_SECONDS = 60;

    /**
     * Starts a server on a free port of 127.0.0.1.
     *
     * @return the created {@link ReleaseServerScenario} instance
     * @throws IOException if the server cannot be started
     */
    public static @NotNull ReleaseServerScenario start() throws IOException {
        return new ReleaseServerScenario(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
    }

    /**
     * Gives the SHA-256 checksum of contents the way {@code sha256sum} prints it.
     *
     * @param contents the contents
     * @param fileName the file name printed after the checksum
     * @return the checksum line
     */
    public static byte @NotNull [] createChecksum(byte @NotNull [] contents, @NotNull String fileName) {
        try {
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));

            return (checksum + "  " + fileName + "\n").getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no SHA-256.");
        }
    }

    /**
     * Gives the entity tag of an API answer: its quoted SHA-256 checksum, so it changes exactly when the answer does.
     */
    private static @NotNull String createEntityTag(byte @NotNull [] contents) {
        try {
            return '"' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)) + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no SHA-256.");
        }
    }

    private static void awaitResume(@NotNull CountDownLatch resume) throws IOException {
        try {
            if (!resume.await(PAUSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw Exceptions.newIOException("The paused answer was not resumed in {0} s", PAUSE_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw Exceptions.newIOException(exception, "Interrupted while the answer was paused");
        }
    }


    private final @NotNull HttpServer server;
    private final @NotNull Map<@NotNull String, byte @NotNull []> files;
    private final @NotNull Map<@NotNull String, @NotNull String> redirects;
    private final @NotNull Map<@NotNull String, @NotNull CountDownLatch> pauses;
    private final @NotNull Map<@NotNull String, @NotNull List<@NotNull Object>> releases;
    private final @NotNull AtomicInteger notModifiedAnswers;

    /**
     * Creates a {@link ReleaseServerScenario} instance and starts the server.
     *
     * @param server the bound server
     */
    private ReleaseServerScenario(@NotNull HttpServer server) {
        this.server             = server;
        this.files              = new ConcurrentHashMap<>();
        this.redirects          = new ConcurrentHashMap<>();
        this.pauses             = new ConcurrentHashMap<>();
        this.releases           = new ConcurrentHashMap<>();
        this.notModifiedAnswers = new AtomicInteger();

        this.server.createContext("/", this::answer);
        this.server.start();
    }

    /**
     * @return the address of the REST API, to create a {@link ReleaseClient} with
     */
    public @NotNull URI getApi() {
        return this.resolve(API_PATH);
    }

    /**
     * @return the number of API answers sent as "not modified", for a conditional request whose entity tag still matched
     */
    public int getNotModifiedAnswerCount() {
        return this.notModifiedAnswers.get();
    }

    /**
     * Publishes a file at {@code /files/<name>}.
     *
     * @param name     the file's name
     * @param contents the contents
     * @return the file's address
     */
    public @NotNull URI addFile(@NotNull String name, byte @NotNull [] contents) {
        this.files.put(FILE_PATH + name, contents);

        return this.resolve(FILE_PATH + name);
    }

    /**
     * Publishes a release without publication time and notes as the latest release of a repository; each release file is served through a
     * redirect.
     *
     * @param repository the repository {@code owner/name}
     * @param tag        the release tag
     * @param assets     the release files by name, in the order the release lists them
     */
    public void addRelease(@NotNull String repository, @NotNull String tag, @NotNull Map<@NotNull String, byte @NotNull []> assets) {
        this.addRelease(repository, tag, null, "", assets);
    }

    /**
     * Publishes a release as the latest release of a repository; each release file is served through a redirect.
     *
     * @param repository  the repository {@code owner/name}
     * @param tag         the release tag
     * @param publishedAt the publication time, or {@code null} to leave it out
     * @param notes       the release notes
     * @param assets      the release files by name, in the order the release lists them
     */
    public void addRelease(@NotNull String repository, @NotNull String tag, @Nullable Instant publishedAt, @NotNull String notes,
                           @NotNull Map<@NotNull String, byte @NotNull []> assets) {
        List<Object> listed = new ArrayList<>();
        Map<String, Object> release = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> asset : assets.entrySet()) {
            String download = Exceptions.format(DOWNLOAD_PATH, repository, tag, asset.getKey());
            String file = FILE_PATH + repository + "/" + tag + "/" + asset.getKey();
            Map<String, Object> entry = new LinkedHashMap<>();

            this.files.put(file, asset.getValue());
            this.redirects.put(download, file);
            entry.put("name", asset.getKey());
            entry.put("size", (long) asset.getValue().length);
            entry.put("browser_download_url", this.resolve(download).toString());
            listed.add(entry);
        }

        release.put("tag_name", tag);
        release.put("draft", false);
        release.put("body", notes);

        if (publishedAt != null) {
            release.put("published_at", publishedAt.toString());
        }

        release.put("assets", listed);
        this.setLatestRelease(repository, JsonWriterUtils.write(release).getBytes(StandardCharsets.UTF_8));

        List<Object> releases = this.releases.computeIfAbsent(repository, name -> new ArrayList<>());

        synchronized (releases) {
            releases.addFirst(release);
            this.setReleases(repository, JsonWriterUtils.write(releases).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Sets the REST API's answer for the latest release of a repository.
     *
     * @param repository the repository {@code owner/name}
     * @param contents   the answer
     */
    public void setLatestRelease(@NotNull String repository, byte @NotNull [] contents) {
        this.files.put(Exceptions.format(LATEST_RELEASE_PATH, repository), contents);
    }

    /**
     * Sets the REST API's answer for the list of the releases of a repository; a release published later replaces it.
     *
     * @param repository the repository {@code owner/name}
     * @param contents   the answer
     */
    public void setReleases(@NotNull String repository, byte @NotNull [] contents) {
        this.files.put(Exceptions.format(RELEASES_PATH, repository), contents);
    }

    /**
     * Publishes a release without publication time and notes with each file and its checksum file {@code <file>.sha256}.
     *
     * @param repository the repository {@code owner/name}
     * @param tag        the release tag
     * @param files      the release files by name
     */
    public void addCheckedRelease(@NotNull String repository, @NotNull String tag, @NotNull Map<@NotNull String, byte @NotNull []> files) {
        this.addCheckedRelease(repository, tag, null, "", files);
    }

    /**
     * Publishes a release with each file and its checksum file {@code <file>.sha256}.
     *
     * @param repository  the repository {@code owner/name}
     * @param tag         the release tag
     * @param publishedAt the publication time, or {@code null} to leave it out
     * @param notes       the release notes
     * @param files       the release files by name
     */
    public void addCheckedRelease(@NotNull String repository, @NotNull String tag, @Nullable Instant publishedAt, @NotNull String notes,
                                  @NotNull Map<@NotNull String, byte @NotNull []> files) {
        Map<String, byte[]> assets = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            assets.put(file.getKey(), file.getValue());
            assets.put(file.getKey() + CHECKSUM_EXTENSION, createChecksum(file.getValue(), file.getKey()));
        }

        this.addRelease(repository, tag, publishedAt, notes, assets);
    }

    /**
     * Pauses the next answer for a release file: the server sends the first half of the file and the rest once the returned latch is
     * counted down.
     *
     * @param name the release file's name
     * @return the latch that resumes the answer
     */
    public @NotNull CountDownLatch pauseFile(@NotNull String name) {
        CountDownLatch resume = new CountDownLatch(1);

        this.pauses.put("/" + name, resume);

        return resume;
    }

    private @NotNull URI resolve(@NotNull String path) {
        return URI.create("http://" + this.server.getAddress().getHostString() + ":" + this.server.getAddress().getPort() + path);
    }

    private void answer(@NotNull HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String target = this.redirects.get(path);
        byte[] contents = this.files.get(path);

        try (exchange) {
            if (target != null) {
                exchange.getResponseHeaders().add(LOCATION_HEADER, this.resolve(target).toString());
                exchange.sendResponseHeaders(FOUND_STATUS, NO_BODY);
            } else if (contents == null) {
                exchange.sendResponseHeaders(NOT_FOUND_STATUS, NO_BODY);
            } else if (path.startsWith(API_PATH) && createEntityTag(contents).equals(exchange.getRequestHeaders().getFirst(IF_NONE_MATCH_HEADER))) {
                this.notModifiedAnswers.incrementAndGet();
                exchange.sendResponseHeaders(NOT_MODIFIED_STATUS, NO_BODY);
            } else {
                CountDownLatch pause = this.takePause(path);

                if (path.startsWith(API_PATH)) {
                    exchange.getResponseHeaders().add(ENTITY_TAG_HEADER, createEntityTag(contents));
                }

                exchange.sendResponseHeaders(OK_STATUS, contents.length == 0 ? NO_BODY : contents.length);

                try (OutputStream output = exchange.getResponseBody()) {
                    if (pause == null) {
                        output.write(contents);
                    } else {
                        output.write(contents, 0, contents.length / HALF);
                        output.flush();
                        awaitResume(pause);
                        output.write(contents, contents.length / HALF, contents.length - contents.length / HALF);
                    }
                }
            }
        }
    }

    private @Nullable CountDownLatch takePause(@NotNull String path) {
        for (Map.Entry<String, CountDownLatch> pause : this.pauses.entrySet()) {
            if (path.endsWith(pause.getKey())) {
                this.pauses.remove(pause.getKey());

                return pause.getValue();
            }
        }

        return null;
    }

    /**
     * Closes this resource, relinquishing any underlying resources: stops the server.
     */
    @Override
    public void close() {
        this.server.stop(0);
    }
}
