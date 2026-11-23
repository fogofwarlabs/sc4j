package org.fogofwarlabs.sc4j.engine.client.host;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.client.render.GpuTimestamps;
import org.fogofwarlabs.sc4j.engine.client.render.GraphicsDevice;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Records optional per-frame phase timing of a client loop, a host diagnostic: each rendered frame writes one CSV row with the CPU and GPU time of
 * every phase. The loop calls {@link #beginFrame()}, {@link #mark(int)} at the end of each phase (phases are numbered in call order, and each
 * mark closes the phase that started at the previous mark) and {@link #endFrame(long)}. The system property {@value #PROPERTY} or the
 * environment variable {@value #ENVIRONMENT_VARIABLE} names the output file ({@link #findConfiguredFile()}); a client without it opens no
 * timing, so disabled timing costs one null check per mark. Presentation only; it never touches simulation state.
 * <p>
 * Each mark also writes a GPU timestamp ({@link GpuTimestamps}); the GPU time of a phase is the distance between its timestamp and the
 * previous one, read {@link #GPU_FRAMES} frames later without waiting, so the {@code gpu*} columns of a row belong to an earlier frame,
 * normally {@link #GPU_FRAMES} rows earlier ({@code -1} when the result was not available). The distances include the GPU's idle time between
 * submissions: while the GPU keeps up, the per-phase split follows the driver's flushes, and only {@code gpuTotalMicroseconds} (first to last
 * timestamp) is a reliable measure. The mark of the last phase (the buffer swap of a usual loop) flushes the device, so that its timestamp is
 * not held back until the next frame's first flush.
 * <p>
 * The opened timing is the active one of the process until it closes: sound output and the simulation add to its current frame through
 * {@link #addSound(long)} and {@link #addSimulationStep()}.
 */
public final class FrameTiming implements AutoCloseable {
    /**
     * The system property that names the output file.
     */
    public static final @NotNull String PROPERTY = "sc4j.frameTiming";
    /**
     * The environment variable that names the output file when the system property is not set.
     */
    public static final @NotNull String ENVIRONMENT_VARIABLE = "SC4J_FRAME_TIMING";
    /**
     * The timestamps one frame may issue: the frame start and the marks.
     */
    public static final int GPU_MARKS = 32;
    /**
     * The frames between issuing the GPU timestamps of a frame and reading them.
     */
    public static final int GPU_FRAMES = 4;

    private static final @NotNull Logger LOGGER = Logger.of(FrameTiming.class);
    private static final int MAXIMUM_RECORDED_FRAMES = 65_536;
    private static final int NANOSECONDS_PER_MICROSECOND = 1000;
    private static final int NANOSECONDS_PER_MILLISECOND = 1_000_000;
    private static final int SPIKE_PHASE_NANOSECONDS = 2_000_000;
    private static final int SUMMARY_PERCENTILE = 99;
    private static final int PERCENT_SCALE = 100;
    private static final int SPIKE_NANOSECONDS = 40_000_000;
    private static final int SPIKE_LIMIT = 40;
    /**
     * The phase recorded for the frame start timestamp, which closes no phase.
     */
    private static final int NO_PHASE = -1;

    private static @Nullable FrameTiming active;

    /**
     * Finds the output file that the system property {@value #PROPERTY} or the environment variable {@value #ENVIRONMENT_VARIABLE} names.
     *
     * @return the named file as given (the caller resolves a relative name), or {@code null} if timing is not requested
     */
    public static @Nullable Path findConfiguredFile() {
        String target = System.getProperty(PROPERTY, System.getenv(ENVIRONMENT_VARIABLE));

        if (target == null || target.isBlank()) {
            return null;
        }

        return Path.of(target);
    }

    /**
     * Creates a {@link FrameTiming} instance that writes its rows to a CSV file and makes it the active timing.
     *
     * @param file       the CSV output file
     * @param phaseNames the names of the phases in mark order, the CSV column names
     * @param device     the device of the loop's rendering context, which creates the GPU timestamps at the first frame
     * @return the created {@link FrameTiming} instance
     * @throws IOException              if the file cannot be created or its header cannot be written
     * @throws IllegalArgumentException if there is no phase or more phases than a frame's timestamps can hold
     */
    public static @NotNull FrameTiming open(@NotNull Path file, @NotNull List<String> phaseNames, @NotNull GraphicsDevice device) throws IOException {
        if (phaseNames.isEmpty() || phaseNames.size() >= GPU_MARKS) {
            throw Exceptions.newIllegalArgumentException("Frame timing needs 1 to {0} phases: {1}", GPU_MARKS - 1, phaseNames.size());
        }

        FrameTiming timing = new FrameTiming(file, phaseNames.toArray(String[]::new), device);

        active = timing;
        LOGGER.info("FRAME TIMING: " + file.toAbsolutePath());

        return timing;
    }

    /**
     * Adds time spent in sound output to the current frame (called from the playback path).
     *
     * @param nanoseconds the playback time to add
     */
    public static void addSound(long nanoseconds) {
        FrameTiming timing = active;

        if (timing != null) {
            timing.soundNanoseconds += nanoseconds;
        }
    }

    /**
     * Counts one completed simulation step in the current frame.
     */
    public static void addSimulationStep() {
        FrameTiming timing = active;

        if (timing != null) {
            timing.simulationSteps++;
        }
    }

    private final @NotNull String @NotNull [] names;
    private final @NotNull GraphicsDevice device;

    private final @NotNull List<GarbageCollectorMXBean> collectors;
    private final long @NotNull [] phaseNanoseconds;
    private final long @NotNull [] totals;

    private final int @NotNull [] @NotNull [] gpuPhases;
    private final int @NotNull [] gpuCounts;
    private final long @NotNull [] gpuPhaseNanoseconds;
    private final long @NotNull [] gpuTimestamps;

    private final @NotNull BufferedWriter writer;

    private long garbageCollectionMilliseconds;
    private int totalCount;
    private long frameStartNanoseconds;
    private long lastMarkNanoseconds;
    private long soundNanoseconds;
    private int simulationSteps;
    private long renderedFrames;
    private int spikes;

    private @Nullable GpuTimestamps gpuTimestampSlots;
    private int gpuSlot;
    private long gpuFrames;
    private long gpuTotalNanoseconds;

    /**
     * Creates a {@link FrameTiming} instance writing its rows to a CSV file.
     *
     * @param path   the CSV output file
     * @param names  the phase names in mark order
     * @param device the device that creates the GPU timestamps
     * @throws IOException if the file cannot be created or its header cannot be written
     */
    private FrameTiming(@NotNull Path path, @NotNull String @NotNull [] names, @NotNull GraphicsDevice device) throws IOException {
        this.names  = names;
        this.device = device;

        this.collectors       = ManagementFactory.getGarbageCollectorMXBeans();
        this.phaseNanoseconds = new long[names.length];
        this.totals           = new long[MAXIMUM_RECORDED_FRAMES];

        this.gpuPhases           = new int[GPU_FRAMES][GPU_MARKS];
        this.gpuCounts           = new int[GPU_FRAMES];
        this.gpuPhaseNanoseconds = new long[names.length];
        this.gpuTimestamps       = new long[GPU_MARKS];

        this.writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII);

        // A header that cannot be written closes the file again.
        try {
            this.writer.write("renderedFrame,gameFrame,totalMicroseconds");

            for (String name : names) {
                this.writer.write("," + name);
            }

            this.writer.write(",simulationSteps,soundMicroseconds,garbageCollectionMilliseconds,gpuTotalMicroseconds");

            for (String name : names) {
                this.writer.write(",gpu_" + name);
            }

            this.writer.write("\n");
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    this.writer::close
            );

            throw exception;
        }

        this.garbageCollectionMilliseconds = this.readCollectionMilliseconds();
        this.totalCount                    = 0;
        this.frameStartNanoseconds         = 0;
        this.lastMarkNanoseconds           = 0;
        this.soundNanoseconds              = 0;
        this.simulationSteps               = 0;
        this.renderedFrames                = 0;
        this.spikes                        = 0;

        this.gpuTimestampSlots   = null;
        this.gpuSlot             = 0;
        this.gpuFrames           = 0;
        this.gpuTotalNanoseconds = 0;
    }


    /**
     * Starts a frame; the device's rendering context must be current.
     */
    public void beginFrame() {
        this.frameStartNanoseconds = System.nanoTime();
        this.lastMarkNanoseconds   = this.frameStartNanoseconds;

        Arrays.fill(this.phaseNanoseconds, 0);
        this.soundNanoseconds = 0;
        this.simulationSteps  = 0;

        this.beginGpuFrame();
    }

    /**
     * Reads the timestamps of the frame that last used the next query slot and issues this frame's start timestamp.
     */
    private void beginGpuFrame() {
        GpuTimestamps timestamps = this.gpuTimestampSlots;

        if (timestamps == null) {
            timestamps             = this.device.createTimestamps(GPU_FRAMES * GPU_MARKS);
            this.gpuTimestampSlots = timestamps;
        }

        this.gpuSlot = (int) (this.gpuFrames++ % GPU_FRAMES);

        Arrays.fill(this.gpuPhaseNanoseconds, 0);
        this.gpuTotalNanoseconds = -1;

        int timestampCount = this.gpuCounts[this.gpuSlot];
        int firstQueryIndex = this.gpuSlot * GPU_MARKS;

        if (timestampCount > 1 && timestamps.isAvailable(firstQueryIndex + timestampCount - 1)) {
            for (int index = 0; index < timestampCount; index++) {
                this.gpuTimestamps[index] = timestamps.read(firstQueryIndex + index);
            }

            for (int index = 1; index < timestampCount; index++) {
                this.gpuPhaseNanoseconds[this.gpuPhases[this.gpuSlot][index]] += this.gpuTimestamps[index] - this.gpuTimestamps[index - 1];
            }

            this.gpuTotalNanoseconds = this.gpuTimestamps[timestampCount - 1] - this.gpuTimestamps[0];
        }

        this.gpuCounts[this.gpuSlot] = 0;

        this.issueGpuTimestamp(NO_PHASE);
    }

    private void issueGpuTimestamp(int phase) {
        int timestampCount = this.gpuCounts[this.gpuSlot];

        GpuTimestamps timestamps = this.gpuTimestampSlots;

        if (timestamps == null || timestampCount >= GPU_MARKS) {
            return;
        }

        timestamps.write(this.gpuSlot * GPU_MARKS + timestampCount);
        this.gpuPhases[this.gpuSlot][timestampCount] = phase;
        this.gpuCounts[this.gpuSlot]                 = timestampCount + 1;
    }

    /**
     * Ends the phase that began at the previous mark; the mark of the last phase also flushes the device.
     *
     * @param phase the phase index, from {@code 0} below the number of phase names
     */
    public void mark(int phase) {
        long now = System.nanoTime();

        this.phaseNanoseconds[phase] += now - this.lastMarkNanoseconds;
        this.lastMarkNanoseconds = now;

        this.issueGpuTimestamp(phase);

        if (phase == this.names.length - 1) {
            this.device.flush();
        }
    }

    /**
     * Writes the frame timing row and records summary samples and slow-phase diagnostics.
     *
     * @param gameFrame the game frame counter
     */
    public void endFrame(long gameFrame) {
        long total = System.nanoTime() - this.frameStartNanoseconds;
        long collection = this.readCollectionMilliseconds();
        long collectionDelta = collection - this.garbageCollectionMilliseconds;

        this.garbageCollectionMilliseconds = collection;

        StringBuilder line = new StringBuilder();

        line.append(this.renderedFrames++).append(',').append(gameFrame).append(',').append(total / NANOSECONDS_PER_MICROSECOND);

        for (long value : this.phaseNanoseconds) {
            line.append(',').append(value / NANOSECONDS_PER_MICROSECOND);
        }

        line.append(',').append(this.simulationSteps).append(',')
            .append(this.soundNanoseconds / NANOSECONDS_PER_MICROSECOND).append(',').append(collectionDelta);
        line.append(',').append(this.gpuTotalNanoseconds < 0 ? -1 : this.gpuTotalNanoseconds / NANOSECONDS_PER_MICROSECOND);

        for (long value : this.gpuPhaseNanoseconds) {
            line.append(',').append(this.gpuTotalNanoseconds < 0 ? -1 : value / NANOSECONDS_PER_MICROSECOND);
        }

        try {
            this.writer.write(line.append('\n').toString());
        } catch (IOException exception) {
            LOGGER.warning("FRAME TIMING: " + exception.getMessage());
        }

        if (this.totalCount < this.totals.length) {
            this.totals[this.totalCount++] = total;
        }

        if (total >= SPIKE_NANOSECONDS && this.spikes++ < SPIKE_LIMIT) {
            StringBuilder spike = new StringBuilder("FRAME SPIKE " + total / NANOSECONDS_PER_MILLISECOND + " ms at game frame " + gameFrame + ":");

            for (int phase = 0; phase < this.names.length; phase++) {
                if (this.phaseNanoseconds[phase] >= SPIKE_PHASE_NANOSECONDS) {
                    spike.append(' ').append(this.names[phase]).append('=').append(this.phaseNanoseconds[phase] / NANOSECONDS_PER_MILLISECOND);
                }
            }

            if (this.soundNanoseconds >= SPIKE_PHASE_NANOSECONDS) {
                spike.append(" sound=").append(this.soundNanoseconds / NANOSECONDS_PER_MILLISECOND);
            }

            if (collectionDelta > 0) {
                spike.append(" gc=").append(collectionDelta);
            }

            LOGGER.info(String.valueOf(spike));
        }
    }

    private long readCollectionMilliseconds() {
        long sum = 0;

        for (GarbageCollectorMXBean collector : this.collectors) {
            sum += Math.max(0, collector.getCollectionTime());
        }

        return sum;
    }

    /**
     * Disposes resources: logs the collected summary, deletes the GPU timestamps, clears the active timing and closes the writer.
     */
    @Override
    public void close() {
        try {
            long[] sorted = Arrays.copyOf(this.totals, this.totalCount);

            Arrays.sort(sorted);

            if (sorted.length > 0) {
                int percentileIndex = Math.min(sorted.length - 1, sorted.length * SUMMARY_PERCENTILE / PERCENT_SCALE);

                LOGGER.info("FRAME TIMING SUMMARY: " + sorted.length + " frames, median " + sorted[sorted.length / 2] / NANOSECONDS_PER_MICROSECOND +
                            " us, p99 " + sorted[percentileIndex] / NANOSECONDS_PER_MICROSECOND +
                            " us, max " + sorted[sorted.length - 1] / NANOSECONDS_PER_MICROSECOND + " us, spikes >= 40 ms " + this.spikes);
            }
        } finally {
            GpuTimestamps timestamps = this.gpuTimestampSlots;

            if (timestamps != null) {
                IgnoreException.execute(
                        timestamps::close
                );

                this.gpuTimestampSlots = null;
            }

            if (active == this) {
                active = null;
            }

            IgnoreException.execute(
                    () -> {
                        try {
                            this.writer.close();
                        } catch (IOException exception) {
                            LOGGER.warning("FRAME TIMING: " + exception.getMessage());
                        }
                    }
            );
        }
    }
}
