package org.fogofwarlabs.sc4j.catalog.github;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads releases of GitHub repositories with the JDK's HTTP client: the latest release of a repository through the GitHub REST API
 * ({@code GET <api>/repos/<owner>/<name>/releases/latest}, which leaves out drafts and pre-releases) with its tag, publication time, notes
 * and files, the list of a repository's releases ({@code GET <api>/repos/<owner>/<name>/releases}), small files such as checksum files and
 * catalogs, and release files. A downloaded file is kept only when its SHA-256 checksum
 * equals the one in the release's checksum file ({@code <file>.sha256}, the {@code sha256sum} output or the bare checksum).
 * <p>
 * Redirects are followed, as GitHub serves release files from another host. Every answer is limited in size, and a download stops when
 * its thread is interrupted.
 */
public final class ReleaseClient {
    /**
     * The address of the GitHub REST API.
     */
    public static final @NotNull URI GITHUB_API = URI.create("https://api.github.com/");

    private static final @NotNull String LATEST_RELEASE_PATH = "repos/{0}/{1}/releases/latest";
    private static final @NotNull String RELEASES_PATH = "repos/{0}/{1}/releases?per_page={2}";
    /**
     * The number of releases {@link #findReleases} reads: one page of the GitHub REST API at its largest size.
     */
    private static final int RELEASE_PAGE_SIZE = 100;
    private static final @NotNull Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final @NotNull Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final @NotNull String USER_AGENT_HEADER = "User-Agent";
    private static final @NotNull String USER_AGENT = "SC4J";
    private static final @NotNull String ACCEPT_HEADER = "Accept";
    private static final @NotNull String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final @NotNull String GITHUB_JSON = "application/vnd.github+json";
    private static final int OK_STATUS = 200;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int MAXIMUM_RELEASE_SIZE = 4 * 1024 * 1024;
    private static final int MAXIMUM_RELEASE_LIST_SIZE = 16 * 1024 * 1024;
    private static final int MAXIMUM_CHECKSUM_SIZE = 4 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final @NotNull String DIGEST_ALGORITHM = "SHA-256";
    private static final @NotNull Pattern CHECKSUM_PATTERN = Pattern.compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{64}(?![0-9A-Fa-f])");
    private static final @NotNull String PARTIAL_SUFFIX = ".part";
    private static final @NotNull String TAG_FIELD = "tag_name";
    private static final @NotNull String PUBLISHED_FIELD = "published_at";
    private static final @NotNull String NOTES_FIELD = "body";
    private static final @NotNull String ASSETS_FIELD = "assets";
    private static final @NotNull String NAME_FIELD = "name";
    private static final @NotNull String ADDRESS_FIELD = "browser_download_url";
    private static final @NotNull String SIZE_FIELD = "size";
    private static final @NotNull String DRAFT_FIELD = "draft";
    private static final @NotNull String PRE_RELEASE_FIELD = "prerelease";

