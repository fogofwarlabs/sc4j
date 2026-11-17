package org.fogofwarlabs.sc4j.launcher.i18n;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountProblem;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.BootstrapState;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListProblem;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseProblem;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeEntryProblem;
import org.fogofwarlabs.sc4j.launcher.nodes.NodeListEditor;
import org.fogofwarlabs.sc4j.launcher.nodes.NodePingResult;
import org.fogofwarlabs.sc4j.manifest.settings.*;
import org.fogofwarlabs.sc4j.manifest.text.LocalizedText;
import org.fogofwarlabs.sc4j.manifest.validation.ValidationProblem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * Provides the launcher's texts in one {@link UiLanguage}: the resource bundle {@code messages} of this package (English
 * in the base bundle, other languages in their own bundles), the texts of a mod manifest in the same language, and the
 * localized messages of validation problems, node list problems, node entry problems, node ping results, the launcher peer's status,
 * account problems and release problems.
 * <p>
 * Messages use {@code {index}} placeholders ({@link Exceptions#format(String, Object...)}).
 */
public final class LauncherTexts {
    private static final @NotNull String BUNDLE_NAME = "org.fogofwarlabs.sc4j.launcher.i18n.messages";
    private static final @NotNull String PROBLEM_KEY_PREFIX = "problem.";
    private static final @NotNull String NODE_LIST_PROBLEM_KEY_PREFIX = "nodes.problem.";
    private static final @NotNull String NODE_ENTRY_PROBLEM_KEY_PREFIX = "nodes.entry.";
    private static final @NotNull String NODE_PING_KEY_PREFIX = "nodes.ping.";
    private static final @NotNull String REACHABILITY_KEY_PREFIX = "beaconlink.reachability.";
    private static final @NotNull String BOOTSTRAP_KEY_PREFIX = "beaconlink.bootstrap.";
    private static final @NotNull String ACCOUNT_PROBLEM_KEY_PREFIX = "accounts.problem.";
    private static final @NotNull String RELEASE_PROBLEM_KEY_PREFIX = "release.problem.";
    private static final char PORT_SEPARATOR = ':';
    private static final char IPV6_OPEN = '[';
    private static final char IPV6_CLOSE = ']';
    private static final @NotNull String LIST_SEPARATOR = ", ";
    private static final @NotNull String RANGE_SEPARATOR = " .. ";
    private static final @NotNull String SINGULAR_SUFFIX = ".one";
    private static final @NotNull String PLURAL_SUFFIX = ".other";
    private static final long KILOBYTE = 1024;
    private static final long MEGABYTE = KILOBYTE * KILOBYTE;
    private static final @NotNull String MEGABYTE_NUMBER_FORMAT = "%.1f";

    /**
     * Creates a {@link LauncherTexts} instance for a language.
     *
     * @param language the language of the texts
     * @return the created {@link LauncherTexts} instance
     * @throws MissingResourceException if the launcher's resource bundle is missing
     */
    public static @NotNull LauncherTexts of(@NotNull UiLanguage language) {
        ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, language.getLocale(), LauncherTexts.class.getClassLoader(), control);

        return new LauncherTexts(language, bundle);
    }

    /**
     * Describes a socket address as the launcher shows it: {@code host:port}, an IPv6 host in brackets.
     *
     * @param address the address, or {@code null}
     * @return the text form, or an empty text for {@code null}
     */
    public static @NotNull String describeAddress(@Nullable InetSocketAddress address) {
        if (address == null) {
            return "";
        }

        String host = address.getAddress().getHostAddress();

        return (host.indexOf(PORT_SEPARATOR) >= 0 ? IPV6_OPEN + host + IPV6_CLOSE : host) + PORT_SEPARATOR + address.getPort();
    }

    private static @NotNull String describeRange(@Nullable Integer minimum, @Nullable Integer maximum) {
        return (minimum == null ? "" : minimum.toString()) + RANGE_SEPARATOR + (maximum == null ? "" : maximum.toString());
    }

    private static @NotNull String describePattern(@Nullable Pattern pattern) {
        return pattern == null ? "" : pattern.pattern();
    }


    private final @NotNull UiLanguage language;
    private final @NotNull ResourceBundle bundle;

    /**
     * Creates a {@link LauncherTexts} instance.
     *
     * @param language the language of the texts
     * @param bundle   the resource bundle of the language
     */
    private LauncherTexts(@NotNull UiLanguage language, @NotNull ResourceBundle bundle) {
        this.language = language;
        this.bundle   = bundle;
    }

    /**
     * @return the language of the texts
     */
    public @NotNull UiLanguage getLanguage() {
        return this.language;
    }

    /**
     * @return the locale of the texts, for case conversion and formatting
     */
    public @NotNull Locale getLocale() {
        return this.language.getLocale();
    }

    /**
     * Returns a text.
     *
     * @param key the text key, such as {@code mods.title}
     * @return the text
     * @throws MissingResourceException if no bundle has the key
     */
    public @NotNull String get(@NotNull String key) {
        return this.bundle.getString(key);
    }

    /**
     * Returns a text with its {@code {index}} placeholders replaced.
     *
     * @param key       the text key
     * @param arguments the placeholder arguments
     * @return the formatted text
     * @throws MissingResourceException if no bundle has the key
     */
    public @NotNull String format(@NotNull String key, @Nullable Object @NotNull ... arguments) {
        return Exceptions.format(this.bundle.getString(key), arguments);
    }

    /**
     * Returns a manifest text in this language ({@link LocalizedText#get(String)} with its fallbacks).
     *
     * @param text the manifest text
     * @return the text to show
     */
    public @NotNull String localize(@NotNull LocalizedText text) {
        return text.get(this.language.getCode());
    }

    /**
     * Describes a validation problem of a mod jar, a manifest or a settings value file: the generic message of its
     * {@link ValidationProblem#getCode() code} with the problem's subject.
     *
     * @param problem the problem
     * @return the localized message
     */
    public @NotNull String describeProblem(@NotNull ValidationProblem problem) {
        LocalizedText message = problem.getMessage();

        if (message != null) {
            return this.localize(message);
        }

        return this.format(PROBLEM_KEY_PREFIX + problem.getCode().getCode(), problem.getSubject(), "");
    }

    /**
     * Describes a problem of a setting value: the setting's own localized {@code message} when the manifest has one, the
     * generic message of the problem's code otherwise, naming what the setting's declarative checks expect.
     *
     * @param problem    the problem
     * @param definition the setting the value belongs to
     * @return the localized message
     */
    public @NotNull String describeSettingProblem(@NotNull ValidationProblem problem, @NotNull SettingDefinition definition) {
        LocalizedText message = problem.getMessage();

        if (message != null) {
            return this.localize(message);
        }

        String expectation = switch (definition) {
            case DirectorySetting directory -> String.join(LIST_SEPARATOR, directory.getContainedPaths());
            case FileSetting file -> String.join(LIST_SEPARATOR, file.getExtensions());
            case ChoiceSetting choice -> String.join(LIST_SEPARATOR, choice.getOptions());
            case IntegerSetting integer -> describeRange(integer.getMinimum(), integer.getMaximum());
            case StringSetting string -> describePattern(string.getPattern());
            case BooleanSetting ignored -> "";
        };

        return this.format(PROBLEM_KEY_PREFIX + problem.getCode().getCode(), this.localize(definition.getLabel()), expectation);
    }

    /**
     * Describes why the Beacon.Link node list could not be read: the localized message of the problem's kind (naming the entry number from
     * {@code 1} for a refused entry) followed by the problem's detail text.
     *
     * @param problem the problem
     * @return the localized message
     */
    public @NotNull String describeNodeListProblem(@NotNull NodeListProblem problem) {
        String key = NODE_LIST_PROBLEM_KEY_PREFIX + problem.getKind().name().toLowerCase(Locale.ROOT);

        return this.format(key, problem.getEntryIndex() + 1, problem.getDetail());
    }

    /**
     * Describes why the node editor's fields make no entry: the localized message of the problem, naming the port range or the list size it
     * expects.
     *
     * @param problem the problem
     * @return the localized message
     */
    public @NotNull String describeNodeEntryProblem(@NotNull NodeEntryProblem problem) {
        String key = NODE_ENTRY_PROBLEM_KEY_PREFIX + problem.name().toLowerCase(Locale.ROOT);

        return this.format(key, ConfiguredNode.MINIMUM_PORT, ConfiguredNode.MAXIMUM_PORT, NodeListEditor.MAXIMUM_NODES);
    }

    /**
     * Describes the result of pinging a node: the localized message of its status with the round-trip delay, the node software version and
     * the address the node observed.
     *
     * @param result the ping result
     * @return the localized message
     */
    public @NotNull String describeNodePing(@NotNull NodePingResult result) {
        String key = NODE_PING_KEY_PREFIX + result.getStatus().name().toLowerCase(Locale.ROOT);
        String software = result.getSoftware();

        return this.format(key, result.getRoundTripMillis(), software == null ? "" : software, describeAddress(result.getObservedAddress()));
    }

    /**
     * Describes the result of the launcher node's reachability self-test; a node that is not reachable names its UDP port.
     *
     * @param reachability the result
     * @param port         the UDP port the node listens on
     * @return the localized message
     */
    public @NotNull String describeReachability(@NotNull Reachability reachability, int port) {
        return this.format(REACHABILITY_KEY_PREFIX + reachability.name().toLowerCase(Locale.ROOT), port);
    }

    /**
     * Describes how far the launcher node joined the Beacon.Link network.
     *
     * @param state the state
     * @return the localized message
     */
    public @NotNull String describeBootstrapState(@NotNull BootstrapState state) {
        return this.get(BOOTSTRAP_KEY_PREFIX + state.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Describes why an account action failed, in one short line.
     *
     * @param problem the reason
     * @return the localized message
     */
    public @NotNull String describeAccountProblem(@NotNull AccountProblem problem) {
        return this.get(ACCOUNT_PROBLEM_KEY_PREFIX + problem.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Describes why reading a release or one of its files failed, in one short line.
     *
     * @param problem the reason
     * @return the localized message
     */
    public @NotNull String describeReleaseProblem(@NotNull ReleaseProblem problem) {
        return this.get(RELEASE_PROBLEM_KEY_PREFIX + problem.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Joins texts into one list text, such as file names in a message.
     *
     * @param items the texts
     * @return the texts separated by commas
     */
    public @NotNull String joinList(@NotNull List<@NotNull String> items) {
        return String.join(LIST_SEPARATOR, items);
    }

    /**
     * Formats a number of items with the singular or plural text: the keys {@code <key>.one} and {@code <key>.other}.
     *
     * @param key   the text key without the suffix
     * @param count the number of items, {@code {0}} of the text
     * @return the formatted text
     */
    public @NotNull String formatCount(@NotNull String key, int count) {
        return this.format(key + (count == 1 ? SINGULAR_SUFFIX : PLURAL_SUFFIX), count);
    }

    /**
     * Formats a file size: below one megabyte in whole kilobytes, rounded up, else in megabytes with one decimal (the texts
     * {@code size.kilobytes} and {@code size.megabytes}); a kilobyte is 1024 bytes.
     *
     * @param bytes the size in bytes
     * @return the formatted size, such as {@code 45.6 MB}
     */
    public @NotNull String formatSize(long bytes) {
        if (bytes < MEGABYTE) {
            return this.format("size.kilobytes", Long.toString((bytes + KILOBYTE - 1) / KILOBYTE));
        }

        return this.format("size.megabytes", String.format(this.getLocale(), MEGABYTE_NUMBER_FORMAT, (double) bytes / MEGABYTE));
    }
}
