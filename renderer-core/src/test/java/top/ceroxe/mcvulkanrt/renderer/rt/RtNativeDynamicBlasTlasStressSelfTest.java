package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.CameraRayMath;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-backed MC-outside gate for the dynamic BLAS/TLAS path.
 *
 * <p>The important invariant is stricter than "eventually correct": after a
 * positive dynamic scene revision, the first completed RT frame for that frame
 * sequence must already use the matching dynamic BLAS inside the bound world
 * TLAS. A stale TLAS is allowed to remain alive for resource lifetime protection,
 * but it must not be dispatched as a new visible frame.</p>
 */
public final class RtNativeDynamicBlasTlasStressSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.dynamicBlasStress.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.dynamicBlasStress.height", 540);
    private static final int PRIMITIVE_COUNT =
            intProperty("mcvulkanrt.rt.dynamicBlasStress.primitiveCount", 160);
    private static final int REPLACEMENT_CYCLES =
            intProperty("mcvulkanrt.rt.dynamicBlasStress.replacementCycles", 32);
    private static final int MAX_FRESH_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicBlasStress.maxFreshPumpFrames", 1200);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.dynamicBlasStress.readbackSampleInterval", 1);
    private static final int MIN_DISTINCT_CHECKSUMS =
            intProperty("mcvulkanrt.rt.dynamicBlasStress.minDistinctChecksums", 12);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.dynamicBlasStress.pumpSleepMillis", 4L);
    private static final int BLOCK_STATE_ID = 1;
    private static final int SENTINEL_COUNT = 3;
    private static final int MINECRAFT_LAYOUT_CHURN_INSTANCES = 58;
    private static final int SAMPLE_RADIUS = 5;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-dynamic-blas-tlas-stress.png");

    private RtNativeDynamicBlasTlasStressSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installStressProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native dynamic BLAS/TLAS stress requires production RT hardware: " + capability.summary()
            );

            DynamicBlasStressResult result = runDynamicBlasTlasStress(capability);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeDynamicBlasTlasStressSelfTest passed: primitiveCount=" + PRIMITIVE_COUNT
                    + ", replacementCycles=" + REPLACEMENT_CYCLES
                    + ", dynamicUpdates=" + result.dynamicUpdates()
                    + ", completedFrames=" + result.completedFrames()
                    + ", freshCycleSnapshots=" + result.freshCycleSnapshots()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", activity=" + result.activity().asLogFragment()
                    + ", readiness=" + result.readiness().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene(
                    "dynamicBlasTlas",
                    OUTPUT_WIDTH,
                    OUTPUT_HEIGHT,
                    result.completedFrames(),
                    result.averageCompletedFps(),
                    result.activity(),
                    result.readiness()
            ));
        } finally {
            restoreProperties(previousProperties);
        }
    }

    private static DynamicBlasStressResult runDynamicBlasTlasStress(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic BLAS/TLAS stress: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            RendererFrameState baseFrameState = frameState(1L);
            List<PrimitiveAnchor> anchors = primitiveAnchors(baseFrameState, PRIMITIVE_COUNT);
            rtCore.acceptFrameUpdate(initialUpdate(
                    terrainDepthAnchor(new SectionKey(0, 0, 0)),
                    baseFrameState,
                    dynamicPrimitiveScene(1L, anchors, Variant.WARM, 0)
            ));

            RtFrameSnapshot warmSnapshot = pumpUntilVariantFrame(
                    rtCore,
                    2L,
                    1L,
                    Variant.WARM,
                    "initial dynamic BLAS warm scene"
            );
            RtNativeStressGuards.assertFrameNotPathological(warmSnapshot, "initial dynamic BLAS warm scene");

            long phaseStartNanos = System.nanoTime();
            /*
             * Frame-state sequence is a Minecraft payload identifier and may
             * intentionally jump during this stress test.  Dispatch ordinal is
             * the only monotonic unit that represents one completed GPU frame.
             */
            long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
            long completedFrames = 0L;
            long freshCycleSnapshots = 0L;
            int dynamicUpdates = 1;
            Set<Long> checksums = new HashSet<>();
            checksums.add(warmSnapshot.checksum());
            RtFrameSnapshot lastSnapshot = warmSnapshot;

            for (int cycle = 0; cycle < REPLACEMENT_CYCLES; cycle++) {
                Variant variant = (cycle & 1) == 0 ? Variant.COOL : Variant.WARM;
                long sequence = 10_000L + cycle * 100L;
                List<PrimitiveAnchor> cycleAnchors = (cycle & 1) == 0
                        ? anchors.subList(0, Math.min(anchors.size(), MINECRAFT_LAYOUT_CHURN_INSTANCES))
                        : anchors;
                rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                        emptyBatch(),
                        frameState(sequence),
                        RendererUpdateLoop.BacklogSnapshot.empty(),
                        dynamicPrimitiveScene(2L + dynamicUpdates, cycleAnchors, variant, cycle + 1)
                ));
                dynamicUpdates++;

                RtFrameSnapshot snapshot = pumpUntilVariantFrame(
                        rtCore,
                        sequence,
                        2L + dynamicUpdates - 1L,
                        variant,
                        "replacement cycle " + cycle
                );
                RtNativeStressGuards.assertFrameNotPathological(snapshot, "replacement cycle " + cycle);
                checksums.add(snapshot.checksum());
                lastSnapshot = snapshot;
                freshCycleSnapshots++;

                RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
                long latestCompletedDispatch = activity.latestCompletedFrameDispatch();
                if (latestCompletedDispatch > lastCompletedDispatch) {
                    completedFrames += latestCompletedDispatch - lastCompletedDispatch;
                    lastCompletedDispatch = latestCompletedDispatch;
                }
            }

            DynamicRenderScene clearScene = new DynamicRenderScene(
                    50_000L + dynamicUpdates,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                    emptyBatch(),
                    frameState(50_000L),
                    RendererUpdateLoop.BacklogSnapshot.empty(),
                    clearScene
            ));
            RtFrameSnapshot clearSnapshot = pumpUntilClearFrame(
                    rtCore,
                    50_000L,
                    clearScene.revision(),
                    "dynamic BLAS clear"
            );
            RtNativeStressGuards.assertFrameNotPathological(clearSnapshot, "dynamic BLAS clear terrain-only frame");
            checksums.add(clearSnapshot.checksum());
            lastSnapshot = clearSnapshot;
            RtCore.RuntimeActivity clearActivity = rtCore.runtimeActivity();
            long latestCompletedDispatch = clearActivity.latestCompletedFrameDispatch();
            if (latestCompletedDispatch > lastCompletedDispatch) {
                completedFrames += latestCompletedDispatch - lastCompletedDispatch;
                lastCompletedDispatch = latestCompletedDispatch;
            }

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            double averageCompletedFps = completedFrames * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= 15.0D,
                    "native dynamic BLAS/TLAS stress completed frames below 15 fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", completedFrames=" + completedFrames
                            + ", freshCycleSnapshots=" + freshCycleSnapshots
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    checksums.size() >= MIN_DISTINCT_CHECKSUMS,
                    "dynamic BLAS/TLAS stress did not produce enough distinct completed frames"
                            + ", distinctChecksums=" + checksums.size()
                            + ", expectedAtLeast=" + MIN_DISTINCT_CHECKSUMS
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            rtCore.refreshDiagnosticSummary();
            String summary = rtCore.summary().asLogFragment();
            require(
                    sumSummaryLong(summary, "submittedBuilds") == 1L,
                    "legacy MODEL replacements must reuse one stable procedural BLAS"
                            + ", dynamicUpdates=" + dynamicUpdates
                            + ", summary=" + summary
            );
            require(
                    sumSummaryLong(summary, "completedBuilds") == 1L,
                    "stable procedural BLAS must complete exactly once under replacement stress; summary=" + summary
            );
            require(
                    sumSummaryLong(summary, "clears") > 0L,
                    "dynamic BLAS clear was not observed by the cache; summary=" + summary
            );
            require(
                    freshCycleSnapshots == REPLACEMENT_CYCLES,
                    "every dynamic replacement must produce a matching completed RT frame"
                            + ", freshCycleSnapshots=" + freshCycleSnapshots
                            + ", replacementCycles=" + REPLACEMENT_CYCLES
                            + ", summary=" + summary
            );
            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            requireGpuStage(finalActivity.gpuWorkTiming().dynamicBlas(), "dynamicBlas", finalActivity);
            requireGpuStage(finalActivity.gpuWorkTiming().dynamicTlas(), "dynamicTlas", finalActivity);
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native dynamic BLAS/TLAS stress");
            return new DynamicBlasStressResult(
                    lastSnapshot,
                    rtCore.sceneReadiness(),
                    finalActivity,
                    dynamicUpdates,
                    completedFrames,
                    freshCycleSnapshots,
                    averageCompletedFps,
                    checksums.size()
            );
        }
    }

    private static void requireGpuStage(
            RtCore.GpuStageTiming timing,
            String label,
            RtCore.RuntimeActivity activity
    ) {
        require(
                timing.enabled() && timing.completedSamples() > 0L && timing.averageNanos() > 0L,
                "dynamic BLAS/TLAS stress did not resolve " + label + " GPU timing"
                        + ", activity=" + activity.asLogFragment()
        );
        require(
                timing.failedSamples() == 0L,
                "dynamic BLAS/TLAS stress observed invalid " + label + " GPU timing"
                        + ", activity=" + activity.asLogFragment()
        );
    }

    private static RendererFrameUpdate initialUpdate(
            SectionTriangleMesh mesh,
            RendererFrameState frameState,
            DynamicRenderScene dynamicScene
    ) {
        SectionKey key = mesh.key();
        SceneDatabase database = new SceneDatabase();
        SectionMaterialCache materialCache = new SectionMaterialCache();
        SectionGeometryCache geometryCache = new SectionGeometryCache();
        SectionMeshCache meshCache = new SectionMeshCache();
        database.replaceChunkSnapshot(new ChunkSnapshot(key.chunkKey(), key.y(), List.of(filledSection(key))));

        SceneUpdateBatch batch = database.drainPendingUpdates();
        SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
        SectionGeometryCache.ApplyResult geometry = geometryCache.apply(
                material.encodedSections(),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(
                Map.of(key, mesh),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        require(meshResult.trianglesInBatch() > 0, "dynamic BLAS stress terrain anchor must submit visible triangles");
        return new RendererFrameUpdate(
                batch,
                material,
                geometry,
                meshResult,
                frameState,
                RendererUpdateLoop.BacklogSnapshot.empty(),
                dynamicScene
        );
    }

    private static RtFrameSnapshot pumpUntilVariantFrame(
            GuardedRtCore rtCore,
            long minimumSnapshotSequence,
            long minimumDynamicSceneRevision,
            Variant expectedVariant,
            String label
    ) throws InterruptedException {
        for (int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; frame++) {
            long sequence = minimumSnapshotSequence + 1L + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null
                    && snapshot.frameStateSequence() >= minimumSnapshotSequence
                    && snapshot.boundTlasDynamicSceneRevision() >= minimumDynamicSceneRevision) {
                try {
                    assertVariantSentinels(snapshot, expectedVariant, label);
                } catch (AssertionError ex) {
                    writeFailureSnapshot(snapshot, label, ex);
                    throw ex;
                }
                return snapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while pumping " + label + ": state=" + rtCore.state()
                            + ", lastSnapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not produce a fresh dynamic BLAS/TLAS snapshot"
                + ", minimumSequence=" + minimumSnapshotSequence
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static RtFrameSnapshot pumpUntilClearFrame(
            GuardedRtCore rtCore,
            long minimumSnapshotSequence,
            long minimumDynamicSceneRevision,
            String label
    ) throws InterruptedException {
        for (int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; frame++) {
            long sequence = minimumSnapshotSequence + 1L + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null
                    && snapshot.frameStateSequence() >= minimumSnapshotSequence
                    && snapshot.boundTlasDynamicSceneRevision() >= minimumDynamicSceneRevision) {
                try {
                    assertNoSentinelColors(snapshot, label);
                } catch (AssertionError ex) {
                    writeFailureSnapshot(snapshot, label, ex);
                    throw ex;
                }
                return snapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while pumping " + label + ": state=" + rtCore.state()
                            + ", lastSnapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not produce a fresh terrain-only snapshot"
                + ", minimumSequence=" + minimumSnapshotSequence
                + ", minimumDynamicSceneRevision=" + minimumDynamicSceneRevision
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static DynamicRenderScene dynamicPrimitiveScene(
            long revision,
            List<PrimitiveAnchor> anchors,
            Variant variant,
            int phase
    ) {
        List<DynamicRenderScene.DynamicPrimitive> primitives = new ArrayList<>(anchors.size());
        for (int index = 0; index < anchors.size(); index++) {
            PrimitiveAnchor anchor = anchors.get(index);
            double wobble = anchor.sentinel() ? 0.0D : Math.sin((phase + index) * 0.37D) * 0.28D;
            int color = anchor.sentinel()
                    ? variant.sentinelColor(anchor.sentinelIndex())
                    : variant.fillerColor(index, phase);
            primitives.add(new DynamicRenderScene.DynamicPrimitive(
                    10_000L + index,
                    DynamicRenderScene.PrimitiveKind.ENTITY,
                    DynamicRenderScene.PrimitiveGeometryKind.MODEL,
                    anchor.x() + anchor.rayX() * wobble,
                    anchor.y() + anchor.rayY() * wobble,
                    anchor.z() + anchor.rayZ() * wobble,
                    0.0F,
                    0.0F,
                    0.0F,
                    anchor.sentinel() ? 0.82F : 0.34F,
                    color,
                    0,
                    0x00F000F0,
                    true,
                    anchor.sentinel() ? "sentinel-" + anchor.sentinelIndex() : "filler-" + index
            ));
        }
        return new DynamicRenderScene(
                revision,
                primitives,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DynamicRenderScene.SceneLight(
                        1L,
                        DynamicRenderScene.LightKind.SKY,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0F,
                        0.0F,
                        -1.0F,
                        1.0F,
                        1.0F,
                        0x70A8FF,
                        false
                ))
        );
    }

    private static List<PrimitiveAnchor> primitiveAnchors(RendererFrameState frameState, int primitiveCount) {
        require(primitiveCount >= SENTINEL_COUNT, "primitiveCount must be at least " + SENTINEL_COUNT);
        List<PrimitiveAnchor> anchors = new ArrayList<>(primitiveCount);
        int[][] sentinels = sentinelSamples();
        for (int index = 0; index < sentinels.length; index++) {
            anchors.add(anchorForSample(frameState, sentinels[index][0], sentinels[index][1], 10.5D + index, index));
        }

        int columns = Math.max(8, (int) Math.ceil(Math.sqrt(primitiveCount)));
        int rows = Math.max(4, (primitiveCount + columns - 1) / columns);
        int cursor = 0;
        while (anchors.size() < primitiveCount) {
            int column = cursor % columns;
            int row = (cursor / columns) % rows;
            int sampleX = OUTPUT_WIDTH * (column + 1) / (columns + 1);
            int sampleY = OUTPUT_HEIGHT * (row + 1) / (rows + 1);
            cursor++;
            if (nearAnySentinel(sampleX, sampleY, sentinels)) {
                continue;
            }
            double distance = 8.0D + (cursor % 9) * 0.45D;
            anchors.add(anchorForSample(frameState, sampleX, sampleY, distance, -1));
        }
        return anchors;
    }

    private static PrimitiveAnchor anchorForSample(
            RendererFrameState frameState,
            int sampleX,
            int sampleY,
            double distance,
            int sentinelIndex
    ) {
        float[] ray = rayDirection(frameState, sampleX, sampleY);
        return new PrimitiveAnchor(
                sampleX,
                sampleY,
                frameState.cameraX() + ray[0] * distance,
                frameState.cameraY() + ray[1] * distance,
                frameState.cameraZ() + ray[2] * distance,
                ray[0],
                ray[1],
                ray[2],
                sentinelIndex
        );
    }

    private static boolean nearAnySentinel(int x, int y, int[][] sentinels) {
        for (int[] sentinel : sentinels) {
            if (Math.abs(x - sentinel[0]) <= 48 && Math.abs(y - sentinel[1]) <= 48) {
                return true;
            }
        }
        return false;
    }

    private static int[][] sentinelSamples() {
        return new int[][]{
                {OUTPUT_WIDTH / 4, OUTPUT_HEIGHT / 2},
                {OUTPUT_WIDTH / 2, OUTPUT_HEIGHT / 2},
                {OUTPUT_WIDTH * 3 / 4, OUTPUT_HEIGHT / 2}
        };
    }

    private static SectionTriangleMesh terrainDepthAnchor(SectionKey key) {
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
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()}
        );
    }

    private static SectionVoxelSnapshot filledSection(SectionKey key) {
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(ids, BLOCK_STATE_ID);
        return new SectionVoxelSnapshot(key, ids, fluids, false, false);
    }

    private static RendererFrameState frameState(long sequence) {
        return new RendererFrameState(
                sequence,
                true,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                8.0D,
                8.0D,
                40.0D,
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

    private static float[] rayDirection(RendererFrameState frameState, int x, int y) {
        CameraRayMath.RayScale rayScale = CameraRayMath.rayScale(frameState, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        float uvX = (x + 0.5F) / OUTPUT_WIDTH;
        float uvY = (y + 0.5F) / OUTPUT_HEIGHT;
        float ndcX = uvX * 2.0F - 1.0F;
        float ndcY = 1.0F - uvY * 2.0F;
        CameraRayMath.RayDirection direction = CameraRayMath.screenRay(frameState, ndcX, ndcY, rayScale);
        return new float[]{direction.x(), direction.y(), direction.z()};
    }

    private static void assertVariantSentinels(RtFrameSnapshot snapshot, Variant expectedVariant, String label) {
        int[][] samples = sentinelSamples();
        for (int index = 0; index < samples.length; index++) {
            int x = samples[index][0];
            int y = samples[index][1];
            PixelPredicate expected = expectedVariant.sentinelPredicate(index);
            PixelPredicate forbidden = expectedVariant.opposite().sentinelPredicate(index);
            require(
                    countMatching(snapshot, x, y, SAMPLE_RADIUS, expected) >= 8,
                    label + " did not render expected dynamic BLAS sentinel"
                            + ", sentinel=" + index
                            + ", expectedVariant=" + expectedVariant
                            + ", sample=(" + x + "," + y + ")"
                            + ", snapshot=" + snapshot.asLogFragment()
                            + ", colors=" + sampleWindow(snapshot, x, y, 2)
            );
            require(
                    countMatching(snapshot, x, y, SAMPLE_RADIUS, forbidden) == 0,
                    label + " rendered stale dynamic BLAS sentinel from the previous variant"
                            + ", sentinel=" + index
                            + ", expectedVariant=" + expectedVariant
                            + ", sample=(" + x + "," + y + ")"
                            + ", snapshot=" + snapshot.asLogFragment()
                            + ", colors=" + sampleWindow(snapshot, x, y, 2)
            );
        }
    }

    private static void assertNoSentinelColors(RtFrameSnapshot snapshot, String label) {
        int[][] samples = sentinelSamples();
        for (int index = 0; index < samples.length; index++) {
            int x = samples[index][0];
            int y = samples[index][1];
            require(
                    countMatching(snapshot, x, y, SAMPLE_RADIUS, Variant.WARM.sentinelPredicate(index)) == 0,
                    label + " left stale warm dynamic BLAS pixels"
                            + ", sentinel=" + index
                            + ", sample=(" + x + "," + y + ")"
                            + ", snapshot=" + snapshot.asLogFragment()
                            + ", colors=" + sampleWindow(snapshot, x, y, 2)
            );
            require(
                    countMatching(snapshot, x, y, SAMPLE_RADIUS, Variant.COOL.sentinelPredicate(index)) == 0,
                    label + " left stale cool dynamic BLAS pixels"
                            + ", sentinel=" + index
                            + ", sample=(" + x + "," + y + ")"
                            + ", snapshot=" + snapshot.asLogFragment()
                            + ", colors=" + sampleWindow(snapshot, x, y, 2)
            );
        }
    }

    private static int countMatching(RtFrameSnapshot snapshot, int centerX, int centerY, int radius, PixelPredicate predicate) {
        byte[] rgba = snapshot.copyRgba8();
        int count = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); x++) {
                if (predicate.test(RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isWarmRed(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red >= 130 && green <= 130 && blue <= 130;
    }

    private static boolean isWarmYellow(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red >= 150 && green >= 120 && blue <= 130;
    }

    private static boolean isWarmOrange(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red >= 140 && green >= 70 && green <= 180 && blue <= 120;
    }

    private static boolean isCoolCyan(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red <= 130 && green >= 130 && blue >= 130;
    }

    private static boolean isCoolBlue(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red <= 130 && green <= 190 && blue >= 150;
    }

    private static boolean isCoolLime(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red <= 130 && green >= 150 && blue <= 160;
    }

    private static String sampleWindow(RtFrameSnapshot snapshot, int centerX, int centerY, int radius) {
        byte[] rgba = snapshot.copyRgba8();
        StringBuilder result = new StringBuilder("[");
        int emitted = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); x++) {
                if (emitted > 0) {
                    result.append(", ");
                }
                int pixel = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
                result.append("(").append(x).append(",").append(y).append("=")
                        .append(RtFrameSnapshot.hex(pixel))
                        .append("/rgba=")
                        .append(pixel & 0xff)
                        .append(',')
                        .append((pixel >>> 8) & 0xff)
                        .append(',')
                        .append((pixel >>> 16) & 0xff)
                        .append(',')
                        .append((pixel >>> 24) & 0xff)
                        .append(")");
                emitted++;
            }
        }
        return result.append("]").toString();
    }

    private static Map<String, String> installStressProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
        /*
         * Keep the hardware gate observable without turning it into a logging
         * benchmark.  The recorder emits each causal edge once and bounded
         * one-second aggregates; it never enables the per-frame verbose-I/O
         * path used for forensic captures.
         */
        set(previous, "mcvulkanrt.takeoverFlightRecorder.enabled", "true");
        set(previous, "mcvulkanrt.takeoverFlightRecorder.verboseIo", "false");
        set(previous, "mcvulkanrt.rt.output.readback.enabled", "true");
        set(previous, "mcvulkanrt.rt.output.readback.interval", Integer.toString(READBACK_SAMPLE_INTERVAL));
        set(previous, "mcvulkanrt.rt.output.dispatchInterval", "1");
        set(previous, "mcvulkanrt.rt.output.externalSemaphore.enabled", "false");
        set(previous, "mcvulkanrt.rt.output.width", Integer.toString(OUTPUT_WIDTH));
        set(previous, "mcvulkanrt.rt.output.height", Integer.toString(OUTPUT_HEIGHT));
        set(previous, "mcvulkanrt.rt.output.maxPixels", Integer.toString(OUTPUT_WIDTH * OUTPUT_HEIGHT));
        set(previous, "mcvulkanrt.rt.worldTlas.minInitialInstances", "1");
        set(previous, "mcvulkanrt.rt.worldTlas.minRebuildIntervalMillis", "0");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingRebuildIntervalMillis", "0");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingRevisionDelta", "1");
        set(previous, "mcvulkanrt.rt.worldTlas.minStreamingInstanceDelta", "1");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxBuildsPerFrame", "8");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxTrianglesPerFrame", "1000000");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildsInFlight", "8");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "8");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildBytesInFlight", "268435456");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingSections", "64");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingBytes", "268435456");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedSections", "64");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedBytes", "268435456");
        return previous;
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

    private static void writeFailureSnapshot(RtFrameSnapshot snapshot, String label, AssertionError failure) {
        String safeLabel = label.replaceAll("[^A-Za-z0-9._-]+", "-");
        Path path = SNAPSHOT_PATH.resolveSibling("mcvulkanrt-native-dynamic-blas-tlas-stress-failed-" + safeLabel + ".png");
        try {
            writeSnapshotPng(snapshot, path);
            failure.addSuppressed(new AssertionError("failure snapshot written to " + path));
        } catch (IOException ioFailure) {
            failure.addSuppressed(ioFailure);
        }
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

    private static long longProperty(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= 0L ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
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

    private static long sumSummaryLong(String summary, String key) {
        long sum = 0L;
        boolean present = false;
        String prefix = key + "=";
        int searchFrom = 0;
        while (searchFrom < summary.length()) {
            int start = summary.indexOf(prefix, searchFrom);
            if (start < 0) {
                break;
            }
            present = true;
            int valueStart = start + prefix.length();
            int valueEnd = valueStart;
            while (valueEnd < summary.length() && Character.isDigit(summary.charAt(valueEnd))) {
                valueEnd++;
            }
            require(valueEnd > valueStart, "summary key has no numeric value: " + key + "; summary=" + summary);
            sum += Long.parseLong(summary.substring(valueStart, valueEnd));
            searchFrom = valueEnd;
        }
        require(present, "summary key was not present: " + key + "; summary=" + summary);
        return sum;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface PixelPredicate {
        boolean test(int rgba8);
    }

    private enum Variant {
        WARM {
            @Override
            int sentinelColor(int index) {
                return switch (index) {
                    case 0 -> rgba8(238, 54, 42, 255);
                    case 1 -> rgba8(250, 214, 44, 255);
                    case 2 -> rgba8(238, 104, 34, 255);
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
            }

            @Override
            PixelPredicate sentinelPredicate(int index) {
                return switch (index) {
                    case 0 -> RtNativeDynamicBlasTlasStressSelfTest::isWarmRed;
                    case 1 -> RtNativeDynamicBlasTlasStressSelfTest::isWarmYellow;
                    case 2 -> RtNativeDynamicBlasTlasStressSelfTest::isWarmOrange;
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
            }

            @Override
            Variant opposite() {
                return COOL;
            }
        },
        COOL {
            @Override
            int sentinelColor(int index) {
                return switch (index) {
                    case 0 -> rgba8(42, 214, 238, 255);
                    case 1 -> rgba8(58, 118, 248, 255);
                    case 2 -> rgba8(72, 236, 88, 255);
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
            }

            @Override
            PixelPredicate sentinelPredicate(int index) {
                return switch (index) {
                    case 0 -> RtNativeDynamicBlasTlasStressSelfTest::isCoolCyan;
                    case 1 -> RtNativeDynamicBlasTlasStressSelfTest::isCoolBlue;
                    case 2 -> RtNativeDynamicBlasTlasStressSelfTest::isCoolLime;
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
            }

            @Override
            Variant opposite() {
                return WARM;
            }
        };

        abstract int sentinelColor(int index);

        abstract PixelPredicate sentinelPredicate(int index);

        abstract Variant opposite();

        int fillerColor(int index, int phase) {
            int seed = index * 0x45D9F3B + phase * 0x119DE1F3 + ordinal() * 0x27D4EB2D;
            int red = 64 + (seed & 0x7F);
            int green = 64 + ((seed >>> 8) & 0x7F);
            int blue = 64 + ((seed >>> 16) & 0x7F);
            return rgba8(red, green, blue, 255);
        }
    }

    private record PrimitiveAnchor(
            int sampleX,
            int sampleY,
            double x,
            double y,
            double z,
            double rayX,
            double rayY,
            double rayZ,
            int sentinelIndex
    ) {
        private PrimitiveAnchor {
            if (sentinelIndex < -1 || sentinelIndex >= SENTINEL_COUNT) {
                throw new IllegalArgumentException("invalid sentinel index: " + sentinelIndex);
            }
        }

        boolean sentinel() {
            return sentinelIndex >= 0;
        }
    }

    private record DynamicBlasStressResult(
            RtFrameSnapshot lastSnapshot,
            RtSceneReadiness readiness,
            RtCore.RuntimeActivity activity,
            int dynamicUpdates,
            long completedFrames,
            long freshCycleSnapshots,
            double averageCompletedFps,
            int distinctChecksums
    ) {
    }
}
