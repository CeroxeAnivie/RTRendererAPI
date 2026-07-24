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
import top.ceroxe.mcvulkanrt.renderer.scene.SectionGeometrySnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionEncodedSnapshot;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMaterialCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshBuilder;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMeshCache;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionMesher;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionNeighborhood;
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
import java.util.function.IntPredicate;

/**
 * Hardware-backed MC-outside gate for fluid terrain failures.
 *
 * <p>The scene intentionally uses Minecraft-shaped section updates instead of a
 * standalone RT demo: every prepared mesh is paired with a renderer-owned
 * SectionVoxelSnapshot so the same SceneDatabase -> material/geometry cache ->
 * prepared mesh -> BLAS/TLAS -> material table -> shared frame chain is stressed.
 */
public final class RtNativeFluidSceneSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.fluidStress.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.fluidStress.height", 540);
    private static final int SECTION_COLUMNS = intProperty("mcvulkanrt.rt.fluidStress.sectionColumns", 37);
    private static final int SECTION_ROWS = intProperty("mcvulkanrt.rt.fluidStress.sectionRows", 19);
    private static final int TOTAL_SECTIONS = SECTION_COLUMNS * SECTION_ROWS;
    private static final int WATERLOGGED_STRESS_SECTIONS =
            intProperty("mcvulkanrt.rt.fluidStress.waterloggedSections", 1024);
    private static final int FLUID_FAMILY_STRESS_SECTIONS =
            intProperty("mcvulkanrt.rt.fluidStress.fluidFamilySections", 512);
    private static final int MAX_INITIAL_READY_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.fluidStress.maxInitialReadyPumpFrames", 3600);
    private static final int SUSTAINED_FRAMES = intProperty("mcvulkanrt.rt.fluidStress.sustainedFrames", 180);
    private static final int MAX_FINAL_DRAIN_FRAMES =
            intProperty("mcvulkanrt.rt.fluidStress.maxFinalDrainFrames", 2400);
    private static final int MUTATION_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.fluidStress.mutationPeriodFrames", 6);
    private static final int MUTATIONS_PER_BURST =
            intProperty("mcvulkanrt.rt.fluidStress.mutationsPerBurst", 64);
    private static final int MAX_READY_SNAPSHOT_LAG =
            intProperty("mcvulkanrt.rt.fluidStress.maxReadySnapshotLag", 180);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.fluidStress.readbackSampleInterval", 8);
    private static final long MAX_READY_PENDING_FRAME_AGE_MILLIS =
            longProperty("mcvulkanrt.rt.fluidStress.maxReadyPendingFrameAgeMillis", 1500L);
    private static final long MAX_READY_COMPLETION_STALL_MILLIS =
            longProperty("mcvulkanrt.rt.fluidStress.maxReadyCompletionStallMillis", 1500L);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.fluidStress.pumpSleepMillis", 6L);
    private static final double MIN_COMPLETED_FPS =
            doubleProperty("mcvulkanrt.rt.fluidStress.minCompletedFps", 15.0D);
    private static final boolean EXPORT_SHARED_FRAME_ENABLED =
            booleanProperty("mcvulkanrt.rt.fluidStress.exportSharedFrame.enabled", true);
    private static final int SHARED_FRAME_EXPORT_SAMPLE_DELTA =
            intProperty("mcvulkanrt.rt.fluidStress.sharedFrameExportSampleDelta", 30);
    private static final int SOLID_BLOCK_STATE_ID = 1;
    private static final int WATER_BLOCK_STATE_ID = 91;
    private static final int WATER_FLOWING_BLOCK_STATE_ID = 92;
    private static final int WATER_FLUID_TYPE_ID = 7;
    private static final int FULL_FLUID_AMOUNT = 8;
    private static final float FRONT_Z = 15.92F;
    private static final float BACK_Z = 15.58F;
    private static final String WATER_A_TEXTURE = "mcvulkanrt:selftest/fluid_water_a";
    private static final String WATER_B_TEXTURE = "mcvulkanrt:selftest/fluid_water_b";
    private static final String BACKPLATE_TEXTURE = "mcvulkanrt:selftest/fluid_backplate";
    private static final String FOAM_CUTOUT_TEXTURE = "mcvulkanrt:selftest/fluid_foam_cutout";
    private static final String SEAGRASS_CUTOUT_TEXTURE = "mcvulkanrt:selftest/fluid_seagrass_cutout";
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-fluid-scene.png");

    private RtNativeFluidSceneSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installStressProperties();
        try (RtTextureCatalog.TestTextureScope textures = RtTextureCatalog.installTestTexturesForSelfTest(testTextures())) {
            WaterloggedStressStats waterloggedStats = assertWaterloggedMeshingStressContract();
            FluidFamilyStressStats fluidFamilyStats = assertFluidFamilyMeshingStressContract();
            FluidNeighborhoodStressStats fluidNeighborhoodStats = assertFluidNeighborhoodMeshingStressContract();
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native fluid scene requires production RT hardware: " + capability.summary()
            );

            StressResult result = runFluidScene(capability, textures);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeFluidSceneSelfTest passed: sections=" + TOTAL_SECTIONS
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
                    + ", waterloggedPreflight=" + waterloggedStats.asLogFragment()
                    + ", fluidFamilyPreflight=" + fluidFamilyStats.asLogFragment()
                    + ", fluidNeighborhoodPreflight=" + fluidNeighborhoodStats.asLogFragment()
                    + ", readiness=" + result.readiness().asLogFragment()
                    + ", activity=" + result.activity().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene(
                    "fluidMedium",
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

    private static StressResult runFluidScene(
            VulkanRtCapabilityProbe.Result capability,
            RtTextureCatalog.TestTextureScope textures
    ) throws Exception {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for fluid scene: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            FluidSceneState scene = new FluidSceneState();
            List<SectionKey> keys = buildSectionKeys();
            SectionKey probeKey = probeKey();
            rtCore.acceptFrameUpdate(scene.initialUpdate(buildPreparedSections(keys, textures, FluidVariant.FULL_A), frameState(1L)));
            RtFrameSnapshot initialSnapshot = pumpUntilProbeReady(
                    rtCore,
                    2L,
                    FluidVariant.FULL_A,
                    MAX_INITIAL_READY_PUMP_FRAMES,
                    "initial fluid scene"
            );
            ProbeSamples initialProbe = assertProbePixels(initialSnapshot, FluidVariant.FULL_A, "initial");
            RtNativeStressGuards.assertFrameNotPathological(initialSnapshot, "initial fluid scene frame");
            scene.resetMutationTimings();

            long phaseStartNanos = System.nanoTime();
            long lastCompletedSequence = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameStateSequence());
            long lastCompletedDispatch = Math.max(0L, rtCore.runtimeActivity().latestCompletedFrameDispatch());
            long lastCompletionNanos = System.nanoTime();
            long lastExportedSharedFrameSequence = -1L;
            long completedFrameCount = 0L;
            long maxPendingAge = 0L;
            long maxCompletionStallMillis = 0L;
            long maxSnapshotLag = 0L;
            long mutationMeshPreparationNanos = 0L;
            long mutationPublicationNanos = 0L;
            long acceptFrameNanos = 0L;
            int dynamicBursts = 0;
            FluidVariant expectedProbeVariant = FluidVariant.FULL_A;
            boolean observedFluidA = true;
            boolean observedFluidB = false;
            Set<Long> checksums = new HashSet<>();
            checksums.add(initialSnapshot.checksum());
            RtFrameSnapshot lastSnapshot = initialSnapshot;

            for (int frame = 0; frame < SUSTAINED_FRAMES; frame++) {
                long sequence = 10_000L + frame;
                RendererFrameUpdate update;
                if (frame % MUTATION_PERIOD_FRAMES == 0) {
                    dynamicBursts++;
                    expectedProbeVariant = (dynamicBursts & 1) == 0 ? FluidVariant.FULL_A : FluidVariant.FULL_B;
                    long mutationStageStartNanos = System.nanoTime();
                    Map<SectionKey, PreparedSection> preparedSections = mutationPreparedSections(
                            keys, probeKey, textures, dynamicBursts, expectedProbeVariant
                    );
                    mutationMeshPreparationNanos += System.nanoTime() - mutationStageStartNanos;
                    mutationStageStartNanos = System.nanoTime();
                    update = scene.replacePreparedSections(preparedSections, frameState(sequence));
                    mutationPublicationNanos += System.nanoTime() - mutationStageStartNanos;
                } else {
                    update = RendererFrameUpdate.empty(emptyBatch(), frameState(sequence));
                }

                long acceptFrameStartNanos = System.nanoTime();
                rtCore.acceptFrameUpdate(update);
                acceptFrameNanos += System.nanoTime() - acceptFrameStartNanos;
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
                    ProbeSamples visibleSamples = probeSamples(snapshot);
                    observedFluidA |= isFluidA(visibleSamples.centerColor());
                    observedFluidB |= isFluidB(visibleSamples.centerColor());
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
                            "fluid scene has a stale pending RT frame after scene became current"
                                    + ", sequence=" + sequence
                                    + ", pendingAgeMillis=" + pendingAge
                                    + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                                    + ", readiness=" + readiness.asLogFragment()
                                    + ", activity=" + activity.asLogFragment()
                                    + ", summary=" + rtCore.summary().asLogFragment()
                    );
                    require(
                            completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                            "fluid scene completed-frame stream stalled after scene became current"
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
                                "fluid scene completed RT output is too far behind a ready scene"
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
                                "fluid scene diagnostic snapshot is too far behind a ready scene"
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
                        ProbeSamples samples = assertSustainedProbePixels(snapshot, "sustained fluid frame " + frame);
                        RtNativeStressGuards.assertFrameNotPathological(snapshot, "fluid ready frame " + frame);
                    }
                }
                require(
                        rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                        "RT core failed during fluid scene: state=" + rtCore.state()
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
                            "fluid scene frame " + frame
                    );
                }
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

            if (!observedFluidB) {
                /*
                 * Mutation cadence is intentionally faster than the readback cadence and the
                 * renderer may coalesce superseded A/B sources while the 703-section backlog
                 * converges. Preserve that pressure test, then make the visual B contract
                 * deterministic instead of treating a legally skipped diagnostic sample as a
                 * rendering failure.
                 */
                long verificationSequence = 19_000L;
                rtCore.acceptFrameUpdate(scene.replacePreparedSections(
                        Map.of(probeKey, probePreparedSection(probeKey, textures, FluidVariant.FULL_B)),
                        frameState(verificationSequence)
                ));
                lastSnapshot = pumpUntilProbeReady(
                        rtCore,
                        verificationSequence + 1L,
                        FluidVariant.FULL_B,
                        MAX_INITIAL_READY_PUMP_FRAMES,
                        "deterministic fluid B verification"
                );
                checksums.add(lastSnapshot.checksum());
                observedFluidB = isFluidB(probeSamples(lastSnapshot).centerColor());
                RtCore.RuntimeActivity verifiedActivity = rtCore.runtimeActivity();
                long verifiedCompletedDispatch = verifiedActivity.latestCompletedFrameDispatch();
                if (verifiedCompletedDispatch > lastCompletedDispatch) {
                    /*
                     * The verification pump is part of the measured sustained phase. Its
                     * completed dispatches must therefore contribute to the same numerator;
                     * otherwise legal mutation coalescing adds verification time while silently
                     * discarding the frames completed during that time.
                     */
                    completedFrameCount += verifiedCompletedDispatch - lastCompletedDispatch;
                }
                lastCompletedSequence = Math.max(
                        lastCompletedSequence,
                        verifiedActivity.latestCompletedFrameStateSequence()
                );
                lastCompletedDispatch = Math.max(
                        lastCompletedDispatch,
                        verifiedCompletedDispatch
                );
                lastCompletionNanos = System.nanoTime();
            }

            rtCore.acceptFrameUpdate(scene.replacePreparedSections(
                    Map.of(probeKey, probePreparedSection(probeKey, textures, FluidVariant.DRAINED)),
                    frameState(20_000L)
            ));
            require(
                    observedFluidA && observedFluidB,
                    "fluid scene did not observe both dynamic water material variants before drain"
                            + ", observedFluidA=" + observedFluidA
                            + ", observedFluidB=" + observedFluidB
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            DrainResult drain = pumpUntilProbeDrained(
                    rtCore,
                    20_001L,
                    FluidVariant.DRAINED,
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
            ProbeSamples finalProbe = assertProbePixels(lastSnapshot, FluidVariant.DRAINED, "final drained fluid scene");
            RtNativeStressGuards.assertFrameNotPathological(lastSnapshot, "final drained fluid scene frame");

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            RtCore.RuntimeActivity finalActivity = rtCore.runtimeActivity();
            lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                    rtCore,
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                    true,
                    "fluid scene final frame"
            );
            double averageCompletedFps = completedFrameCount * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= MIN_COMPLETED_FPS,
                    "fluid scene completed frames below fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", minCompletedFps=" + MIN_COMPLETED_FPS
                            + ", completedFrames=" + completedFrameCount
                            + ", elapsedMillis=" + elapsedNanos / 1_000_000L
                            + ", mutationMeshPreparationMillis=" + mutationMeshPreparationNanos / 1_000_000L
                            + ", mutationPublicationMillis=" + mutationPublicationNanos / 1_000_000L
                            + ", mutationPublicationStages=" + scene.mutationTimingSummary()
                            + ", acceptFrameMillis=" + acceptFrameNanos / 1_000_000L
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            RtNativeStressGuards.assertSharedFrameReachedCompletedFrame(
                    EXPORT_SHARED_FRAME_ENABLED,
                    finalActivity.latestCompletedFrameStateSequence(),
                    lastExportedSharedFrameSequence,
                    "fluid scene"
            );
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "fluid scene");
            require(
                    checksums.size() >= 3,
                    "fluid scene did not visibly change across fill/drain replacements"
                            + ", distinctChecksums=" + checksums.size()
                            + ", lastSnapshot=" + lastSnapshot.asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", activity=" + finalActivity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    initialProbe.centerColor() != finalProbe.centerColor(),
                    "fluid drain replacement did not reach visible RT output"
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
            FluidVariant expectedVariant,
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
                if (firstReadySequence >= 0L
                        && snapshot.frameStateSequence() >= firstReadySequence
                        && hasExpectedProbeCoverage(snapshot, expectedVariant)) {
                    assertProbePixels(snapshot, expectedVariant, label);
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
            FluidVariant expectedVariant,
            int maxPumpFrames,
            long lastCompletedSequence,
            long lastCompletedDispatch,
            long lastCompletionNanos,
            long lastExportedSharedFrameSequence
    ) throws InterruptedException {
        RtFrameSnapshot lastSnapshot = rtCore.latestFrameSnapshot();
        long firstReadySequence = -1L;
        long completedFrames = 0L;
        long maxPendingAge = 0L;
        long maxCompletionStallMillis = 0L;
        long maxSnapshotLag = 0L;
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
            if (firstReadySequence < 0L && readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                firstReadySequence = sequence;
            }
            RtFrameSnapshot snapshot = rtCore.latestFrameSnapshot();
            if (snapshot != null) {
                lastSnapshot = snapshot;
            }
            long snapshotLag = snapshot == null ? Long.MAX_VALUE : Math.max(0L, sequence - snapshot.frameStateSequence());
            long completionStallMillis = Math.max(0L, nowNanos - lastCompletionNanos) / 1_000_000L;
            maxSnapshotLag = Math.max(maxSnapshotLag, snapshotLag);
            maxCompletionStallMillis = Math.max(maxCompletionStallMillis, completionStallMillis);
            if (readiness.builtRevisionIsCurrent() && !readiness.hasPendingRtBuilds()) {
                long pendingAge = activity.pendingFrameAgeMillis();
                maxPendingAge = Math.max(maxPendingAge, pendingAge);
                require(
                        pendingAge <= MAX_READY_PENDING_FRAME_AGE_MILLIS,
                        "fluid final drain has a stale pending RT frame"
                                + ", pendingAgeMillis=" + pendingAge
                                + ", maxAllowedMillis=" + MAX_READY_PENDING_FRAME_AGE_MILLIS
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                require(
                        completionStallMillis <= MAX_READY_COMPLETION_STALL_MILLIS,
                        "fluid final drain completed-frame stream stalled"
                                + ", completionStallMillis=" + completionStallMillis
                                + ", maxAllowedMillis=" + MAX_READY_COMPLETION_STALL_MILLIS
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                long completedLag = completedSequenceLag(sequence, activity.latestCompletedFrameStateSequence());
                require(
                        completedLag <= MAX_READY_SNAPSHOT_LAG,
                        "fluid final drain completed RT output is too far behind a ready scene"
                                + ", sequence=" + sequence
                                + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                + ", completedLag=" + completedLag
                                + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                require(
                        snapshot != null && snapshotLag <= MAX_READY_SNAPSHOT_LAG,
                        "fluid final drain diagnostic snapshot is too far behind a ready scene"
                                + ", sequence=" + sequence
                                + ", snapshotLag=" + snapshotLag
                                + ", maxAllowedLag=" + MAX_READY_SNAPSHOT_LAG
                                + ", snapshot=" + (snapshot == null ? "none" : snapshot.asLogFragment())
                                + ", latestCompletedFrameStateSequence=" + activity.latestCompletedFrameStateSequence()
                                + ", readiness=" + readiness.asLogFragment()
                                + ", activity=" + activity.asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
            }
            if (EXPORT_SHARED_FRAME_ENABLED && activity.latestCompletedFrameStateSequence() > lastExportedSharedFrameSequence) {
                lastExportedSharedFrameSequence = RtNativeStressGuards.sampleCompletedSharedFrame(
                        rtCore,
                        true,
                        activity.latestCompletedFrameStateSequence(),
                        lastExportedSharedFrameSequence,
                        SHARED_FRAME_EXPORT_SAMPLE_DELTA,
                        false,
                        "fluid final drain frame " + frame
                );
            }
            if (firstReadySequence >= 0L
                    && lastSnapshot != null
                    && lastSnapshot.frameStateSequence() >= firstReadySequence
                    && lastSnapshot.frameStateSequence() >= sequence - MAX_READY_SNAPSHOT_LAG) {
                assertProbePixels(lastSnapshot, expectedVariant, "final fluid drain");
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
                    "RT core failed during fluid final drain: state=" + rtCore.state()
                            + ", readiness=" + readiness.asLogFragment()
                            + ", activity=" + activity.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            Thread.sleep(PUMP_SLEEP_MILLIS);
        }
        throw new AssertionError("fluid final drain never reached current RT output"
                + ", lastSnapshot=" + (lastSnapshot == null ? "none" : lastSnapshot.asLogFragment())
                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                + ", summary=" + rtCore.summary().asLogFragment());
    }

    private static ProbeSamples assertProbePixels(RtFrameSnapshot snapshot, FluidVariant expectedVariant, String label) {
        ProbeSamples samples = probeSamples(snapshot);
        if (expectedVariant == FluidVariant.DRAINED) {
            require(
                    countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3,
                    label + " drained fluid center did not reveal the backplate"
                            + ", samples=" + samples.asLogFragment()
                            + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2)
                            + ", snapshot=" + snapshot.asLogFragment()
            );
            require(
                    countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3,
                    label + " drained fluid plant probe did not reveal the backplate after the water-surface cutout was removed"
                            + ", samples=" + samples.asLogFragment()
                            + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2)
                            + ", snapshot=" + snapshot.asLogFragment()
            );
        } else {
            IntPredicate predicate = expectedVariant == FluidVariant.FULL_A
                    ? RtNativeFluidSceneSelfTest::isFluidA
                    : RtNativeFluidSceneSelfTest::isFluidB;
            require(
                    countMatching(snapshot, samples.centerX(), samples.centerY(), 2, predicate) >= 3,
                    label + " fluid center was not shaded from the expected water material"
                            + ", expectedVariant=" + expectedVariant
                            + ", samples=" + samples.asLogFragment()
                            + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2)
                            + ", snapshot=" + snapshot.asLogFragment()
            );
            requireFluidProbeNotNearBlack(snapshot, samples.centerX(), samples.centerY(), samples, label, "center");
            require(
                    countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) == 0,
                    label + " translucent water center was routed as masked cutout and revealed submerged seagrass"
                            + ", samples=" + samples.asLogFragment()
                            + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2)
                            + ", snapshot=" + snapshot.asLogFragment()
            );
            require(
                    countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) >= 3,
                    label + " water-surface cutout plant did not render as an opaque masked texel"
                            + ", samples=" + samples.asLogFragment()
                            + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2)
                            + ", snapshot=" + snapshot.asLogFragment()
            );
        }
        require(
                countMatching(snapshot, samples.shoreX(), samples.shoreY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3,
                label + " fluid boundary did not expose the solid backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.shoreX(), samples.shoreY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                samples.centerColor() != RtSceneMaterialTable.missRgba8()
                        && samples.shoreColor() != RtSceneMaterialTable.missRgba8()
                        && samples.plantColor() != RtSceneMaterialTable.missRgba8(),
                label + " fluid probe collapsed into miss/sky blue"
                        + ", samples=" + samples.asLogFragment()
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        return samples;
    }

    private static boolean hasExpectedProbeCoverage(RtFrameSnapshot snapshot, FluidVariant expectedVariant) {
        ProbeSamples samples = probeSamples(snapshot);
        if (expectedVariant == FluidVariant.DRAINED) {
            return countMatching(
                    snapshot,
                    samples.centerX(),
                    samples.centerY(),
                    2,
                    RtNativeFluidSceneSelfTest::isBackplate
            ) >= 3 && countMatching(
                    snapshot,
                    samples.plantX(),
                    samples.plantY(),
                    2,
                    RtNativeFluidSceneSelfTest::isBackplate
            ) >= 3;
        }
        IntPredicate predicate = expectedVariant == FluidVariant.FULL_A
                ? RtNativeFluidSceneSelfTest::isFluidA
                : RtNativeFluidSceneSelfTest::isFluidB;
        return countMatching(snapshot, samples.centerX(), samples.centerY(), 2, predicate) >= 3;
    }

    private static ProbeSamples assertSustainedProbePixels(RtFrameSnapshot snapshot, String label) {
        ProbeSamples samples = probeSamples(snapshot);
        require(
                countMatching(snapshot, samples.centerX(), samples.centerY(), 2, pixel -> isFluidA(pixel) || isFluidB(pixel)) >= 3,
                label + " fluid center was neither valid water material variant; a water-surface plant may be occluding the fluid hole"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        requireFluidProbeNotNearBlack(snapshot, samples.centerX(), samples.centerY(), samples, label, "center");
        require(
                countMatching(snapshot, samples.centerX(), samples.centerY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) == 0,
                label + " translucent water center was routed as masked cutout and revealed submerged seagrass"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.centerX(), samples.centerY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.shoreX(), samples.shoreY(), 2, RtNativeFluidSceneSelfTest::isBackplate) >= 3,
                label + " fluid boundary did not expose the solid backplate"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.shoreX(), samples.shoreY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                countMatching(snapshot, samples.plantX(), samples.plantY(), 2, RtNativeFluidSceneSelfTest::isSeagrassCutout) >= 3,
                label + " water-surface cutout plant did not stay opaque over fluid"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, samples.plantX(), samples.plantY(), 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        require(
                samples.centerColor() != RtSceneMaterialTable.missRgba8()
                        && samples.shoreColor() != RtSceneMaterialTable.missRgba8()
                        && samples.plantColor() != RtSceneMaterialTable.missRgba8(),
                label + " fluid probe collapsed into miss/sky blue"
                        + ", samples=" + samples.asLogFragment()
                        + ", snapshot=" + snapshot.asLogFragment()
        );
        return samples;
    }

    private static void requireFluidProbeNotNearBlack(
            RtFrameSnapshot snapshot,
            int centerX,
            int centerY,
            ProbeSamples samples,
            String label,
            String probeName
    ) {
        require(
                countMatching(snapshot, centerX, centerY, 2, RtNativeFluidSceneSelfTest::isNearBlackWaterFailure) == 0,
                label + " fluid " + probeName + " contains near-black water pixels"
                        + ", samples=" + samples.asLogFragment()
                        + ", window=" + sampleWindow(snapshot, centerX, centerY, 2)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
    }

    private static ProbeSamples probeSamples(RtFrameSnapshot snapshot) {
        SectionKey key = probeKey();
        int centerX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 8.0F, FRONT_Z);
        int centerY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 8.0F, FRONT_Z);
        int shoreX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 1.25F, FRONT_Z);
        int shoreY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 1.25F, FRONT_Z);
        int plantX = pixelXForWorld(snapshot.width(), snapshot.height(), key.x() * 16.0F + 3.0F, FRONT_Z);
        int plantY = pixelYForWorld(snapshot.width(), snapshot.height(), key.y() * 16.0F + 8.0F, FRONT_Z);
        byte[] pixels = snapshot.copyRgba8();
        return new ProbeSamples(
                centerX,
                centerY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), centerX, centerY),
                shoreX,
                shoreY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), shoreX, shoreY),
                plantX,
                plantY,
                RtFrameSnapshot.pixel(pixels, snapshot.width(), plantX, plantY)
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

    private static boolean isFluidA(int pixel) {
        /* Water tint keeps A green-dominant after repeated translucent surface composition. */
        return red(pixel) <= 80
                && green(pixel) >= 95
                && blue(pixel) >= 100
                && green(pixel) > red(pixel) + 25
                && blue(pixel) > red(pixel) + 60
                && green(pixel) > blue(pixel) + 35;
    }

    private static boolean isFluidB(int pixel) {
        /* B remains a balanced green/blue water tone; keep this range disjoint from A and seagrass. */
        return red(pixel) >= 20
                && red(pixel) <= 100
                && green(pixel) >= 100
                && green(pixel) <= 200
                && blue(pixel) >= 120
                && blue(pixel) <= 220
                && blue(pixel) > red(pixel) + 70
                && green(pixel) > red(pixel) + 70
                && Math.abs(green(pixel) - blue(pixel)) <= 45;
    }

    private static boolean isBackplate(int pixel) {
        return red(pixel) >= 65 && red(pixel) <= 190
                && green(pixel) >= 30 && green(pixel) <= 120
                && blue(pixel) <= 90
                && red(pixel) > green(pixel)
                && green(pixel) > blue(pixel);
    }

    private static boolean isSeagrassCutout(int pixel) {
        return green(pixel) >= 120
                && red(pixel) <= 90
                && blue(pixel) <= 120
                && green(pixel) > red(pixel) + 55
                && green(pixel) > blue(pixel) + 45;
    }

    private static boolean isNearBlackWaterFailure(int pixel) {
        return red(pixel) <= 24 && green(pixel) <= 36 && blue(pixel) <= 52;
    }

    private static long completedSequenceLag(long sequence, long latestCompletedSequence) {
        if (latestCompletedSequence < 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, sequence - latestCompletedSequence);
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

    private static Map<SectionKey, PreparedSection> buildPreparedSections(
            List<SectionKey> keys,
            RtTextureCatalog.TestTextureScope textures,
            FluidVariant probeVariant
    ) {
        Map<SectionKey, PreparedSection> sections = new LinkedHashMap<>();
        SectionKey probe = probeKey();
        for (int index = 0; index < keys.size(); index++) {
            SectionKey key = keys.get(index);
            sections.put(key, key.equals(probe)
                    ? probePreparedSection(key, textures, probeVariant)
                    : pressurePreparedSection(key, textures, index));
        }
        return sections;
    }

    private static Map<SectionKey, PreparedSection> mutationPreparedSections(
            List<SectionKey> keys,
            SectionKey probeKey,
            RtTextureCatalog.TestTextureScope textures,
            int burst,
            FluidVariant probeVariant
    ) {
        Map<SectionKey, PreparedSection> sections = new LinkedHashMap<>();
        sections.put(probeKey, probePreparedSection(probeKey, textures, probeVariant));
        int offset = Math.floorMod(burst * 67, keys.size());
        for (int index = 0; index < Math.min(MUTATIONS_PER_BURST, keys.size()); index++) {
            SectionKey key = keys.get((offset + index * 19) % keys.size());
            if (!key.equals(probeKey)) {
                sections.put(key, pressurePreparedSection(key, textures, burst * 8191 + index));
            }
        }
        return sections;
    }

    private static PreparedSection probePreparedSection(
            SectionKey key,
            RtTextureCatalog.TestTextureScope textures,
            FluidVariant variant
    ) {
        MeshBuilder builder = new MeshBuilder(key);
        builder.addPositiveZSolidQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                BACK_Z,
                textures.textureId(BACKPLATE_TEXTURE)
        );
        if (variant.fluidPresent()) {
            builder.addPositiveZFluidQuad(
                    2.0F,
                    2.0F,
                    14.0F,
                    14.0F,
                    FRONT_Z,
                    textures.textureId(variant.textureName()),
                    FULL_FLUID_AMOUNT
            );
            builder.addCutoutQuad(
                    new float[]{
                            6.0F, 6.0F, 15.84F,
                            10.0F, 6.0F, 15.84F,
                            10.0F, 10.0F, 15.84F,
                            6.0F, 10.0F, 15.84F
                    },
                    textures.textureId(SEAGRASS_CUTOUT_TEXTURE),
                    standardUvs()
            );
            /*
             * MC renders waterlogged plants as separate block-model quads over an
             * independently emitted FluidRenderer surface. This cutout sits at the
             * water surface height so the gate fails if alpha-cutout any-hit turns
             * the plant into a translucent blue sheet or if the plant suppresses the
             * water top face.
             */
            builder.addCutoutQuad(
                    new float[]{
                            1.0F, 4.5F, 15.99F,
                            5.5F, 4.5F, 15.99F,
                            5.5F, 11.5F, 15.99F,
                            1.0F, 11.5F, 15.99F
                    },
                    textures.textureId(SEAGRASS_CUTOUT_TEXTURE),
                    standardUvs()
            );
        }
        return new PreparedSection(builder.build(), variant.fluidPresent());
    }

    private static PreparedSection pressurePreparedSection(
            SectionKey key,
            RtTextureCatalog.TestTextureScope textures,
            int variant
    ) {
        MeshBuilder builder = new MeshBuilder(key);
        int waterTexture = textures.textureId((variant & 1) == 0 ? WATER_A_TEXTURE : WATER_B_TEXTURE);
        int alternateWaterTexture = textures.textureId((variant & 1) == 0 ? WATER_B_TEXTURE : WATER_A_TEXTURE);
        builder.addPositiveZSolidQuad(
                0.0F,
                0.0F,
                16.0F,
                16.0F,
                BACK_Z,
                textures.textureId(BACKPLATE_TEXTURE)
        );
        builder.addPositiveZFluidQuad(1.5F, 1.5F, 7.6F, 7.6F, FRONT_Z, waterTexture, FULL_FLUID_AMOUNT);
        builder.addPositiveZFluidQuad(8.4F, 1.5F, 14.5F, 7.6F, FRONT_Z, alternateWaterTexture, 6);
        builder.addPositiveZFluidQuad(1.5F, 8.4F, 7.6F, 14.5F, FRONT_Z, alternateWaterTexture, 5);
        builder.addPositiveZFluidQuad(8.4F, 8.4F, 14.5F, 14.5F, FRONT_Z, waterTexture, FULL_FLUID_AMOUNT);
        builder.addCutoutQuad(
                new float[]{
                        2.0F, 3.0F, 15.98F,
                        14.0F, 3.0F, 15.98F,
                        14.0F, 4.8F, 15.98F,
                        2.0F, 4.8F, 15.98F
                },
                textures.textureId(FOAM_CUTOUT_TEXTURE),
                rotatedUvs(variant)
        );
        builder.addCutoutQuad(
                new float[]{
                        4.0F, 12.5F, 15.97F,
                        12.0F, 2.0F, 15.52F,
                        12.0F, 2.0F, 14.92F,
                        4.0F, 12.5F, 15.37F
                },
                textures.textureId(FOAM_CUTOUT_TEXTURE),
                rotatedUvs(variant + 1)
        );
        return new PreparedSection(builder.build(), true);
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

    private static SectionVoxelSnapshot sectionSnapshot(SectionKey key, boolean fluidPresent) {
        int[] ids = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] fluids = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] lightEmissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] flags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(ids, fluidPresent ? WATER_BLOCK_STATE_ID : SOLID_BLOCK_STATE_ID);
        Arrays.fill(mapColors, fluidPresent ? 0x2A6FDB : 0xB46430);
        byte materialFlags = (byte) (SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN
                | (fluidPresent
                        ? SectionVoxelSnapshot.FLAG_LIQUID
                        : SectionVoxelSnapshot.FLAG_OCCLUDES_NEIGHBORS));
        Arrays.fill(flags, materialFlags);
        if (fluidPresent) {
            Arrays.fill(fluids, (byte) FULL_FLUID_AMOUNT);
        }
        return new SectionVoxelSnapshot(key, ids, fluids, mapColors, lightEmissions, flags, false, fluidPresent);
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

    private static WaterloggedStressStats assertWaterloggedMeshingStressContract() {
        SectionMesher mesher = new SectionMesher();
        SectionMeshBuilder meshBuilder = new SectionMeshBuilder();
        long waterloggedVoxels = 0L;
        long plantFaces = 0L;
        long fluidTopFaces = 0L;
        long meshFaces = 0L;
        int maxSectionFaces = 0;
        int maxMeshFaces = 0;

        for (int section = 0; section < WATERLOGGED_STRESS_SECTIONS; section++) {
            SectionKey key = new SectionKey(section % 64, section / 64, 900 + section % 17);
            SectionVoxelSnapshot snapshot = waterloggedStressSection(key, section);
            SectionGeometrySnapshot geometry = mesher.build(snapshot, 64, 64);
            maxSectionFaces = Math.max(maxSectionFaces, geometry.faceCount());

            for (top.ceroxe.mcvulkanrt.renderer.scene.SectionFace face : geometry.faces()) {
                if (isWaterloggedPlantState(face.voxelTypeId())) {
                    plantFaces++;
                    require(
                            face.mediumAmount() == 0
                                    && (face.materialFlags() & SectionVoxelSnapshot.FLAG_LIQUID) == 0,
                            "waterlogged stress leaked fluid material into plant face"
                                    + ", section=" + key
                                    + ", face=" + face
                    );
                }
                if (face.voxelTypeId() == WATER_BLOCK_STATE_ID
                        && face.mediumAmount() == FULL_FLUID_AMOUNT
                        && face.direction() == FaceDirection.POSITIVE_Y
                        && (face.materialFlags() & SectionVoxelSnapshot.FLAG_LIQUID) != 0) {
                    fluidTopFaces++;
                }
            }

            SectionTriangleMesh mesh = meshBuilder.build(geometry);
            int[] blockIds = mesh.faceVoxelStateIds();
            byte[] fluids = mesh.faceFluidAmounts();
            byte[] flags = mesh.faceMaterialFlags();
            for (int face = 0; face < mesh.faceCount(); face++) {
                if (isWaterloggedPlantState(blockIds[face])) {
                    require(Byte.toUnsignedInt(fluids[face]) == 0,
                            "waterlogged stress mesh leaked fluid amount into plant face");
                    require((Byte.toUnsignedInt(flags[face]) & SectionVoxelSnapshot.FLAG_LIQUID) == 0,
                            "waterlogged stress mesh leaked liquid flag into plant face");
                }
            }
            waterloggedVoxels += waterloggedVoxelCount(section);
            meshFaces += mesh.faceCount();
            maxMeshFaces = Math.max(maxMeshFaces, mesh.faceCount());
        }

        require(plantFaces >= waterloggedVoxels,
                "waterlogged stress did not keep plant block faces alive"
                        + ", plantFaces=" + plantFaces
                        + ", waterloggedVoxels=" + waterloggedVoxels);
        require(fluidTopFaces >= waterloggedVoxels,
                "waterlogged stress did not emit independent fluid top faces"
                        + ", fluidTopFaces=" + fluidTopFaces
                        + ", waterloggedVoxels=" + waterloggedVoxels);
        require(maxSectionFaces < 16_384,
                "waterlogged stress generated pathological section face count"
                        + ", maxSectionFaces=" + maxSectionFaces);
        return new WaterloggedStressStats(
                WATERLOGGED_STRESS_SECTIONS,
                waterloggedVoxels,
                plantFaces,
                fluidTopFaces,
                meshFaces,
                maxSectionFaces,
                maxMeshFaces
        );
    }

    private static FluidFamilyStressStats assertFluidFamilyMeshingStressContract() {
        SectionMesher mesher = new SectionMesher();
        SectionMeshBuilder meshBuilder = new SectionMeshBuilder();
        long fluidVoxels = 0L;
        long geometryFaces = 0L;
        long meshFaces = 0L;
        int maxSectionFaces = 0;
        int maxMeshFaces = 0;

        for (int section = 0; section < FLUID_FAMILY_STRESS_SECTIONS; section++) {
            SectionKey key = new SectionKey(section % 64, section / 64, 1200 + section % 23);
            SectionVoxelSnapshot snapshot = fluidFamilyStressSection(key, section);
            SectionEncodedSnapshot encoded = SectionEncodedSnapshot.encode(snapshot);
            SectionGeometrySnapshot geometry = mesher.build(snapshot, encoded.paletteSize(), encoded.runCount());
            SectionTriangleMesh mesh = meshBuilder.build(geometry);
            long sectionFluidVoxels = fluidFamilyVoxelCount(section);
            fluidVoxels += sectionFluidVoxels;
            geometryFaces += geometry.faceCount();
            meshFaces += mesh.faceCount();
            maxSectionFaces = Math.max(maxSectionFaces, geometry.faceCount());
            maxMeshFaces = Math.max(maxMeshFaces, mesh.faceCount());

            require(encoded.mediumTypeIdAt(0, 0, 0) == WATER_FLUID_TYPE_ID,
                    "fluid family stress lost fluid type id through palette/RLE");
            require(geometry.faceCount() < 1_600,
                    "fluid family stress generated pathological internal same-fluid faces"
                            + ", section=" + key
                            + ", faceCount=" + geometry.faceCount()
                            + ", fluidVoxels=" + sectionFluidVoxels);
            require(mesh.faceCount() == geometry.faceCount(),
                    "fluid family stress mesh must preserve geometry face count");
        }

        return new FluidFamilyStressStats(
                FLUID_FAMILY_STRESS_SECTIONS,
                fluidVoxels,
                geometryFaces,
                meshFaces,
                maxSectionFaces,
                maxMeshFaces
        );
    }

    private static FluidNeighborhoodStressStats assertFluidNeighborhoodMeshingStressContract() {
        SectionMesher mesher = new SectionMesher();
        int checkedCorners = 0;
        int diagonalInfluencedCorners = 0;
        int rebuiltDependents = 0;

        for (int section = 0; section < 128; section++) {
            SectionKey centerKey = new SectionKey(1600 + section * 3, section & 7, 1700 + section * 5);
            SectionVoxelSnapshot center = singleFluidVoxel(centerKey, 15, 7, 15, 4);
            SectionVoxelSnapshot east = singleFluidVoxel(new SectionKey(centerKey.x() + 1, centerKey.y(), centerKey.z()), 0, 7, 15, 2);
            SectionVoxelSnapshot south = singleFluidVoxel(new SectionKey(centerKey.x(), centerKey.y(), centerKey.z() + 1), 15, 7, 0, 2);
            SectionVoxelSnapshot diagonal = singleFluidVoxel(new SectionKey(centerKey.x() + 1, centerKey.y(), centerKey.z() + 1), 0, 7, 0, FULL_FLUID_AMOUNT);

            SectionGeometrySnapshot complete = mesher.build(
                    center,
                    4,
                    4,
                    SectionNeighborhood.fromSnapshots(
                            centerKey,
                            Map.of(
                                    east.key(), east,
                                    south.key(), south,
                                    diagonal.key(), diagonal
                            )
                    )
            );
            SectionGeometrySnapshot missingDiagonal = mesher.build(
                    center,
                    3,
                    3,
                    SectionNeighborhood.fromSnapshots(
                            centerKey,
                            Map.of(
                                    east.key(), east,
                                    south.key(), south
                            )
                    )
            );
            top.ceroxe.mcvulkanrt.renderer.scene.SectionFace completeTop = fluidTopFace(complete);
            top.ceroxe.mcvulkanrt.renderer.scene.SectionFace missingDiagonalTop = fluidTopFace(missingDiagonal);
            checkedCorners++;
            if (completeTop.fluidHeight1() > missingDiagonalTop.fluidHeight1()) {
                diagonalInfluencedCorners++;
            }

            SceneDatabase database = new SceneDatabase();
            database.replaceChunkSnapshot(new ChunkSnapshot(centerKey.chunkKey(), centerKey.y(), List.of(center)));
            database.drainPendingUpdates();
            database.replaceChunkSnapshot(new ChunkSnapshot(diagonal.key().chunkKey(), diagonal.key().y(), List.of(diagonal)));
            SceneUpdateBatch batch = database.drainPendingUpdates();
            if (batch.sectionSnapshots().containsKey(centerKey)
                    && database.snapshotSectionNeighborhood(centerKey).snapshots().containsKey(diagonal.key())) {
                rebuiltDependents++;
            }
        }

        require(
                diagonalInfluencedCorners == checkedCorners,
                "fluid neighborhood stress lost cross-section diagonal corner heights"
                        + ", checkedCorners=" + checkedCorners
                        + ", diagonalInfluencedCorners=" + diagonalInfluencedCorners
        );
        require(
                rebuiltDependents == checkedCorners,
                "fluid neighborhood stress did not dirty all diagonal corner dependents"
                        + ", checkedCorners=" + checkedCorners
                        + ", rebuiltDependents=" + rebuiltDependents
        );
        return new FluidNeighborhoodStressStats(checkedCorners, diagonalInfluencedCorners, rebuiltDependents);
    }

    private static top.ceroxe.mcvulkanrt.renderer.scene.SectionFace fluidTopFace(SectionGeometrySnapshot geometry) {
        return geometry.faces()
                .stream()
                .filter(face -> face.direction() == FaceDirection.POSITIVE_Y)
                .filter(face -> face.mediumAmount() > 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("fluid neighborhood stress top face missing: " + geometry.key()));
    }

    private static SectionVoxelSnapshot singleFluidVoxel(SectionKey key, int x, int y, int z, int amount) {
        int[] voxelTypeIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mediumStateIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mediumTypeIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] mediumAmounts = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] fluidMapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] lightEmissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] materialFlags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] shadeBrightnesses = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(shadeBrightnesses, (byte) 255);
        int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
        voxelTypeIds[index] = amount == FULL_FLUID_AMOUNT ? WATER_BLOCK_STATE_ID : WATER_FLOWING_BLOCK_STATE_ID;
        mediumStateIds[index] = voxelTypeIds[index];
        mediumTypeIds[index] = WATER_FLUID_TYPE_ID;
        mediumAmounts[index] = (byte) amount;
        mapColors[index] = SectionVoxelSnapshot.packMapColorAndLight(0x2A6FDB, 15, 0);
        fluidMapColors[index] = mapColors[index];
        materialFlags[index] = SectionVoxelSnapshot.FLAG_LIQUID
                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN
                | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
        return new SectionVoxelSnapshot(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumTypeIds,
                mediumAmounts,
                mapColors,
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                false,
                true
        );
    }

    private static SectionVoxelSnapshot waterloggedStressSection(SectionKey key, int sectionOrdinal) {
        int[] voxelTypeIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mediumStateIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] mediumAmounts = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] lightEmissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] materialFlags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];

        for (int y = 0; y < SectionVoxelSnapshot.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionVoxelSnapshot.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionVoxelSnapshot.SECTION_SIZE; x++) {
                    int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
                    if (y < 8) {
                        voxelTypeIds[index] = WATER_BLOCK_STATE_ID;
                        mediumStateIds[index] = WATER_BLOCK_STATE_ID;
                        mediumAmounts[index] = FULL_FLUID_AMOUNT;
                        materialFlags[index] = SectionVoxelSnapshot.FLAG_LIQUID
                                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN;
                    } else if (y == 8 && isWaterloggedStressPlant(x, z, sectionOrdinal)) {
                        voxelTypeIds[index] = waterloggedPlantStateId(x, z, sectionOrdinal);
                        mediumStateIds[index] = WATER_BLOCK_STATE_ID;
                        mediumAmounts[index] = FULL_FLUID_AMOUNT;
                        materialFlags[index] = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN;
                    } else if (y == 8 && ((x * 31 + z * 17 + sectionOrdinal) & 7) == 0) {
                        voxelTypeIds[index] = SOLID_BLOCK_STATE_ID;
                        materialFlags[index] = SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE
                                | SectionVoxelSnapshot.FLAG_OCCLUDES_NEIGHBORS
                                | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN;
                    } else {
                        materialFlags[index] = SectionVoxelSnapshot.FLAG_AIR;
                    }
                }
            }
        }

        return new SectionVoxelSnapshot(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumAmounts,
                mapColors,
                lightEmissions,
                materialFlags,
                false,
                true
        );
    }

    private static SectionVoxelSnapshot fluidFamilyStressSection(SectionKey key, int sectionOrdinal) {
        int[] voxelTypeIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mediumStateIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mediumTypeIds = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] mediumAmounts = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] mapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        int[] fluidMapColors = new int[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] lightEmissions = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] materialFlags = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        byte[] shadeBrightnesses = new byte[SectionVoxelSnapshot.BLOCKS_PER_SECTION];
        Arrays.fill(shadeBrightnesses, (byte) 255);

        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < SectionVoxelSnapshot.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionVoxelSnapshot.SECTION_SIZE; x++) {
                    int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
                    int legacyState = ((x + z + y + sectionOrdinal) & 1) == 0
                            ? WATER_BLOCK_STATE_ID
                            : WATER_FLOWING_BLOCK_STATE_ID;
                    int amount = legacyState == WATER_BLOCK_STATE_ID ? FULL_FLUID_AMOUNT : 2 + ((x + z + sectionOrdinal) & 3);
                    voxelTypeIds[index] = legacyState;
                    mediumStateIds[index] = legacyState;
                    mediumTypeIds[index] = WATER_FLUID_TYPE_ID;
                    mediumAmounts[index] = (byte) amount;
                    mapColors[index] = SectionVoxelSnapshot.packMapColorAndLight(0x2A6FDB, 15, 0);
                    fluidMapColors[index] = mapColors[index];
                    materialFlags[index] = SectionVoxelSnapshot.FLAG_LIQUID
                            | SectionVoxelSnapshot.FLAG_OCCLUSION_KNOWN
                            | SectionVoxelSnapshot.FLAG_LIGHT_KNOWN;
                }
            }
        }
        for (int y = 8; y < SectionVoxelSnapshot.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionVoxelSnapshot.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionVoxelSnapshot.SECTION_SIZE; x++) {
                    int index = SectionVoxelSnapshot.localBlockIndex(x, y, z);
                    materialFlags[index] = SectionVoxelSnapshot.FLAG_AIR;
                }
            }
        }

        return new SectionVoxelSnapshot(
                key,
                voxelTypeIds,
                mediumStateIds,
                mediumTypeIds,
                mediumAmounts,
                mapColors,
                fluidMapColors,
                lightEmissions,
                materialFlags,
                shadeBrightnesses,
                false,
                true
        );
    }

    private static long fluidFamilyVoxelCount(int sectionOrdinal) {
        return 8L * SectionVoxelSnapshot.SECTION_SIZE * SectionVoxelSnapshot.SECTION_SIZE;
    }

    private static long waterloggedVoxelCount(int sectionOrdinal) {
        long count = 0L;
        for (int z = 0; z < SectionVoxelSnapshot.SECTION_SIZE; z++) {
            for (int x = 0; x < SectionVoxelSnapshot.SECTION_SIZE; x++) {
                if (isWaterloggedStressPlant(x, z, sectionOrdinal)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isWaterloggedStressPlant(int x, int z, int sectionOrdinal) {
        return ((x * 13 + z * 7 + sectionOrdinal) & 3) == 0;
    }

    private static int waterloggedPlantStateId(int x, int z, int sectionOrdinal) {
        return 2_001 + Math.floorMod(x + z + sectionOrdinal, 3);
    }

    private static boolean isWaterloggedPlantState(int voxelTypeId) {
        return voxelTypeId >= 2_001 && voxelTypeId <= 2_003;
    }

    private static List<RtTextureCatalog.TestTexture> testTextures() {
        return List.of(
                /*
                 * Keep the probe textures recognizably water-colored after the
                 * closest-hit shader applies vanilla face shade. The gate must
                 * validate material-driven fluid color, not the old shader-side
                 * "every liquid is blue water" fallback.
                 */
                new RtTextureCatalog.TestTexture(WATER_A_TEXTURE, 16, 16, solidTexture(0, 190, 220, 96, 16, 16)),
                new RtTextureCatalog.TestTexture(WATER_B_TEXTURE, 16, 16, solidTexture(60, 80, 255, 96, 16, 16)),
                new RtTextureCatalog.TestTexture(BACKPLATE_TEXTURE, 8, 8, solidTexture(180, 100, 48, 255, 8, 8)),
                new RtTextureCatalog.TestTexture(FOAM_CUTOUT_TEXTURE, 8, 8, foamCutoutTexture()),
                new RtTextureCatalog.TestTexture(SEAGRASS_CUTOUT_TEXTURE, 8, 8, seagrassCutoutTexture())
        );
    }

    private static int[] foamCutoutTexture() {
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean visible = x == y || x + y == 7 || (y == 3 && x >= 2 && x <= 5);
                pixels[y * 8 + x] = visible
                        ? rgba8(232, 248, 255, 255)
                        : rgba8(0, 0, 0, 0);
            }
        }
        return pixels;
    }

    private static int[] seagrassCutoutTexture() {
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean blade = x >= 2 && x <= 5 || (y >= 4 && (x == 1 || x == 6));
                pixels[y * 8 + x] = blade
                        ? rgba8(36, 214, 78, 255)
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

    private enum FluidVariant {
        FULL_A(WATER_A_TEXTURE, true),
        FULL_B(WATER_B_TEXTURE, true),
        DRAINED("", false);

        private final String textureName;
        private final boolean fluidPresent;

        FluidVariant(String textureName, boolean fluidPresent) {
            this.textureName = textureName;
            this.fluidPresent = fluidPresent;
        }

        private String textureName() {
            if (!fluidPresent) {
                throw new IllegalStateException("drained fluid variant has no water texture");
            }
            return textureName;
        }

        private boolean fluidPresent() {
            return fluidPresent;
        }
    }

    private record PreparedSection(SectionTriangleMesh mesh, boolean fluidPresent) {
        private PreparedSection {
            mesh = java.util.Objects.requireNonNull(mesh, "mesh");
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
            int centerX,
            int centerY,
            int centerColor,
            int shoreX,
            int shoreY,
            int shoreColor,
            int plantX,
            int plantY,
            int plantColor
    ) {
        private String asLogFragment() {
            return "probeSamples{center=(" + centerX + "," + centerY + "=" + RtFrameSnapshot.hex(centerColor)
                    + "), shore=(" + shoreX + "," + shoreY + "=" + RtFrameSnapshot.hex(shoreColor)
                    + "), plant=(" + plantX + "," + plantY + "=" + RtFrameSnapshot.hex(plantColor) + ")}";
        }
    }

    private record WaterloggedStressStats(
            int sections,
            long waterloggedVoxels,
            long plantFaces,
            long fluidTopFaces,
            long meshFaces,
            int maxSectionFaces,
            int maxMeshFaces
    ) {
        private String asLogFragment() {
            return "waterloggedStress{sections=" + sections
                    + ", waterloggedVoxels=" + waterloggedVoxels
                    + ", plantFaces=" + plantFaces
                    + ", fluidTopFaces=" + fluidTopFaces
                    + ", meshFaces=" + meshFaces
                    + ", maxSectionFaces=" + maxSectionFaces
                    + ", maxMeshFaces=" + maxMeshFaces
                    + "}";
        }
    }

    private record FluidFamilyStressStats(
            int sections,
            long fluidVoxels,
            long geometryFaces,
            long meshFaces,
            int maxSectionFaces,
            int maxMeshFaces
    ) {
        private String asLogFragment() {
            return "fluidFamilyStress{sections=" + sections
                    + ", fluidVoxels=" + fluidVoxels
                    + ", geometryFaces=" + geometryFaces
                    + ", meshFaces=" + meshFaces
                    + ", maxSectionFaces=" + maxSectionFaces
                    + ", maxMeshFaces=" + maxMeshFaces
                    + "}";
        }
    }

    private record FluidNeighborhoodStressStats(
            int checkedCorners,
            int diagonalInfluencedCorners,
            int rebuiltDependents
    ) {
        private String asLogFragment() {
            return "fluidNeighborhoodStress{checkedCorners=" + checkedCorners
                    + ", diagonalInfluencedCorners=" + diagonalInfluencedCorners
                    + ", rebuiltDependents=" + rebuiltDependents
                    + "}";
        }
    }

    private static final class FluidSceneState {
        private final SceneDatabase database = new SceneDatabase();
        private final SectionMaterialCache materialCache = new SectionMaterialCache();
        private final SectionGeometryCache geometryCache = SectionGeometryCache.transientProductionStaging();
        private final SectionMeshCache meshCache = new SectionMeshCache();
        private long snapshotNanos;
        private long databaseReplaceNanos;
        private long databaseDrainNanos;
        private long materialNanos;
        private long geometryNanos;
        private long meshNanos;

        private RendererFrameUpdate initialUpdate(
                Map<SectionKey, PreparedSection> sections,
                RendererFrameState frameState
        ) {
            Map<ChunkKey, List<SectionVoxelSnapshot>> sectionsByChunk = new LinkedHashMap<>();
            for (Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
                sectionsByChunk
                        .computeIfAbsent(entry.getKey().chunkKey(), ignored -> new ArrayList<>())
                        .add(sectionSnapshot(entry.getKey(), entry.getValue().fluidPresent()));
            }
            for (Map.Entry<ChunkKey, List<SectionVoxelSnapshot>> entry : sectionsByChunk.entrySet()) {
                int minY = entry.getValue().stream()
                        .mapToInt(section -> section.key().y())
                        .min()
                        .orElse(0);
                database.replaceChunkSnapshot(new ChunkSnapshot(entry.getKey(), minY, entry.getValue()));
            }
            return applyPreparedSections(sections, frameState);
        }

        private RendererFrameUpdate replacePreparedSections(
                Map<SectionKey, PreparedSection> sections,
                RendererFrameState frameState
        ) {
            long stageStartNanos = System.nanoTime();
            List<SectionVoxelSnapshot> snapshots = sections.entrySet().stream()
                    .map(entry -> sectionSnapshot(entry.getKey(), entry.getValue().fluidPresent()))
                    .toList();
            snapshotNanos += System.nanoTime() - stageStartNanos;
            stageStartNanos = System.nanoTime();
            for (SectionVoxelSnapshot snapshot : snapshots) {
                database.replaceBlockMutationSectionSnapshot(snapshot);
            }
            databaseReplaceNanos += System.nanoTime() - stageStartNanos;
            return applyPreparedSections(sections, frameState);
        }

        private RendererFrameUpdate applyPreparedSections(
                Map<SectionKey, PreparedSection> sections,
                RendererFrameState frameState
        ) {
            long stageStartNanos = System.nanoTime();
            SceneUpdateBatch batch = database.drainPendingUpdates();
            databaseDrainNanos += System.nanoTime() - stageStartNanos;
            if (!batch.hasChanges() && !sections.isEmpty()) {
                batch = preparedSectionBatch(sections);
            }
            stageStartNanos = System.nanoTime();
            SectionMaterialCache.MaterialFacts materialFacts = SectionMaterialCache.MaterialFacts.empty();
            for (SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
                materialFacts = materialFacts.plus(SectionMaterialCache.MaterialFacts.fromSnapshot(snapshot));
            }
            SectionMaterialCache.ApplyResult material = materialCache.applyMaterialUpdates(
                    batch,
                    batch.sectionSnapshots().keySet(),
                    materialFacts
            );
            materialNanos += System.nanoTime() - stageStartNanos;
            stageStartNanos = System.nanoTime();
            SectionGeometryCache.ApplyResult geometry = geometryCache.applyPrepared(
                    Map.of(),
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            geometryNanos += System.nanoTime() - stageStartNanos;
            Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
            for (Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
                meshes.put(entry.getKey(), entry.getValue().mesh());
            }
            stageStartNanos = System.nanoTime();
            SectionMeshCache.ApplyResult meshResult = meshCache.applyPrepared(
                    meshes,
                    batch.removedSections(),
                    batch.fullResyncRequested()
            );
            meshNanos += System.nanoTime() - stageStartNanos;
            require(meshResult.trianglesInBatch() > 0, "fluid scene update must submit visible triangles");
            return new RendererFrameUpdate(batch, material, geometry, meshResult, frameState);
        }

        private void resetMutationTimings() {
            snapshotNanos = 0L;
            databaseReplaceNanos = 0L;
            databaseDrainNanos = 0L;
            materialNanos = 0L;
            geometryNanos = 0L;
            meshNanos = 0L;
        }

        private String mutationTimingSummary() {
            return "snapshot=" + snapshotNanos / 1_000_000L
                    + "ms,dbReplace=" + databaseReplaceNanos / 1_000_000L
                    + "ms,dbDrain=" + databaseDrainNanos / 1_000_000L
                    + "ms,material=" + materialNanos / 1_000_000L
                    + "ms,geometry=" + geometryNanos / 1_000_000L
                    + "ms,mesh=" + meshNanos / 1_000_000L + "ms";
        }

        private static SceneUpdateBatch preparedSectionBatch(Map<SectionKey, PreparedSection> sections) {
            Set<SectionKey> dirtySections = Set.copyOf(sections.keySet());
            Set<ChunkKey> dirtyChunks = new java.util.LinkedHashSet<>();
            Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
            for (Map.Entry<SectionKey, PreparedSection> entry : sections.entrySet()) {
                dirtyChunks.add(entry.getKey().chunkKey());
                snapshots.put(entry.getKey(), sectionSnapshot(entry.getKey(), entry.getValue().fluidPresent()));
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

        private void addPositiveZSolidQuad(float x0, float y0, float x1, float y1, float z, int textureId) {
            addQuad(
                    new float[]{
                            x0, y0, z,
                            x1, y0, z,
                            x1, y1, z,
                            x0, y1, z
                    },
                    FaceDirection.POSITIVE_Z,
                    SOLID_BLOCK_STATE_ID,
                    0,
                    SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE | SectionVoxelSnapshot.FLAG_OCCLUDES_NEIGHBORS,
                    textureId,
                    false,
                    false,
                    standardUvs()
            );
        }

        private void addPositiveZFluidQuad(
                float x0,
                float y0,
                float x1,
                float y1,
                float z,
                int textureId,
                int mediumAmount
        ) {
            addQuad(
                    new float[]{
                            x0, y0, z,
                            x1, y0, z,
                            x1, y1, z,
                            x0, y1, z
                    },
                    FaceDirection.POSITIVE_Z,
                    WATER_BLOCK_STATE_ID,
                    mediumAmount,
                    SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE | SectionVoxelSnapshot.FLAG_LIQUID,
                    textureId,
                    false,
                    false,
                    standardUvs()
            );
        }

        private void addCutoutQuad(float[] quadPositions, int textureId, int[] packedUvs) {
            addQuad(
                    quadPositions,
                    FaceDirection.POSITIVE_Z,
                    SOLID_BLOCK_STATE_ID,
                    0,
                    SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE,
                    textureId,
                    false,
                    true,
                    packedUvs
            );
        }

        private void addQuad(
                float[] quadPositions,
                FaceDirection direction,
                int voxelTypeId,
                int mediumAmount,
                int packedMaterialFlags,
                int textureId,
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
            voxelTypeIds.add(voxelTypeId);
            mediumAmounts.add((byte) mediumAmount);
            directions.add((byte) direction.ordinal());
            mapColors.add(0);
            lightEmissions.add((byte) 0);
            materialFlags.add((byte) packedMaterialFlags);
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
            require(positions.size() == faceCount * VERTICES_PER_FACE * 3, "fluid mesh vertex count mismatch");
            require(indices.size() == faceCount * INDICES_PER_FACE, "fluid mesh index count mismatch");
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
