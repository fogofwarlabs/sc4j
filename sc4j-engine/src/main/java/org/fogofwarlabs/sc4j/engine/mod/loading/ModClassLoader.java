package org.fogofwarlabs.sc4j.engine.mod.loading;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Loads the classes and resources of one mod jar. It holds only the mod jar; its parent is the runtime's class loader.
 * <p>
 * The packages of the JDK and of SC4J's engine side (base, manifest, engine, runtime) are protected: they come only from
 * the parent, so a mod that ships a class in one of them neither replaces nor extends it. Every other class and resource
 * is looked up in the mod jar first and then in the parent, which provides the runtime's libraries.
 * <p>
 * The class loader isolates mods from the engine's own types; it is not a security boundary. Mods are code the user
 * installed.
 */
public final class ModClassLoader extends URLClassLoader {
    /**
     * The package prefixes that only the parent provides.
     */
    public static final @NotNull List<@NotNull String> PROTECTED_PACKAGES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.jetbrains.annotations.",
            "org.fogofwarlabs.sc4j.base.",
            "org.fogofwarlabs.sc4j.manifest.",
            "org.fogofwarlabs.sc4j.engine.",
            "org.fogofwarlabs.sc4j.runtime."
    );

    private static final char PACKAGE_SEPARATOR = '.';
    private static final char RESOURCE_SEPARATOR = '/';

    static {
        registerAsParallelCapable();
    }

    /**
     * Checks whether a class belongs to a protected package.
     *
     * @param className the binary class name
     * @return {@code true} if only the parent may provide the class, {@code false} otherwise
     */
    public static boolean isProtected(@NotNull String className) {
        for (String prefix : PROTECTED_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isProtectedResource(@NotNull String resourceName) {
        return isProtected(resourceName.replace(RESOURCE_SEPARATOR, PACKAGE_SEPARATOR));
    }


    /**
     * Creates a {@link ModClassLoader} instance.
     *
     * @param modId  the mod id, used as the class loader name
     * @param jar    the URL of the mod jar
     * @param parent the runtime's class loader
     */
    public ModClassLoader(@NotNull String modId, @NotNull URL jar, @NotNull ClassLoader parent) {
        super("mod:" + modId, new URL[]{jar}, parent);
    }

    /**
     * Loads the class with the specified binary name.
     *
     * @param name    the binary name of the class
     * @param resolve if {@code true} then resolve the class
     * @return the resulting {@code Class} object
     * @throws ClassNotFoundException if the class could not be found
     * @implSpec A protected class is loaded by the parent only; any other class is looked up in the mod jar first and then in
     *         the parent.
     */
    @Override
    protected @NotNull Class<?> loadClass(@NotNull String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this.getClassLoadingLock(name)) {
            Class<?> loaded = this.findLoadedClass(name);

            if (loaded == null) {
                loaded = isProtected(name) ? this.getParent().loadClass(name) : this.loadModFirst(name);
            }

            if (resolve) {
                this.resolveClass(loaded);
            }

            return loaded;
        }
    }

    /**
     * Finds the resource with the given name.
     *
     * @param name the resource name
     * @return a {@code URL} object for reading the resource; {@code null} if the resource could not be found
     * @implSpec A resource in a protected package comes from the parent only; any other resource is looked up in the mod jar
     *         first and then in the parent.
     */
    @Override
    public @Nullable URL getResource(@NotNull String name) {
        if (isProtectedResource(name)) {
            return this.getParent().getResource(name);
        }

        URL found = this.findResource(name);

        return found != null ? found : this.getParent().getResource(name);
    }

    /**
     * Finds all the resources with the given name.
     *
     * @param name the resource name
     * @return an enumeration of {@link URL} objects for the resource
     * @throws IOException if I/O errors occur
     * @implSpec A resource in a protected package comes from the parent only; otherwise the mod jar's resource comes first,
     *         followed by the parent's.
     */
    @Override
    public @NotNull Enumeration<URL> getResources(@NotNull String name) throws IOException {
        if (isProtectedResource(name)) {
            return this.getParent().getResources(name);
        }

        List<URL> resources = new ArrayList<>(Collections.list(this.findResources(name)));

        resources.addAll(Collections.list(this.getParent().getResources(name)));

        return Collections.enumeration(resources);
    }

    private @NotNull Class<?> loadModFirst(@NotNull String name) throws ClassNotFoundException {
        try {
            return this.findClass(name);
        } catch (ClassNotFoundException exception) {
            return this.getParent().loadClass(name);
        }
    }
}
