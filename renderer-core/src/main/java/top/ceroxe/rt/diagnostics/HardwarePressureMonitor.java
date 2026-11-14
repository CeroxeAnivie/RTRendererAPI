package top.ceroxe.rt.diagnostics;


import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-frequency hardware pressure sampler for separating renderer bottlenecks
 * from unused CPU/GPU capacity.
 *
 * <p>The render thread only requests a sample. CPU counters and the optional
 * {@code nvidia-smi} query run off-thread so diagnostics cannot become the next
 * frame-time problem.</p>
 */
public final class HardwarePressureMonitor {
    /**
     * System property controlling whether hardware-pressure sampling is enabled.
     */
    public static final String ENABLED_PROPERTY = "top.ceroxe.rt.telemetry.hardwarePressure.enabled";
    /**
     * System property defining the minimum interval between sample requests in milliseconds.
     */
    public static final String SAMPLE_INTERVAL_MILLIS_PROPERTY =
            "top.ceroxe.rt.telemetry.hardwarePressure.sampleIntervalMillis";
    /**
     * System property overriding the executable used for NVIDIA telemetry queries.
     */
    public static final String NVIDIA_SMI_COMMAND_PROPERTY =
            "top.ceroxe.rt.telemetry.hardwarePressure.nvidiaSmiCommand";
    /**
     * System property defining the NVIDIA telemetry command timeout in milliseconds.
     */
    public static final String NVIDIA_SMI_TIMEOUT_MILLIS_PROPERTY =
            "top.ceroxe.rt.telemetry.hardwarePressure.nvidiaSmiTimeoutMillis";

    private static final long DEFAULT_SAMPLE_INTERVAL_MILLIS = 1_000L;
    private static final long DEFAULT_NVIDIA_SMI_TIMEOUT_MILLIS = 750L;
    private static final String DEFAULT_NVIDIA_SMI_COMMAND = "nvidia-smi";

    private final com.sun.management.OperatingSystemMXBean operatingSystem;
    private final long sampleIntervalNanos;
    private final String nvidiaSmiCommand;
    private final long nvidiaSmiTimeoutMillis;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>(Snapshot.unavailable("notSampledYet"));
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicLong lastSampleRequestNanos = new AtomicLong(Long.MIN_VALUE);

    /**
     * Creates a monitor configured from the documented system properties.
     */
    public HardwarePressureMonitor() {
        this(
                ManagementFactory.getOperatingSystemMXBean(),
                positiveLongProperty(SAMPLE_INTERVAL_MILLIS_PROPERTY, DEFAULT_SAMPLE_INTERVAL_MILLIS),
                System.getProperty(NVIDIA_SMI_COMMAND_PROPERTY, DEFAULT_NVIDIA_SMI_COMMAND),
                positiveLongProperty(NVIDIA_SMI_TIMEOUT_MILLIS_PROPERTY, DEFAULT_NVIDIA_SMI_TIMEOUT_MILLIS)
        );
    }

    HardwarePressureMonitor(
            java.lang.management.OperatingSystemMXBean operatingSystem,
            long sampleIntervalMillis,
            String nvidiaSmiCommand,
            long nvidiaSmiTimeoutMillis
    ) {
        if (!(Objects.requireNonNull(operatingSystem, "operatingSystem")
                instanceof com.sun.management.OperatingSystemMXBean sunOperatingSystem)) {
            throw new IllegalArgumentException("operatingSystem must expose com.sun.management metrics");
        }
        if (sampleIntervalMillis <= 0L) {
            throw new IllegalArgumentException("sampleIntervalMillis must be positive");
        }
        if (nvidiaSmiTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("nvidiaSmiTimeoutMillis must be positive");
        }
        this.operatingSystem = sunOperatingSystem;
        this.sampleIntervalNanos = TimeUnit.MILLISECONDS.toNanos(sampleIntervalMillis);
        this.nvidiaSmiCommand = nvidiaSmiCommand == null || nvidiaSmiCommand.isBlank()
                ? DEFAULT_NVIDIA_SMI_COMMAND
                : nvidiaSmiCommand.trim();
        this.nvidiaSmiTimeoutMillis = nvidiaSmiTimeoutMillis;
    }

    static GpuSample parseNvidiaSmiCsv(String output) {
        if (output == null || output.isBlank()) {
            return GpuSample.unavailable("nvidiaSmiEmptyOutput");
        }
        String firstLine = output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        String[] columns = firstLine.split(",", -1);
        if (columns.length < 5) {
            return GpuSample.unavailable("nvidiaSmiUnexpectedOutput");
        }
        return new GpuSample(
                true,
                columns[0].trim(),
                parseIntOrMinusOne(columns[1]),
                parseIntOrMinusOne(columns[2]),
                parseIntOrMinusOne(columns[3]),
                parseIntOrMinusOne(columns[4]),
                "ok"
        );
    }

    private static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    private static double sanitizeLoad(double value) {
        return value >= 0.0D && Double.isFinite(value) ? Math.min(1.0D, value) : -1.0D;
    }

