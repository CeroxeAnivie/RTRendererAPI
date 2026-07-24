package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
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
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-backed native RT stress test that models the failure shape seen in MC:
 * many world sections, mixed opaque/cutout geometry, sustained frame dispatch,
 * and repeated dirty-section replacement.
 */
public final class RtNativeStressSceneSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.stress.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.stress.height", 540);
    private static final int SECTION_COLUMNS = intProperty("mcvulkanrt.rt.stress.sectionColumns", 48);
    private static final int SECTION_ROWS = intProperty("mcvulkanrt.rt.stress.sectionRows", 16);
    private static final int TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
    private static final int MAX_INITIAL_READY_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.stress.maxInitialReadyPumpFrames", 3000);
    private static final int SUSTAINED_FRAMES = intProperty("mcvulkanrt.rt.stress.sustainedFrames", 420);
    private static final int MAX_POST_MUTATION_DRAIN_FRAMES =
            intProperty("mcvulkanrt.rt.stress.maxPostMutationDrainFrames", 2000);
    private static final int MUTATION_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.stress.mutationPeriodFrames", 12);
    private static final int MUTATIONS_PER_BURST =
            intProperty("mcvulkanrt.rt.stress.mutationsPerBurst", 24);
    private static final int MIN_DISTINCT_CHECKSUMS =
            intProperty("mcvulkanrt.rt.stress.minDistinctChecksums", 4);
    private static final int MAX_VISUAL_EVIDENCE_MUTATIONS =
            intProperty("mcvulkanrt.rt.stress.maxVisualEvidenceMutations", 8);
    private static final int MAX_READY_SNAPSHOT_LAG =
            intProperty("mcvulkanrt.rt.stress.maxReadySnapshotLag", 180);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.stress.readbackSampleInterval", 8);
    private static final long MAX_READY_PENDING_FRAME_AGE_MILLIS =
            longProperty("mcvulkanrt.rt.stress.maxReadyPendingFrameAgeMillis", 1500L);
    private static final long MAX_READY_COMPLETION_STALL_MILLIS =
            longProperty("mcvulkanrt.rt.stress.maxReadyCompletionStallMillis", 1500L);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.stress.pumpSleepMillis", 8L);
    private static final boolean ALPHA_CUTOUT_ENABLED =
            booleanProperty("mcvulkanrt.rt.stress.alphaCutout.enabled", true);
    private static final boolean EXPORT_SHARED_FRAME_ENABLED =
            booleanProperty("mcvulkanrt.rt.stress.exportSharedFrame.enabled", true);
    private static final int SHARED_FRAME_EXPORT_SAMPLE_DELTA =
            intProperty("mcvulkanrt.rt.stress.sharedFrameExportSampleDelta", 30);
    private static final int BLOCK_STATE_ID = 1;
    private static final int MIN_FOREGROUND_PIXELS = OUTPUT_WIDTH * OUTPUT_HEIGHT / 32;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-stress-scene.png");

    private RtNativeStressSceneSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installStressProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native stress scene requires production RT hardware: " + capability.summary()
            );

            StressResult result = runStressScene(capability);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeStressSceneSelfTest passed: sections=" + TOTAL_SECTIONS
                    + ", sustainedFrames=" + SUSTAINED_FRAMES
                    + ", dynamicBursts=" + result.dynamicBursts()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", completedFrames=" + result.completedFrames()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", maxReadyPendingFrameAgeMillis=" + result.maxReadyPendingFrameAgeMillis()
                    + ", maxReadyCompletionStallMillis=" + result.maxReadyCompletionStallMillis()
                    + ", maxReadySnapshotLag=" + result.maxReadySnapshotLag()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", readiness=" + result.readiness().asLogFragment()
                    + ", activity=" + result.activity().asLogFragment());
        } finally {
            restoreProperties(previousProperties);
        }
    }

    private static StressResult runStressScene(VulkanRtCapabilityProbe.Result capability) throws Exception {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for stress scene: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            StressSceneState scene = new StressSceneState();
            List<SectionKey> keys = buildSectionKeys();
            rtCore.acceptFrameUpdate(scene.initialUpdate(buildMeshes(keys, 0), frameState(1L)));

            RtFrameSnapshot readySnapshot = pumpUntilSceneReady(rtCore, 2L, MAX_INITIAL_READY_PUMP_FRAMES);
            require(
                    readySnapshot.foregroundPixels() >= MIN_FOREGROUND_PIXELS,
                    "stress scene initial ready frame has too little foreground: "
                            + readySnapshot.asLogFragment()
                            + ", foregroundSample=" + foregroundSample(readySnapshot, 32)
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtNativeStressGuards.assertFrameNotPathological(readySnapshot, "initial native stress frame");

            long phaseStartNanos = System.nanoTime();
            long firstCompleted = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
            long lastCompletedSequence = firstCompleted;
            long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
            long lastCompletionNanos = System.nanoTime();
            long lastExportedSharedFrameSequence = -1L;
            long completedFrameCount = 0L;
            long maxPendingAge = 0L;
            long maxCompletionStallMillis = 0L;
            long maxSnapshotLag = 0L;
            int dynamicBursts = 0;
            Set<Long> checksums = new HashSet<>();
            checksums.add(readySnapshot.checksum());
            RtFrameSnapshot lastSnapshot = readySnapshot;

            for (int frame = 0; frame < SUSTAINED_FRAMES; frame++) {
                long sequence = 10_000L + frame;
                RendererFrameUpdate update;
                if (frame % MUTATION_PERIOD_FRAMES == 0) {
                    update = scene.replacePreparedMeshes(
                            mutationMeshes(keys, dynamicBursts + 1),
                            frameState(sequence)
                    );
                    dynamicBursts++;
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
                    boolean currentOutputInFlight = currentFrameStillInFlight(activity, sequence);
                    maxPendingAge = Math.max(maxPendingAge, pendingAge);
                    if (!currentOutputInFlight) {
                        maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
                    }
                    maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
                    require(
                            pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS,
                            "stress scene has a stale pending RT frame after scene became current"
                                    + ", sequence=" + sequence
                                    + ", pendingAgeMillis=" + pendingAge
                                    + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                    if (!currentOutputInFlight) {
                        require(
                                completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                                "stress scene completed-frame stream stalled after scene became current"
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
                                    "stress scene completed RT output is too far behind a ready scene"
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
                                    "stress scene diagnostic snapshot is too far behind a ready scene"
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
                    if (snapshot != null && snapshot.frameStateSequence() >= sequence
                            && snapshotLag <= MAX_READY_SNAPSHOT_LAG) {
                        RtNativeStressGuards.assertFrameNotPathological(
                                snapshot,
                                "native stress ready frame " + frame
                        );
                    }
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during native stress scene: state=" + rtCore.state()
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
                            "native stress frame " + frame
                    );
                }
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

            DrainResult drain = pumpUntilSceneDrained(
                    rtCore,
                    20_000L,
                    MAX_POST_MUTATION_DRAIN_FRAMES,
                    rtCore.sceneReadiness().latestRevision(),
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
            RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "final native stress frame");

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            double averageCompletedFps = completedFrameCount * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= 15.0D,
                    "native stress scene completed frames below 15 fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", completedFrames=" + completedFrameCount
                            + ", elapsedMillis=" + elapsedNanos / 1_000_000L
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            /*
             * The sustained phase intentionally permits build and frame coalescing: that is the
             * production behavior under pressure, not a correctness defect. A fixed readback cadence
             * can therefore observe only the pre-pressure and fully converged images even though all
             * intermediate revisions were processed. Prove visible replacement separately by allowing
             * each evidence mutation to converge, then accepting only a diagnostic frame submitted after
             * that exact revision became current. This prevents cadence aliasing without weakening either
             * the pressure workload or the visual-diversity requirement.
             */
            for (int evidenceCycle = 0;
                 checksums.size() < MIN_DISTINCT_CHECKSUMS
                         && evidenceCycle < MAX_VISUAL_EVIDENCE_MUTATIONS;
                 evidenceCycle++) {
                long evidenceSequence = 100_000L
                        + (long) evidenceCycle * (MAX_POST_MUTATION_DRAIN_FRAMES + 1L);
                rtCore.acceptFrameUpdate(scene.replacePreparedMeshes(
                        mutationMeshes(keys, dynamicBursts + 1),
                        frameState(evidenceSequence)
                ));
                dynamicBursts++;
                long evidenceRevision = rtCore.sceneReadiness().latestRevision();
                require(
                        evidenceRevision >= 0L,
                        "visual evidence mutation did not publish a scene revision"
                                + ", evidenceCycle=" + evidenceCycle
                                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                DrainResult evidence = pumpUntilSceneDrained(
                        rtCore,
                        evidenceSequence + 1L,
                        MAX_POST_MUTATION_DRAIN_FRAMES,
                        evidenceRevision,
                        rtCore.runtimeActivity().latestCompletedFrameStateSequence(),
                        rtCore.runtimeActivity().latestCompletedFrameDispatch(),
                        System.nanoTime(),
                        lastExportedSharedFrameSequence
                );
                lastSnapshot = evidence.snapshot();
                lastExportedSharedFrameSequence = evidence.lastExportedSharedFrameSequence();
                checksums.add(lastSnapshot.checksum());
                maxPendingAge = Math.max(maxPendingAge, evidence.maxPendingFrameAgeMillis());
                maxCompletionStallMillis = Math.max(
                        maxCompletionStallMillis,
                        evidence.maxCompletionStallMillis()
                );
                maxSnapshotLag = Math.max(maxSnapshotLag, evidence.maxSnapshotLag());
            }

            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                    rtCore,
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                    true,
                    "native stress final frame"
            );
            require(
                    checksums.size() >= MIN_DISTINCT_CHECKSUMS,
                    "dynamic stress scene did not visibly change across replacements"
                            + ", distinctChecksums=" + checksums.size()
                            + ", required=" + MIN_DISTINCT_CHECKSUMS
                            + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    "native stress scene"
            );
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native stress scene");
            require(lastSnapshot != null, "stress scene did not produce any snapshot");
            return new StressResult(
                    lastSnapshot,
                    rtCore.sceneReadiness(),
                    finalActivity,
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

    private static DrainResult pumpUntilSceneDrained(
            GuardedRtCore rtCore,
            long firstSequence,
            int maxPumpFrames,
            long requiredRevision,
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
                        "native stress drain frame " + frame
                );
            }
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null) {
                lastSnapshot = snapshot;
            }
            if (firstReadySequence < 0L
                    && readiness.builtRevisionIsCurrent()
                    && readiness.builtRevision() >= requiredRevision
                    && !readiness.hasPendingRtBuilds()) {
                firstReadySequence = sequence;
            }

            long pendingAge = activity.pendingFrameAgeMillis();
            long snapshotLag = snapshot == null ? Long.MAX_VALUE : Math.max(0L, sequence - snapshot.frameStateSequence());
            long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1_000_000L;
            boolean currentOutputInFlight = currentFrameStillInFlight(activity, sequence);
            maxPendingAge = Math.max(maxPendingAge, pendingAge);
            if (!currentOutputInFlight) {
                maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
            }
            maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
            require(
                    pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS,
                    "post-mutation drain has a stale pending RT frame"
                            + ", sequence=" + sequence
                            + ", pendingAgeMillis=" + pendingAge
                            + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            if (!currentOutputInFlight) {
                require(
                        completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                        "post-mutation drain completed-frame stream stalled"
                                + ", sequence=" + sequence
                                + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                + ", completionStallMillis=" + completionStallMillis
                                + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
            }
            if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds() && !currentOutputInFlight) {
                long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
                if (activity.latestCompletedFrameStateSequence() >= firstSequence) {
                    require(
                            completedLag <= MAX_READY_SNAPSHOT_LAG,
                            "post-mutation drain completed RT output is too far behind a ready scene"
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
                            "post-mutation drain diagnostic snapshot is too far behind a ready scene"
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
                    && firstReadySequence >= 0L
                    && lastSnapshot.frameStateSequence() >= firstReadySequence
                    && lastSnapshot.frameStateSequence() >= sequence - MAX_READY_SNAPSHOT_LAG) {
                RtNativeStressGuards.assertFrameNotPathological(
                        lastSnapshot,
                        "native stress drained frame"
                );
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
                    "RT core failed during post-mutation drain: state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError("post-mutation stress scene never drained to current RT output"
                + ", firstReadySequence=" + firstReadySequence
                + ", requiredRevision=" + requiredRevision
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static RtFrameSnapshot pumpUntilSceneReady(
            GuardedRtCore rtCore,
            long firstSequence,
            int maxPumpFrames
    ) throws InterruptedException {
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        long firstReadySequence = -1L;
        for (int frame = 0; frame < maxPumpFrames; frame++) {
            long sequence = firstSequence + frame;
            rtCore.acceptFrameUpdate(RendererFrameUpdate.empty(emptyBatch(), frameState(sequence)));
            RtSceneReadiness readiness = rtCore.sceneReadiness();
            RtCore.RuntimeActivity activity = rtCore.runtimeActivity();
            if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                firstReadySequence = sequence;
            }
            lastSnapshot = rtCore.latestFrameSnapshot();
            if (lastSnapshot != null
                    && firstReadySequence >= 0L
                    && lastSnapshot.frameStateSequence() >= firstReadySequence) {
                return lastSnapshot;
            }
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core failed while waiting for stress scene readiness: state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError("native stress scene never became current"
                + ", firstReadySequence=" + firstReadySequence
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
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

    private static Map<SectionKey, SectionTriangleMesh> buildMeshes(List<SectionKey> keys, int variant) {
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            SectionKey key = keys.get(index);
            meshes.put(key, stressSectionMesh(key, variant + index));
        }
        return meshes;
    }

    private static Map<SectionKey, SectionTriangleMesh> mutationMeshes(List<SectionKey> keys, int burst) {
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        int offset = Math.floorMod(burst * 37, keys.size());
        for (int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); index++) {
            SectionKey key = keys.get((offset + index * 11) % keys.size());
            meshes.put(key, stressSectionMesh(key, burst * 4099 + index));
        }
        return meshes;
    }

    private static SectionTriangleMesh stressSectionMesh(SectionKey key, int variant) {
        MeshBuilder builder = new MeshBuilder(key);
        int baseColor = colorVariant(variant, 0x315f28, 0x8fbf52);
        int accentColor = colorVariant(variant * 17 + 3, 0x2b6bbf, 0xd7d15b);
        for (int row = 0; row < 4; row++) {
            float y0 = row * 4.0F;
            float y1 = y0 + 4.0F;
            builder.addPositiveZQuad(0.0F, y0, 16.0F, y1, 15.92F, baseColor, false);
            builder.addPositiveZQuad(
                    4.0F,
                    y0 + 0.35F,
                    12.0F,
                    y1 - 0.35F,
                    15.98F,
                    accentColor,
                    ALPHA_CUTOUT_ENABLED
            );
            builder.addPositiveZQuad(
                    12.0F,
                    y0 + 0.65F,
                    16.0F,
                    y1 - 0.65F,
                    15.99F,
                    accentColor,
                    ALPHA_CUTOUT_ENABLED
            );
        }
        return builder.build();
    }

    private static int colorVariant(int variant, int firstRgb, int secondRgb) {
        int mixed = variant * 1_103_515_245 + 12_345;
        int weight = (mixed >>> 24) & 0xff;
        int red = blend((firstRgb >>> 16) & 0xff, (secondRgb >>> 16) & 0xff, weight);
        int green = blend((firstRgb >>> 8) & 0xff, (secondRgb >>> 8) & 0xff, weight);
        int blue = blend(firstRgb & 0xff, secondRgb & 0xff, weight);
        return (red << 16) | (green << 8) | blue;
    }

    private static int blend(int first, int second, int weight) {
        return (first * (255 - weight) + second * weight) / 255;
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
        set(previous, "mcvulkanrt.rt.sectionBlas.maxBuildsPerFrame", "128");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxTrianglesPerFrame", "4000000");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildsInFlight", "16");
        set(previous, "mcvulkanrt.rt.sectionBlas.maxAsyncBuildSectionsInFlight", "768");
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

    private static short fixed(float blockUnits) {
        return (short) Math.round(blockUnits * SectionTriangleMesh.POSITION_SCALE);
    }

    private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
        if (latestCompletedSequence < 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, sequence - latestCompletedSequence);
    }

    private static boolean currentFrameStillInFlight(RtCore.RuntimeActivity activity, long sequence) {
        return activity.pendingFrame()
                && activity.pendingFrameSequence() >= sequence
                && activity.pendingFrameAgeMillis() <= MAX_READY_PENDING_FRAME_AGE_MILLIS;
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

    private static final class StressSceneState {
        private final SceneDatabase database = new SceneDatabase();
        private final SectionMaterialCache materialCache = new SectionMaterialCache();
        private final SectionGeometryCache geometryCache = new SectionGeometryCache();
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
            require(meshResult.trianglesInBatch() > 0, "stress scene update must submit visible section triangles");
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
                int mapColor,
                boolean alphaCutout
        ) {
            int firstVertex = positions.size() / 3;
            addVertex(x0, y0, z);
            addVertex(x1, y0, z);
            addVertex(x1, y1, z);
            addVertex(x0, y1, z);
            indices.add(firstVertex);
            indices.add(firstVertex + 1);
            indices.add(firstVertex + 2);
            indices.add(firstVertex);
            indices.add(firstVertex + 2);
            indices.add(firstVertex + 3);
            voxelTypeIds.add(BLOCK_STATE_ID);
            mediumAmounts.add((byte) 0);
            directions.add((byte) FaceDirection.POSITIVE_Z.ordinal());
            mapColors.add(mapColor);
            lightEmissions.add((byte) 0);
            materialFlags.add((byte) SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE);
            textureIds.add(0);
            uv0.add(top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 0.0F));
            uv1.add(top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 0.0F));
            uv2.add(top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(1.0F, 1.0F));
            uv3.add(top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog.packUv16(0.0F, 1.0F));
            tintFlags.add((byte) 1);
            alphaCutoutFlags.add((byte) (alphaCutout ? 1 : 0));
        }

        private void addVertex(float x, float y, float z) {
            positions.add(fixed(x));
            positions.add(fixed(y));
            positions.add(fixed(z));
        }

        private SectionTriangleMesh build() {
            int faceCount = voxelTypeIds.size();
            require(positions.size() == faceCount * VERTICES_PER_FACE * 3, "stress mesh vertex count mismatch");
            require(indices.size() == faceCount * INDICES_PER_FACE, "stress mesh index count mismatch");
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
