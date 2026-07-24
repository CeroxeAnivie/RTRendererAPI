package top.ceroxe.mcvulkanrt.renderer.rt;

import jdk.jfr.Recording;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneDatabase;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionGeometryCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMaterialCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-backed GPU-resident throughput gate.
 *
 * <p>The visual stress gates intentionally read pixels back to the CPU, which is
 * useful for correctness but measures PCIe/JVM synchronization more than RTCore
 * throughput. This gate mirrors the UE-style contract used by resident render
 * graphs: verify the scene once with readback, then run the sustained phase with
 * readback disabled and assert completed GPU frames rather than Java screenshots.</p>
 */
public final class RtNativeGpuThroughputSelfTest {
    /* The gate must model the requested 1080p viewport, not a reduced internal proxy. */
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.gpuThroughput.width", 1920);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.gpuThroughput.height", 1080);
    private static final int SECTION_COLUMNS = intProperty("mcvulkanrt.rt.gpuThroughput.sectionColumns", 16);
    private static final int SECTION_ROWS = intProperty("mcvulkanrt.rt.gpuThroughput.sectionRows", 16);
    private static final int TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
    private static final int WARMUP_VALID_FRAMES = intProperty("mcvulkanrt.rt.gpuThroughput.warmupFrames", 240);
    private static final int MEASURED_VALID_FRAMES = intProperty("mcvulkanrt.rt.gpuThroughput.measuredFrames", 1800);
    private static final int MAX_INITIAL_PUMP_FRAMES = intProperty("mcvulkanrt.rt.gpuThroughput.maxInitialPumpFrames", 2400);
    private static final int MIN_GPU_ONLY_COMPLETED_FRAMES =
            intProperty("mcvulkanrt.rt.gpuThroughput.minGpuOnlyCompletedFrames", 512);
    private static final int MAX_DRAIN_PUMP_FRAMES = intProperty(
            "mcvulkanrt.rt.gpuThroughput.maxDrainPumpFrames",
            /* A fast CPU may need many non-blocking polls before one 1080p GPU completion. */
            Math.max(12_000, MIN_GPU_ONLY_COMPLETED_FRAMES * 128)
    );
    /*
     * This hardware gate intentionally owns one fixed contract. Keeping the
     * Average throughput models the requested resident capacity. The independent
     * low-window floor rejects completion stalls without requiring every short
     * scheduling window to equal the long-run throughput target.
     */
    private static final double MIN_COMPLETED_FPS = 1_000.0D;
    private static final double MIN_LOW_COMPLETED_FPS = 500.0D;
    private static final int LOW_FPS_COMPLETION_WINDOW_FRAMES = 8;
    private static final int MC_PRESSURE_MUTATION_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.gpuThroughput.mcMutationPeriodFrames", 8);
    private static final int MC_PRESSURE_MUTATION_SECTIONS =
            intProperty("mcvulkanrt.rt.gpuThroughput.mcMutationSections", 2);
    private static final int BLOCK_STATE_ID = 1;
    private static final int CUTOUT_TEXTURE_SIZE = 8;
    private static final String CUTOUT_TEXTURE = "mcvulkanrt:selftest/throughput_cutout";
    private static final String FIRE_TEXTURE_A = "mcvulkanrt:selftest/throughput_fire_a";
    private static final String FIRE_TEXTURE_B = "mcvulkanrt:selftest/throughput_fire_b";
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-gpu-throughput-diagnostic.png");
    private static final String JFR_PATH_PROPERTY = "mcvulkanrt.rt.gpuThroughput.jfrPath";

    private RtNativeGpuThroughputSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path recordingPath = optionalJfrPath();
        if (recordingPath == null) {
            run();
            return;
        }
        Path parent = recordingPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Recording recording = new Recording()) {
            /*
             * The hardware gate measures GPU completions, but a delayed Java
             * completion poll produces the same low-FPS symptom.  Keep this
             * opt-in so the release gate never pays profiling overhead, while
             * failed runs retain allocation, GC, and monitor evidence.
             */
            recording.enable("jdk.ObjectAllocationSample").withStackTrace();
            recording.enable("jdk.GarbageCollection").withStackTrace();
            recording.enable("jdk.JavaMonitorEnter").withStackTrace();
            recording.enable("jdk.ThreadPark").withStackTrace();
            recording.start();
            try {
                run();
            } finally {
                recording.stop();
                recording.dump(recordingPath);
                System.out.println("RtNativeGpuThroughputSelfTest JFR=" + recordingPath);
            }
        }
    }

    private static void run() throws Exception {
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures())) {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native GPU throughput gate requires production RT hardware: " + capability.summary()
            );

            RtFrameSnapshot diagnostic = runDiagnosticReadback(capability, textures);
            writeSnapshotPng(diagnostic, SNAPSHOT_PATH);
            RtNativeStressGuards.assertFrameNotPathological(diagnostic, "GPU throughput diagnostic scene");

            ThroughputResult result = runGpuOnlyThroughput(capability, textures);
            require(
                    result.averageCompletedFps() >= MIN_COMPLETED_FPS,
                    "GPU-only RT average throughput is below the fixed 1000 fps gate"
                            + ", averageCompletedFps=" + result.averageCompletedFps()
                            + ", minCompletedFps=" + MIN_COMPLETED_FPS
                            + ", minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES
                            + ", completedFrames=" + result.completedFrames()
                            + ", submittedFrames=" + result.submittedFrames()
                            + ", residentPumpFrames=" + result.residentPumpFrames()
                            + ", terrainMutationSections=" + result.terrainMutationSections()
                            + ", pressureFrameUpdates=" + result.pressureFrameUpdates()
                            + ", pressureElapsedMillis=" + result.pressureElapsedNanos() / 1_000_000L
                            + ", elapsedMillis=" + result.elapsedNanos() / 1_000_000L
                            + ", activity=" + result.activity().asLogFragment()
                            + ", readiness=" + result.readiness().asLogFragment()
                            + ", summary=" + result.summary().asLogFragment()
            );
            require(
                    result.lowCompletedFps() >= MIN_LOW_COMPLETED_FPS,
                    "GPU-only RT throughput has a completed-frame low-FPS stall"
                            + ", lowCompletedFps=" + result.lowCompletedFps()
                            + ", minLowCompletedFps=" + MIN_LOW_COMPLETED_FPS
                            + ", lowFpsSampleWindows=" + result.lowFpsSampleWindows()
                            + ", maxCompletionGapMillis=" + result.maxCompletionGapNanos() / 1_000_000.0D
                            + ", completedFrames=" + result.completedFrames()
                            + ", activity=" + result.activity().asLogFragment()
                            + ", readiness=" + result.readiness().asLogFragment()
                            + ", summary=" + result.summary().asLogFragment()
            );

            System.out.println("RtNativeGpuThroughputSelfTest passed: sections=" + TOTAL_SECTIONS
                    + ", output=" + OUTPUT_WIDTH + "x" + OUTPUT_HEIGHT
                    + ", submittedFrames=" + result.submittedFrames()
                    + ", completedFrames=" + result.completedFrames()
                    + ", residentPumpFrames=" + result.residentPumpFrames()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", lowCompletedFps=" + result.lowCompletedFps()
                    + ", lowFpsSampleWindows=" + result.lowFpsSampleWindows()
                    + ", maxCompletionGapMillis=" + result.maxCompletionGapNanos() / 1_000_000.0D
                    + ", terrainMutationSections=" + result.terrainMutationSections()
                    + ", pressureFrameUpdates=" + result.pressureFrameUpdates()
                    + ", pressureElapsedMillis=" + result.pressureElapsedNanos() / 1_000_000L
                    + ", diagnostic=" + diagnostic.asLogFragment()
                    + ", diagnosticPng=" + SNAPSHOT_PATH
                    + ", activity=" + result.activity().asLogFragment()
                    + ", readiness=" + result.readiness().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.throughputScene(
                    "staticDense",
                    OUTPUT_WIDTH,
                    OUTPUT_HEIGHT,
                    result.completedFrames(),
                    result.averageCompletedFps(),
                    result.lowCompletedFps(),
                    result.activity(),
                    result.readiness()
            ));
        }
    }

    private static Path optionalJfrPath() {
        String configured = System.getProperty(JFR_PATH_PROPERTY);
        return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath();
    }

    private static RtFrameSnapshot runDiagnosticReadback(
            VulkanRtCapabilityProbe.Result capability,
            RtTextureCatalog.TestTextureScope textures
    ) throws InterruptedException {
        Map<String, String> previous = installProperties(true);
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            McPressureScene scene = new McPressureScene(textures);
            rtCore.acceptCapability(capability);
            requireReady(rtCore, "diagnostic readback");
            rtCore.acceptFrameUpdate(scene.initialUpdate(frameState(1L)));
            return pumpUntilStrictVisualSnapshot(rtCore, 2L, "GPU throughput diagnostic readback");
        } finally {
            restoreProperties(previous);
        }
    }

    private static ThroughputResult runGpuOnlyThroughput(
            VulkanRtCapabilityProbe.Result capability,
            RtTextureCatalog.TestTextureScope textures
    ) throws InterruptedException {
        Map<String, String> previous = installProperties(false);
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            McPressureScene scene = new McPressureScene(textures);
            rtCore.acceptCapability(capability);
            requireReady(rtCore, "GPU-only throughput");
            rtCore.acceptFrameUpdate(scene.initialUpdate(frameState(1L)));
            pumpUntilReady(rtCore, 2L, "GPU-only throughput initial scene");

            for (int frame = 0; frame < WARMUP_VALID_FRAMES; frame++) {
                rtCore.acceptFrameUpdate(emptyUpdate(10_000L + frame));
            }
            drainPendingFrames(rtCore, 20_000L, "GPU-only throughput warmup drain");

            long pressureStartNanos = System.nanoTime();
            int terrainMutationSections = 0;
            int pressureFrameUpdates = 0;
            for (int frame = 0; frame < MEASURED_VALID_FRAMES; frame++) {
                RendererFrameUpdate update = scene.pressureUpdate(30_000L + frame, frame);
                if (update.hasTerrainChanges()) {
                    terrainMutationSections += update.batch().dirtySectionCount();
                    pressureFrameUpdates++;
                }
                rtCore.acceptFrameUpdate(update);
            }
            drainSceneAndFrames(rtCore, 40_000L, "GPU-only throughput measured drain");
            long pressureElapsedNanos = Math.max(1L, System.nanoTime() - pressureStartNanos);

            long startDispatches = rtCore.runtimeActivity().frameDispatches();
            long startNanos = System.nanoTime();
            CompletionRateTracker completionRates = new CompletionRateTracker(
                    rtCore.runtimeActivity(),
                    startNanos
            );
            int residentPumpFrames = 0;
            long submittedFrames = 0L;
            for (; residentPumpFrames < MAX_DRAIN_PUMP_FRAMES; residentPumpFrames++) {
                rtCore.acceptFrameUpdate(emptyUpdate(60_000L + residentPumpFrames));
                RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
                completionRates.observe(activity, System.nanoTime());
                submittedFrames = activity.frameDispatches() - startDispatches;
                /*
                 * Submission is intentionally allowed to run ahead of a
                 * bounded frame-slot ring. It is not completion evidence:
                 * using it here made a high-resolution run stop with GPU work
                 * still pending, then mislabeled submitted frames as complete.
                 */
                if (completionRates.completedFrames() >= MIN_GPU_ONLY_COMPLETED_FRAMES) {
                    residentPumpFrames++;
                    break;
                }
                requireReady(rtCore, "GPU-only throughput resident frame pump");
            }
            drainPendingFrames(
                    rtCore,
                    70_000L,
                    "GPU-only throughput resident frame drain",
                    completionRates
            );
            long elapsedNanos = Math.max(1L, System.nanoTime() - startNanos);

            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            submittedFrames = finalActivity.frameDispatches() - startDispatches;
            long completedFrames = completionRates.completedFrames();
            require(
                    completedFrames > 0L && submittedFrames > 0L,
                    "GPU-only throughput gate did not submit and complete RT frames"
                            + ", completedFrames=" + completedFrames
                            + ", submittedFrames=" + submittedFrames
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    completedFrames >= MIN_GPU_ONLY_COMPLETED_FRAMES,
                    "GPU-only throughput gate completed too few resident frames for a stable 500 fps measurement"
                            + ", completedFrames=" + completedFrames
                            + ", minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES
                            + ", submittedFrames=" + submittedFrames
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    completionRates.completedFrames() >= MIN_GPU_ONLY_COMPLETED_FRAMES,
                    "GPU-only throughput low-FPS sampling missed completed frames"
                            + ", sampledCompletedFrames=" + completionRates.completedFrames()
                            + ", minGpuOnlyCompletedFrames=" + MIN_GPU_ONLY_COMPLETED_FRAMES
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
            );
            require(
                    finalActivity.frameReadbacks() == 0L,
                    "GPU-only throughput gate must not use CPU frame readbacks"
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    !finalActivity.pendingFrame(),
                    "GPU-only throughput gate ended with uncompleted RT submissions"
                            + ", completedFrames=" + completedFrames
                            + ", submittedFrames=" + submittedFrames
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtCore.GpuFrameTiming gpuFrameTiming = finalActivity.gpuFrameTiming();
            require(
                    gpuFrameTiming.enabled(),
                    "GPU-only throughput gate did not enable Vulkan timestamp instrumentation"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            require(
                    gpuFrameTiming.completedSamples() > 0L,
                    "GPU-only throughput gate did not resolve any Vulkan timestamp samples"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            require(
                    gpuFrameTiming.failedSamples() == 0L,
                    "GPU-only throughput gate observed invalid Vulkan timestamp results"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            require(
                    gpuFrameTiming.averageTraceNanos() > 0L,
                    "GPU-only throughput gate reported no ray-trace GPU duration"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            require(
                    gpuFrameTiming.averageTotalNanos() >= gpuFrameTiming.averageTraceNanos(),
                    "GPU-only throughput gate reported a total GPU duration shorter than ray tracing"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            RtCore.GpuWorkTiming gpuWorkTiming = finalActivity.gpuWorkTiming();
            requireCompletedGpuStage(gpuWorkTiming.sectionBlas(), "sectionBlas", finalActivity);
            requireCompletedGpuStage(gpuWorkTiming.worldTlas(), "worldTlas", finalActivity);
            requireCompletedGpuStage(gpuWorkTiming.materialUpload(), "materialUpload", finalActivity);
            require(
                    terrainMutationSections > 0,
                    "MC pressure throughput did not inject any terrain/material/fluid mutations"
            );
            require(
                    pressureFrameUpdates > 0,
                    "MC pressure throughput did not execute any dynamic terrain/material update frames"
            );
            require(
                    RtSceneReadiness.READY_REASON.equals(rtCore.sceneReadiness().frameDispatchBlockReason(throughputBacklog())),
                    "MC pressure throughput ended with stale TLAS/material state"
                            + ", terrainMutationSections=" + terrainMutationSections
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            refreshBackendSummary(rtCore, 50_000L);
            RtCore.Summary finalSummary = rtCore.summary();
            require(
                    finalActivity.asLogFragment().contains("gpuFrame{enabled=true"),
                    "runtime activity omitted structured GPU frame timing"
                            + ", activity=" + finalActivity.asLogFragment()
            );
            require(
                    finalSummary.asLogFragment().contains("gpuTimestamps{enabled=true"),
                    "backend summary omitted Vulkan timestamp-pool diagnostics"
                            + ", summary=" + finalSummary.asLogFragment()
            );
            return new ThroughputResult(
                    completedFrames,
                    submittedFrames,
                    elapsedNanos,
                    completedFrames * 1_000_000_000.0D / elapsedNanos,
                    completionRates.lowestCompletedFps(),
                    completionRates.windowSamples(),
                    completionRates.maxCompletionGapNanos(),
                    residentPumpFrames,
                    terrainMutationSections,
                    pressureFrameUpdates,
                    pressureElapsedNanos,
                    finalActivity,
                    rtCore.sceneReadiness(),
                    finalSummary
            );
        } finally {
            restoreProperties(previous);
        }
    }

    private static void refreshBackendSummary(GuardedRtCore rtCore, long sequenceBase) {
        for (int frame = 0; frame < 128; frame++) {
            rtCore.acceptFrameUpdate(unavailableEmptyUpdate(sequenceBase + frame));
            requireReady(rtCore, "GPU throughput summary refresh");
        }
    }

    private static final class McPressureScene {
        private final RtTextureCatalog.TestTextureScope textures;
        private final SectionMaterialCache materialCache = new SectionMaterialCache();
        /*
         * The hardware workload already owns packed meshes.  Mirroring the
         * production update loop here is essential: re-meshing encoded voxel
         * snapshots solely to populate an inspection cache manufactures a
         * transient SectionFace object graph that never reaches the RT backend.
         */
        private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
        private final SectionMeshCache meshCache = new SectionMeshCache();

        private McPressureScene(RtTextureCatalog.TestTextureScope textures) {
            this.textures = textures;
        }

        RendererFrameUpdate initialUpdate(RendererFrameState frameState) {
            Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
            Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
            Set<SectionKey> dirtySections = new LinkedHashSet<>();
            Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
            for (int index = 0; index < TOTAL_SECTIONS; index++) {
                SectionKey key = sectionKeyByLinearIndex(index);
                int phase = index;
                dirtySections.add(key);
                dirtyChunks.add(key.chunkKey());
                snapshots.put(key, filledSection(key, phase));
                meshes.put(key, pressureSectionMesh(key, phase));
            }
            SceneUpdateBatch batch = new SceneUpdateBatch(
                    dirtySections,
                    dirtyChunks,
                    Set.of(),
                    Set.of(),
                    snapshots,
                    true,
                    dirtySections.size(),
                    0L,
                    dirtyChunks.size(),
                    dirtyChunks.size(),
                    0L,
                    0L,
                    1L,
                    SceneUpdateBatch.SOURCE_RENDER_DIRTY
                            | SceneUpdateBatch.SOURCE_CHUNK_STREAMING
                            | SceneUpdateBatch.SOURCE_FULL_RESYNC
            );
            return apply(batch, meshes, frameState, throughputLightingScene());
        }

        RendererFrameUpdate pressureUpdate(long sequence, int frame) {
            if (frame % MC_PRESSURE_MUTATION_PERIOD_FRAMES != 0) {
                return emptyUpdate(sequence);
            }
            int mutationOrdinal = frame / MC_PRESSURE_MUTATION_PERIOD_FRAMES;
            int mutationSections = Math.max(1, Math.min(MC_PRESSURE_MUTATION_SECTIONS, TOTAL_SECTIONS));
            Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
            Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
            Set<SectionKey> dirtySections = new LinkedHashSet<>();
            Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
            Map<SectionKey, Integer> sectionSourceFlags = new LinkedHashMap<>();
            boolean streamingLikeBatch = mutationOrdinal % 4 == 0;
            int sourceFlags = SceneUpdateBatch.SOURCE_RENDER_DIRTY | SceneUpdateBatch.SOURCE_BLOCK_MUTATION;
            if (streamingLikeBatch) {
                sourceFlags |= SceneUpdateBatch.SOURCE_CHUNK_STREAMING;
            }
            for (int offset = 0; offset < mutationSections; offset++) {
                int index = Math.floorMod(mutationOrdinal * mutationSections + offset, TOTAL_SECTIONS);
                SectionKey key = sectionKeyByLinearIndex(index);
                int phase = mutationOrdinal + offset + 1;
                dirtySections.add(key);
                dirtyChunks.add(key.chunkKey());
                snapshots.put(key, filledSection(key, phase));
                meshes.put(key, pressureSectionMesh(key, phase));
                sectionSourceFlags.put(key, sourceFlags);
            }
            SceneUpdateBatch batch = new SceneUpdateBatch(
                    dirtySections,
                    dirtyChunks,
                    Set.of(),
                    Set.of(),
                    snapshots,
                    false,
                    mutationSections,
                    mutationSections,
                    streamingLikeBatch ? dirtyChunks.size() : 0L,
                    streamingLikeBatch ? dirtyChunks.size() : 0L,
                    0L,
                    0L,
                    0L,
                    sourceFlags,
                    sectionSourceFlags
            );
            return apply(batch, meshes, frameState(sequence), DynamicRenderScene.empty());
        }

        private RendererFrameUpdate apply(
                SceneUpdateBatch batch,
                Map<SectionKey, SectionTriangleMesh> meshes,
                RendererFrameState frameState,
                DynamicRenderScene dynamicScene
        ) {
            SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
            SectionGeometryCache.ApplyResult geometry = geometryCache.applyProducedFaceCounts(
                    producedFaceCounts(meshes),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            SectionMeshCache.ApplyResult mesh = meshCache.applyPrepared(
                    meshes,
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            require(mesh.trianglesInBatch() > 0, "MC pressure update must submit visible triangles");
            return new RendererFrameUpdate(
                    batch,
                    material,
                    geometry,
                    mesh,
                    frameState,
                    throughputBacklog(),
                    dynamicScene
            );
        }

        private SectionTriangleMesh pressureSectionMesh(SectionKey key, int phase) {
            int pattern = Math.floorMod(key.x() * 31 + key.y() * 17 + phase * 7, 13);
            boolean liquid = pattern == 0 || pattern == 5 || pattern == 9;
            boolean fire = pattern == 3 || pattern == 7;
            boolean cutout = fire || pattern == 4 || pattern == 11;
            int textureId = fire
                    ? textures.textureId((phase & 1) == 0 ? FIRE_TEXTURE_A : FIRE_TEXTURE_B)
                    : (cutout ? textures.textureId(CUTOUT_TEXTURE) : 0);
            int mediumAmount = liquid ? 4 + Math.floorMod(phase, 5) : 0;
            int lightEmission = fire ? 15 : 0;
            int rgb = pressureMapColor(pattern, phase, liquid, fire);
            return sectionQuad(
                    key,
                    mediumAmount,
                    liquid,
                    cutout,
                    textureId,
                    lightEmission,
                    SectionVoxelSnapshot.packMapColorAndLight(rgb, 15, Math.max(lightEmission, liquid ? 0 : 2))
            );
        }
    }

    private static RendererFrameUpdate initialUpdate(
            RtTextureCatalog.TestTextureScope textures,
            RendererFrameState frameState
    ) {
        Map<SectionKey, SectionTriangleMesh> meshes = buildMeshes(textures);
        SceneDatabase database = new SceneDatabase();
        Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();
        for (SectionKey key : meshes.keySet()) {
            sectionsByChunk
                    .computeIfAbsent(key.chunkKey(), ignored -> new ArrayList<>())
                    .add(filledSection(key));
        }
        for (Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
            int minY = entry.getValue().stream()
                    .mapToInt(section -> section.key().y())
                    .min()
                    .orElse(0);
            database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
        }
        SceneUpdateBatch batch = database.drainPendingUpdates();
        SectionMaterialCache.ApplyResult material = new SectionMaterialCache().apply(batch);
        SectionGeometryCache.ApplyResult geometry = SectionGeometryCache.transientProductionStaging()
                .applyProducedFaceCounts(
                producedFaceCounts(meshes),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        SectionMeshCache.ApplyResult meshResult = new SectionMeshCache().applyPrepared(
                meshes,
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        require(meshResult.trianglesInBatch() > 0, "GPU throughput scene must submit visible triangles");
        return new RendererFrameUpdate(
                batch,
                material,
                geometry,
                meshResult,
                frameState,
                throughputBacklog()
        );
    }

    private static Map<SectionKey, SectionTriangleMesh> buildMeshes(RtTextureCatalog.TestTextureScope textures) {
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        int cutoutTextureId = textures.textureId(CUTOUT_TEXTURE);
        for (int y = 0; y < SECTION_ROWS; y++) {
            for (int x = 0; x < SECTION_COLUMNS; x++) {
                SectionKey key = new SectionKey(x, y, 0);
                int pattern = Math.floorMod(x * 31 + y * 17, 11);
                boolean liquid = pattern == 0 || pattern == 5;
                boolean cutout = pattern == 3 || pattern == 7;
                int rgb = switch (pattern % 5) {
                    case 0 -> 0x4C8BE8;
                    case 1 -> 0x58A84B;
                    case 2 -> 0xA0A0A0;
                    case 3 -> 0x6FBF62;
                    default -> 0xD0C070;
                };
                meshes.put(key, sectionQuad(
                        key,
                        liquid ? 8 : 0,
                        liquid,
                        cutout,
                        cutout ? cutoutTextureId : 0,
                        SectionVoxelSnapshot.packMapColorAndLight(rgb, 15, liquid ? 0 : 2)
                ));
            }
        }
        return Map.copyOf(meshes);
    }

    private static Map<SectionKey, Integer> producedFaceCounts(
            Map<SectionKey, SectionTriangleMesh> meshes
    ) {
        Map<SectionKey, Integer> faceCounts = new LinkedHashMap<>(meshes.size());
        for (Map.Entry<SectionKey, SectionTriangleMesh> entry : meshes.entrySet()) {
            faceCounts.put(entry.getKey(), entry.getValue().faceCount());
        }
        return Map.copyOf(faceCounts);
    }

    private static SectionKey sectionKeyByLinearIndex(int index) {
        int wrapped = Math.floorMod(index, TOTAL_SECTIONS);
        return new SectionKey(wrapped % SECTION_COLUMNS, wrapped / SECTION_COLUMNS, 0);
    }

    private static int pressureMapColor(int pattern, int phase, boolean liquid, boolean fire) {
        if (fire) {
            return (phase & 1) == 0 ? 0xF28A24 : 0xFFD45A;
        }
        if (liquid) {
            return switch (Math.floorMod(phase, 3)) {
                case 0 -> 0xD85B22;
                case 1 -> 0x3B7BC0;
                default -> 0x68A850;
            };
        }
        return switch (Math.floorMod(pattern, 5)) {
            case 0 -> 0x4C8BE8;
            case 1 -> 0x58A84B;
            case 2 -> 0xA0A0A0;
            case 3 -> 0x6FBF62;
            default -> 0xD0C070;
        };
    }

    private static SectionTriangleMesh sectionQuad(
            SectionKey key,
            int mediumAmount,
            boolean liquid,
            boolean alphaCutout,
            int textureId,
            int packedMapColorAndLight
    ) {
        return sectionQuad(key, mediumAmount, liquid, alphaCutout, textureId, 0, packedMapColorAndLight);
    }

    private static SectionTriangleMesh sectionQuad(
            SectionKey key,
            int mediumAmount,
            boolean liquid,
            boolean alphaCutout,
            int textureId,
            int lightEmission,
            int packedMapColorAndLight
    ) {
        int flags = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
        if (liquid) {
            flags |= SectionVoxelSnapshot.FLAG_LIQUID;
        }
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(16.0F), fixed(16.0F),
                        fixed(0.0F), fixed(16.0F), fixed(16.0F),
                },
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{BLOCK_STATE_ID},
                new byte[]{(byte) mediumAmount},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()},
                new int[]{packedMapColorAndLight},
                new byte[]{(byte) lightEmission},
                new byte[]{(byte) flags},
                new int[]{textureId},
                new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F)},
                new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F)},
                new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F)},
                new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F)},
                new byte[]{(byte) (alphaCutout ? 1 : 0)},
                new byte[]{(byte) (alphaCutout ? 1 : 0)}
        );
    }

    private static SectionVoxelSnapshot filledSection(SectionKey key) {
        return filledSection(key, 0);
    }

    private static SectionVoxelSnapshot filledSection(SectionKey key, int phase) {
        int pattern = Math.floorMod(key.x() * 31 + key.y() * 17 + phase * 7, 13);
        boolean liquid = pattern == 0 || pattern == 5 || pattern == 9;
        boolean fire = pattern == 3 || pattern == 7;
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] emissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] flags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(ids, BLOCK_STATE_ID);
        Arrays.fill(fluids, (byte) (liquid ? 4 + Math.floorMod(phase, 5) : 0));
        Arrays.fill(mapColors, SectionVoxelSnapshot.packMapColorAndLight(
                pressureMapColor(pattern, phase, liquid, fire),
                15,
                fire ? 15 : 0
        ));
        Arrays.fill(emissions, (byte) (fire ? 15 : 0));
        int blockFlags = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN
                | (liquid ? SectionVoxelSnapshot.FLAG_LIQUID : 0);
        Arrays.fill(flags, (byte) blockFlags);
        return new SectionVoxelSnapshot(key, ids, fluids, mapColors, emissions, flags, false, false);
    }

    private static RtFrameSnapshot pumpUntilStrictVisualSnapshot(
            GuardedRtCore rtCore,
            long minimumSequence,
            String label
    ) throws InterruptedException {
        RtFrameSnapshot snapshot = null;
        long strictReadyMinimumSequence = -1L;
        for (int frame = 0; frame < MAX_INITIAL_PUMP_FRAMES; frame++) {
            long sequence = minimumSequence + frame;
            rtCore.acceptFrameUpdate(emptyUpdate(sequence));
            snapshot = rtCore.latestFrameSnapshot();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            String strictReason = readiness.frameDispatchBlockReason(throughputBacklog());
            if (RtSceneReadiness.READY_REASON.equals(strictReason) && strictReadyMinimumSequence < 0L) {
                strictReadyMinimumSequence = sequence;
            }
            if (strictReadyMinimumSequence >= 0L
                    && snapshot != null
                    && snapshot.frameStateSequence() >= strictReadyMinimumSequence) {
                return snapshot;
            }
            requireReady(rtCore, label);
        }
        throw new AssertionError(label + " did not produce a fresh RT readback"
                + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                + ", strictReadyMinimumSequence=" + strictReadyMinimumSequence
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", backlog=" + throughputBacklog().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static void pumpUntilReady(
            GuardedRtCore rtCore,
            long minimumSequence,
            String label
    ) throws InterruptedException {
        long strictReadyMinimumSequence = -1L;
        for (int frame = 0; frame < MAX_INITIAL_PUMP_FRAMES; frame++) {
            long sequence = minimumSequence + frame;
            rtCore.acceptFrameUpdate(emptyUpdate(sequence));
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            if (RtSceneReadiness.READY_REASON.equals(readiness.frameDispatchBlockReason(throughputBacklog()))
                    && strictReadyMinimumSequence < 0L) {
                strictReadyMinimumSequence = sequence;
            }
            if (strictReadyMinimumSequence >= 0L
                    && activity.latestCompletedFrameStateSequence() >= strictReadyMinimumSequence) {
                return;
            }
            requireReady(rtCore, label);
        }
        throw new AssertionError(label + " did not reach a ready GPU-only RT scene"
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", strictReadyMinimumSequence=" + strictReadyMinimumSequence
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static void drainPendingFrames(
            GuardedRtCore rtCore,
            long sequenceBase,
            String label
    ) {
        drainPendingFrames(rtCore, sequenceBase, label, null);
    }

    private static void drainPendingFrames(
            GuardedRtCore rtCore,
            long sequenceBase,
            String label,
            CompletionRateTracker completionRates
    ) {
        for (int frame = 0; frame < MAX_DRAIN_PUMP_FRAMES; frame++) {
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            if (completionRates != null) {
                completionRates.observe(activity, System.nanoTime());
            }
            if (!activity.pendingFrame()) {
                return;
            }
            rtCore.acceptFrameUpdate(unavailableEmptyUpdate(sequenceBase + frame));
            if (completionRates != null) {
                completionRates.observe(rtCore.runtimeActivity(), System.nanoTime());
            }
            requireReady(rtCore, label);
        }
        throw new AssertionError(label + " did not drain pending GPU frames"
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static void drainSceneAndFrames(
            GuardedRtCore rtCore,
            long sequenceBase,
            String label
    ) {
        long strictReadyMinimumDispatch = -1L;
        for (int frame = 0; frame < MAX_DRAIN_PUMP_FRAMES; frame++) {
            long sequence = sequenceBase + frame;
            RtSceneReadiness readinessBeforePump = rtCore.sceneReadiness();
            RtCore.RuntimeActivity activityBeforePump = rtCore.runtimeActivity();
            boolean sceneReady = RtSceneReadiness.READY_REASON.equals(
                    readinessBeforePump.frameDispatchBlockReason(throughputBacklog())
            );
            if (sceneReady && strictReadyMinimumDispatch < 0L) {
                strictReadyMinimumDispatch = activityBeforePump.frameDispatches() + 1L;
            }
            /*
             * A drain must stop producing full-resolution GPU work.  The measured phase above
             * already exercises continuous 1080p dispatch under terrain mutation; submitting a
             * fresh valid frame for every non-blocking convergence poll can fill the entire frame
             * ring and starve the BLAS work this loop is meant to await.  Pump ownership and fence
             * completion with an unavailable frame until the scene is strict-ready, then submit
             * exactly one valid frame as completed-output proof.
             */
            rtCore.acceptFrameUpdate(sceneReady && activityBeforePump.frameDispatches() < strictReadyMinimumDispatch
                    ? emptyUpdate(sequence)
                    : unavailableEmptyUpdate(sequence));
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            if (strictReadyMinimumDispatch >= 0L
                    && !activity.pendingFrame()
                    && activity.latestCompletedFrameDispatch() >= strictReadyMinimumDispatch) {
                return;
            }
            requireReady(rtCore, label);
        }
        throw new AssertionError(label + " did not drain GPU scene and frame work"
                + ", strictReadyMinimumDispatch=" + strictReadyMinimumDispatch
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static RendererFrameState frameState(long sequence) {
        return new RendererFrameState(
                sequence,
                true,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                SECTION_COLUMNS * 8.0D,
                SECTION_ROWS * 8.0D,
                72.0D,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                -1.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                1.7320508F,
                1.7320508F,
                1.0F,
                0.0F,
                -1.0F,
                0.0F,
                false,
                true
        );
    }

    private static SceneUpdateBatch emptyBatch() {
        return new SceneUpdateBatch(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L
        );
    }

    private static RendererFrameUpdate emptyUpdate(long sequence) {
        return RendererFrameUpdate.empty(emptyBatch(), frameState(sequence), throughputBacklog());
    }

    private static RendererFrameUpdate unavailableEmptyUpdate(long sequence) {
        return RendererFrameUpdate.empty(
                emptyBatch(),
                RendererFrameState.unavailable(sequence),
                throughputBacklog()
        );
    }

    private static DynamicRenderScene throughputLightingScene() {
        float diagonal = 0.70710677F;
        return new DynamicRenderScene(
                1L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DynamicRenderScene.SceneLight(
                        1L,
                        DynamicRenderScene.LightKind.SUN,
                        0.0D,
                        0.0D,
                        0.0D,
                        diagonal,
                        0.0F,
                        diagonal,
                        1.0F,
                        1.0F,
                        0xFFFFFF,
                        true
                ))
        );
    }

    private static RendererUpdateLoop.BacklogSnapshot throughputBacklog() {
        /*
         * The throughput scene bypasses the real MC update loop and injects
         * prepared meshes directly. Keep the same renderable-coverage contract
         * the game path would carry, otherwise the first tiny streaming TLAS can
         * masquerade as a valid diagnostic frame.
         */
        int sectionBudget = Math.max(1, Math.min(TOTAL_SECTIONS, 256));
        return new RendererUpdateLoop.BacklogSnapshot(
                0,
                0,
                0,
                0,
                sectionBudget,
                sectionBudget,
                sectionBudget,
                16,
                Math.max(16, sectionBudget),
                TOTAL_SECTIONS,
                TOTAL_SECTIONS,
                0L
        );
    }

    private static List<RtTextureCatalog.TestTexture> testTextures() {
        return List.of(
                new RtTextureCatalog.TestTexture(
                        CUTOUT_TEXTURE,
                        CUTOUT_TEXTURE_SIZE,
                        CUTOUT_TEXTURE_SIZE,
                        cutoutTexture()
                ),
                new RtTextureCatalog.TestTexture(
                        FIRE_TEXTURE_A,
                        CUTOUT_TEXTURE_SIZE,
                        CUTOUT_TEXTURE_SIZE,
                        fireTexture(0)
                ),
                new RtTextureCatalog.TestTexture(
                        FIRE_TEXTURE_B,
                        CUTOUT_TEXTURE_SIZE,
                        CUTOUT_TEXTURE_SIZE,
                        fireTexture(1)
                )
        );
    }

    private static int[] cutoutTexture() {
        int[] pixels = new int[CUTOUT_TEXTURE_SIZE * CUTOUT_TEXTURE_SIZE];
        for (int y = 0; y < CUTOUT_TEXTURE_SIZE; y++) {
            for (int x = 0; x < CUTOUT_TEXTURE_SIZE; x++) {
                boolean leaf = (x + y) % 3 != 0;
                pixels[y * CUTOUT_TEXTURE_SIZE + x] = leaf
                        ? rgba8(70, 180, 72, 255)
                        : rgba8(0, 0, 0, 0);
            }
        }
        return pixels;
    }

    private static int[] fireTexture(int phase) {
        int[] pixels = new int[CUTOUT_TEXTURE_SIZE * CUTOUT_TEXTURE_SIZE];
        for (int y = 0; y < CUTOUT_TEXTURE_SIZE; y++) {
            for (int x = 0; x < CUTOUT_TEXTURE_SIZE; x++) {
                boolean flame = y >= x / 2 && (x + y + phase) % 4 != 0;
                int warm = Math.min(255, 140 + y * 12 + phase * 20);
                pixels[y * CUTOUT_TEXTURE_SIZE + x] = flame
                        ? rgba8(255, warm, phase == 0 ? 32 : 72, 255)
                        : rgba8(0, 0, 0, 0);
            }
        }
        return pixels;
    }

    private static Map<String, String> installProperties(boolean readbackEnabled) {
        Map<String, String> previous = new LinkedHashMap<>();
        int sectionCapacity = Math.max(1024, TOTAL_SECTIONS * 2);
        set(previous, "mcvulkanrt.rt.output.readback.enabled", Boolean.toString(readbackEnabled));
        set(previous, "mcvulkanrt.rt.output.readback.interval", readbackEnabled ? "1" : "1000000");
        set(previous, "mcvulkanrt.rt.output.dispatchInterval", "1");
        set(previous, "mcvulkanrt.rt.output.externalSemaphore.enabled", "false");
        set(previous, "mcvulkanrt.rt.output.frameResourceRingSize", "24");
        set(previous, "mcvulkanrt.rt.output.maxPendingFrames", "24");
        set(previous, "mcvulkanrt.rt.output.width", Integer.toString(OUTPUT_WIDTH));
        set(previous, "mcvulkanrt.rt.output.height", Integer.toString(OUTPUT_HEIGHT));
        set(previous, "mcvulkanrt.rt.output.maxPixels", Integer.toString(OUTPUT_WIDTH * OUTPUT_HEIGHT));
        set(previous, "mcvulkanrt.rt.worldTlas.minInitialInstances", "1");
        set(previous, "mcvulkanrt.rt.worldTlas.minRebuildIntervalMillis", "0");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingRebuildIntervalMillis", "0");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingRevisionDelta", "1");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingInstanceDelta", "1");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxBuildsPerFrame", "256");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxTrianglesPerFrame", "8000000");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", Integer.toString(sectionCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildBytesInFlight", "1073741824");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingSections", Integer.toString(sectionCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingBytes", "1073741824");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedSections", Integer.toString(sectionCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedBytes", "1073741824");
        return previous;
    }

    private static void requireReady(GuardedRtCore rtCore, String label) {
        require(
                rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                label + " RT core is not ready: state=" + rtCore.state()
                        + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                        + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                        + ", summary=" + rtCore.summary().asLogFragment()
        );
    }

    private static void requireCompletedGpuStage(
            RtCore.GpuStageTiming timing,
            String label,
            RtCore.RuntimeActivity activity
    ) {
        require(
                timing.enabled() && timing.completedSamples() > 0L && timing.averageNanos() > 0L,
                "GPU-only throughput gate did not resolve " + label + " timestamp evidence"
                        + ", activity=" + activity.asLogFragment()
        );
        require(
                timing.failedSamples() == 0L,
                "GPU-only throughput gate observed invalid " + label + " timestamps"
                        + ", activity=" + activity.asLogFragment()
        );
    }

    private static void set(Map<String, String> previous, String name, String value) {
        previous.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private static void restoreProperties(Map<String, String> previousProperties) {
        for (Map.Entry<String, String> entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static int intProperty(String name, int defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double doubleProperty(String name, double defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed > 0.0D ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static void writeSnapshotPng(RtFrameSnapshot snapshot, Path path) throws IOException {
        byte[] rgba = snapshot.copyRgba8();
        BufferedImage image = new BufferedImage(snapshot.width(), snapshot.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < snapshot.height(); y++) {
            for (int x = 0; x < snapshot.width(); x++) {
                int rgba8 = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
                int argb = ((rgba8 >>> 24) & 0xff) << 24
                        | (rgba8 & 0xff) << 16
                        | ((rgba8 >>> 8) & 0xff) << 8
                        | ((rgba8 >>> 16) & 0xff);
                image.setRGB(x, y, argb);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static short fixed(float blockUnits) {
        return (short) Math.round(blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return (red & 0xff)
                | ((green & 0xff) << 8)
                | ((blue & 0xff) << 16)
                | ((alpha & 0xff) << 24);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record ThroughputResult(
            long completedFrames,
            long submittedFrames,
            long elapsedNanos,
            double averageCompletedFps,
            double lowCompletedFps,
            long lowFpsSampleWindows,
            long maxCompletionGapNanos,
            int residentPumpFrames,
            int terrainMutationSections,
            int pressureFrameUpdates,
            long pressureElapsedNanos,
            RtCore.RuntimeActivity activity,
            RtSceneReadiness readiness,
            RtCore.Summary summary
    ) {
    }

    /**
     * Samples actual Vulkan completion progress rather than CPU submission rate.
     *
     * <p>A one-frame host poll interval is not a GPU frame time: the Java
     * thread can be preempted after a completed fence and before the next
     * {@code vkGetFenceStatus}.  The gate instead evaluates the worst
     * continuous completed-frame window used by the smoke contract.  A real
     * queue stall still stretches that window, while a single scheduling gap
     * remains visible separately through {@link #maxCompletionGapNanos()}.</p>
     */
    private static final class CompletionRateTracker {
        private long completedDispatches;
        private long sampleNanos;
        private long completedFrames;
        private long completionWindowStartNanos;
        private long completionWindowFrames;
        private long windowSamples;
        private long maxCompletionGapNanos;
        private double lowestCompletedFps = Double.POSITIVE_INFINITY;

        private CompletionRateTracker(RtCore.RuntimeActivity activity, long sampleNanos) {
            this.completedDispatches = activity.latestCompletedFrameDispatch();
            this.sampleNanos = sampleNanos;
        }

        private void observe(RtCore.RuntimeActivity activity, long nowNanos) {
            long completed = activity.latestCompletedFrameDispatch();
            if (completed < completedDispatches) {
                throw new AssertionError("completed RT frame dispatch ordinal moved backwards");
            }
            long completedDelta = completed - completedDispatches;
            long elapsedNanos = nowNanos - sampleNanos;
            if (completedDelta > 0L && elapsedNanos > 0L) {
                maxCompletionGapNanos = Math.max(maxCompletionGapNanos, elapsedNanos);
                if (completionWindowFrames == 0L) {
                    completionWindowStartNanos = sampleNanos;
                }
                completionWindowFrames += completedDelta;
                completedFrames += completedDelta;
                completedDispatches = completed;
                sampleNanos = nowNanos;
                if (completionWindowFrames >= LOW_FPS_COMPLETION_WINDOW_FRAMES) {
                    long windowElapsedNanos = Math.max(1L, nowNanos - completionWindowStartNanos);
                    double completedFps = completionWindowFrames * 1_000_000_000.0D / windowElapsedNanos;
                    lowestCompletedFps = Math.min(lowestCompletedFps, completedFps);
                    windowSamples++;
                    completionWindowFrames = 0L;
                    completionWindowStartNanos = 0L;
                }
            }
        }

        private long completedFrames() {
            return completedFrames;
        }

        private double lowestCompletedFps() {
            return Double.isFinite(lowestCompletedFps) ? lowestCompletedFps : 0.0D;
        }

        private long windowSamples() {
            return windowSamples;
        }

        private long maxCompletionGapNanos() {
            return maxCompletionGapNanos;
        }
    }
}
