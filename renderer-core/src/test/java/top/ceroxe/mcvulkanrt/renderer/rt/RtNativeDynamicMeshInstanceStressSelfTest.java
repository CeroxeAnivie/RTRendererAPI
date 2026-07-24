package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.CameraRayMath;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAsset;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtBlendMode;
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
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;

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

/** Hardware gate for one cached item BLAS referenced by many moving TLAS instances. */
public final class RtNativeDynamicMeshInstanceStressSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.dynamicMeshStress.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.dynamicMeshStress.height", 540);
    private static final int INSTANCE_COUNT =
            intProperty("mcvulkanrt.rt.dynamicMeshStress.instanceCount", 96);
    private static final int UPDATE_CYCLES =
            intProperty("mcvulkanrt.rt.dynamicMeshStress.updateCycles", 24);
    private static final int MAX_FRESH_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicMeshStress.maxFreshPumpFrames", 1200);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.dynamicMeshStress.pumpSleepMillis", 4L);
    private static final int SENTINEL_COUNT = 3;
    private static final int SAMPLE_RADIUS = 5;
    private static final int CUBE_TRIANGLES = 12;
    private static final int BLOCK_STATE_ID = 1;
    private static final Path SNAPSHOT_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "mcvulkanrt-native-dynamic-mesh-instance-stress.png"
    );

    private RtNativeDynamicMeshInstanceStressSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installStressProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native dynamic mesh instance stress requires production RT hardware: " + capability.summary()
            );
            StressResult result = runStress(capability);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeDynamicMeshInstanceStressSelfTest passed: instances=" + INSTANCE_COUNT
                    + ", updateCycles=" + UPDATE_CYCLES
                    + ", completedFrames=" + result.completedFrames()
                    + ", freshCycleSnapshots=" + result.freshCycleSnapshots()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", dynamicCache=" + result.dynamicCacheSummary()
                    + ", readiness=" + result.readiness().asLogFragment());
        } finally {
            restoreProperties(previousProperties);
        }
    }

    private static StressResult runStress(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic mesh instance stress: "
                            + rtCore.summary().asLogFragment()
            );

            DynamicMeshAsset sharedAsset = sharedCubeAsset();
            RendererFrameState baseFrame = frameState(1L);
            List<InstanceAnchor> anchors = instanceAnchors(baseFrame, INSTANCE_COUNT);
            rtCore.acceptFrameUpdate(initialUpdate(
                    terrainDepthAnchor(new SectionKey(0, 0, 0)),
                    baseFrame,
                    instanceScene(1L, sharedAsset, anchors, Variant.WARM, 0)
            ));

            RtFrameSnapshot initialSnapshot = pumpUntilFresh(
                    rtCore,
                    2L,
                    1L,
                    INSTANCE_COUNT,
                    Variant.WARM,
                    "initial shared-asset scene"
            );
            Set<Long> checksums = new HashSet<>();
            checksums.add(initialSnapshot.checksum());
            RtFrameSnapshot lastSnapshot = initialSnapshot;
            long completedFrames = 0L;
            long freshCycleSnapshots = 0L;
            long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
            long phaseStartNanos = System.nanoTime();

            for (int cycle = 0; cycle < UPDATE_CYCLES; cycle++) {
                Variant variant = (cycle & 1) == 0 ? Variant.COOL : Variant.WARM;
                long sequence = 10_000L + cycle * 100L;
                rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                        emptyBatch(),
                        frameState(sequence),
                        RendererUpdateLoop.BacklogSnapshot.empty(),
                        instanceScene(2L + cycle, sharedAsset, anchors, variant, cycle + 1)
                ));
                RtFrameSnapshot snapshot = pumpUntilFresh(
                        rtCore,
                        sequence,
                        2L + cycle,
                        INSTANCE_COUNT,
                        variant,
                        "shared-asset update cycle " + cycle
                );
                checksums.add(snapshot.checksum());
                lastSnapshot = snapshot;
                freshCycleSnapshots++;
                long latestCompletedDispatch = rtCore.runtimeActivity().latestCompletedFrameDispatch();
                if (latestCompletedDispatch > lastCompletedDispatch) {
                    completedFrames += latestCompletedDispatch - lastCompletedDispatch;
                    lastCompletedDispatch = latestCompletedDispatch;
                }
            }

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            double averageCompletedFps = completedFrames * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= 15.0D,
                    "shared-asset TLAS/material updates fell below the 15 fps readback floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", completedFrames=" + completedFrames
                            + ", freshCycleSnapshots=" + freshCycleSnapshots
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    checksums.size() >= Math.min(8, UPDATE_CYCLES / 2 + 1),
                    "moving/tinted shared instances did not produce enough distinct frames"
                            + ", distinctChecksums=" + checksums.size()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            rtCore.refreshDiagnosticSummary();
            String dynamicBeforeClear = componentSummary(
                    rtCore.summary().asLogFragment(),
                    "dynamicBlasCache"
            );
            assertSingleSharedAssetBuild(dynamicBeforeClear, INSTANCE_COUNT, "before clear");

            long clearSequence = 90_000L;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                    emptyBatch(),
                    frameState(clearSequence),
                    RendererUpdateLoop.BacklogSnapshot.empty(),
                    new DynamicRenderScene(
                            80_000L,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    )
            ));
            pumpUntilFresh(rtCore, clearSequence, 80_000L, 0, null, "shared-asset clear");
            rtCore.refreshDiagnosticSummary();
            String dynamicAfterClear = componentSummary(
                    rtCore.summary().asLogFragment(),
                    "dynamicBlasCache"
            );
            require(summaryLong(dynamicAfterClear, "submittedBuilds") == 1L,
                    "clearing instances must not rebuild the cached asset: " + dynamicAfterClear);
            require(summaryLong(dynamicAfterClear, "activeMeshInstances") == 0L,
                    "clearing instances must remove all active TLAS instances: " + dynamicAfterClear);
            require(summaryLong(dynamicAfterClear, "cachedAssetBlases") == 1L,
                    "clearing instances should retain the reusable asset BLAS: " + dynamicAfterClear);

            long readdSequence = 100_000L;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                    emptyBatch(),
                    frameState(readdSequence),
                    RendererUpdateLoop.BacklogSnapshot.empty(),
                    instanceScene(80_001L, sharedAsset, anchors, Variant.COOL, UPDATE_CYCLES + 1)
            ));
            lastSnapshot = pumpUntilFresh(
                    rtCore,
                    readdSequence,
                    80_001L,
                    INSTANCE_COUNT,
                    Variant.COOL,
                    "shared-asset cache reuse"
            );
            rtCore.refreshDiagnosticSummary();
            String dynamicAfterReuse = componentSummary(
                    rtCore.summary().asLogFragment(),
                    "dynamicBlasCache"
            );
            assertSingleSharedAssetBuild(dynamicAfterReuse, INSTANCE_COUNT, "after cache reuse");
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native dynamic mesh instance stress");
            return new StressResult(
                    lastSnapshot,
                    rtCore.sceneReadiness(),
                    checksums.size(),
                    completedFrames,
                    freshCycleSnapshots,
                    averageCompletedFps,
                    dynamicAfterReuse
            );
        }
    }

    private static void assertSingleSharedAssetBuild(String summary, int activeInstances, String phase) {
        require(summaryLong(summary, "submittedBuilds") == 1L,
                phase + " must have exactly one asset BLAS submission: " + summary);
        require(summaryLong(summary, "completedBuilds") == 1L,
                phase + " must have exactly one completed asset BLAS: " + summary);
        require(summaryLong(summary, "cachedAssetBlases") == 1L,
                phase + " must retain one shared asset BLAS: " + summary);
        require(summaryLong(summary, "activeMeshInstances") == activeInstances,
                phase + " active mesh instance count mismatch: " + summary);
        require(summaryLong(summary, "totalTrianglesBuilt") == CUBE_TRIANGLES,
                phase + " per-instance changes must not rebuild geometry: " + summary);
        require(summaryLong(summary, "queuedAssetBuilds") == 0L,
                phase + " must drain the asset build queue: " + summary);
        require(summary.contains("pendingAssetBuild=false"),
                phase + " must not leave an asset build pending: " + summary);
    }

    private static RtFrameSnapshot pumpUntilFresh(
            GuardedRtCore rtCore,
            long minimumSequence,
            long minimumDynamicSceneRevision,
            int expectedDynamicInstances,
            Variant expectedVariant,
            String label
    ) throws InterruptedException {
        for (int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; frame++) {
            long sequence = minimumSequence + 1L + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            if (snapshot != null
                    && snapshot.frameStateSequence() >= minimumSequence
                    && snapshot.boundTlasDynamicSceneRevision() >= minimumDynamicSceneRevision
                    && readiness.builtRevisionIsCurrent()
                    && readiness.observedDynamicInstances() == expectedDynamicInstances
                    && readiness.builtDynamicInstances() == expectedDynamicInstances) {
                RtNativeStressGuards.assertFrameNotPathological(snapshot, label);
                if (expectedVariant != null) {
                    assertVariantSentinels(snapshot, expectedVariant, label);
                }
                return snapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while pumping " + label
                            + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                            + ", readiness=" + readiness.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not converge to a fresh shared-instance TLAS"
                + ", minimumSequence=" + minimumSequence
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static DynamicRenderScene instanceScene(
            long revision,
            DynamicMeshAsset asset,
            List<InstanceAnchor> anchors,
            Variant variant,
            int phase
    ) {
        List<DynamicRenderScene.DynamicPrimitive> primitives = new ArrayList<>(anchors.size());
        for (int index = 0; index < anchors.size(); index++) {
            InstanceAnchor anchor = anchors.get(index);
            float scale = anchor.sentinel() ? 0.85F : 0.24F + (index % 4) * 0.03F;
            float angle = phase * 0.17F + index * 0.071F;
            float lateralX = anchor.sentinel() ? 0.0F : (float) Math.sin(phase * 0.31D + index) * 0.18F;
            float lateralY = anchor.sentinel() ? 0.0F : (float) Math.cos(phase * 0.23D + index) * 0.14F;
            float depth = (float) Math.sin(phase * 0.19D + index * 0.11D) * 0.12F;
            float translateX = (float) anchor.x() + lateralX + (float) anchor.rayX() * depth;
            float translateY = (float) anchor.y() + lateralY + (float) anchor.rayY() * depth;
            float translateZ = (float) anchor.z() + (float) anchor.rayZ() * depth;
            DynamicMeshInstance.AffineTransform transform = affineY(
                    scale,
                    angle,
                    translateX,
                    translateY,
                    translateZ
            );
            int tint = anchor.sentinel()
                    ? variant.sentinelColor(anchor.sentinelIndex())
                    : variant.fillerColor(index, phase);
            DynamicMeshInstance instance = new DynamicMeshInstance(
                    asset,
                    transform,
                    cubeMaterials(asset.faceCount(), tint, (phase + index) % 3)
            );
            primitives.add(new DynamicRenderScene.DynamicPrimitive(
                    0x0200_0000_0000_0000L | index,
                    DynamicRenderScene.PrimitiveKind.DROPPED_ITEM,
                    DynamicRenderScene.PrimitiveGeometryKind.MODEL,
                    translateX,
                    translateY,
                    translateZ,
                    0.0F,
                    0.0F,
                    0.0F,
                    scale,
                    scale,
                    scale,
                    0,
                    0,
                    (phase & 1) == 0 ? 0x00F0_00F0 : 0x00C0_00A0,
                    true,
                    anchor.sentinel() ? "shared-item-sentinel-" + anchor.sentinelIndex() : "shared-item-" + index,
                    instance
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

    private static DynamicMeshInstance.AffineTransform affineY(
            float scale,
            float angle,
            float translateX,
            float translateY,
            float translateZ
    ) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        return new DynamicMeshInstance.AffineTransform(
                cosine * scale, 0.0F, sine * scale, translateX,
                0.0F, scale, 0.0F, translateY,
                -sine * scale, 0.0F, cosine * scale, translateZ
        );
    }

    private static List<InstanceAnchor> instanceAnchors(RendererFrameState frameState, int count) {
        require(count >= SENTINEL_COUNT, "instanceCount must be at least " + SENTINEL_COUNT);
        ArrayList<InstanceAnchor> anchors = new ArrayList<>(count);
        int[][] sentinels = sentinelSamples();
        for (int index = 0; index < sentinels.length; index++) {
            anchors.add(anchorForSample(frameState, sentinels[index][0], sentinels[index][1], 10.0D + index, index));
        }
        int columns = Math.max(8, (int) Math.ceil(Math.sqrt(count)));
        int rows = Math.max(4, (count + columns - 1) / columns);
        int cursor = 0;
        while (anchors.size() < count) {
            int column = cursor % columns;
            int row = (cursor / columns) % rows;
            int sampleX = OUTPUT_WIDTH * (column + 1) / (columns + 1);
            int sampleY = OUTPUT_HEIGHT * (row + 1) / (rows + 1);
            cursor++;
            if (nearAnySentinel(sampleX, sampleY, sentinels)) {
                continue;
            }
            anchors.add(anchorForSample(frameState, sampleX, sampleY, 8.5D + (cursor % 11) * 0.35D, -1));
        }
        return List.copyOf(anchors);
    }

    private static InstanceAnchor anchorForSample(
            RendererFrameState frameState,
            int sampleX,
            int sampleY,
            double distance,
            int sentinelIndex
    ) {
        float[] ray = rayDirection(frameState, sampleX, sampleY);
        return new InstanceAnchor(
                frameState.cameraX() + ray[0] * distance,
                frameState.cameraY() + ray[1] * distance,
                frameState.cameraZ() + ray[2] * distance,
                ray[0],
                ray[1],
                ray[2],
                sentinelIndex
        );
    }

    private static void assertVariantSentinels(RtFrameSnapshot snapshot, Variant variant, String label) {
        int[][] samples = sentinelSamples();
        for (int sentinel = 0; sentinel < samples.length; sentinel++) {
            int matching = countMatching(
                    snapshot,
                    samples[sentinel][0],
                    samples[sentinel][1],
                    SAMPLE_RADIUS,
                    variant.sentinelPredicate(sentinel)
            );
            require(
                    matching >= 8,
                    label + " did not render shared-asset sentinel " + sentinel
                            + ", matchingPixels=" + matching
                            + ", snapshot=" + snapshot.asLogFragment()
                            + ", colors=" + sampleWindow(
                                    snapshot,
                                    samples[sentinel][0],
                                    samples[sentinel][1],
                                    2
                            )
            );
        }
    }

    private static int countMatching(
            RtFrameSnapshot snapshot,
            int centerX,
            int centerY,
            int radius,
            PixelPredicate predicate
    ) {
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

    private static String sampleWindow(
            RtFrameSnapshot snapshot,
            int centerX,
            int centerY,
            int radius
    ) {
        byte[] rgba = snapshot.copyRgba8();
        StringBuilder result = new StringBuilder("[");
        int emitted = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); x++) {
                if (emitted++ > 0) {
                    result.append(", ");
                }
                int pixel = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
                result.append('(')
                        .append(x).append(',').append(y).append('=')
                        .append(Variant.red(pixel)).append('/')
                        .append(Variant.green(pixel)).append('/')
                        .append(Variant.blue(pixel)).append(')');
            }
        }
        return result.append(']').toString();
    }

    private static DynamicMeshAsset sharedCubeAsset() {
        float half = 0.5F;
        float[] positions = {
                -half, -half, half, half, -half, half, half, half, half, -half, half, half,
                half, -half, -half, -half, -half, -half, -half, half, -half, half, half, -half,
                half, -half, half, half, -half, -half, half, half, -half, half, half, half,
                -half, -half, -half, -half, -half, half, -half, half, half, -half, half, -half,
                -half, half, half, half, half, half, half, half, -half, -half, half, -half,
                -half, -half, -half, half, -half, -half, half, -half, half, -half, -half, half
        };
        int[] indices = new int[6 * 6];
        List<DynamicMeshAsset.Face> faces = new ArrayList<>(6);
        FaceDirection[] directions = {
                FaceDirection.POSITIVE_Z,
                FaceDirection.NEGATIVE_Z,
                FaceDirection.POSITIVE_X,
                FaceDirection.NEGATIVE_X,
                FaceDirection.POSITIVE_Y,
                FaceDirection.NEGATIVE_Y
        };
        for (int face = 0; face < directions.length; face++) {
            int vertex = face * 4;
            int offset = face * 6;
            indices[offset] = vertex;
            indices[offset + 1] = vertex + 1;
            indices[offset + 2] = vertex + 2;
            indices[offset + 3] = vertex;
            indices[offset + 4] = vertex + 2;
            indices[offset + 5] = vertex + 3;
            faces.add(new DynamicMeshAsset.Face(directions[face].ordinal(), true));
        }
        return new DynamicMeshAsset(0x1000L, 1L, positions, indices, faces);
    }

    private static List<DynamicMeshInstance.FaceMaterial> cubeMaterials(
            int faceCount,
            int tintRgba8,
            int foilMode
    ) {
        DynamicMeshInstance.FaceMaterial material = new DynamicMeshInstance.FaceMaterial(
                0,
                RtTextureCatalog.packUv16(0.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 1.0F),
                RtTextureCatalog.packUv16(0.0F, 1.0F),
                tintRgba8,
                true,
                false,
                RtBlendMode.OPAQUE,
                0,
                foilMode,
                0,
                false,
                false,
                DynamicMeshInstance.FaceMaterial.NO_OVERLAY_COORDS
        );
        return java.util.Collections.nCopies(faceCount, material);
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
        require(meshResult.trianglesInBatch() > 0, "dynamic mesh stress terrain anchor must be visible");
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

    private static SectionTriangleMesh terrainDepthAnchor(SectionKey key) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(16.0F), fixed(16.0F),
                        fixed(0.0F), fixed(16.0F), fixed(16.0F)
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
        float ndcX = ((x + 0.5F) / OUTPUT_WIDTH) * 2.0F - 1.0F;
        float ndcY = 1.0F - ((y + 0.5F) / OUTPUT_HEIGHT) * 2.0F;
        CameraRayMath.RayDirection direction = CameraRayMath.screenRay(frameState, ndcX, ndcY, rayScale);
        return new float[]{direction.x(), direction.y(), direction.z()};
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

    private static String componentSummary(String summary, String componentName) {
        String prefix = componentName + "{";
        int start = summary.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("missing summary component " + componentName + ": " + summary);
        }
        int end = summary.indexOf('}', start);
        if (end < 0) {
            throw new AssertionError("unterminated summary component " + componentName + ": " + summary);
        }
        return summary.substring(start, end + 1);
    }

    private static long summaryLong(String summary, String key) {
        String prefix = key + "=";
        int start = summary.indexOf(prefix);
        if (start < 0) {
            throw new AssertionError("missing summary key " + key + ": " + summary);
        }
        int valueStart = start + prefix.length();
        int valueEnd = valueStart;
        while (valueEnd < summary.length() && Character.isDigit(summary.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            throw new AssertionError("summary key has no numeric value " + key + ": " + summary);
        }
        return Long.parseLong(summary.substring(valueStart, valueEnd));
    }

    private static Map<String, String> installStressProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
        set(previous, "mcvulkanrt.takeoverFlightRecorder.enabled", "true");
        set(previous, "mcvulkanrt.takeoverFlightRecorder.verboseIo", "false");
        set(previous, "mcvulkanrt.rt.output.readback.enabled", "true");
        set(previous, "mcvulkanrt.rt.output.readback.interval", "1");
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
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingSections", "64");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedSections", "64");
        return previous;
    }

    private static void writeSnapshotPng(RtFrameSnapshot snapshot, Path path) throws IOException {
        byte[] rgba = snapshot.copyRgba8();
        BufferedImage image = new BufferedImage(snapshot.width(), snapshot.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < snapshot.height(); y++) {
            for (int x = 0; x < snapshot.width(); x++) {
                int rgba8 = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
                int argb = ((rgba8 >>> 24) & 0xFF) << 24
                        | (rgba8 & 0xFF) << 16
                        | ((rgba8 >>> 8) & 0xFF) << 8
                        | ((rgba8 >>> 16) & 0xFF);
                image.setRGB(x, y, argb);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static short fixed(float blockUnits) {
        return (short) Math.round(blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return (red & 0xFF)
                | ((green & 0xFF) << 8)
                | ((blue & 0xFF) << 16)
                | ((alpha & 0xFF) << 24);
    }

    private static int intProperty(String name, int defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
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
            long value = Long.parseLong(raw.trim());
            return value >= 0L ? value : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static void set(Map<String, String> previous, String name, String value) {
        previous.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private static void restoreProperties(Map<String, String> previous) {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
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
                    case 0 -> color -> red(color) >= 60
                            && dominates(red(color), green(color), 3, 2)
                            && dominates(red(color), blue(color), 3, 2);
                    case 1 -> color -> red(color) >= 70
                            && green(color) >= 55
                            && dominates(red(color), blue(color), 7, 5)
                            && dominates(green(color), blue(color), 6, 5);
                    case 2 -> color -> red(color) >= 60
                            && green(color) >= 25
                            && dominates(red(color), green(color), 6, 5)
                            && dominates(green(color), blue(color), 11, 10);
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
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
                    case 0 -> color -> green(color) >= 55
                            && blue(color) >= 60
                            && dominates(green(color), red(color), 13, 10)
                            && dominates(blue(color), red(color), 13, 10);
                    case 1 -> color -> blue(color) >= 60
                            && dominates(blue(color), red(color), 7, 5)
                            && dominates(blue(color), green(color), 23, 20);
                    case 2 -> color -> green(color) >= 60
                            && dominates(green(color), red(color), 13, 10)
                            && dominates(green(color), blue(color), 6, 5);
                    default -> throw new IllegalArgumentException("invalid sentinel index: " + index);
                };
            }
        };

        abstract int sentinelColor(int index);

        abstract PixelPredicate sentinelPredicate(int index);

        int fillerColor(int index, int phase) {
            int value = index * 0x45D9F3B + phase * 0x119DE1F3 + ordinal() * 0x27D4EB2D;
            return rgba8(
                    64 + (value & 0x7F),
                    64 + ((value >>> 8) & 0x7F),
                    64 + ((value >>> 16) & 0x7F),
                    255
            );
        }

        static int red(int rgba8) {
            return rgba8 & 0xFF;
        }

        static int green(int rgba8) {
            return (rgba8 >>> 8) & 0xFF;
        }

        static int blue(int rgba8) {
            return (rgba8 >>> 16) & 0xFF;
        }

        static boolean dominates(int dominant, int other, int numerator, int denominator) {
            return dominant * denominator >= other * numerator;
        }
    }

    private record InstanceAnchor(
            double x,
            double y,
            double z,
            double rayX,
            double rayY,
            double rayZ,
            int sentinelIndex
    ) {
        private InstanceAnchor {
            if (sentinelIndex < -1 || sentinelIndex >= SENTINEL_COUNT) {
                throw new IllegalArgumentException("invalid sentinel index: " + sentinelIndex);
            }
        }

        boolean sentinel() {
            return sentinelIndex >= 0;
        }
    }

    private record StressResult(
            RtFrameSnapshot lastSnapshot,
            RtSceneReadiness readiness,
            int distinctChecksums,
            long completedFrames,
            long freshCycleSnapshots,
            double averageCompletedFps,
            String dynamicCacheSummary
    ) {
    }
}
