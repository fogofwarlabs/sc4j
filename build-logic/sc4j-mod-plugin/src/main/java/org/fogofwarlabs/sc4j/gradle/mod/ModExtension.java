package org.fogofwarlabs.sc4j.gradle.mod;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.NotNull;

/**
 * Declares a mod of the SC4J engine: the extension {@code sc4jMod} of the mod plugin {@code org.fogofwarlabs.sc4j.mod}.
 * {@snippet lang = "groovy":
 * plugins {
 *     id 'org.fogofwarlabs.sc4j.mod' version '<engine version>'
 * }
 *}
 * The plugin compiles the source sets with the engine's Java version (a Java 25 toolchain) against the mod API of its own version, the engine
 * modules that the runtime provides and the mod jar does not hold, in whichever project of the build owns each source set, and sets up these
 * tasks of the group {@code sc4j}:
 * <ul>
 *     <li>{@code jar}: packs the classes and resources of the source sets into {@code build/libs/<id>.jar} without file times in a fixed
 *     order, with the licence files in {@code META-INF/}, and checks that its manifest {@code sc4j-mod.json} names the mod id, the
 *     versions below, an entry class of the jar and a class of the jar as the {@code main} of each tool.</li>
 *     <li>{@code run}: runs the mod jar in the engine runtime of the plugin's version, with the user data of the install or of
 *     {@code build/sc4j-run}; {@code --args} passes runtime options (such as {@code --headless}) and {@code --debug-jvm} waits for a
 *     debugger. {@code prepareRun} stages the runtime's native libraries for it.</li>
 *     <li>{@code installMod}: copies the mod jar into {@code mods/} of the install.</li>
 * </ul>
 * The {@code processResources} task of each source set writes {@code version} (the project version) and {@code engine} (the engine API
 * version {@code MAJOR.MINOR} of the plugin's version) into {@code sc4j-mod.json}. A source manifest with either field, without an
 * {@code id}, with another {@code id} than {@link #getId()} when that is set, or without a {@code format} that the engine of the plugin's
 * version reads, fails the build.
 * The variant of the usage {@code sc4j-mod-jar} ({@code modJarElements}) gives the mod jar to other projects of the build.
 */
public abstract class ModExtension {
    /**
     * Creates a {@link ModExtension} instance.
     */
    public ModExtension() {
    }

    /**
     * Returns the mod id: the {@code id} of the manifest and the name of the mod jar ({@code <id>.jar}). When it is not set, the plugin reads
     * it from the source manifest {@code sc4j-mod.json} in the resource folders of the mod's source sets; a manifest that a task writes
     * needs it set.
     *
     * @return the mod id
     */
    public abstract @NotNull Property<@NotNull String> getId();

    /**
     * Returns the source sets whose classes and resources make up the mod jar, with those of the main source set, which the {@code jar} task
     * always holds. The source sets of this project among them compile against the engine API. The default is the main source set.
     *
     * @return the source sets of the mod
     */
    public abstract @NotNull ListProperty<@NotNull SourceSet> getSourceSets();

    /**
     * Returns the SC4J install folder that {@code installMod} copies the mod jar into and whose user data {@code run} uses. The default is
     * the Gradle property {@code sc4j.install}.
     *
     * @return the install folder
     */
    public abstract @NotNull DirectoryProperty getInstall();

    /**
     * Returns the licence files that the mod jar carries in {@code META-INF/}. The default is the files {@code LICENSE} and {@code NOTICE}
     * of the root project folder that exist.
     *
     * @return the licence files
     */
    public abstract @NotNull ConfigurableFileCollection getLicenses();
}
