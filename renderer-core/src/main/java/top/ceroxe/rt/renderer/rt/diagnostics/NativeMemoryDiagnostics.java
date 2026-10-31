package top.ceroxe.rt.renderer.rt.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

/** Opt-in phase attribution for driver/compiler memory outside HotSpot NMT. */
public final class NativeMemoryDiagnostics {
    public static final boolean ENABLED = initialize();
    private static final Scope NOOP = new Scope(null, 0, 0, 0);
    private static final AtomicLong IDS = new AtomicLong();

    private NativeMemoryDiagnostics() { }

    private static boolean initialize() {
        if (!Boolean.getBoolean("top.ceroxe.rt.nativeMemoryDiagnostics")) return false;
        String readyFile = System.getProperty("top.ceroxe.rt.nativeMemoryProbeReady");
        if (readyFile != null) {
            var ready = java.nio.file.Path.of(readyFile);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!java.nio.file.Files.exists(ready)) {
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Native memory observer did not become ready: " + ready);
                try { Thread.sleep(10); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for native memory observer", exception);
                }
            }
        }
        return true;
    }

    private static final class Metrics {
        static final com.sun.management.OperatingSystemMXBean OS =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        static final java.lang.management.MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
        static {
            FlightRecorder.addPeriodicEvent(MemorySample.class,
                    () -> sample("process", "periodic", 0, 0, 0));
        }
    }

    public static Scope begin(String operation, long bytes) {
        if (!ENABLED) return NOOP;
        long id = IDS.incrementAndGet();
        sample(operation, "begin", id, bytes, 0);
        return new Scope(operation, id, bytes, System.nanoTime());
    }

    public static void checkpoint(String operation, long id, long bytes) {
        if (ENABLED) sample(operation, "checkpoint", id, bytes, 0);
    }

    private static void sample(String operation, String phase, long id, long bytes, long elapsedNanos) {
        // Initialize metrics and periodic registration even before the first enabled event.
        var os = Metrics.OS;
        var event = new MemorySample();
        if (!event.isEnabled()) return;
        event.operation = operation;
        event.phase = phase;
        event.id = id;
        event.bytes = bytes;
        event.elapsedNanos = elapsedNanos;
        // On Windows this is process private commit (PagefileUsage), not reserved address space.
        event.processCommit = os.getCommittedVirtualMemorySize();
        var heap = Metrics.MEMORY.getHeapMemoryUsage();
        event.heapUsed = heap.getUsed();
        event.heapCommitted = heap.getCommitted();
        event.nonHeapCommitted = Metrics.MEMORY.getNonHeapMemoryUsage().getCommitted();
        event.commit();
    }

    public static final class Scope implements AutoCloseable {
        private final String operation;
        private final long id, bytes, started;
        private Scope(String operation, long id, long bytes, long started) {
            this.operation = operation; this.id = id; this.bytes = bytes; this.started = started;
        }
        @Override public void close() {
            if (operation != null) sample(operation, "end", id, bytes, System.nanoTime() - started);
        }
    }

    @Name("top.ceroxe.rt.NativeMemory")
    @Label("Renderer native memory phases")
    @Category({"Renderer", "Memory"})
    @Period("1 s")
    @StackTrace(true)
    public static final class MemorySample extends Event {
        public String operation, phase;
        public long id;
        @DataAmount public long bytes;
        @DataAmount public long processCommit;
        @DataAmount public long heapUsed;
        @DataAmount public long heapCommitted;
        @DataAmount public long nonHeapCommitted;
        public long elapsedNanos;
    }
}