    private static @NotNull Object parseJson(@NotNull ReleaseRepository repository, byte @NotNull [] contents) throws ReleaseException {
        try {
            return JsonReader.parse(contents);
        } catch (IOException exception) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("The release of {0} is not JSON", repository), exception);
        }
    }

    private static @NotNull Release parseRelease(@NotNull ReleaseRepository repository, @Nullable Object parsed) throws ReleaseException {
        if (!(parsed instanceof Map<?, ?> release) || !(release.get(TAG_FIELD) instanceof String tag) ||
            !(release.get(ASSETS_FIELD) instanceof List<?> files)) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("The answer for {0} is not a release", repository));
        }

        List<ReleaseAsset> assets = new ArrayList<>(files.size());

        for (Object file : files) {
            if (file instanceof Map<?, ?> asset && asset.get(NAME_FIELD) instanceof String name && asset.get(ADDRESS_FIELD) instanceof String address) {
                try {
                    assets.add(new ReleaseAsset(name, URI.create(address), asset.get(SIZE_FIELD) instanceof Long size ? size : 0));
                } catch (IllegalArgumentException exception) {
                    throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("Invalid address of {0}", name), exception);
                }
            }
        }

        return new Release(repository, tag, parseTime(release.get(PUBLISHED_FIELD)), release.get(NOTES_FIELD) instanceof String notes ? notes : "",
                           assets);
    }

    private static @Nullable Instant parseTime(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return null;
        }

        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static @NotNull String parseChecksum(@NotNull ReleaseAsset checksum, byte @NotNull [] contents) throws ReleaseException {
        Matcher matcher = CHECKSUM_PATTERN.matcher(new String(contents, StandardCharsets.US_ASCII));

        if (!matcher.find()) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("{0} holds no SHA-256 checksum", checksum.getName()));
        }

        return matcher.group().toLowerCase(Locale.ROOT);
    }

    private static @NotNull MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The Java runtime has no {0}.", DIGEST_ALGORITHM);
        }
    }


    private final @NotNull URI api;
    private final @NotNull HttpClient http;

    /**
     * Creates a {@link ReleaseClient} instance.
     *
     * @param api the address of the GitHub REST API, normally {@link #GITHUB_API}, ending with {@code /}
     */
    public ReleaseClient(@NotNull URI api) {
        this.api  = api;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * Reads the latest release of a repository.
     *
     * @param repository the repository
     * @return the release
     * @throws ReleaseException if the repository has no release, GitHub cannot be reached or its answer is not a release
     */
    public @NotNull Release findLatestRelease(@NotNull ReleaseRepository repository) throws ReleaseException {
        URI address = this.api.resolve(Exceptions.format(LATEST_RELEASE_PATH, repository.getOwner(), repository.getName()));

        return parseRelease(repository, parseJson(repository, this.read(address, GITHUB_JSON, MAXIMUM_RELEASE_SIZE)));
    }

    /**
     * Reads the releases of a repository, newest first as GitHub lists them, without drafts and pre-releases; the first one is the latest
     * release ({@link #findLatestRelease}). Only the first page of the list is read, at most 100 releases.
     *
     * @param repository the repository
     * @return the releases, newest first; empty when the repository has none
     * @throws ReleaseException if the repository does not exist, GitHub cannot be reached or its answer is not a list of releases
     */
    public @NotNull List<@NotNull Release> findReleases(@NotNull ReleaseRepository repository) throws ReleaseException {
        URI address = this.api.resolve(Exceptions.format(RELEASES_PATH, repository.getOwner(), repository.getName(), RELEASE_PAGE_SIZE));

        if (!(parseJson(repository, this.read(address, GITHUB_JSON, MAXIMUM_RELEASE_LIST_SIZE)) instanceof List<?> listed)) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("The answer for {0} is not a list of releases", repository));
        }

        List<Release> releases = new ArrayList<>(listed.size());

        for (Object release : listed) {
            if (release instanceof Map<?, ?> fields && (Boolean.TRUE.equals(fields.get(DRAFT_FIELD)) || Boolean.TRUE.equals(fields.get(PRE_RELEASE_FIELD)))) {
                continue;
            }

            releases.add(parseRelease(repository, release));
        }

        return releases;
    }

    /**
     * Reads a small file.
     *
     * @param address     the file's address
     * @param maximumSize the largest size read, in bytes
     * @return the file contents
     * @throws ReleaseException if the file does not exist, cannot be read or is larger than the limit
     */
    public byte @NotNull [] read(@NotNull URI address, int maximumSize) throws ReleaseException {
        return this.read(address, null, maximumSize);
    }

    /**
     * Downloads a release file and keeps it only when its SHA-256 checksum equals the one of its checksum file. The file is written next to
     * the target first and replaces the target once it is checked; the folder is created when missing.
     *
     * @param file        the release file
     * @param checksum    the release's checksum file of the file
     * @param target      the file to write
     * @param maximumSize the largest size downloaded, in bytes
     * @param progress    receives the share downloaded, from {@code 0} to {@code 1}, on the downloading thread
     * @throws ReleaseException if a file cannot be downloaded, is too large or does not match the checksum
     * @throws IOException      if the target cannot be written
     */
    public void download(@NotNull ReleaseAsset file, @NotNull ReleaseAsset checksum, @NotNull Path target, long maximumSize,
                         @NotNull DoubleConsumer progress) throws IOException {
        String expected = parseChecksum(checksum, this.read(checksum.getAddress(), MAXIMUM_CHECKSUM_SIZE));
        Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);

        Files.createDirectories(partial.toAbsolutePath().getParent());

        HttpResponse<InputStream> response = this.send(file.getAddress(), null, HttpResponse.BodyHandlers.ofInputStream());
        long total = file.getSize() > 0 ? file.getSize() : response.headers().firstValueAsLong(CONTENT_LENGTH_HEADER).orElse(0);
        MessageDigest digest = createDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;

        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial)) {
            int read = input.read(buffer);

            while (read >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("The download was stopped.");
                }

                written += read;

                if (written > maximumSize) {
                    throw new ReleaseException(ReleaseProblem.TOO_LARGE, Exceptions.format("{0} is larger than {1} bytes", file.getName(), maximumSize));
                }

                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);

                if (total > 0) {
                    progress.accept(Math.min(1.0, (double) written / total));
                }

                read = input.read(buffer);
            }
        } catch (ReleaseException exception) {
            Files.deleteIfExists(partial);

            throw exception;
        } catch (IOException exception) {
            Files.deleteIfExists(partial);

            throw new ReleaseException(ReleaseProblem.UNAVAILABLE, Exceptions.format("Cannot download {0}: {1}", file.getName(), exception.getMessage()),
                                       exception);
        }

        String actual = HexFormat.of().formatHex(digest.digest());

        if (!actual.equals(expected)) {
            Files.deleteIfExists(partial);

            throw new ReleaseException(ReleaseProblem.CHECKSUM_MISMATCH,
                                       Exceptions.format("{0} has the checksum {1}, expected {2}", file.getName(), actual, expected));
        }

        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private byte @NotNull [] read(@NotNull URI address, @Nullable String accept, int maximumSize) throws ReleaseException {
        HttpResponse<InputStream> response = this.send(address, accept, HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream input = response.body()) {
            byte[] contents = input.readNBytes(maximumSize + 1);

            if (contents.length > maximumSize) {
                throw new ReleaseException(ReleaseProblem.TOO_LARGE, Exceptions.format("{0} is larger than {1} bytes", address, maximumSize));
            }

            return contents;
        } catch (ReleaseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ReleaseException(ReleaseProblem.UNAVAILABLE, Exceptions.format("Cannot read {0}: {1}", address, exception.getMessage()), exception);
        }
    }

    private <T> @NotNull HttpResponse<T> send(@NotNull URI address, @Nullable String accept,
                                              @NotNull HttpResponse.BodyHandler<T> body) throws ReleaseException {
        HttpResponse<T> response;

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(address).timeout(RESPONSE_TIMEOUT).header(USER_AGENT_HEADER, USER_AGENT).GET();

            if (accept != null) {
                request.header(ACCEPT_HEADER, accept);
            }

            response = this.http.send(request.build(), body);
        } catch (IllegalArgumentException exception) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("Invalid address {0}", address), exception);
        } catch (IOException exception) {
            throw new ReleaseException(ReleaseProblem.UNAVAILABLE, Exceptions.format("Cannot reach {0}: {1}", address, exception.getMessage()), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new ReleaseException(ReleaseProblem.UNAVAILABLE, Exceptions.format("Interrupted while reaching {0}", address), exception);
        }

        if (response.statusCode() == OK_STATUS) {
            return response;
        }

        if (response.body() instanceof InputStream input) {
            try {
                input.close();
            } catch (IOException exception) {
                // The answer is refused anyway.
            }
        }

        if (response.statusCode() == NOT_FOUND_STATUS) {
            throw new ReleaseException(ReleaseProblem.NOT_FOUND, Exceptions.format("{0} was not found", address));
        }

        throw new ReleaseException(ReleaseProblem.UNAVAILABLE, Exceptions.format("{0} answered with status {1}", address, response.statusCode()));
    }
}
