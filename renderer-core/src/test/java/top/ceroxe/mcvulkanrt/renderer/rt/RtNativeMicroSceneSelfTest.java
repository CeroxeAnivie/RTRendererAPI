package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.LightmapPayload;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneDatabase;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionGeometryCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMaterialCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedVoxelLighting;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RtNativeMicroSceneSelfTest {
    private static final int OUTPUT_SIZE = 64;
    private static final int HIGH_RES_OUTPUT_WIDTH = 960;
    private static final int HIGH_RES_OUTPUT_HEIGHT = 540;
    private static final int BLOCK_STATE_ID = 1;
    private static final int MIN_EXPECTED_FOREGROUND_PIXELS = 512;
    private static final int MAX_PUMP_FRAMES = 600;
    private static final long PUMP_SLEEP_MILLIS = 5L;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-micro-scene.png");
    private static final Path CAMERA_PROBE_SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-camera-probe-scene.png");

    private RtNativeMicroSceneSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installDiagnosticProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native micro-scene requires production RT hardware: " + capability.summary()
            );

            RtFrameSnapshot snapshot = runMicroScene(capability);
            writeSnapshotPng(snapshot, SNAPSHOT_PATH);
            require(
                    snapshot.foregroundPixels() >= MIN_EXPECTED_FOREGROUND_PIXELS,
                    "native micro-scene foreground coverage is implausibly sparse: "
                            + snapshot.asLogFragment()
                            + ", foregroundSample=" + foregroundSample(snapshot, 64)
                            + ", png=" + SNAPSHOT_PATH
                            + ", expectedAtLeast=" + MIN_EXPECTED_FOREGROUND_PIXELS
            );
            RtGBufferSnapshot gBuffer = runGBufferScene(capability);
            assertCenterGBuffer(gBuffer);
            RtFrameSnapshot cameraProbeSnapshot = runCameraProbeScene(capability);
            writeSnapshotPng(cameraProbeSnapshot, CAMERA_PROBE_SNAPSHOT_PATH);
            require(
                    cameraProbeSnapshot.foregroundPixels() >= 64,
                    "native smoke-camera probe scene missed nearby geometry: "
                            + cameraProbeSnapshot.asLogFragment()
                            + ", foregroundSample=" + foregroundSample(cameraProbeSnapshot, 64)
                            + ", png=" + CAMERA_PROBE_SNAPSHOT_PATH
            );
            RtFrameSnapshot dynamicReplacementSnapshot = runDynamicReplacementScene(capability);
            DirectionalShadowResult directionalShadow = runDirectionalShadowScene(capability);
            DynamicDeletionResult dynamicDeletion = runDynamicSectionDeletionScene(capability);
            DynamicDeletionResult dynamicBlockDeletion = runDynamicBlockDeletionWithinSectionScene(capability);
            RtFrameSnapshot alphaHighResolutionSnapshot = runAlphaMixedHighResolutionScene(capability);
            System.out.println("RtNativeMicroSceneSelfTest passed: "
                    + snapshot.asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", cameraProbe=" + cameraProbeSnapshot.asLogFragment()
                    + ", cameraProbePng=" + CAMERA_PROBE_SNAPSHOT_PATH
                    + ", dynamicReplacement=" + dynamicReplacementSnapshot.asLogFragment()
                    + ", directionalShadow=" + directionalShadow.asLogFragment()
                    + ", dynamicDeletion=" + dynamicDeletion.asLogFragment()
                    + ", dynamicBlockDeletion=" + dynamicBlockDeletion.asLogFragment()
                    + ", alphaHighResolution=" + alphaHighResolutionSnapshot.asLogFragment());
        } finally {
            restoreProperties(previousProperties);
        }
    }

    private static RtFrameSnapshot runMicroScene(VulkanRtCapabilityProbe.Result capability) throws InterruptedException {
        return runSceneUntilFreshSnapshot(
                capability,
                buildPreparedMeshFrameUpdate(fullSectionPositiveZQuad(new SectionKey(0, 0, 0)), frameState(1L))
        );
    }

    private static RtGBufferSnapshot runGBufferScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open diagnostic G-buffer backend");
            RendererFrameUpdate initial = buildPreparedMeshFrameUpdate(
                    fullSectionPositiveZQuad(new SectionKey(0, 0, 0)), frameState(1L));
            rtCore.acceptFrameUpdate(initial);
            require(rtCore.requestGBufferCapture(), "diagnostic G-buffer request was rejected");
            for (int frame = 2; frame <= MAX_PUMP_FRAMES + 1; frame++) {
                rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(
                        emptyBatch(), copyFrameStateSequence(initial.frameState(), frame)));
                RtGBufferSnapshot snapshot = rtCore.latestGBufferSnapshot();
                if (snapshot != null) {
                    return snapshot;
                }
                require(rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during diagnostic G-buffer capture: " + rtCore.summary().asLogFragment());
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }
            throw new AssertionError("diagnostic G-buffer did not complete: " + rtCore.summary().asLogFragment());
        }
    }

    private static void assertCenterGBuffer(RtGBufferSnapshot snapshot) {
        int center = (snapshot.height() / 2) * snapshot.width() + snapshot.width() / 2;
        require(Float.isFinite(snapshot.depth()[center]) && snapshot.depth()[center] > 0.0F,
                "center G-buffer depth must describe a real hit");
        require(snapshot.materialIds()[center] != -1, "center G-buffer material must not be the miss sentinel");
        require(snapshot.normalOct16()[center] == 0,
                "center G-buffer normal must encode the fixture's +Z surface exactly");
        require((snapshot.albedoRgba8()[center] & 0x00ff_ffff) != 0, "center G-buffer albedo must be populated");
    }

    private static RtFrameSnapshot runCameraProbeScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        return runSceneUntilFreshSnapshot(
                capability,
                buildFrameUpdate(smokeCameraProbeSection(), smokeCameraFrameState(1L))
        );
    }

    private static RtFrameSnapshot runDynamicReplacementScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic replacement: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            MicroSceneState scene = new MicroSceneState();
            SectionKey key = new SectionKey(0, 0, 0);
            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(tintedPositiveZQuad(key, 0xD03030), frameState(1L)));
            RtFrameSnapshot first = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic replacement first");
            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(tintedPositiveZQuad(key, 0x30D060), frameState(100L)));
            RtFrameSnapshot second = pumpUntilFreshSnapshot(
                    rtCore,
                    frameState(101L),
                    101L,
                    "dynamic replacement second",
                    snapshot -> snapshot.checksum() != first.checksum() || snapshot.center() != first.center()
            );
            require(
                    first.checksum() != second.checksum() || first.center() != second.center(),
                    "dynamic replacement did not change RT output: first=" + first.asLogFragment()
                            + ", second=" + second.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            return second;
        }
    }

    private static DirectionalShadowResult runDirectionalShadowScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for directional shadow scene");

            RendererFrameState initialFrame = frameState(1L);
            rtCore.acceptFrameUpdate(buildPreparedMeshFrameUpdate(
                    directionalShadowFixture(new SectionKey(0, 0, 0)),
                    initialFrame,
                    directionalShadowScene(1L, true)
            ));
            RtFrameSnapshot shadowed = pumpUntilFreshSnapshot(
                    rtCore,
                    frameState(2L),
                    2L,
                    "directional shadow enabled"
            );

            rtCore.acceptFrameUpdate(RendererFrameUpdate.dynamicOnly(
                    emptyBatch(),
                    frameState(100L),
                    RendererUpdateLoop.BacklogSnapshot.empty(),
                    directionalShadowScene(2L, false)
            ));
            RtFrameSnapshot unshadowed = pumpUntilFreshSnapshot(
                    rtCore,
                    frameState(101L),
                    101L,
                    "directional shadow disabled",
                    snapshot -> snapshot.checksum() != shadowed.checksum()
            );

            int sampleY = OUTPUT_SIZE / 2;
            int shadowSampleX = OUTPUT_SIZE * 7 / 16;
            int litSampleX = OUTPUT_SIZE * 10 / 16;
            int shadowedOccluded = pixelLuminance(shadowed, shadowSampleX, sampleY);
            int unshadowedOccluded = pixelLuminance(unshadowed, shadowSampleX, sampleY);
            int shadowedLit = pixelLuminance(shadowed, litSampleX, sampleY);
            int unshadowedLit = pixelLuminance(unshadowed, litSampleX, sampleY);
            require(unshadowedOccluded >= shadowedOccluded + 90,
                    "directional visibility ray did not darken the occluded terrain sample"
                            + ", shadowed=" + shadowedOccluded
                            + ", unshadowed=" + unshadowedOccluded
                            + ", shadowedFrame=" + shadowed.asLogFragment()
                            + ", unshadowedFrame=" + unshadowed.asLogFragment());
            require(Math.abs(unshadowedLit - shadowedLit) <= 24,
                    "directional visibility ray changed the non-occluded control sample"
                            + ", shadowed=" + shadowedLit
                            + ", unshadowed=" + unshadowedLit);
            return new DirectionalShadowResult(
                    shadowed,
                    unshadowed,
                    shadowedOccluded,
                    unshadowedOccluded,
                    shadowedLit,
                    unshadowedLit
            );
        }
    }

    private static DynamicDeletionResult runDynamicSectionDeletionScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(List.of(
                new RtTextureCatalog.TestTexture(
                        "selftest:dynamic_deletion_front",
                        2,
                        2,
                        solidTexture(216, 40, 40, 255, 2, 2)
                ),
                new RtTextureCatalog.TestTexture(
                        "selftest:dynamic_deletion_back",
                        2,
                        2,
                        solidTexture(32, 192, 96, 255, 2, 2)
                )
        ));
             GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic deletion: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            MicroSceneState scene = new MicroSceneState();
            SectionKey backKey = new SectionKey(0, 0, 0);
            SectionKey frontKey = new SectionKey(0, 0, 1);
            int frontTextureId = textures.textureId("selftest:dynamic_deletion_front");
            int backTextureId = textures.textureId("selftest:dynamic_deletion_back");
            rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(
                    Map.of(
                            backKey, texturedPositiveZQuad(backKey, backTextureId),
                            frontKey, texturedPositiveZQuad(frontKey, frontTextureId)
                    ),
                    frameState(1L)
            ));
            RtFrameSnapshot front = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic deletion front");
            require(
                    colorNear(front.center(), shadedRgba8(216, 40, 40, FaceDirection.POSITIVE_Z), 3),
                    "dynamic deletion setup did not hit the front red section with MC face shading: "
                            + front.asLogFragment()
                            + ", center=" + RtFrameSnapshot.hex(front.center())
                            + ", foregroundSample=" + foregroundSample(front, 32)
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            rtCore.acceptFrameUpdate(scene.removePreparedMesh(frontKey, frameState(100L)));
            RtFrameSnapshot revealed = pumpUntilFreshSnapshot(
                    rtCore,
                    frameState(101L),
                    101L,
                    "dynamic deletion reveal"
            );
            require(
                    front.center() != revealed.center() || front.checksum() != revealed.checksum(),
                    "section deletion did not change RT output: front=" + front.asLogFragment()
                            + ", revealed=" + revealed.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    colorNear(revealed.center(), shadedRgba8(32, 192, 96, FaceDirection.POSITIVE_Z), 3),
                    "section deletion removed the front section but did not reveal the back green section with MC face shading: "
                            + revealed.asLogFragment()
                            + ", center=" + RtFrameSnapshot.hex(revealed.center())
                            + ", foregroundSample=" + foregroundSample(revealed, 32)
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            return new DynamicDeletionResult(front, revealed);
        }
    }

    private static DynamicDeletionResult runDynamicBlockDeletionWithinSectionScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(List.of(
                new RtTextureCatalog.TestTexture(
                        "selftest:dynamic_block_deletion_front",
                        2,
                        2,
                        solidTexture(224, 48, 40, 255, 2, 2)
                ),
                new RtTextureCatalog.TestTexture(
                        "selftest:dynamic_block_deletion_back",
                        2,
                        2,
                        solidTexture(48, 176, 224, 255, 2, 2)
                )
        ));
             GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic block deletion: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            MicroSceneState scene = new MicroSceneState();
            SectionKey key = new SectionKey(0, 0, 0);
            int frontTextureId = textures.textureId("selftest:dynamic_block_deletion_front");
            int backTextureId = textures.textureId("selftest:dynamic_block_deletion_back");
            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(
                    stackedTexturedPositiveZQuads(key, frontTextureId, backTextureId),
                    frameState(1L)
            ));
            RtFrameSnapshot front = pumpUntilFreshSnapshot(rtCore, frameState(2L), 2L, "dynamic block deletion front");
            require(
                    colorNear(front.center(), shadedRgba8(224, 48, 40, FaceDirection.POSITIVE_Z), 3),
                    "dynamic block deletion setup did not hit the nearer red face inside the same section: "
                            + front.asLogFragment()
                            + ", center=" + RtFrameSnapshot.hex(front.center())
                            + ", foregroundSample=" + foregroundSample(front, 32)
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            rtCore.acceptFrameUpdate(scene.replacePreparedMesh(
                    texturedPositiveZQuadAtLocalZ(key, backTextureId, 8),
                    frameState(100L)
            ));
            RtFrameSnapshot revealed = pumpUntilFreshSnapshot(
                    rtCore,
                    frameState(101L),
                    101L,
                    "dynamic block deletion reveal"
            );
            require(
                    front.center() != revealed.center() || front.checksum() != revealed.checksum(),
                    "same-section block deletion did not change RT output: front=" + front.asLogFragment()
                            + ", revealed=" + revealed.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    colorNear(revealed.center(), shadedRgba8(48, 176, 224, FaceDirection.POSITIVE_Z), 3),
                    "same-section block deletion did not replace the stale front BLAS with the back cyan face: "
                            + revealed.asLogFragment()
                            + ", center=" + RtFrameSnapshot.hex(revealed.center())
                            + ", foregroundSample=" + foregroundSample(revealed, 32)
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            return new DynamicDeletionResult(front, revealed);
        }
    }

    private static RtFrameSnapshot runAlphaMixedHighResolutionScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        Map<String, String> previous = new LinkedHashMap<>();
        set(previous, "mcvulkanrt.rt.output.width", Integer.toString(HIGH_RES_OUTPUT_WIDTH));
        set(previous, "mcvulkanrt.rt.output.height", Integer.toString(HIGH_RES_OUTPUT_HEIGHT));
        set(previous, "mcvulkanrt.rt.output.maxPixels", Integer.toString(HIGH_RES_OUTPUT_WIDTH * HIGH_RES_OUTPUT_HEIGHT));
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for high-resolution alpha scene: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            RendererFrameState initialFrame = frameState(1L, HIGH_RES_OUTPUT_WIDTH, HIGH_RES_OUTPUT_HEIGHT);
            rtCore.acceptFrameUpdate(buildPreparedMeshFrameUpdate(alphaMixedPositiveZQuads(new SectionKey(0, 0, 0)), initialFrame));
            RtFrameSnapshot lastSnapshot = null;
            long firstSnapshotSequence = -1L;
            for (int frame = 2; frame <= 181; frame++) {
                RendererFrameState frameState = frameState(frame, HIGH_RES_OUTPUT_WIDTH, HIGH_RES_OUTPUT_HEIGHT);
                rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState));
                RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
                if (snapshot != null) {
                    if (firstSnapshotSequence < 0L) {
                        firstSnapshotSequence = snapshot.frameStateSequence();
                    }
                    lastSnapshot = snapshot;
                    if (frame >= 120 && snapshot.frameStateSequence() >= frame - 8L) {
                        require(
                                snapshot.width() == HIGH_RES_OUTPUT_WIDTH && snapshot.height() == HIGH_RES_OUTPUT_HEIGHT,
                                "high-resolution RT frame extent mismatch: " + snapshot.asLogFragment()
                        );
                        require(
                                snapshot.foregroundPixels() >= HIGH_RES_OUTPUT_WIDTH * HIGH_RES_OUTPUT_HEIGHT / 16,
                                "high-resolution alpha-mixed scene rendered too little geometry: "
                                        + snapshot.asLogFragment()
                                        + ", foregroundSample=" + foregroundSample(snapshot, 64)
                                        + ", summary=" + rtCore.summary().asLogFragment()
                        );
                        return snapshot;
                    }
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during high-resolution alpha scene: state=" + rtCore.state()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }
            throw new AssertionError("high-resolution alpha-mixed RT scene did not keep producing fresh frames"
                    + ", firstSnapshotSequence=" + firstSnapshotSequence
                    + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                    + ", summary=" + rtCore.summary().asLogFragment());
        } finally {
            restoreProperties(previous);
        }
    }

    private static RtFrameSnapshot runSceneUntilFreshSnapshot(
            VulkanRtCapabilityProbe.Result capability,
            RendererFrameUpdate initialUpdate
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            rtCore.acceptFrameUpdate(initialUpdate);

            RtFrameSnapshot lastSnapshot = null;
            long firstReadyPumpFrame = -1L;
            for (int frame = 2; frame <= MAX_PUMP_FRAMES + 1; frame++) {
                rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(
                        emptyBatch(),
                        copyFrameStateSequence(initialUpdate.frameState(), frame)
                ));
                lastSnapshot = rtCore.latestFrameSnapshot();
                RtSceneReadiness readiness = rtCore.sceneReadiness();
                if (firstReadyPumpFrame < 0L
                        && readiness.builtRevisionIsCurrent()
                        && !readiness.hasPendingRtBuilds()) {
                    firstReadyPumpFrame = frame;
                }
                if (lastSnapshot != null
                        && firstReadyPumpFrame >= 0L
                        && lastSnapshot.frameStateSequence() >= firstReadyPumpFrame) {
                    return lastSnapshot;
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during native micro-scene pump: state=" + rtCore.state()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

            throw new AssertionError("native micro-scene rendered only miss/background pixels"
                    + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                    + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                    + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                    + ", summary=" + rtCore.summary().asLogFragment());
        }
    }

    private static RtFrameSnapshot pumpUntilFreshSnapshot(
            GuardedRtCore rtCore,
            RendererFrameState firstPumpFrameState,
            long minimumSequence,
            String label
    ) throws InterruptedException {
        return pumpUntilFreshSnapshot(rtCore, firstPumpFrameState, minimumSequence, label, snapshot -> true);
    }

    private static RtFrameSnapshot pumpUntilFreshSnapshot(
            GuardedRtCore rtCore,
            RendererFrameState firstPumpFrameState,
            long minimumSequence,
            String label,
            java.util.function.Predicate<RtFrameSnapshot> snapshotPredicate
    ) throws InterruptedException {
        Objects.requireNonNull(snapshotPredicate, "snapshotPredicate");
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        long firstReadyPumpFrame = -1L;
        for (int frame = 0; frame < MAX_PUMP_FRAMES; frame++) {
            RendererFrameState frameState = copyFrameStateSequence(
                    firstPumpFrameState,
                    firstPumpFrameState.sequence() + frame
            );
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState));
            lastSnapshot = rtCore.latestFrameSnapshot();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            if (firstReadyPumpFrame < 0L
                    && readiness.builtRevisionIsCurrent()
                    && !readiness.hasPendingRtBuilds()) {
                firstReadyPumpFrame = frameState.sequence();
            }
            if (lastSnapshot != null
                    && firstReadyPumpFrame >= 0L
                    && lastSnapshot.frameStateSequence() >= Math.max(minimumSequence, firstReadyPumpFrame)
                    && snapshotPredicate.test(lastSnapshot)) {
                return lastSnapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed during " + label + ": state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " did not produce a fresh native RT snapshot"
                + ", minimumSequence=" + minimumSequence
                + ", firstReadyPumpFrame=" + firstReadyPumpFrame
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static RendererFrameUpdate buildPreparedMeshFrameUpdate(
            SectionTriangleMesh mesh,
            RendererFrameState frameState
    ) {
        return buildPreparedMeshFrameUpdate(mesh, frameState, DynamicRenderScene.empty());
    }

    private static RendererFrameUpdate buildPreparedMeshFrameUpdate(
            SectionTriangleMesh mesh,
            RendererFrameState frameState,
            DynamicRenderScene dynamicScene
    ) {
        Objects.requireNonNull(dynamicScene, "dynamicScene");
        SectionKey key = mesh.key();
        SceneDatabase database = new SceneDatabase();
        SectionMaterialCache materialCache = new SectionMaterialCache();
        SectionGeometryCache geometryCache = new SectionGeometryCache();
        SectionMeshCache meshCache = new SectionMeshCache();
        database.replaceChunkSnapshot(new ChunkSnapshot(key.chunkKey(), key.y(), List.of(filledSection(key, BLOCK_STATE_ID))));

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
        require(meshResult.trianglesInBatch() > 0, "micro-scene must submit visible section triangles");
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

    private static RendererFrameUpdate buildFrameUpdate(
            SectionKey key,
            int voxelTypeId,
            RendererFrameState frameState
    ) {
        return buildFrameUpdate(filledSection(key, voxelTypeId), frameState);
    }

    private static RendererFrameUpdate buildFrameUpdate(
            SectionVoxelSnapshot section,
            RendererFrameState frameState
    ) {
        SceneDatabase database = new SceneDatabase();
        SectionMaterialCache materialCache = new SectionMaterialCache();
        SectionGeometryCache geometryCache = new SectionGeometryCache();
        SectionMeshCache meshCache = new SectionMeshCache();
        database.replaceChunkSnapshot(new ChunkSnapshot(section.key().chunkKey(), section.key().y(), List.of(section)));

        SceneUpdateBatch batch = database.drainPendingUpdates();
        SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
        SectionGeometryCache.ApplyResult geometry = geometryCache.apply(
                material.encodedSections(),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        SectionMeshCache.ApplyResult mesh = meshCache.apply(
                geometry.geometrySections(),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        require(mesh.trianglesInBatch() > 0, "micro-scene must build visible section triangles");
        return new RendererFrameUpdate(batch, material, geometry, mesh, frameState);
    }

    private static RendererFrameState copyFrameStateSequence(RendererFrameState source, long sequence) {
        return new RendererFrameState(
                sequence,
                source.valid(),
                source.targetWidth(),
                source.targetHeight(),
                source.cameraX(),
                source.cameraY(),
                source.cameraZ(),
                source.cameraPitch(),
                source.cameraYaw(),
                source.cameraForwardX(),
                source.cameraForwardY(),
                source.cameraForwardZ(),
                source.cameraRightX(),
                source.cameraRightY(),
                source.cameraRightZ(),
                source.cameraUpX(),
                source.cameraUpY(),
                source.cameraUpZ(),
                source.projection00(),
                source.projection11(),
                source.projection22(),
                source.projection23(),
                source.projection32(),
                source.projection33(),
                source.renderBlockOutline(),
                source.renderBlockEntities()
        );
    }

    private static SectionTriangleMesh fullSectionPositiveZQuad(SectionKey key) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(16),
                        fixed(16), fixed(0), fixed(16),
                        fixed(16), fixed(16), fixed(16),
                        fixed(0), fixed(16), fixed(16),
                },
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{BLOCK_STATE_ID},
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()}
        );
    }

    private static SectionTriangleMesh tintedPositiveZQuad(SectionKey key, int mapColor) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(16),
                        fixed(16), fixed(0), fixed(16),
                        fixed(16), fixed(16), fixed(16),
                        fixed(0), fixed(16), fixed(16),
                },
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{BLOCK_STATE_ID},
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()},
                new int[]{mapColor},
                new byte[]{0},
                new byte[]{SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE},
                new int[]{0},
                new int[]{top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 0.0F)},
                new int[]{top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 0.0F)},
                new int[]{top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 1.0F)},
                new int[]{top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 1.0F)},
                new byte[]{1},
                new byte[]{0}
        );
    }

    /**
     * A front-facing receiver and a perpendicular blocker make the expected
     * visibility boundary independent of texture sampling. Rays travel toward
     * +X/+Z: receiver points left of x=12 are occluded while points to its
     * right retain an unobstructed control sample in the same material.
     */
    private static SectionTriangleMesh directionalShadowFixture(SectionKey key) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(8),
                        fixed(16), fixed(0), fixed(8),
                        fixed(16), fixed(16), fixed(8),
                        fixed(0), fixed(16), fixed(8),
                        fixed(12), fixed(0), fixed(8),
                        fixed(12), fixed(16), fixed(8),
                        fixed(12), fixed(16), fixed(16),
                        fixed(12), fixed(0), fixed(16),
                },
                new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7},
                new int[]{BLOCK_STATE_ID, BLOCK_STATE_ID},
                new byte[]{0, 0},
                new byte[]{
                        (byte) FaceDirection.POSITIVE_Z.ordinal(),
                        (byte) FaceDirection.POSITIVE_X.ordinal()
                },
                new int[]{0xE0E0E0, 0xE0E0E0},
                new byte[]{0, 0},
                new byte[]{
                        SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE,
                        SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                }
        );
    }

    private static DynamicRenderScene directionalShadowScene(long revision, boolean castsShadow) {
        float diagonal = 0.70710677F;
        return new DynamicRenderScene(
                revision,
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
                        castsShadow
                )),
                LightmapPayload.unknown()
        );
    }

    private static SectionTriangleMesh texturedPositiveZQuad(SectionKey key, int textureId) {
        return texturedPositiveZQuadAtLocalZ(key, textureId, 16);
    }

    private static SectionTriangleMesh texturedPositiveZQuadAtLocalZ(SectionKey key, int textureId, int localZ) {
        int vertexLighting = fullBrightPositiveZVertexLighting();
        byte knownLightMaterial = (byte) (SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN);
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(localZ),
                        fixed(16), fixed(0), fixed(localZ),
                        fixed(16), fixed(16), fixed(localZ),
                        fixed(0), fixed(16), fixed(localZ),
                },
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{BLOCK_STATE_ID},
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()},
                new int[]{0xFFFFFF},
                new int[]{vertexLighting},
                new int[]{vertexLighting},
                new int[]{vertexLighting},
                new int[]{vertexLighting},
                new byte[]{0},
                new byte[]{knownLightMaterial},
                new int[]{textureId},
                new int[]{RtTextureCatalog.packUv16(0.0F, 0.0F)},
                new int[]{RtTextureCatalog.packUv16(1.0F, 0.0F)},
                new int[]{RtTextureCatalog.packUv16(1.0F, 1.0F)},
                new int[]{RtTextureCatalog.packUv16(0.0F, 1.0F)},
                new byte[]{0},
                new byte[]{0}
        );
    }

    private static SectionTriangleMesh stackedTexturedPositiveZQuads(
            SectionKey key,
            int frontTextureId,
            int backTextureId
    ) {
        int vertexLighting = fullBrightPositiveZVertexLighting();
        byte knownLightMaterial = (byte) (SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN);
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(16),
                        fixed(16), fixed(0), fixed(16),
                        fixed(16), fixed(16), fixed(16),
                        fixed(0), fixed(16), fixed(16),
                        fixed(0), fixed(0), fixed(8),
                        fixed(16), fixed(0), fixed(8),
                        fixed(16), fixed(16), fixed(8),
                        fixed(0), fixed(16), fixed(8),
                },
                new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7},
                new int[]{BLOCK_STATE_ID, BLOCK_STATE_ID},
                new byte[]{0, 0},
                new byte[]{
                        (byte) FaceDirection.POSITIVE_Z.ordinal(),
                        (byte) FaceDirection.POSITIVE_Z.ordinal()
                },
                new int[]{0xFFFFFF, 0xFFFFFF},
                new int[]{vertexLighting, vertexLighting},
                new int[]{vertexLighting, vertexLighting},
                new int[]{vertexLighting, vertexLighting},
                new int[]{vertexLighting, vertexLighting},
                new byte[]{0, 0},
                new byte[]{
                        knownLightMaterial,
                        knownLightMaterial
                },
                new int[]{frontTextureId, backTextureId},
                new int[]{
                        RtTextureCatalog.packUv16(0.0F, 0.0F),
                        RtTextureCatalog.packUv16(0.0F, 0.0F)
                },
                new int[]{
                        RtTextureCatalog.packUv16(1.0F, 0.0F),
                        RtTextureCatalog.packUv16(1.0F, 0.0F)
                },
                new int[]{
                        RtTextureCatalog.packUv16(1.0F, 1.0F),
                        RtTextureCatalog.packUv16(1.0F, 1.0F)
                },
                new int[]{
                        RtTextureCatalog.packUv16(0.0F, 1.0F),
                        RtTextureCatalog.packUv16(0.0F, 1.0F)
                },
                new byte[]{0, 0},
                new byte[]{0, 0}
        );
    }

    private static SectionTriangleMesh alphaMixedPositiveZQuads(SectionKey key) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0), fixed(0), fixed(16),
                        fixed(8), fixed(0), fixed(16),
                        fixed(8), fixed(16), fixed(16),
                        fixed(0), fixed(16), fixed(16),
                        fixed(8), fixed(0), fixed(16),
                        fixed(16), fixed(0), fixed(16),
                        fixed(16), fixed(16), fixed(16),
                        fixed(8), fixed(16), fixed(16),
                },
                new int[]{0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7},
                new int[]{BLOCK_STATE_ID, BLOCK_STATE_ID},
                new byte[]{0, 0},
                new byte[]{
                        (byte) FaceDirection.POSITIVE_Z.ordinal(),
                        (byte) FaceDirection.POSITIVE_Z.ordinal()
                },
                new int[]{0x78D858, 0xD8D858},
                new byte[]{0, 0},
                new byte[]{
                        SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE,
                        SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                },
                new int[]{0, 0},
                new int[]{
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 0.0F),
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 0.0F)
                },
                new int[]{
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 0.0F),
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 0.0F)
                },
                new int[]{
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 1.0F),
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 1.0F)
                },
                new int[]{
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 1.0F),
                        top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 1.0F)
                },
                new byte[]{1, 1},
                new byte[]{0, 1}
        );
    }

    private static short fixed(int blockUnits) {
        return (short) (blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static int fullBrightPositiveZVertexLighting() {
        return PackedVoxelLighting.packVertex(
                PackedVoxelLighting.SMOOTH_LIGHT_MAX,
                PackedVoxelLighting.SMOOTH_LIGHT_MAX,
                PackedVoxelLighting.cardinalShade(FaceDirection.POSITIVE_Z)
        );
    }

    private static RendererFrameState frameState(long sequence) {
        return frameState(sequence, OUTPUT_SIZE, OUTPUT_SIZE);
    }

    private static RendererFrameState frameState(long sequence, int width, int height) {
        return new RendererFrameState(
                sequence,
                true,
                width,
                height,
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

    private static RendererFrameState smokeCameraFrameState(long sequence) {
        return new RendererFrameState(
                sequence,
                true,
                OUTPUT_SIZE,
                OUTPUT_SIZE,
                162.5D,
                76.61999988555908D,
                170.5D,
                60.0F,
                -0.15000002F,
                0.0012918696F,
                -0.8660254F,
                0.49999833F,
                -0.99999666F,
                -0.0F,
                0.0025837393F,
                0.0022375837F,
                0.5F,
                0.8660225F,
                0.8027061F,
                1.428148F,
                2.441466E-5F,
                -1.0F,
                0.050001223F,
                0.0F,
                true,
                true
        );
    }

    private static SectionVoxelSnapshot filledSection(SectionKey key, int voxelTypeId) {
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(ids, voxelTypeId);
        return new SectionVoxelSnapshot(key, ids, fluids, false, false);
    }

    private static SectionVoxelSnapshot smokeCameraProbeSection() {
        SectionKey key = new SectionKey(10, 4, 10);
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] lightEmissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] materialFlags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(materialFlags, (byte) SectionVoxelSnapshot.FLAG_AIR);

        for (int z = 10; z <= 12; z++) {
            for (int x = 1; x <= 3; x++) {
                int index = SectionVoxelSnapshot.localBlockIndex(x, 10, z);
                ids[index] = BLOCK_STATE_ID;
                mapColors[index] = 0x2f8f3f;
                materialFlags[index] = (byte) SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE;
            }
        }

        return new SectionVoxelSnapshot(
                key,
                ids,
                fluids,
                mapColors,
                lightEmissions,
                materialFlags,
                false,
                false
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

    private static Map<String, String> installDiagnosticProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
        set(previous, "mcvulkanrt.rt.output.readback.enabled", "true");
        set(previous, "mcvulkanrt.oracleGBuffer.enabled", "true");
        set(previous, "mcvulkanrt.rt.output.readback.interval", "1");
        set(previous, "mcvulkanrt.rt.output.dispatchInterval", "1");
        set(previous, "mcvulkanrt.rt.output.width", Integer.toString(OUTPUT_SIZE));
        set(previous, "mcvulkanrt.rt.output.height", Integer.toString(OUTPUT_SIZE));
        set(previous, "mcvulkanrt.rt.output.maxPixels", Integer.toString(OUTPUT_SIZE * OUTPUT_SIZE));
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

    private static String foregroundSample(RtFrameSnapshot snapshot, int maxPixels) {
        byte[] rgba = snapshot.copyRgba8();
        int background = RtSceneMaterialTable.missRgba8();
        StringBuilder sample = new StringBuilder("[");
        int emitted = 0;
        for (int y = 0; y < snapshot.height(); y++) {
            for (int x = 0; x < snapshot.width(); x++) {
                int pixel = RtFrameSnapshot.pixel(rgba, snapshot.width(), x, y);
                if (pixel == background) {
                    continue;
                }
                if (emitted > 0) {
                    sample.append(", ");
                }
                sample.append("(").append(x).append(",").append(y).append("=")
                        .append(RtFrameSnapshot.hex(pixel)).append(")");
                emitted++;
                if (emitted >= maxPixels) {
                    sample.append(", ...");
                    return sample.append("]").toString();
                }
            }
        }
        return sample.append("]").toString();
    }

    private static int[] solidTexture(int red, int green, int blue, int alpha, int width, int height) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, rgba8(red, green, blue, alpha));
        return pixels;
    }

    private static int rgba8(int red, int green, int blue, int alpha) {
        return (red & 0xFF)
                | ((green & 0xFF) << 8)
                | ((blue & 0xFF) << 16)
                | ((alpha & 0xFF) << 24);
    }

    private static int pixelLuminance(RtFrameSnapshot snapshot, int x, int y) {
        if (x < 0 || x >= snapshot.width() || y < 0 || y >= snapshot.height()) {
            throw new IllegalArgumentException("pixel coordinate outside snapshot: (" + x + "," + y + ")");
        }
        int rgba8 = RtFrameSnapshot.pixel(snapshot.copyRgba8(), snapshot.width(), x, y);
        int red = rgba8 & 0xFF;
        int green = (rgba8 >>> 8) & 0xFF;
        int blue = (rgba8 >>> 16) & 0xFF;
        return (red * 54 + green * 183 + blue * 19 + 128) >>> 8;
    }

    private static int shadedRgba8(int red, int green, int blue, FaceDirection direction) {
        double vanillaDirectional = switch (direction) {
            case NEGATIVE_Y -> 0.50D;
            case POSITIVE_Y -> 1.00D;
            case NEGATIVE_X, POSITIVE_X -> 0.60D;
            case NEGATIVE_Z, POSITIVE_Z -> 0.80D;
        };
        double shade = vanillaDirectional;
        return rgba8(
                (int) Math.round(red * shade),
                (int) Math.round(green * shade),
                (int) Math.round(blue * shade),
                255
        );
    }

    private static boolean colorNear(int actual, int expected, int tolerance) {
        return Math.abs((actual & 0xFF) - (expected & 0xFF)) <= tolerance
                && Math.abs(((actual >>> 8) & 0xFF) - ((expected >>> 8) & 0xFF)) <= tolerance
                && Math.abs(((actual >>> 16) & 0xFF) - ((expected >>> 16) & 0xFF)) <= tolerance
                && Math.abs(((actual >>> 24) & 0xFF) - ((expected >>> 24) & 0xFF)) <= tolerance;
    }

    private static final class MicroSceneState {
        private final SceneDatabase database = new SceneDatabase();
        private final SectionMaterialCache materialCache = new SectionMaterialCache();
        private final SectionGeometryCache geometryCache = new SectionGeometryCache();
        private final SectionMeshCache meshCache = new SectionMeshCache();

        private RendererFrameUpdate replacePreparedMesh(
                SectionTriangleMesh mesh,
                RendererFrameState frameState
        ) {
            return replacePreparedMeshes(Map.of(mesh.key(), mesh), frameState);
        }

        private RendererFrameUpdate replacePreparedMeshes(
                Map<SectionKey, SectionTriangleMesh> meshes,
                RendererFrameState frameState
        ) {
            require(!meshes.isEmpty(), "dynamic micro-scene replacement must not be empty");
            for (SectionTriangleMesh mesh : meshes.values()) {
                database.replaceBlockMutationSectionSnapshot(filledSection(mesh.key(), BLOCK_STATE_ID));
            }
            SceneUpdateBatch batch = database.drainPendingUpdates();
            if (!batch.hasChanges()) {
                batch = preparedMeshBatch(meshes);
            }
            SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
            SectionGeometryCache.ApplyResult geometry = geometryCache.apply(
                    material.encodedSections(),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(
                    meshes,
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            require(meshResult.trianglesInBatch() > 0, "dynamic micro-scene must submit visible section triangles");
            return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
        }

        private static SceneUpdateBatch preparedMeshBatch(Map<SectionKey, SectionTriangleMesh> meshes) {
            Set<SectionKey> dirtySections = Set.copyOf(meshes.keySet());
            Set<ChunkKey> dirtyChunks = new LinkedHashSet<>();
            Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
            for (SectionKey key : dirtySections) {
                dirtyChunks.add(key.chunkKey());
                snapshots.put(key, filledSection(key, BLOCK_STATE_ID));
            }
            return new SceneUpdateBatch(
                    dirtySections,
                    dirtyChunks,
                    Set.of(),
                    Set.of(),
                    snapshots,
                    false,
                    dirtySections.size(),
                    dirtySections.size(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    SceneUpdateBatch.sourceFlagsForBlockMutation()
            );
        }

        private RendererFrameUpdate removePreparedMesh(
                SectionKey key,
                RendererFrameState frameState
        ) {
            database.removeBlockMutationSectionSnapshot(key);
            SceneUpdateBatch batch = database.drainPendingUpdates();
            SectionMaterialCache.ApplyResult material = materialCache.apply(batch);
            SectionGeometryCache.ApplyResult geometry = geometryCache.apply(
                    material.encodedSections(),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(
                    Map.of(),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            require(meshResult.removedInBatch() > 0, "dynamic micro-scene removal must remove a prepared section");
            return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
        }
    }

    private record DynamicDeletionResult(RtFrameSnapshot front, RtFrameSnapshot revealed) {
        private String asLogFragment() {
            return "dynamicDeletion{front=" + front.asLogFragment()
                    + ", revealed=" + revealed.asLogFragment()
                    + ", frontCenter=" + RtFrameSnapshot.hex(front.center())
                    + ", revealedCenter=" + RtFrameSnapshot.hex(revealed.center())
                    + "}";
        }
    }

    private record DirectionalShadowResult(
            RtFrameSnapshot shadowed,
            RtFrameSnapshot unshadowed,
            int shadowedOccludedLuminance,
            int unshadowedOccludedLuminance,
            int shadowedLitLuminance,
            int unshadowedLitLuminance
    ) {
        private String asLogFragment() {
            return "directionalShadow{shadowed=" + shadowed.asLogFragment()
                    + ", unshadowed=" + unshadowed.asLogFragment()
                    + ", occluded=" + shadowedOccludedLuminance + "->" + unshadowedOccludedLuminance
                    + ", control=" + shadowedLitLuminance + "->" + unshadowedLitLuminance
                    + "}";
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