    private static int parseIntOrMinusOne(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * Requests a rate-limited asynchronous sample without blocking the calling thread.
     * Concurrent requests coalesce while a sample is in flight.
     */
    public void requestSample() {
        if (!enabled()) {
            latest.set(Snapshot.unavailable("disabled"));
            return;
        }

        long now = System.nanoTime();
        long previous = lastSampleRequestNanos.get();
        if (previous != Long.MIN_VALUE && now - previous < sampleIntervalNanos) {
            return;
        }
        if (!lastSampleRequestNanos.compareAndSet(previous, now) || !sampling.compareAndSet(false, true)) {
            return;
        }

        Thread sampler = new Thread(this::sampleSafely, "RTRenderer-HardwarePressure");
        sampler.setDaemon(true);
        sampler.start();
    }

    /**
     * Returns the most recently completed sample without blocking.
     *
     * @return immutable hardware-pressure snapshot
     */
    public Snapshot snapshot() {
        return latest.get();
    }

    private void sampleSafely() {
        try {
            latest.set(sample());
        } catch (RuntimeException | LinkageError ex) {
            latest.set(Snapshot.unavailable(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            top.ceroxe.rt.renderer.RendererLog.warn("hardware pressure sampling failed", ex);
        } finally {
            sampling.set(false);
        }
    }

    private Snapshot sample() {
        MemorySample memory = sampleMemory();
        GpuSample gpu = sampleGpu();
        return new Snapshot(
                true,
                Instant.now().toEpochMilli(),
                sanitizeLoad(operatingSystem.getCpuLoad()),
                sanitizeLoad(operatingSystem.getProcessCpuLoad()),
                memory.freePhysicalBytes(),
                memory.totalPhysicalBytes(),
                memory.heapUsedBytes(),
                memory.heapCommittedBytes(),
                memory.heapMaxBytes(),
                gpu.name(),
                gpu.utilizationPercent(),
                gpu.memoryUsedMiB(),
                gpu.memoryTotalMiB(),
                gpu.temperatureCelsius(),
                gpu.reason()
        );
    }

    private MemorySample sampleMemory() {
        Runtime runtime = Runtime.getRuntime();
        long heapCommitted = runtime.totalMemory();
        long heapUsed = heapCommitted - runtime.freeMemory();
        return new MemorySample(
                Math.max(0L, operatingSystem.getFreeMemorySize()),
                Math.max(0L, operatingSystem.getTotalMemorySize()),
                Math.max(0L, heapUsed),
                Math.max(0L, heapCommitted),
                Math.max(0L, runtime.maxMemory())
        );
    }

    private GpuSample sampleGpu() {
        GpuSample sample = runNvidiaSmi(nvidiaSmiCommand);
        if (sample.available() || !DEFAULT_NVIDIA_SMI_COMMAND.equals(nvidiaSmiCommand)) {
            return sample;
        }

        String system32NvidiaSmi = System.getenv("SystemRoot");
        if (system32NvidiaSmi == null || system32NvidiaSmi.isBlank()) {
            return sample;
        }
        File fallback = new File(system32NvidiaSmi, "System32\\nvidia-smi.exe");
        if (!fallback.isFile()) {
            return sample;
        }
        return runNvidiaSmi(fallback.getAbsolutePath());
    }

    private GpuSample runNvidiaSmi(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    command,
                    "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                    "--format=csv,noheader,nounits"
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(nvidiaSmiTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return GpuSample.unavailable("nvidiaSmiTimeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return GpuSample.unavailable(output.isBlank() ? "nvidiaSmiExit" + process.exitValue() : output);
            }
            return parseNvidiaSmiCsv(output);
        } catch (IOException ex) {
            return GpuSample.unavailable("nvidiaSmiUnavailable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return GpuSample.unavailable("nvidiaSmiInterrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record MemorySample(
            long freePhysicalBytes,
            long totalPhysicalBytes,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes
    ) {
    }

    /**
     * Result of one optional GPU telemetry query.
     *
     * @param available          whether GPU telemetry was obtained
     * @param name               reported GPU name
     * @param utilizationPercent GPU utilization percentage, or a negative value when unavailable
     * @param memoryUsedMiB      used video memory in MiB, or a negative value when unavailable
     * @param memoryTotalMiB     total video memory in MiB, or a negative value when unavailable
     * @param temperatureCelsius GPU temperature in Celsius, or a negative value when unavailable
     * @param reason             availability or failure reason
     */
    public record GpuSample(
            boolean available,
            String name,
            int utilizationPercent,
            int memoryUsedMiB,
            int memoryTotalMiB,
            int temperatureCelsius,
            String reason
    ) {
        /**
         * Normalizes blank device names and reasons for stable diagnostics.
         *
         * @param available          whether GPU telemetry was obtained
         * @param name               reported GPU name
         * @param utilizationPercent GPU utilization percentage
         * @param memoryUsedMiB      used video memory in MiB
         * @param memoryTotalMiB     total video memory in MiB
         * @param temperatureCelsius GPU temperature in Celsius
         * @param reason             availability or failure reason
         */
        public GpuSample {
            name = name == null || name.isBlank() ? "unknown" : name;
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
        }

        static GpuSample unavailable(String reason) {
            return new GpuSample(false, "unavailable", -1, -1, -1, -1, reason);
        }
    }

    /**
     * Immutable CPU, memory, heap and GPU pressure observation.
     *
     * @param available             whether the sampling pass completed
     * @param sampledEpochMillis    wall-clock sampling time in epoch milliseconds
     * @param systemCpuLoad         normalized system CPU load, or a negative value when unavailable
     * @param processCpuLoad        normalized process CPU load, or a negative value when unavailable
     * @param freePhysicalBytes     free physical memory in bytes
     * @param totalPhysicalBytes    total physical memory in bytes
     * @param heapUsedBytes         used Java heap in bytes
     * @param heapCommittedBytes    committed Java heap in bytes
     * @param heapMaxBytes          maximum Java heap in bytes
     * @param gpuName               reported GPU name
     * @param gpuUtilizationPercent GPU utilization percentage, or a negative value when unavailable
     * @param gpuMemoryUsedMiB      used video memory in MiB, or a negative value when unavailable
     * @param gpuMemoryTotalMiB     total video memory in MiB, or a negative value when unavailable
     * @param gpuTemperatureCelsius GPU temperature in Celsius, or a negative value when unavailable
     * @param reason                availability or failure reason
     */
    public record Snapshot(
            boolean available,
            long sampledEpochMillis,
            double systemCpuLoad,
            double processCpuLoad,
            long freePhysicalBytes,
            long totalPhysicalBytes,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            String gpuName,
            int gpuUtilizationPercent,
            int gpuMemoryUsedMiB,
            int gpuMemoryTotalMiB,
            int gpuTemperatureCelsius,
            String reason
    ) {
        /**
         * Normalizes blank GPU names and reasons for stable diagnostics.
         *
         * @param available             whether the sampling pass completed
         * @param sampledEpochMillis    wall-clock sampling time in epoch milliseconds
         * @param systemCpuLoad         normalized system CPU load
         * @param processCpuLoad        normalized process CPU load
         * @param freePhysicalBytes     free physical memory in bytes
         * @param totalPhysicalBytes    total physical memory in bytes
         * @param heapUsedBytes         used Java heap in bytes
         * @param heapCommittedBytes    committed Java heap in bytes
         * @param heapMaxBytes          maximum Java heap in bytes
         * @param gpuName               reported GPU name
         * @param gpuUtilizationPercent GPU utilization percentage
         * @param gpuMemoryUsedMiB      used video memory in MiB
         * @param gpuMemoryTotalMiB     total video memory in MiB
         * @param gpuTemperatureCelsius GPU temperature in Celsius
         * @param reason                availability or failure reason
         */
        public Snapshot {
            gpuName = gpuName == null || gpuName.isBlank() ? "unknown" : gpuName;
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
        }

        static Snapshot unavailable(String reason) {
            return new Snapshot(
                    false,
                    Instant.now().toEpochMilli(),
                    -1.0D,
                    -1.0D,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "unavailable",
                    -1,
                    -1,
                    -1,
                    -1,
                    reason
            );
        }

        private static String percent(double value) {
            if (value < 0.0D || !Double.isFinite(value)) {
                return "unknown";
            }
            return String.format(Locale.ROOT, "%.1f%%", value * 100.0D);
        }

        private static long bytesToMiB(long value) {
            return value <= 0L ? 0L : value / (1024L * 1024L);
        }

        private static String intOrUnknown(int value) {
            return value < 0 ? "unknown" : Integer.toString(value);
        }

        /**
         * Formats the full sample for structured diagnostic logs.
         *
         * @return stable single-line log fragment
         */
        public String asLogFragment() {
            return "hardwarePressure{available=" + available
                    + ", cpuSystem=" + percent(systemCpuLoad)
                    + ", cpuProcess=" + percent(processCpuLoad)
                    + ", physicalMemoryFreeMiB=" + bytesToMiB(freePhysicalBytes)
                    + ", physicalMemoryTotalMiB=" + bytesToMiB(totalPhysicalBytes)
                    + ", heapMiB=" + bytesToMiB(heapUsedBytes)
                    + "/" + bytesToMiB(heapCommittedBytes)
                    + "/" + bytesToMiB(heapMaxBytes)
                    + ", gpu=" + gpuName
                    + ", gpuUtil=" + intOrUnknown(gpuUtilizationPercent)
                    + ", vramMiB=" + intOrUnknown(gpuMemoryUsedMiB)
                    + "/" + intOrUnknown(gpuMemoryTotalMiB)
                    + ", gpuTempC=" + intOrUnknown(gpuTemperatureCelsius)
                    + (available ? "" : ", reason=" + reason)
                    + "}";
        }

        /**
         * Formats the compact CPU/GPU/VRAM subset suitable for an overlay.
         *
         * @return compact single-line overlay fragment
         */
        public String overlayFragment() {
            return "CPU " + percent(processCpuLoad)
                    + " | GPU " + intOrUnknown(gpuUtilizationPercent)
                    + " | VRAM " + intOrUnknown(gpuMemoryUsedMiB)
                    + "/" + intOrUnknown(gpuMemoryTotalMiB) + " MiB";
        }
    }
}
