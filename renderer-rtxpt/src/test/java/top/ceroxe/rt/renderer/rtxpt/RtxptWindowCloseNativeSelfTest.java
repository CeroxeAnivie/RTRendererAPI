package top.ceroxe.rt.renderer.rtxpt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import top.ceroxe.rt.renderer.api.*;

/** One own-window WM_CLOSE regression; run under the external bounded process wrapper. */
public final class RtxptWindowCloseNativeSelfTest {
    private static final String CLOSED = "The original RTXPT renderer is closed";

    @SuppressWarnings("restricted")
    private static final class OwnWindow {
        private static final MethodHandle POST_MESSAGE = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("user32.dll", Arena.global()).find("PostMessageW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        static int close(long window) throws Throwable {
            if (window == 0) throw new AssertionError("Renderer returned a null HWND");
            return (int) POST_MESSAGE.invokeExact(MemorySegment.ofAddress(window), 0x0010, 0L, 0L);
        }
    }

    private static RendererConfig config() {
        var quality = PathTracingQualityOptions.defaults().withPostProcessing(
                new PathTracingQualityOptions.PostProcessing(false, 8, 0, false, 0));
        return RendererConfig.builder().frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled()).denoising(DenoisingOptions.disabled())
                .lowLatency(LowLatencyOptions.disabled()).rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .pathTracing(new PathTracingOptions(1, 1, 0, RendererFeaturePreference.DISABLED,
                        RendererFeaturePreference.DISABLED, RendererFeaturePreference.DISABLED, quality)).build();
    }

    private static String quoted(String value) {
        if (value == null) return "null";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + '"';
    }

    private static Thread watchdog(Path output, AtomicReference<String> phase, AtomicBoolean done, Thread main) {
        return Thread.ofPlatform().daemon(true).name("own-window-close-watchdog").start(() -> {
            try {
                while (!done.get()) {
                    Thread.sleep(30_000);
                    if (done.get()) return;
                    String current = phase.get();
                    System.err.println("WINDOW_CLOSE_WATCHDOG pending phase=" + current + "; external timeout remains authoritative");
                    for (StackTraceElement element : main.getStackTrace()) System.err.println("  at " + element);
                    try {
                        Files.writeString(output.resolve("watchdog.json"),
                                "{\"alarm\":true,\"phase\":" + quoted(current) + "}\n", StandardCharsets.UTF_8);
                    } catch (Exception reportFailure) { System.err.println("Watchdog report: " + reportFailure); }
                }
            } catch (InterruptedException finished) { Thread.currentThread().interrupt(); }
        });
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: RtxptWindowCloseNativeSelfTest <output-directory>");
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output);
        var phase = new AtomicReference<>("opening"); var done = new AtomicBoolean();
        var posted = new AtomicInteger(); var postFailure = new AtomicReference<Throwable>();
        var startPost = new CountDownLatch(1);
        Thread alarm = watchdog(output, phase, done, Thread.currentThread()); Thread poster = null;
        Renderer renderer = null; Throwable problem = null;
        boolean observedClosed = false, closeReturned = false; String closedMessage = null;
        long window = 0, polls = 0, closeNanos = -1;
        try {
            renderer = RtxptRenderers.openHiddenWindow(config());
            RtxptAccess access = renderer.extension(RtxptAccess.class).orElseThrow(
                    () -> new AssertionError("RTXPT public window access missing"));
            access.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            // Exercise shutdown of a valid native scene. A geometry-free scene
            // currently fails in original writeBuffer before reaching this race.
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(91).build())
                    .upsert(MeshAsset.builder(92, new float[]{-1,-1,5, 1,-1,5, 0,1,5},
                            new int[]{0,1,2}, new long[]{91}).build())
                    .upsert(SceneInstance.builder(93,92).build()).build());
            var camera = CameraState.lookAt(0, 0, 4, 0, 0, 0).build();
            for (long frame = 0; frame < 2; frame++) renderer.submit(
                    RenderFrameRequest.builder(frame, 64, 64, camera).minimumSceneRevision(1).build());
            window = access.windowHandle();
            if (window == 0) throw new AssertionError("Hidden renderer has no own HWND");
            final long ownWindow = window;
            poster = Thread.ofPlatform().daemon(true).name("post-own-renderer-close").start(() -> {
                try {
                    startPost.await(); posted.set(OwnWindow.close(ownWindow));
                    if (posted.get() == 0) throw new AssertionError("PostMessageW(WM_CLOSE) returned zero");
                } catch (Throwable failure) { postFailure.set(failure); }
            });
            phase.set("overlay-selection-close-race");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!observedClosed) {
                if (postFailure.get() != null) throw new AssertionError("Own-window close posting failed", postFailure.get());
                try { access.overlaySelection(); polls++; }
                catch (IllegalStateException failure) {
                    if (!CLOSED.equals(failure.getMessage())) throw failure;
                    observedClosed = true; closedMessage = failure.getMessage();
                }
                if (polls >= 4) startPost.countDown();
                if (!observedClosed && System.nanoTime() >= deadline)
                    throw new AssertionError("WM_CLOSE was not observed by repeated public overlaySelection");
            }
            poster.join(2_000);
            if (poster.isAlive() || postFailure.get() != null || posted.get() == 0 || polls < 4)
                throw new AssertionError("Close was not confirmed as this fixture's successful WM_CLOSE", postFailure.get());
            phase.set("renderer-close"); long closeBegin = System.nanoTime();
            renderer.close(); closeNanos = System.nanoTime() - closeBegin; closeReturned = true;
            if (renderer.status() != Renderer.Status.CLOSED) throw new AssertionError("Renderer close returned without CLOSED status");
        } catch (Exception | Error failure) { problem = failure; }
        finally {
            if (poster != null && poster.isAlive()) poster.interrupt();
            if (renderer != null && !closeReturned) {
                phase.set("failure-cleanup-close");
                try { renderer.close(); }
                catch (Exception | Error cleanup) { if (problem == null) problem = cleanup; else problem.addSuppressed(cleanup); }
            }
            done.set(true); alarm.interrupt();
        }
        boolean pass = problem == null && observedClosed && closeReturned && posted.get() != 0;
        Files.writeString(output.resolve("summary.json"), "{\n  \"pass\":" + pass
                + ",\n  \"rendererRuns\":1,\n  \"hidden\":true,\n  \"width\":64,\n  \"height\":64,\n  \"diagnostics\":\"OFF\","
                + "\n  \"frameGeneration\":false,\n  \"superResolution\":false,\n  \"denoising\":false,\n  \"optimizations\":false,"
                + "\n  \"ownHwnd\":" + quoted(Long.toUnsignedString(window)) + ",\n  \"postMessageResult\":" + posted.get()
                + ",\n  \"overlayPollsBeforeClosed\":" + polls + ",\n  \"closedMessage\":" + quoted(closedMessage)
                + ",\n  \"closeReturned\":" + closeReturned + ",\n  \"closeNanos\":" + closeNanos
                + ",\n  \"failure\":" + quoted(problem == null ? null : problem.toString()) + "\n}\n", StandardCharsets.UTF_8);
        if (problem instanceof Exception failure) throw failure;
        if (problem instanceof Error failure) throw failure;
        if (!pass) throw new AssertionError("Window-close regression did not complete");
        System.out.println("WINDOW_CLOSE_NATIVE PASS hidden=true ownWindowOnly=true closedMessage=true closeReturned=true runs=1 report="
                + output.resolve("summary.json"));
    }
}
