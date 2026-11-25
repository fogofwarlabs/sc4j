package org.fogofwarlabs.sc4j.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Names the build outputs that the runtime's tests use.
 */
public final class RuntimeTestResources {
    /**
     * The system property, set by the build for the task {@code distributionTest}, that names the built user package
     * {@code SC4J-<version>-<platform>.zip}.
     */
    public static final @NotNull String DISTRIBUTION_PROPERTY = "sc4j.distribution";

    /**
     * @return the built user package
     * @throws IllegalStateException if the build did not name the package
     */
    public static @NotNull Path getDistribution() {
        String distribution = System.getProperty(DISTRIBUTION_PROPERTY, "");

        if (distribution.isBlank()) {
            throw Exceptions.newIllegalStateException("The distribution tests need the system property {0} of the build.", DISTRIBUTION_PROPERTY);
        }

        return Path.of(distribution);
    }


    private RuntimeTestResources() {
    }
}
