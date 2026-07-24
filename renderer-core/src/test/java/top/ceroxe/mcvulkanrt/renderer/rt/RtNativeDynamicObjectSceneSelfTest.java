package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-backed MC-outside gate for non-terrain dynamic visual content.
 *
 * <p>This test deliberately keeps terrain as a simple depth anchor and drives
 * sustained dynamic-only frames for primitive impostors, particles, and beams.
 * It catches the exact failure class where Minecraft gameplay/audio continues
 * but entities, dropped items, particles, and beam-like effects never become
 * visible in the RT output because the dynamic scene stopped at Java telemetry.</p>
 */
public final class RtNativeDynamicObjectSceneSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.dynamicObjects.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.dynamicObjects.height", 540);
    private static final int SUSTAINED_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicObjects.sustainedFrames", 260);
    private static final int DYNAMIC_UPDATE_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicObjects.updatePeriodFrames", 3);
    private static final int MAX_INITIAL_READY_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicObjects.maxInitialReadyPumpFrames", 900);
    private static final int MAX_FRESH_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicObjects.maxFreshPumpFrames", 900);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.dynamicObjects.readbackSampleInterval", 1);
    private static final int MIN_DISTINCT_CHECKSUMS =
            intProperty("mcvulkanrt.rt.dynamicObjects.minDistinctChecksums", 8);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.dynamicObjects.pumpSleepMillis", 5L);
    private static final int BLOCK_STATE_ID = 1;
    private static final int PRIMITIVE_SAMPLE_X = OUTPUT_WIDTH / 2;
    private static final int PRIMITIVE_SAMPLE_Y = OUTPUT_HEIGHT / 2;
    private static final int PARTICLE_SAMPLE_X = OUTPUT_WIDTH * 13 / 32;
    private static final int PARTICLE_SAMPLE_Y = OUTPUT_HEIGHT * 7 / 16;
    private static final int BEAM_SAMPLE_X = OUTPUT_WIDTH * 19 / 32;
    private static final int BEAM_SAMPLE_Y = OUTPUT_HEIGHT / 2;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-dynamic-object-scene.png");

    private RtNativeDynamicObjectSceneSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installDynamicObjectProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native dynamic object scene requires production RT hardware: " + capability.summary()
            );

            DynamicObjectResult result = runDynamicObjectScene(capability);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeDynamicObjectSceneSelfTest passed: sustainedFrames=" + SUSTAINED_FRAMES
                    + ", dynamicUpdates=" + result.dynamicUpdates()
                    + ", completedFrames=" + result.completedFrames()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", activity=" + result.activity().asLogFragment()
                    + ", readiness=" + result.readiness().asLogFragment());
        } finally {
            restoreProperties(previousProperties);
        }
    }

    private static DynamicObjectResult runDynamicObjectScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        String particleTextureName = "native-dynamic-object-white-particle";
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(
                List.of(new RtTextureCatalog.TestTexture(
                        particleTextureName,
                        1,
                        1,
                        new int[]{rgba8(255, 255, 255, 255)}
                ))
        ); GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            int particleTextureId = textures.textureId(particleTextureName);
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic object scene: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            RendererFrameState baseFrameState = frameState(1L);
            DynamicAnchors anchors = dynamicAnchors(baseFrameState);
            rtCore.acceptFrameUpdate(initialUpdate(
                    terrainDepthAnchor(new SectionKey(0, 0, 0)),
                    baseFrameState,
                    dynamicObjectScene(1L, anchors, 0.0F, particleTextureId)
            ));

            RtFrameSnapshot readySnapshot = pumpUntilFreshSnapshot(rtCore, 2L, "dynamic object initial ready");
            RtNativeStressGuards.assertFrameNotPathological(readySnapshot, "initial native dynamic object frame");
            assertRedPrimitiveAt(readySnapshot, PRIMITIVE_SAMPLE_X, PRIMITIVE_SAMPLE_Y, "initial dynamic object");
            assertGreenParticleAt(readySnapshot, PARTICLE_SAMPLE_X, PARTICLE_SAMPLE_Y, "initial dynamic object");
            assertAdditiveAmberBeamAt(readySnapshot, BEAM_SAMPLE_X, BEAM_SAMPLE_Y, "initial dynamic object");

            long phaseStartNanos = System.nanoTime();
            long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
            long completedFrames = 0L;
            int dynamicUpdates = 1;
            Set<Long> checksums = new HashSet<>();
            checksums.add(readySnapshot.checksum());
            RtFrameSnapshot lastSnapshot = readySnapshot;

            for (int frame = 0; frame < SUSTAINED_FRAMES; frame++) {
                long sequence = 10_000L + frame;
                RendererFrameState currentFrameState = frameState(sequence);
                RendererFrameUpdate update;
                if (frame % DYNAMIC_UPDATE_PERIOD_FRAMES == 0) {
                    float phase = frame / (float) Math.max(1, SUSTAINED_FRAMES - 1);
                    update = RendererFrameUpdate.dynamicOnly(
                            emptyBatch(),
                            currentFrameState,
                            RendererUpdateLoop.BacklogSnapshot.empty(),
                            dynamicObjectScene(2L + dynamicUpdates, anchors, phase, particleTextureId)
                    );
                    dynamicUpdates++;
                } else {
                    update = RendererFrameUpdate.empty(emptyBatch(), currentFrameState);
                }

                rtCore.acceptFrameUpdate(update);
                RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
                if (activity.latestCompletedFrameStateSequence() > lastCompletedSequence) {
                    completedFrames++;
                    lastCompletedSequence = activity.latestCompletedFrameStateSequence();
                }
                RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
                if (snapshot != null) {
                    lastSnapshot = snapshot;
                    checksums.add(snapshot.checksum());
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during native dynamic object scene: state=" + rtCore.state()
                                + ", activity=" + activity.asLogFragment()
                                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

            rtCore.refreshDiagnosticSummary();
            String dynamicSummaryBeforeClear = rtCore.summary().asLogFragment();
            require(
                    sumSummaryLong(dynamicSummaryBeforeClear, "observedDynamicInstances") == 0L,
                    "analytic dynamic object scene should not rebuild the world TLAS for impostor/particle/beam streams; summary="
                            + dynamicSummaryBeforeClear
            );
            require(
                    sumSummaryLong(dynamicSummaryBeforeClear, "dynamicSceneUploads") > 0L,
                    "analytic dynamic object scene did not upload the per-frame dynamic SSBO; summary="
                            + dynamicSummaryBeforeClear
            );

            DynamicRenderScene clearScene = new DynamicRenderScene(
                    10_000L + dynamicUpdates,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                    emptyBatch(),
                    frameState(30_000L),
                    RendererUpdateLoop.BacklogSnapshot.empty(),
                    clearScene
            ));
            RtFrameSnapshot clearSnapshot = pumpUntilSnapshotSequenceAtLeast(
                    rtCore,
                    30_000L,
                    "dynamic object clear"
            );
            checksums.add(clearSnapshot.checksum());
            lastSnapshot = clearSnapshot;
            require(
                    countMatching(clearSnapshot, PRIMITIVE_SAMPLE_X, PRIMITIVE_SAMPLE_Y, 4,
                            RtNativeDynamicObjectSceneSelfTest::isRedPrimitive) == 0,
                    "dynamic clear left stale primitive pixels"
                            + ", snapshot=" + clearSnapshot.asLogFragment()
                            + ", colors=" + sampleWindow(clearSnapshot, PRIMITIVE_SAMPLE_X, PRIMITIVE_SAMPLE_Y, 2)
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    countMatching(clearSnapshot, PARTICLE_SAMPLE_X, PARTICLE_SAMPLE_Y, 4,
                            RtNativeDynamicObjectSceneSelfTest::isGreenParticle) == 0,
                    "dynamic clear left stale particle pixels"
                            + ", snapshot=" + clearSnapshot.asLogFragment()
                            + ", colors=" + sampleWindow(clearSnapshot, PARTICLE_SAMPLE_X, PARTICLE_SAMPLE_Y, 2)
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    countMatching(clearSnapshot, BEAM_SAMPLE_X, BEAM_SAMPLE_Y, 4,
                            RtNativeDynamicObjectSceneSelfTest::isAdditiveAmberBeam) == 0,
                    "dynamic clear left stale beam pixels"
                            + ", snapshot=" + clearSnapshot.asLogFragment()
                            + ", colors=" + sampleWindow(clearSnapshot, BEAM_SAMPLE_X, BEAM_SAMPLE_Y, 2)
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            double averageCompletedFps = completedFrames * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= 15.0D,
                    "native dynamic object scene completed frames below 15 fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", completedFrames=" + completedFrames
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    checksums.size() >= MIN_DISTINCT_CHECKSUMS,
                    "dynamic object scene did not visibly change across dynamic-only revisions"
                            + ", distinctChecksums=" + checksums.size()
                            + ", expectedAtLeast=" + MIN_DISTINCT_CHECKSUMS
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            rtCore.refreshDiagnosticSummary();
            String summary = rtCore.summary().asLogFragment();
            require(
                    sumSummaryLong(summary, "dynamicSceneUploads") > 0L,
                    "RT pipeline did not report dynamic scene GPU uploads; summary=" + summary
            );
            require(
                    sumSummaryLong(summary, "latestDynamicSceneElements") == 0L,
                    "dynamic object clear did not reach the RT pipeline; summary=" + summary
            );
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native dynamic object scene");
            return new DynamicObjectResult(
                    lastSnapshot,
                    rtCore.sceneReadiness(),
                    rtCore.runtimeActivity(),
                    dynamicUpdates,
                    completedFrames,
                    averageCompletedFps,
                    checksums.size()
            );
        }
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
        require(meshResult.trianglesInBatch() > 0, "dynamic object terrain anchor must submit visible triangles");
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

    private static RtFrameSnapshot pumpUntilFreshSnapshot(
            GuardedRtCore rtCore,
            long minimumSequence,
            String label
    ) throws InterruptedException {
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        long firstReadySequence = -1L;
        for (int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; frame++) {
            long sequence = minimumSequence + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                firstReadySequence = sequence;
            }
            lastSnapshot = rtCore.latestFrameSnapshot();
            if (lastSnapshot != null
                    && firstReadySequence >= 0L
                    && lastSnapshot.frameStateSequence() >= Math.max(firstReadySequence, minimumSequence)) {
                return lastSnapshot;
            }
            require(
                    frame < MAX_INITIAL_READY_PUMP_FRAMES || firstReadySequence >= 0L,
                    label + " did not build the initial RT world scene"
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while pumping " + label + ": state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not produce a fresh native RT snapshot"
                + ", minimumSequence=" + minimumSequence
                + ", firstReadySequence=" + firstReadySequence
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static RtFrameSnapshot pumpUntilSnapshotSequenceAtLeast(
            GuardedRtCore rtCore,
            long minimumSnapshotSequence,
            String label
    ) throws InterruptedException {
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        for (int frame = 0; frame < MAX_FRESH_PUMP_FRAMES; frame++) {
            long sequence = minimumSnapshotSequence + 1L + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            lastSnapshot = rtCore.latestFrameSnapshot();
            if (lastSnapshot != null && lastSnapshot.frameStateSequence() >= minimumSnapshotSequence) {
                return lastSnapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while pumping " + label + ": state=" + rtCore.state()
                            + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not produce a completed snapshot at or after sequence "
                + minimumSnapshotSequence
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static DynamicRenderScene dynamicObjectScene(
            long revision,
            DynamicAnchors anchors,
            float phase,
            int particleTextureId
    ) {
        double bob = Math.sin(phase * Math.PI * 2.0D) * 0.04D;
        return new DynamicRenderScene(
                revision,
                List.of(
                        new DynamicRenderScene.DynamicPrimitive(
                                1L,
                                DynamicRenderScene.PrimitiveKind.ENTITY,
                                DynamicRenderScene.PrimitiveGeometryKind.IMPOSTOR,
                                anchors.primitiveX(),
                                anchors.primitiveY() + bob,
                                anchors.primitiveZ(),
                                0.0F,
                                0.0F,
                                0.0F,
                                0.42F,
                                0.96F,
                                0.58F,
                                rgba8(236, 54, 45, 255),
                                0,
                                0x00F000F0,
                                true,
                                "dynamic-red-entity-impostor"
                        )
                ),
                List.of(
                        new DynamicRenderScene.BillboardParticle(
                                2L,
                                DynamicRenderScene.ParticleKind.CUTOUT_BILLBOARD,
                                anchors.particleX(),
                                anchors.particleY() - bob,
                                anchors.particleZ(),
                                1.10F,
                                rgba8(48, 224, 96, 255),
                                particleTextureId,
                                0x00F000F0,
                                clamp01(phase)
                        )
                ),
                List.of(
                        new DynamicRenderScene.Beam(
                                3L,
                                DynamicRenderScene.BeamKind.BEACON,
                                anchors.beamStartX(),
                                anchors.beamStartY(),
                                anchors.beamStartZ(),
                                anchors.beamEndX(),
                                anchors.beamEndY(),
                                anchors.beamEndZ(),
                                0.24F,
                                rgba8(255, 214, 48, 255),
                                0,
                                0x00F0_00F0,
                                true
                        )
                ),
                List.of(),
                List.of(new DynamicRenderScene.SceneLight(
                        4L,
                        DynamicRenderScene.LightKind.SKY,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0F,
                        0.0F,
                        -1.0F,
                        1.0F,
                        1.1F,
                        0x70A8FF,
                        false
                ))
        );
    }

    private static DynamicAnchors dynamicAnchors(RendererFrameState frameState) {
        float[] primitiveDirection = rayDirection(frameState, PRIMITIVE_SAMPLE_X, PRIMITIVE_SAMPLE_Y);
        float[] particleDirection = rayDirection(frameState, PARTICLE_SAMPLE_X, PARTICLE_SAMPLE_Y);
        float[] beamDirection = rayDirection(frameState, BEAM_SAMPLE_X, BEAM_SAMPLE_Y);
        double primitiveDistance = 12.0D;
        double particleDistance = 11.0D;
        double beamDistance = 10.5D;
        double beamVertical = 1.8D;
        return new DynamicAnchors(
                frameState.cameraX() + primitiveDirection[0] * primitiveDistance,
                frameState.cameraY() + primitiveDirection[1] * primitiveDistance,
                frameState.cameraZ() + primitiveDirection[2] * primitiveDistance,
                frameState.cameraX() + particleDirection[0] * particleDistance,
                frameState.cameraY() + particleDirection[1] * particleDistance,
                frameState.cameraZ() + particleDirection[2] * particleDistance,
                frameState.cameraX() + beamDirection[0] * beamDistance,
                frameState.cameraY() + beamDirection[1] * beamDistance - beamVertical,
                frameState.cameraZ() + beamDirection[2] * beamDistance,
                frameState.cameraX() + beamDirection[0] * beamDistance,
                frameState.cameraY() + beamDirection[1] * beamDistance + beamVertical,
                frameState.cameraZ() + beamDirection[2] * beamDistance
        );
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

    private static void assertRedPrimitiveAt(RtFrameSnapshot snapshot, int x, int y, String label) {
        require(
                countMatching(snapshot, x, y, 5, RtNativeDynamicObjectSceneSelfTest::isRedPrimitive) >= 8,
                label + " did not render red dynamic primitive near sample"
                        + ", sample=(" + x + "," + y + ")"
                        + ", snapshot=" + snapshot.asLogFragment()
                        + ", colors=" + sampleWindow(snapshot, x, y, 2)
        );
    }

    private static void assertGreenParticleAt(RtFrameSnapshot snapshot, int x, int y, String label) {
        require(
                countMatching(snapshot, x, y, 5, RtNativeDynamicObjectSceneSelfTest::isGreenParticle) >= 8,
                label + " did not render green dynamic particle near sample"
                        + ", sample=(" + x + "," + y + ")"
                        + ", snapshot=" + snapshot.asLogFragment()
                        + ", colors=" + sampleWindow(snapshot, x, y, 2)
        );
    }

    private static void assertAdditiveAmberBeamAt(RtFrameSnapshot snapshot, int x, int y, String label) {
        require(
                countMatching(snapshot, x, y, 5, RtNativeDynamicObjectSceneSelfTest::isAdditiveAmberBeam) >= 5,
                label + " did not render additive amber dynamic beam near sample"
                        + ", sample=(" + x + "," + y + ")"
                        + ", snapshot=" + snapshot.asLogFragment()
                        + ", colors=" + sampleWindow(snapshot, x, y, 2)
        );
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

    private static boolean isRedPrimitive(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red >= 120 && green <= 120 && blue <= 120;
    }

    private static boolean isGreenParticle(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red <= 120 && green >= 150 && blue <= 150;
    }

    private static boolean isAdditiveAmberBeam(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        /* Additive beacon blending preserves the background blue while amber saturates red/green. */
        return red >= 220 && green >= 190 && blue <= 210
                && red - blue >= 35 && green - blue >= 20;
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
                result.append("(").append(x).append(",").append(y).append("=")
                        .append(RtFrameSnapshot.hex(RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y)))
                        .append(")");
                emitted++;
            }
        }
        return result.append("]").toString();
    }

    private static Map<String, String> installDynamicObjectProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
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

    private static short fixed(float blockUnits) {
        return (short) Math.round(blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return (red & 0xff)
                | ((green & 0xff) << 8)
                | ((blue & 0xff) << 16)
                | ((alpha & 0xff) << 24);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
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

    private record DynamicAnchors(
            double primitiveX,
            double primitiveY,
            double primitiveZ,
            double particleX,
            double particleY,
            double particleZ,
            double beamStartX,
            double beamStartY,
            double beamStartZ,
            double beamEndX,
            double beamEndY,
            double beamEndZ
    ) {
    }

    private record DynamicObjectResult(
            RtFrameSnapshot lastSnapshot,
            RtSceneReadiness readiness,
            RtCore.RuntimeActivity activity,
            int dynamicUpdates,
            long completedFrames,
            double averageCompletedFps,
            int distinctChecksums
    ) {
    }
}
