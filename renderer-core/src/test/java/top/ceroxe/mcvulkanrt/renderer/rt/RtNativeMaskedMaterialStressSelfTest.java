package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.CameraRayMath;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Hardware-backed MC-outside stress scene for the exact masked-material failure
 * class seen in-world: cutout leaves/grass/cocoa-like flat quads turning into
 * sky-blue misses, holes failing to reveal geometry behind them, stale dynamic
 * block replacements, and low-frame stalls under descriptor/TLAS churn.
 */
public final class RtNativeMaskedMaterialStressSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.maskedStress.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.maskedStress.height", 540);
    private static final int SECTION_COLUMNS = intProperty("mcvulkanrt.rt.maskedStress.sectionColumns", 41);
    private static final int SECTION_ROWS = intProperty("mcvulkanrt.rt.maskedStress.sectionRows", 21);
    private static final int TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
    private static final int MAX_INITIAL_READY_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.maskedStress.maxInitialReadyPumpFrames", 3600);
    private static final int SUSTAINED_FRAMES = intProperty("mcvulkanrt.rt.maskedStress.sustainedFrames", 240);
    private static final int MAX_FINAL_DRAIN_FRAMES =
            intProperty("mcvulkanrt.rt.maskedStress.maxFinalDrainFrames", 2400);
    private static final int MUTATION_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.maskedStress.mutationPeriodFrames", 8);
    private static final int MUTATIONS_PER_BURST =
            intProperty("mcvulkanrt.rt.maskedStress.mutationsPerBurst", 48);
    private static final int MAX_READY_SNAPSHOT_LAG =
            intProperty("mcvulkanrt.rt.maskedStress.maxReadySnapshotLag", 180);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.maskedStress.readbackSampleInterval", 5);
    private static final long MAX_READY_PENDING_FRAME_AGE_MILLIS =
            longProperty("mcvulkanrt.rt.maskedStress.maxReadyPendingFrameAgeMillis", 1500L);
    private static final long MAX_READY_COMPLETION_STALL_MILLIS =
            longProperty("mcvulkanrt.rt.maskedStress.maxReadyCompletionStallMillis", 1500L);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.maskedStress.pumpSleepMillis", 6L);
    private static final double MIN_COMPLETED_FPS =
            doubleProperty("mcvulkanrt.rt.maskedStress.minCompletedFps", 15.0D);
    private static final boolean EXPORT_SHARED_FRAME_ENABLED =
            booleanProperty("mcvulkanrt.rt.maskedStress.exportSharedFrame.enabled", true);
    private static final int SHARED_FRAME_EXPORT_SAMPLE_DELTA =
            intProperty("mcvulkanrt.rt.maskedStress.sharedFrameExportSampleDelta", 30);
    private static final int BLOCK_STATE_ID = 1;
    private static final float FRONT_Z = 15.95F;
    private static final float BACK_Z = 15.70F;
    private static final float CANOPY_Z = 15.99F;
    private static final String CUTOUT_A_TEXTURE = "mcvulkanrt:selftest/cutout_a";
    private static final String CUTOUT_B_TEXTURE = "mcvulkanrt:selftest/cutout_b";
    private static final String BACKPLATE_TEXTURE = "mcvulkanrt:selftest/backplate";
    private static final String COCOA_TEXTURE = "mcvulkanrt:selftest/cocoa_flat";
    private static final int BACKPLATE_RED = 68;
    private static final int BACKPLATE_GREEN = 74;
    private static final int BACKPLATE_BLUE = 84;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-masked-material-stress.png");

    private RtNativeMaskedMaterialStressSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installStressProperties();
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures())) {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native masked-material stress requires production RT hardware: " + capability.summary()
            );

            StressResult result = runStressScene(capability, textures);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeMaskedMaterialStressSelfTest passed: sections=" + TOTAL_SECTIONS
                    + ", sustainedFrames=" + SUSTAINED_FRAMES
                    + ", dynamicBursts=" + result.dynamicBursts()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", completedFrames=" + result.completedFrames()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", maxReadyPendingFrameAgeMillis=" + result.maxReadyPendingFrameAgeMillis()
                    + ", maxReadyCompletionStallMillis=" + result.maxReadyCompletionStallMillis()
                    + ", maxReadySnapshotLag=" + result.maxReadySnapshotLag()
                    + ", initialProbe=" + result.initialProbe().asLogFragment()
                    + ", finalProbe=" + result.finalProbe().asLogFragment()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", readiness=" + result.readiness().asLogFragment()
                    + ", activity=" + result.activity().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene(
                    "maskedMaterial",
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

    private static StressResult runStressScene(
            VulkanRtCapabilityProbe.Result capability,
            RtTextureCatalog.TestTextureScope textures
    ) throws Exception {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for masked-material stress: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            MaskedSceneState scene = new MaskedSceneState();
            List<SectionKey> keys = buildSectionKeys();
            SectionKey probeKey = probeKey();
            rtCore.acceptFrameUpdate(scene.initialUpdate(buildMeshes(keys, textures, 0), frameState(1L)));
            RtFrameSnapshot initialSnapshot = pumpUntilProbeReady(
                    rtCore,
                    2L,
                    0,
                    MAX_INITIAL_READY_PUMP_FRAMES,
                    "initial masked-material scene"
            );
            ProbeSamples initialProbe = assertProbePixels(initialSnapshot, 0, "initial");
            RtNativeStressGuards.assertFrameNotPathological(
                    initialSnapshot,
                    "initial masked-material stress frame"
            );

            long phaseStartNanos = System.nanoTime();
            long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
            long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
            long lastCompletionNanos = System.nanoTime();
            long lastExportedSharedFrameSequence = -1L;
            long completedFrameCount = 0L;
            long maxPendingAge = 0L;
            long maxCompletionStallMillis = 0L;
            long maxSnapshotLag = 0L;
            int dynamicBursts = 0;
            int expectedProbeVariant = 0;
            boolean observedProbeVariant0 = true;
            boolean observedProbeVariant1 = false;
            Set<Long> checksums = new HashSet<>();
            checksums.add(initialSnapshot.checksum());
            RtFrameSnapshot lastSnapshot = initialSnapshot;

            for (int frame = 0; frame < SUSTAINED_FRAMES; frame++) {
                long sequence = 10_000L + frame;
                RendererFrameUpdate update;
                if (frame % MUTATION_PERIOD_FRAMES == 0) {
                    dynamicBursts++;
                    expectedProbeVariant = dynamicBursts & 1;
                    update = scene.replacePreparedMeshes(
                            mutationMeshes(keys, probeKey, textures, dynamicBursts, expectedProbeVariant),
                            frameState(sequence)
                    );
                } else {
                    update = RendererFrameUpdate.empty(emptyBatch(), frameState(sequence));
                }

                rtCore.acceptFrameUpdate(update);
                RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
                RtSceneReadiness readiness = rtCore.sceneReadiness();
                long nowNanos = System.nanoTime();
                if (activity.latestCompletedFrameDispatch() > lastCompletedDispatch) {
                    completedFrameCount += activity.latestCompletedFrameDispatch() - lastCompletedDispatch;
                    lastCompletedDispatch = activity.latestCompletedFrameDispatch();
                    lastCompletedSequence = activity.latestCompletedFrameStateSequence();
                    lastCompletionNanos = nowNanos;
                }
                RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
                if (snapshot != null) {
                    lastSnapshot = snapshot;
                    checksums.add(snapshot.checksum());
                }
                if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                    long pendingAge = activity.pendingFrameAgeMillis();
                    long snapshotLag = snapshot == null ? Long.MAX_VALUE : Math.max(0L, sequence - snapshot.frameStateSequence());
                    long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1_000_000L;
                    maxPendingAge = Math.max(maxPendingAge, pendingAge);
                    maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
                    maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
                    require(
                            pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS,
                            "masked-material stress has a stale pending RT frame after scene became current"
                                    + ", sequence=" + sequence
                                    + ", pendingAgeMillis=" + pendingAge
                                    + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                    require(
                            completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                            "masked-material stress completed-frame stream stalled after scene became current"
                                    + ", sequence=" + sequence
                                    + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                                    + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                    + ", completionStallMillis=" + completionStallMillis
                                    + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                    long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
                    if (activity.latestCompletedFrameStateSequence() >= sequence) {
                        require(
                                completedLag <= MAX_READY_SNAPSHOT_LAG,
                                "masked-material completed RT output is too far behind a ready scene"
                                        + ", sequence=" + sequence
                                        + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                        + ", completedLag=" + completedLag
                                        + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                        + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                                        + ", readiness=" + readiness.asLogFragment()
                                        + ", activity=" + activity.asLogFragment()
                                        + ", summary=" + rtCore.summary().asLogFragment()
                        );
                    }
                    if (snapshot != null && snapshot.frameStateSequence() >= sequence) {
                        require(
                                snapshotLag <= MAX_READY_SNAPSHOT_LAG,
                                "masked-material diagnostic snapshot is too far behind a ready scene"
                                        + ", sequence=" + sequence
                                        + ", snapshotLag=" + snapshotLag
                                        + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                        + ", snapshot=" + snapshot.asLogFragment()
                                        + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                        + ", readiness=" + readiness.asLogFragment()
                                        + ", activity=" + activity.asLogFragment()
                                        + ", summary=" + rtCore.summary().asLogFragment()
                        );
                    }
                    if (snapshot != null && snapshot.frameStateSequence() >= sequence
                            && snapshotLag <= MAX_READY_SNAPSHOT_LAG) {
                        ProbeSamples samples = assertSustainedProbePixels(snapshot, "sustained frame " + frame);
                        observedProbeVariant0 |= matchesProbeVariant(snapshot, samples, 0);
                        observedProbeVariant1 |= matchesProbeVariant(snapshot, samples, 1);
                        RtNativeStressGuards.assertFrameNotPathological(
                                snapshot,
                                "masked-material ready frame " + frame
                        );
                    }
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during masked-material stress: state=" + rtCore.state()
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                if (EXPORT_SHARED_FRAME_ENABLED
                        && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
                    lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                            rtCore,
                            true,
                            activity.latestCompletedFrameStateSequence(),
                            lastExportedSharedFrameSequence,
                            SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                            false,
                            "masked-material stress frame " + frame
                    );
                }
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

            int finalProbeVariant = 1;
            rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(
                    Map.of(probeKey, probeSectionMesh(probeKey, textures, finalProbeVariant)),
                    frameState(20_000L)
            ));
            DrainResult drain = pumpUntilProbeDrained(
                    rtCore,
                    20_001L,
                    finalProbeVariant,
                    MAX_FINAL_DRAIN_FRAMES,
                    lastCompletedSequence,
                    lastCompletedDispatch,
                    lastCompletionNanos,
                    lastExportedSharedFrameSequence
            );
            lastSnapshot = drain.snapshot();
            lastExportedSharedFrameSequence = drain.lastExportedSharedFrameSequence();
            checksums.add(lastSnapshot.checksum());
            completedFrameCount += drain.completedFrames();
            maxPendingAge = Math.max(maxPendingAge, drain.maxPendingFrameAgeMillis());
            maxCompletionStallMillis = Math.max(maxCompletionStallMillis, drain.maxCompletionStallMillis());
            maxSnapshotLag = Math.max(maxSnapshotLag, drain.maxSnapshotLag());
            ProbeSamples finalProbe = assertProbePixels(lastSnapshot, finalProbeVariant, "final");
            observedProbeVariant1 |= matchesProbeVariant(lastSnapshot, finalProbe, finalProbeVariant);
            require(
                    observedProbeVariant0 && observedProbeVariant1,
                    "masked-material stress did not observe both dynamic cutout texture variants after the final explicit replacement"
                            + ", observedProbeVariant0=" + observedProbeVariant0
                            + ", observedProbeVariant1=" + observedProbeVariant1
                            + ", finalProbe=" + finalProbe.asLogFragment()
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtNativeStressGuards.assertFrameNotPathological(
                    lastSnapshot,
                    "final masked-material stress frame"
            );

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                    rtCore,
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                    true,
                    "masked-material final frame"
            );
            double averageCompletedFps = completedFrameCount * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= MIN_COMPLETED_FPS,
                    "masked-material stress completed frames below fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", minCompletedFps=" + MIN_COMPLETED_FPS
                            + ", completedFrames=" + completedFrameCount
                            + ", elapsedMillis=" + elapsedNanos / 1_000_000L
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    "masked-material stress scene"
            );
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "masked-material stress scene");
            require(
                    checksums.size() >= 4,
                    "masked-material dynamic scene did not visibly change across replacements"
                            + ", distinctChecksums=" + checksums.size()
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    initialProbe.primaryColor() != finalProbe.primaryColor(),
                    "probe texture replacement did not reach visible RT output"
                            + ", initialProbe=" + initialProbe.asLogFragment()
                            + ", finalProbe=" + finalProbe.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            return new StressResult(
                    lastSnapshot,
                    rtCore.sceneReadiness(),
                    finalActivity,
                    initialProbe,
                    finalProbe,
                    dynamicBursts,
                    checksums.size(),
                    completedFrameCount,
                    averageCompletedFps,
                    maxPendingAge,
                    maxCompletionStallMillis,
                    maxSnapshotLag
            );
        }
    }

    private static RtFrameSnapshot pumpUntilProbeReady(
            GuardedRtCore rtCore,
            long firstSequence,
            int expectedProbeVariant,
            int maxPumpFrames,
            String label
    ) throws InterruptedException {
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        long firstReadySequence = -1L;
        for (int frame = 0; frame < maxPumpFrames; frame++) {
            long sequence = firstSequence + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                firstReadySequence = sequence;
            }
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null) {
                lastSnapshot = snapshot;
                if (firstReadySequence >= 0L && snapshot.frameStateSequence() >= firstReadySequence) {
                    assertProbePixels(snapshot, expectedProbeVariant, label);
                    return snapshot;
                }
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while waiting for " + label + ": state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError(label + " never produced a probe-valid RT output"
                + ", firstReadySequence=" + firstReadySequence
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static DrainResult pumpUntilProbeDrained(
            GuardedRtCore rtCore,
            long firstSequence,
            int expectedProbeVariant,
            int maxPumpFrames,
            long initialCompletedSequence,
            long initialCompletedDispatch,
            long initialCompletionNanos,
            long initialExportedSharedFrameSequence
    ) throws InterruptedException {
        long lastCompletedSequence = initialCompletedSequence;
        long lastCompletedDispatch = initialCompletedDispatch;
        long lastCompletionNanos = initialCompletionNanos;
        long lastExportedSharedFrameSequence = initialExportedSharedFrameSequence;
        long completedFrames = 0L;
        long maxPendingAge = 0L;
        long maxCompletionStallMillis = 0L;
        long maxSnapshotLag = 0L;
        long firstReadySequence = -1L;
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        for (int frame = 0; frame < maxPumpFrames; frame++) {
            long sequence = firstSequence + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            long nowNanos = System.nanoTime();
            if (activity.latestCompletedFrameDispatch() > lastCompletedDispatch) {
                completedFrames += activity.latestCompletedFrameDispatch() - lastCompletedDispatch;
                lastCompletedDispatch = activity.latestCompletedFrameDispatch();
                lastCompletedSequence = activity.latestCompletedFrameStateSequence();
                lastCompletionNanos = nowNanos;
            }
            if (EXPORT_SHARED_FRAME_ENABLED
                    && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
                lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                        rtCore,
                        true,
                        activity.latestCompletedFrameStateSequence(),
                        lastExportedSharedFrameSequence,
                        SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                        false,
                        "masked-material drain frame " + frame
                );
            }
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null) {
                lastSnapshot = snapshot;
            }

            long pendingAge = activity.pendingFrameAgeMillis();
            long snapshotLag = snapshot == null ? Long.MAX_VALUE : Math.max(0L, sequence - snapshot.frameStateSequence());
            long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1_000_000L;
            maxPendingAge = Math.max(maxPendingAge, pendingAge);
            maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
            maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
            require(
                    pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS,
                    "masked-material final drain has a stale pending RT frame"
                            + ", sequence=" + sequence
                            + ", pendingAgeMillis=" + pendingAge
                            + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                    "masked-material final drain completed-frame stream stalled"
                            + ", sequence=" + sequence
                            + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                            + ", completionStallMillis=" + completionStallMillis
                            + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
                if (activity.latestCompletedFrameStateSequence() >= firstSequence) {
                    require(
                            completedLag <= MAX_READY_SNAPSHOT_LAG,
                            "masked-material final drain completed RT output is too far behind a ready scene"
                                    + ", sequence=" + sequence
                                    + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                    + ", completedLag=" + completedLag
                                    + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                    + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                }
                if (snapshot != null && snapshot.frameStateSequence() >= firstSequence) {
                    require(
                            snapshotLag <= MAX_READY_SNAPSHOT_LAG,
                            "masked-material final drain diagnostic snapshot is too far behind a ready scene"
                                    + ", sequence=" + sequence
                                    + ", snapshotLag=" + snapshotLag
                                    + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                    + ", snapshot=" + snapshot.asLogFragment()
                                    + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                }
            }

            if (lastSnapshot != null
                    && readiness.builtRevisionIsCurrent()
                    && !readiness.hasPendingRtBuilds()
                    && firstReadySequence < 0L) {
                firstReadySequence = sequence;
            }
            if (lastSnapshot != null
                    && firstReadySequence >= 0L
                    && lastSnapshot.frameStateSequence() >= firstReadySequence
                    && lastSnapshot.frameStateSequence() >= sequence - MAX_READY_SNAPSHOT_LAG) {
                RtNativeStressGuards.assertFrameNotPathological(
                        lastSnapshot,
                        "masked-material drained frame"
                );
                ProbeSamples samples = probeSamples(lastSnapshot);
                if (!matchesProbeVariant(lastSnapshot, samples, expectedProbeVariant)) {
                    Thread.sleep(PUMP_SLEEP_MILLIS);
                    continue;
                }
                try {
                    assertProbePixels(lastSnapshot, expectedProbeVariant, "final drain");
                } catch (AssertionError failure) {
                    throw new AssertionError(failure.getMessage()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment(), failure);
                }
                return new DrainResult(
                        lastSnapshot,
                        completedFrames,
                        maxPendingAge,
                        maxCompletionStallMillis,
                        maxSnapshotLag,
                        lastExportedSharedFrameSequence
                );
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed during masked-material final drain: state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        if (lastSnapshot != null) {
            try {
                assertProbePixels(lastSnapshot, expectedProbeVariant, "final drain timeout");
            } catch (AssertionError failure) {
                throw new AssertionError(failure.getMessage()
                        + ", firstReadySequence=" + firstReadySequence
                        + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                        + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                        + ", summary=" + rtCore.summary().asLogFragment(), failure);
            }
        }
        throw new AssertionError("masked-material final drain never reached current RT output"
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static ProbeSamples assertProbePixels(RtFrameSnapshot snapshot, int expectedVariant, String label) {
        ProbeSamples samples = probeSamples(snapshot);
        IntPredicate primaryPredicate = expectedVariant == 0
                ? RtNativeMaskedMaterialStressSelfTest::isStrongRed
                : RtNativeMaskedMaterialStressSelfTest::isStrongBlue;
        IntPredicate secondaryPredicate = expectedVariant == 0
                ? RtNativeMaskedMaterialStressSelfTest::isStrongGreen
                : RtNativeMaskedMaterialStressSelfTest::isStrongYellow;

        require(
                countMatching(snapshot, samples.primaryX(), samples.primaryY(), 2, primaryPredicate) >= 3,
                label + " primary solid cutout texel was not shaded from the front masked texture"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.primaryX(), samples.primaryY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.secondaryX(), samples.secondaryY(), 2, secondaryPredicate) >= 3,
                label + " secondary solid cutout texel has wrong UV orientation or wrong texture"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.secondaryX(), samples.secondaryY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.holeX(), samples.holeY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3,
                label + " transparent cutout hole did not reveal the opaque backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.holeX(), samples.holeY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3,
                label + " second transparent cutout hole did not reveal the opaque backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                samples.primaryColor() != RtSceneMaterialTable.missRgba8()
                        && samples.secondaryColor() != RtSceneMaterialTable.missRgba8(),
                label + " solid masked texel collapsed into miss/sky blue"
                        + ", samples=" + samples.asLogFragment()
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                samples.holeColor() != RtSceneMaterialTable.missRgba8()
                        && samples.secondHoleColor() != RtSceneMaterialTable.missRgba8(),
                label + " transparent masked hole skipped the opaque backplate and fell through to sky"
                        + ", samples=" + samples.asLogFragment()
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        assertCanopyProbePixel(snapshot, label);
        return samples;
    }

    private static ProbeSamples assertSustainedProbePixels(RtFrameSnapshot snapshot, String label) {
        ProbeSamples samples = probeSamples(snapshot);
        require(
                countMatching(
                        snapshot,
                        samples.primaryX(),
                        samples.primaryY(),
                        2,
                        pixel -> isStrongRed(pixel) || isStrongBlue(pixel)
                ) >= 3,
                label + " primary solid cutout texel was neither valid dynamic texture variant"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.primaryX(), samples.primaryY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(
                        snapshot,
                        samples.secondaryX(),
                        samples.secondaryY(),
                        2,
                        pixel -> isStrongGreen(pixel) || isStrongYellow(pixel)
                ) >= 3,
                label + " secondary solid cutout texel has wrong UV orientation or an unknown dynamic texture"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.secondaryX(), samples.secondaryY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.holeX(), samples.holeY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3,
                label + " transparent cutout hole did not reveal the opaque backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.holeX(), samples.holeY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2, RtNativeMaskedMaterialStressSelfTest::isBackplate) >= 3,
                label + " second transparent cutout hole did not reveal the opaque backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.secondHoleX(), samples.secondHoleY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                samples.primaryColor() != RtSceneMaterialTable.missRgba8()
                        && samples.secondaryColor() != RtSceneMaterialTable.missRgba8()
                        && samples.holeColor() != RtSceneMaterialTable.missRgba8()
                        && samples.secondHoleColor() != RtSceneMaterialTable.missRgba8(),
                label + " masked probe collapsed into miss/sky blue"
                        + ", samples=" + samples.asLogFragment()
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        assertCanopyProbePixel(snapshot, label);
        return samples;
    }

    private static void assertCanopyProbePixel(RtFrameSnapshot snapshot, String label) {
        SectionKey key = probeKey();
        int canopyX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 8.0F, CANOPY_Z);
        int canopyY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 8.0F, CANOPY_Z);
        require(
                countMatching(snapshot, canopyX, canopyY, 2, RtNativeMaskedMaterialStressSelfTest::isCocoaBrown) >= 3,
                label + " dense canopy/cocoa cutout face used the wrong material record or disappeared"
                        + ", canopy=(" + canopyX + "," + canopyY + ")"
                        + ", window=" + sampleWindow(snapshot, canopyX, canopyY, 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
    }

    private static boolean matchesProbeVariant(RtFrameSnapshot snapshot, ProbeSamples samples, int variant) {
        if (variant == 0) {
            return countMatching(
                    snapshot,
                    samples.primaryX(),
                    samples.primaryY(),
                    2,
                    RtNativeMaskedMaterialStressSelfTest::isStrongRed
            ) >= 3
                    && countMatching(
                    snapshot,
                    samples.secondaryX(),
                    samples.secondaryY(),
                    2,
                    RtNativeMaskedMaterialStressSelfTest::isStrongGreen
            ) >= 3;
        }
        return countMatching(
                snapshot,
                samples.primaryX(),
                samples.primaryY(),
                2,
                RtNativeMaskedMaterialStressSelfTest::isStrongBlue
        ) >= 3
                && countMatching(
                snapshot,
                samples.secondaryX(),
                samples.secondaryY(),
                2,
                RtNativeMaskedMaterialStressSelfTest::isStrongYellow
        ) >= 3;
    }

    private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
        if (latestCompletedSequence < 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, sequence - latestCompletedSequence);
    }

    private static ProbeSamples probeSamples(RtFrameSnapshot snapshot) {
        SectionKey key = probeKey();
        int primaryX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 12.0F, FRONT_Z);
        int primaryY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 4.0F, FRONT_Z);
        int secondaryX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 4.0F, FRONT_Z);
        int secondaryY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 12.0F, FRONT_Z);
        int holeX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 4.0F, FRONT_Z);
        int holeY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 4.0F, FRONT_Z);
        int secondHoleX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 12.0F, FRONT_Z);
        int secondHoleY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 12.0F, FRONT_Z);
        byte[] pixels = snapshot.copyRgba8();
        return new ProbeSamples(
                primaryX,
                primaryY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), primaryX, primaryY),
                secondaryX,
                secondaryY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), secondaryX, secondaryY),
                holeX,
                holeY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), holeX, holeY),
                secondHoleX,
                secondHoleY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), secondHoleX, secondHoleY)
        );
    }

    private static int pixelXForWorld(int width, int height, float worldX, float worldZ) {
        RendererFrameState frameState = frameState(0L);
        CameraRayMath.RayScale scale = CameraRayMath.rayScale(frameState, width, height);
        float distance = (float) frameState.cameraZ() - worldZ;
        float ndcX = (worldX - (float) frameState.cameraX()) / (distance * scale.horizontalTan());
        return clampPixel(Math.round(((ndcX + 1.0F) * 0.5F * width) - 0.5F), width);
    }

    private static int pixelYForWorld(int width, int height, float worldY, float worldZ) {
        RendererFrameState frameState = frameState(0L);
        CameraRayMath.RayScale scale = CameraRayMath.rayScale(frameState, width, height);
        float distance = (float) frameState.cameraZ() - worldZ;
        float ndcY = (worldY - (float) frameState.cameraY()) / (distance * scale.verticalTan());
        return clampPixel(Math.round(((1.0F - ndcY) * 0.5F * height) - 0.5F), height);
    }

    private static int clampPixel(int value, int extent) {
        return Math.max(0, Math.min(extent - 1, value));
    }

    private static boolean isStrongRed(int pixel) {
        return red(pixel) >= 96
                && green(pixel) <= 120
                && blue(pixel) <= 90
                && red(pixel) > green(pixel) + 48
                && red(pixel) > blue(pixel) + 48;
    }

    private static boolean isStrongGreen(int pixel) {
        return green(pixel) >= 96
                && red(pixel) <= 120
                && blue(pixel) <= 90
                && green(pixel) > red(pixel) + 48
                && green(pixel) > blue(pixel) + 48;
    }

    private static boolean isStrongBlue(int pixel) {
        return blue(pixel) >= 96
                && red(pixel) <= 90
                && green(pixel) <= 120
                && blue(pixel) > red(pixel) + 48
                && blue(pixel) > green(pixel) + 48;
    }

    private static boolean isStrongYellow(int pixel) {
        return red(pixel) >= 96
                && green(pixel) >= 88
                && blue(pixel) <= 90
                && Math.abs(red(pixel) - green(pixel)) <= 50;
    }

    private static boolean isBackplate(int pixel) {
        /*
         * The masked-material gate is about any-hit alpha discard revealing the
         * opaque surface behind it. Some synthetic meshes carry no vanilla-light
         * flag and therefore render the exact test texture, while older shaded
         * paths darken the same texture. Keep the predicate tied to this texture
         * family instead of a single historical lighting level.
         */
        return red(pixel) >= 20 && red(pixel) <= BACKPLATE_RED + 8
                && green(pixel) >= 20 && green(pixel) <= BACKPLATE_GREEN + 8
                && blue(pixel) >= 25 && blue(pixel) <= BACKPLATE_BLUE + 8
                && Math.abs(red(pixel) - green(pixel)) <= 18
                && Math.abs(green(pixel) - blue(pixel)) <= 24;
    }

    private static boolean isCocoaBrown(int pixel) {
        return red(pixel) >= 55 && red(pixel) <= 190
                && green(pixel) >= 32 && green(pixel) <= 140
                && blue(pixel) >= 12 && blue(pixel) <= 85
                && red(pixel) > green(pixel) + 18
                && green(pixel) > blue(pixel) + 8;
    }

    private static int countMatching(RtFrameSnapshot snapshot, int centerX, int centerY, int radius, IntPredicate predicate) {
        byte[] pixels = snapshot.copyRgba8();
        int count = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); x++) {
                if (predicate.test(RtFrameSnapshot.pixel(pixels, snapshot.width(), x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String sampleWindow(RtFrameSnapshot snapshot, int centerX, int centerY, int radius) {
        byte[] pixels = snapshot.copyRgba8();
        StringBuilder builder = new StringBuilder("[");
        int emitted = 0;
        for (int y = Math.max(0, centerY - radius); y <= Math.min(snapshot.height() - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x <= Math.min(snapshot.width() - 1, centerX + radius); x++) {
                if (emitted > 0) {
                    builder.append(", ");
                }
                int pixel = RtFrameSnapshot.pixel(pixels, snapshot.width(), x, y);
                builder.append("(").append(x).append(",").append(y).append("=")
                        .append(RtFrameSnapshot.hex(pixel))
                        .append("/rgba=")
                        .append(red(pixel))
                        .append(',')
                        .append(green(pixel))
                        .append(',')
                        .append(blue(pixel))
                        .append(',')
                        .append((pixel >>> 24) & 0xff)
                        .append(")");
                emitted++;
            }
        }
        return builder.append("]").toString();
    }

    private static List<SectionKey> buildSectionKeys() {
        List<SectionKey> keys = new ArrayList<>(TOTAL_SECTIONS);
        for (int y = 0; y < SECTION_ROWS; y++) {
            for (int x = 0; x < SECTION_COLUMNS; x++) {
                keys.add(new SectionKey(x, y, 0));
            }
        }
        return List.copyOf(keys);
    }

    private static SectionKey probeKey() {
        return new SectionKey(SECTION_COLUMNS / 2, SECTION_ROWS / 2, 0);
    }

    private static Map<SectionKey, SectionTriangleMesh> buildMeshes(
            List<SectionKey> keys,
            RtTextureCatalog.TestTextureScope textures,
            int probeVariant
    ) {
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        SectionKey probe = probeKey();
        for (int index = 0; index < keys.size(); index++) {
            SectionKey key = keys.get(index);
            meshes.put(key, key.equals(probe)
                    ? probeSectionMesh(key, textures, probeVariant)
                    : pressureSectionMesh(key, textures, index));
        }
        return meshes;
    }

    private static Map<SectionKey, SectionTriangleMesh> mutationMeshes(
            List<SectionKey> keys,
            SectionKey probeKey,
            RtTextureCatalog.TestTextureScope textures,
            int burst,
            int probeVariant
    ) {
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        meshes.put(probeKey, probeSectionMesh(probeKey, textures, probeVariant));
        int offset = Math.floorMod(burst * 53, keys.size());
        for (int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); index++) {
            SectionKey key = keys.get((offset + index * 17) % keys.size());
            if (!key.equals(probeKey)) {
                meshes.put(key, pressureSectionMesh(key, textures, burst * 4099 + index));
            }
        }
        return meshes;
    }

    private static SectionTriangleMesh probeSectionMesh(
            SectionKey key,
            RtTextureCatalog.TestTextureScope textures,
            int variant
    ) {
        MeshBuilder builder = new MeshBuilder(key);
        builder.addPositiveZQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                BACK_Z,
                textures.textureId(BACKPLATE_TEXTURE),
                0x000000,
                false,
                false
        );
        builder.addPositiveZQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                FRONT_Z,
                textures.textureId(variant == 0 ? CUTOUT_A_TEXTURE : CUTOUT_B_TEXTURE),
                0x000000,
                false,
                true
        );
        /*
         * MC leaf/cocoa failures happen in dense masked geometry, not in a single
         * isolated cutout quad. Keep one opaque backplate, then make the masked
         * geometry segment longer than the opaque prefix. If hit shaders ever
         * subtract the material-table offset from geometry-local primitive ids
         * again, the final cocoa target below samples a previous cutout material
         * and the canopy probe fails outside Minecraft.
         */
        builder.addPositiveZQuad(
                1.0F,
                1.0F,
                3.0F,
                3.0F,
                CANOPY_Z,
                textures.textureId(variant == 0 ? CUTOUT_A_TEXTURE : CUTOUT_B_TEXTURE),
                0x000000,
                false,
                true
        );
        builder.addPositiveZQuad(
                13.0F,
                13.0F,
                15.0F,
                15.0F,
                CANOPY_Z,
                textures.textureId(variant == 0 ? CUTOUT_B_TEXTURE : CUTOUT_A_TEXTURE),
                0x000000,
                false,
                true
        );
        builder.addPositiveZQuad(
                6.0F,
                6.0F,
                10.0F,
                10.0F,
                CANOPY_Z,
                textures.textureId(COCOA_TEXTURE),
                0x000000,
                false,
                true
        );
        return builder.build();
    }

    private static SectionTriangleMesh pressureSectionMesh(
            SectionKey key,
            RtTextureCatalog.TestTextureScope textures,
            int variant
    ) {
        MeshBuilder builder = new MeshBuilder(key);
        int cutoutTexture = textures.textureId((variant & 1) == 0 ? CUTOUT_A_TEXTURE : CUTOUT_B_TEXTURE);
        builder.addPositiveZQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                BACK_Z,
                textures.textureId(BACKPLATE_TEXTURE),
                0x000000,
                false,
                false
        );
        builder.addPositiveZQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                FRONT_Z,
                cutoutTexture,
                0x000000,
                false,
                true
        );
        builder.addQuad(
                new float[]{
                        2.0F, 2.0F, 15.98F,
                        6.0F, 2.0F, 15.98F,
                        6.0F, 6.0F, 15.98F,
                        2.0F, 6.0F, 15.98F
                },
                FaceDirection.POSITIVE_Z,
                textures.textureId(COCOA_TEXTURE),
                0x000000,
                false,
                true,
                standardUvs()
        );
        builder.addQuad(
                new float[]{
                        3.0F, 1.0F, 15.96F,
                        13.0F, 15.0F, 15.42F,
                        13.0F, 15.0F, 14.62F,
                        3.0F, 1.0F, 15.16F
                },
                FaceDirection.POSITIVE_Z,
                cutoutTexture,
                0x000000,
                false,
                true,
                rotatedUvs(variant)
        );
        builder.addQuad(
                new float[]{
                        13.0F, 1.0F, 15.96F,
                        3.0F, 15.0F, 15.42F,
                        3.0F, 15.0F, 14.62F,
                        13.0F, 1.0F, 15.16F
                },
                FaceDirection.POSITIVE_Z,
                cutoutTexture,
                0x000000,
                false,
                true,
                rotatedUvs(variant + 1)
        );
        return builder.build();
    }

    private static int[] standardUvs() {
        return new int[]{
                RtTextureCatalog.packUv16(0.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 0.0F),
                RtTextureCatalog.packUv16(1.0F, 1.0F),
                RtTextureCatalog.packUv16(0.0F, 1.0F)
        };
    }

    private static int[] rotatedUvs(int variant) {
        int[] uv = standardUvs();
        if ((variant & 1) == 0) {
            return uv;
        }
        return new int[]{uv[1], uv[2], uv[3], uv[0]};
    }

    private static RendererFrameState frameState(long sequence) {
        double centerX = SECTION_COLUMNS * 8.0D;
        double centerY = SECTION_ROWS * 8.0D;
        double cameraZ = 640.0D;
        return new RendererFrameState(
                sequence,
                true,
                OUTPUT_WIDTH,
                OUTPUT_HEIGHT,
                centerX,
                centerY,
                cameraZ,
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

    private static SectionVoxelSnapshot filledSection(SectionKey key, int voxelTypeId) {
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(ids, voxelTypeId);
        return new SectionVoxelSnapshot(key, ids, fluids, false, false);
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

    private static List<RtTextureCatalog.TestTexture> testTextures() {
        return List.of(
                new RtTextureCatalog.TestTexture(CUTOUT_A_TEXTURE, 8, 8, cutoutTextureA()),
                new RtTextureCatalog.TestTexture(CUTOUT_B_TEXTURE, 8, 8, cutoutTextureB()),
                new RtTextureCatalog.TestTexture(
                        BACKPLATE_TEXTURE,
                        4,
                        4,
                        solidTexture(BACKPLATE_RED, BACKPLATE_GREEN, BACKPLATE_BLUE, 255, 4, 4)
                ),
                new RtTextureCatalog.TestTexture(COCOA_TEXTURE, 8, 8, cocoaTexture())
        );
    }

    private static int[] cutoutTextureA() {
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int offset = y * 8 + x;
                if (x >= 4 && y < 4) {
                    pixels[offset] = rgba8(240, 48, 32, 255);
                } else if (x < 4 && y >= 4) {
                    pixels[offset] = rgba8(32, 224, 80, 255);
                } else {
                    pixels[offset] = rgba8(0, 0, 0, 0);
                }
            }
        }
        return pixels;
    }

    private static int[] cutoutTextureB() {
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int offset = y * 8 + x;
                if (x >= 4 && y < 4) {
                    pixels[offset] = rgba8(32, 96, 240, 255);
                } else if (x < 4 && y >= 4) {
                    pixels[offset] = rgba8(232, 208, 40, 255);
                } else {
                    pixels[offset] = rgba8(0, 0, 0, 0);
                }
            }
        }
        return pixels;
    }

    private static int[] cocoaTexture() {
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean border = x == 0 || x == 7 || y == 0 || y == 7;
                boolean bean = x >= 2 && x <= 5 && y >= 2 && y <= 5;
                pixels[y * 8 + x] = border || bean
                        ? rgba8(148, 86, 36, 255)
                        : rgba8(0, 0, 0, 0);
            }
        }
        return pixels;
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

    private static int red(int pixel) {
        return pixel & 0xFF;
    }

    private static int green(int pixel) {
        return (pixel >>> 8) & 0xFF;
    }

    private static int blue(int pixel) {
        return (pixel >>> 16) & 0xFF;
    }

    private static short fixed(float blockUnits) {
        return (short) Math.round(blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static Map<String, String> installStressProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
        int sectionCapacity = Math.max(4096, TOTAL_SECTIONS * 2);
        long byteCapacity = Math.max(1_610_612_736L, TOTAL_SECTIONS * 512L * 1024L);
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
        set(previous, "mcvulkanrt.rt.worldTlas.allowBackloggedStreamingRebuilds", "true");
        set(previous, "mcvulkanrt.rt.scheduler.maxStreamingSceneBindDeferrals", "2");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxBuildsPerFrame", "192");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxTrianglesPerFrame", "6000000");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "1024");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildBytesInFlight", Long.toString(byteCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingSections", Integer.toString(sectionCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxPendingBytes", Long.toString(byteCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedSections", Integer.toString(sectionCapacity));
        set(previous, "mcvulkanrt.rt.sectionBlas.maxCachedBytes", Long.toString(byteCapacity));
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

    private static double doubleProperty(String name, double defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed >= 0.0D ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean booleanProperty(String name, boolean defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record StressResult(
            RtFrameSnapshot lastSnapshot,
            RtSceneReadiness readiness,
            RtCore.RuntimeActivity activity,
            ProbeSamples initialProbe,
            ProbeSamples finalProbe,
            int dynamicBursts,
            int distinctChecksums,
            long completedFrames,
            double averageCompletedFps,
            long maxReadyPendingFrameAgeMillis,
            long maxReadyCompletionStallMillis,
            long maxReadySnapshotLag
    ) {
    }

    private record DrainResult(
            RtFrameSnapshot snapshot,
            long completedFrames,
            long maxPendingFrameAgeMillis,
            long maxCompletionStallMillis,
            long maxSnapshotLag,
            long lastExportedSharedFrameSequence
    ) {
    }

    private record ProbeSamples(
            int primaryX,
            int primaryY,
            int primaryColor,
            int secondaryX,
            int secondaryY,
            int secondaryColor,
            int holeX,
            int holeY,
            int holeColor,
            int secondHoleX,
            int secondHoleY,
            int secondHoleColor
    ) {
        private String asLogFragment() {
            return "probeSamples{primary=(" + primaryX + "," + primaryY + "=" + RtFrameSnapshot.hex(primaryColor)
                    + "), secondary=(" + secondaryX + "," + secondaryY + "=" + RtFrameSnapshot.hex(secondaryColor)
                    + "), hole=(" + holeX + "," + holeY + "=" + RtFrameSnapshot.hex(holeColor)
                    + "), secondHole=(" + secondHoleX + "," + secondHoleY + "="
                    + RtFrameSnapshot.hex(secondHoleColor) + ")}";
        }
    }

    private static final class MaskedSceneState {
        private final SceneDatabase database = new SceneDatabase();
        private final SectionMaterialCache materialCache = new SectionMaterialCache();
        private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
        private final SectionMeshCache meshCache = new SectionMeshCache();

        private RendererFrameUpdate initialUpdate(
                Map<SectionKey, SectionTriangleMesh> meshes,
                RendererFrameState frameState
        ) {
            Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();
            for (SectionKey key : meshes.keySet()) {
                sectionsByChunk
                        .computeIfAbsent(key.chunkKey(), ignored -> new ArrayList<>())
                        .add(filledSection(key, BLOCK_STATE_ID));
            }
            for (Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
                int minY = entry.getValue().stream()
                        .mapToInt(section -> section.key().y())
                        .min()
                        .orElse(0);
                database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
            }
            return applyPreparedMeshes(meshes, frameState);
        }

        private RendererFrameUpdate replacePreparedMeshes(
                Map<SectionKey, SectionTriangleMesh> meshes,
                RendererFrameState frameState
        ) {
            for (SectionKey key : meshes.keySet()) {
                database.replaceBlockMutationSectionSnapshot(filledSection(key, BLOCK_STATE_ID));
            }
            return applyPreparedMeshes(meshes, frameState);
        }

        private RendererFrameUpdate applyPreparedMeshes(
                Map<SectionKey, SectionTriangleMesh> meshes,
                RendererFrameState frameState
        ) {
            SceneUpdateBatch batch = database.drainPendingUpdates();
            if (!batch.hasChanges() && !meshes.isEmpty()) {
                batch = preparedMeshBatch(meshes);
            }
            SectionMaterialCache.MaterialFacts materialFacts = SectionMaterialCache.MaterialFacts.empty();
            for (SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
                materialFacts = materialFacts.plus(SectionMaterialCache.MaterialFacts.fromSnapshot(snapshot));
            }
            SectionMaterialCache.ApplyResult material = materialCache.applyMaterialUpdates(
                    batch,
                    batch.sectionSnapshots().keySet(),
                    materialFacts
            );
            SectionGeometryCache.ApplyResult geometry = geometryCache.applyPrepared(
                    Map.of(),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(
                    meshes,
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            require(meshResult.trianglesInBatch() > 0, "masked-material stress update must submit visible triangles");
            return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
        }

        private static SceneUpdateBatch preparedMeshBatch(Map<SectionKey, SectionTriangleMesh> meshes) {
            Set<SectionKey> dirtySections = Set.copyOf(meshes.keySet());
            Set<ChunkKey> dirtyChunks = new java.util.LinkedHashSet<>();
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
    }

    private static final class MeshBuilder {
        private static final int VERTICES_PER_FACE = 4;
        private static final int INDICES_PER_FACE = 6;
        private final SectionKey key;
        private final List<Short> positions = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
        private final List<Integer> voxelTypeIds = new ArrayList<>();
        private final List<Byte> mediumAmounts = new ArrayList<>();
        private final List<Byte> directions = new ArrayList<>();
        private final List<Integer> mapColors = new ArrayList<>();
        private final List<Byte> lightEmissions = new ArrayList<>();
        private final List<Byte> materialFlags = new ArrayList<>();
        private final List<Integer> textureIds = new ArrayList<>();
        private final List<Integer> uv0 = new ArrayList<>();
        private final List<Integer> uv1 = new ArrayList<>();
        private final List<Integer> uv2 = new ArrayList<>();
        private final List<Integer> uv3 = new ArrayList<>();
        private final List<Byte> tintFlags = new ArrayList<>();
        private final List<Byte> alphaCutoutFlags = new ArrayList<>();

        private MeshBuilder(SectionKey key) {
            this.key = key;
        }

        private void addPositiveZQuad(
                float x0,
                float y0,
                float x1,
                float y1,
                float z,
                int textureId,
                int mapColor,
                boolean tinted,
                boolean alphaCutout
        ) {
            addQuad(
                    new float[]{
                            x0, y0, z,
                            x1, y0, z,
                            x1, y1, z,
                            x0, y1, z
                    },
                    FaceDirection.POSITIVE_Z,
                    textureId,
                    mapColor,
                    tinted,
                    alphaCutout,
                    standardUvs()
            );
        }

        private void addQuad(
                float[] quadPositions,
                FaceDirection direction,
                int textureId,
                int mapColor,
                boolean tinted,
                boolean alphaCutout,
                int[] packedUvs
        ) {
            if (quadPositions.length != VERTICES_PER_FACE * 3) {
                throw new IllegalArgumentException("quadPositions must contain four xyz vertices");
            }
            if (packedUvs.length != VERTICES_PER_FACE) {
                throw new IllegalArgumentException("packedUvs must contain four UVs");
            }
            int firstVertex = positions.size() / 3;
            for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++) {
                int offset = vertex * 3;
                addVertex(quadPositions[offset], quadPositions[offset + 1], quadPositions[offset + 2]);
            }
            indices.add(firstVertex);
            indices.add(firstVertex + 1);
            indices.add(firstVertex + 2);
            indices.add(firstVertex);
            indices.add(firstVertex + 2);
            indices.add(firstVertex + 3);
            voxelTypeIds.add(BLOCK_STATE_ID);
            mediumAmounts.add((byte) 0);
            directions.add((byte) direction.ordinal());
            mapColors.add(mapColor);
            lightEmissions.add((byte) 0);
            materialFlags.add((byte) SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE);
            textureIds.add(textureId);
            uv0.add(packedUvs[0]);
            uv1.add(packedUvs[1]);
            uv2.add(packedUvs[2]);
            uv3.add(packedUvs[3]);
            tintFlags.add((byte) (tinted ? 1 : 0));
            alphaCutoutFlags.add((byte) (alphaCutout ? 1 : 0));
        }

        private void addVertex(float x, float y, float z) {
            positions.add(fixed(x));
            positions.add(fixed(y));
            positions.add(fixed(z));
        }

        private SectionTriangleMesh build() {
            int faceCount = voxelTypeIds.size();
            require(positions.size() == faceCount * VERTICES_PER_FACE * 3, "masked mesh vertex count mismatch");
            require(indices.size() == faceCount * INDICES_PER_FACE, "masked mesh index count mismatch");
            return new SectionTriangleMesh(
                    key,
                    shorts(positions),
                    ints(indices),
                    ints(voxelTypeIds),
                    bytes(mediumAmounts),
                    bytes(directions),
                    ints(mapColors),
                    bytes(lightEmissions),
                    bytes(materialFlags),
                    ints(textureIds),
                    ints(uv0),
                    ints(uv1),
                    ints(uv2),
                    ints(uv3),
                    bytes(tintFlags),
                    bytes(alphaCutoutFlags)
            );
        }

        private static short[] shorts(List<Short> values) {
            short[] array = new short[values.size()];
            for (int index = 0; index < values.size(); index++) {
                array[index] = values.get(index);
            }
            return array;
        }

        private static int[] ints(List<Integer> values) {
            int[] array = new int[values.size()];
            for (int index = 0; index < values.size(); index++) {
                array[index] = values.get(index);
            }
            return array;
        }

        private static byte[] bytes(List<Byte> values) {
            byte[] array = new byte[values.size()];
            for (int index = 0; index < values.size(); index++) {
                array[index] = values.get(index);
            }
            return array;
        }
    }
}
