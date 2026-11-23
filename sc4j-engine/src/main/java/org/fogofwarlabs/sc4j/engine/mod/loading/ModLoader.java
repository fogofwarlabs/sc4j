package org.fogofwarlabs.sc4j.engine.mod.loading;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.mod.Mod;
import org.fogofwarlabs.sc4j.engine.mod.ModException;
import org.fogofwarlabs.sc4j.manifest.ModArchive;
import org.fogofwarlabs.sc4j.manifest.ModManifest;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;

/**
 * Creates a mod from its jar: a {@link ModClassLoader} for the jar, then an instance of the manifest's {@code entry} class
 * through its public constructor without arguments. Every failure becomes a {@link ModException} that names the jar and
 * the reason.
 */
public final class ModLoader {
    private final @NotNull ClassLoader parent;

    /**
     * Creates a {@link ModLoader} instance.
     *
     * @param parent the class loader that provides the engine and the runtime's libraries to mods
     */
    public ModLoader(@NotNull ClassLoader parent) {
        this.parent = parent;
    }

    /**
     * Loads the mod of a jar whose manifest has been validated. The entry class must be a concrete class in the mod jar that implements
     * {@link Mod} and has a public constructor without arguments.
     *
     * @param archive the mod jar with its validated manifest
     * @return the created mod with its class loader
     * @throws ModException if the entry class is missing or breaks these rules, or its constructor fails
     */
    public @NotNull LoadedMod load(@NotNull ModArchive archive) throws ModException {
        ModManifest manifest = archive.getManifest();
        ModClassLoader classLoader;

        try {
            classLoader = new ModClassLoader(manifest.getId(), archive.getPath().toUri().toURL(), this.parent);
        } catch (MalformedURLException exception) {
            throw ModException.create(exception, "The mod jar path is not a valid URL: {0}", archive.getPath());
        }

        try {
            return new LoadedMod(this.createMod(classLoader, manifest.getEntry(), archive), classLoader);
        } catch (ModException | RuntimeException | Error exception) {
            IgnoreException.execute(
                    classLoader::close
            );

            throw exception;
        }
    }

    private @NotNull Mod createMod(@NotNull ModClassLoader classLoader, @NotNull String entry, @NotNull ModArchive archive) throws ModException {
        Class<?> entryClass;

        try {
            entryClass = Class.forName(entry, false, classLoader);
        } catch (ClassNotFoundException | LinkageError exception) {
            throw ModException.create(exception, "The entry class {0} of {1} cannot be loaded: {2}", entry, archive.getPath(), exception.toString());
        }

        if (entryClass.getClassLoader() != classLoader) {
            throw ModException.create("The entry class {0} of {1} is not in the mod jar.", entry, archive.getPath());
        }

        if (!Mod.class.isAssignableFrom(entryClass)) {
            throw ModException.create("The entry class {0} of {1} does not implement {2}.", entry, archive.getPath(), Mod.class.getName());
        }

        if (entryClass.isInterface() || Modifier.isAbstract(entryClass.getModifiers())) {
            throw ModException.create("The entry class {0} of {1} is not a concrete class.", entry, archive.getPath());
        }

        Constructor<? extends Mod> constructor;

        try {
            constructor = entryClass.asSubclass(Mod.class).getConstructor();
        } catch (NoSuchMethodException exception) {
            throw ModException.create(exception, "The entry class {0} of {1} has no public constructor without arguments.", entry, archive.getPath());
        }

        try {
            return constructor.newInstance();
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;

            throw ModException.create(cause, "The constructor of the entry class {0} of {1} failed: {2}", entry, archive.getPath(), cause.toString());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw ModException.create(exception, "The entry class {0} of {1} cannot be created: {2}", entry, archive.getPath(), exception.toString());
        }
    }
}
