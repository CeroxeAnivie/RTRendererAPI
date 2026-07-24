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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hardware-backed MC-outside gate for the dynamic scene SSBO path.
 *
 * <p>The scene intentionally keeps terrain almost trivial after the initial TLAS
 * build. Sustained frames then mutate only sky/celestial data, which catches the
 * class of bugs where Java telemetry says dynamic content exists but the RT
 * shader still renders stale fixed miss color or overwrites a pending frame's
 * dynamic buffer.</p>
 */
public final class RtNativeDynamicSkySceneSelfTest {
    private static final int OUTPUT_WIDTH = intProperty("mcvulkanrt.rt.dynamicSky.width", 960);
    private static final int OUTPUT_HEIGHT = intProperty("mcvulkanrt.rt.dynamicSky.height", 540);
    private static final int SUSTAINED_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicSky.sustainedFrames", 240);
    private static final int DYNAMIC_UPDATE_PERIOD_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicSky.updatePeriodFrames", 4);
    private static final int MAX_INITIAL_READY_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicSky.maxInitialReadyPumpFrames", 900);
    private static final int MAX_FRESH_PUMP_FRAMES =
            intProperty("mcvulkanrt.rt.dynamicSky.maxFreshPumpFrames", 900);
    private static final int READBACK_SAMPLE_INTERVAL =
            intProperty("mcvulkanrt.rt.dynamicSky.readbackSampleInterval", 1);
    private static final int MIN_DISTINCT_CHECKSUMS =
            intProperty("mcvulkanrt.rt.dynamicSky.minDistinctChecksums", 6);
    private static final long PUMP_SLEEP_MILLIS =
            longProperty("mcvulkanrt.rt.dynamicSky.pumpSleepMillis", 5L);
    private static final int BLOCK_STATE_ID = 1;
    private static final int SUN_SAMPLE_X = OUTPUT_WIDTH * 3 / 4;
    private static final int SUN_SAMPLE_Y = OUTPUT_HEIGHT / 4;
    private static final int MOON_SAMPLE_X = OUTPUT_WIDTH / 4;
    private static final int MOON_SAMPLE_Y = OUTPUT_HEIGHT / 3;
    private static final Path SNAPSHOT_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mcvulkanrt-native-dynamic-sky-scene.png");

    private RtNativeDynamicSkySceneSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> previousProperties = installDynamicSkyProperties();
        try {
            VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
            require(
                    capability.hardwareRayTracingReady(),
                    "native dynamic sky scene requires production RT hardware: " + capability.summary()
            );

            DynamicSkyResult result = runDynamicSkyScene(capability);
            writeSnapshotPng(result.lastSnapshot(), SNAPSHOT_PATH);
            System.out.println("RtNativeDynamicSkySceneSelfTest passed: sustainedFrames=" + SUSTAINED_FRAMES
                    + ", dynamicUpdates=" + result.dynamicUpdates()
                    + ", completedFrames=" + result.completedFrames()
                    + ", averageCompletedFps=" + result.averageCompletedFps()
                    + ", distinctChecksums=" + result.distinctChecksums()
                    + ", lastSnapshot=" + result.lastSnapshot().asLogFragment()
                    + ", png=" + SNAPSHOT_PATH
                    + ", activity=" + result.activity().asLogFragment()
                    + ", readiness=" + result.readiness().asLogFragment());
            System.out.println(RtNativeBenchmarkReport.pacedScene(
                    "dynamicSky",
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

    private static DynamicSkyResult runDynamicSkyScene(
            VulkanRtCapabilityProbe.Result capability
    ) throws InterruptedException {
        try (GuardedRtCore rtCore = GuardedRtCore.isolatedHardwareTest()) {
            rtCore.acceptCapability(capability);
            require(
                    rtCore.state() == RtCore.State.READY_FOR_SCENE_UPDATES,
                    "RT core did not open native backend for dynamic sky scene: state=" + rtCore.state()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            RendererFrameState baseFrameState = frameState(1L);
            float[] sunDirection = rayDirection(baseFrameState, SUN_SAMPLE_X, SUN_SAMPLE_Y);
            float[] moonDirection = rayDirection(baseFrameState, MOON_SAMPLE_X, MOON_SAMPLE_Y);
            rtCore.acceptFrameUpdate(initialUpdate(
                    terrainAnchor(new SectionKey(0, 0, 0)),
                    baseFrameState,
                    dynamicSkyScene(1L, sunDirection, rgba8(255, 244, 176, 255), moonDirection, rgba8(128, 176, 255, 255))
            ));

            RtFrameSnapshot readySnapshot = pumpUntilFreshSnapshot(rtCore, 2L, "dynamic sky initial ready");
            RtNativeStressGuards.assertFrameNotPathological(readySnapshot, "initial native dynamic sky frame");
            assertSkyGradient(readySnapshot, "initial dynamic sky");
            assertWarmSunAt(readySnapshot, SUN_SAMPLE_X, SUN_SAMPLE_Y, "initial dynamic sky");
            assertCoolMoonAt(readySnapshot, MOON_SAMPLE_X, MOON_SAMPLE_Y, "initial dynamic sky");

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
                    float t = frame / (float) Math.max(1, SUSTAINED_FRAMES - 1);
                    float[] animatedSunDirection = normalize(
                            sunDirection[0] * (1.0F - t) + moonDirection[0] * t * 0.35F,
                            sunDirection[1] * (1.0F - t) + moonDirection[1] * t * 0.20F,
                            sunDirection[2]
                    );
                    int sunColor = (frame / DYNAMIC_UPDATE_PERIOD_FRAMES & 1) == 0
                            ? rgba8(255, 236, 160, 255)
                            : rgba8(255, 192, 96, 255);
                    update = RendererFrameUpdate.dynamicOnly(
                            emptyBatch(),
                            currentFrameState,
                            RendererUpdateLoop.BacklogSnapshot.empty(),
                            dynamicSkyScene(
                                    2L + dynamicUpdates,
                                    animatedSunDirection,
                                    sunColor,
                                    moonDirection,
                                    rgba8(112, 168, 255, 255)
                            )
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
                        "RT core failed during native dynamic sky scene: state=" + rtCore.state()
                                + ", activity=" + activity.asLogFragment()
                                + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                                + ", summary=" + rtCore.summary().asLogFragment()
                );
                Thread.sleep(PUMP_SLEEP_MILLIS);
            }

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
            RtFrameSnapshot clearSnapshot = pumpUntilFreshSnapshot(rtCore, 30_001L, "dynamic sky clear");
            checksums.add(clearSnapshot.checksum());
            lastSnapshot = clearSnapshot;
            require(
                    countMatching(clearSnapshot, SUN_SAMPLE_X, SUN_SAMPLE_Y, 3, RtNativeDynamicSkySceneSelfTest::isWarmSun) == 0,
                    "dynamic sky clear left stale sun pixels at the previous sun sample"
                            + ", snapshot=" + clearSnapshot.asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );

            long elapsedNanos = Math.max(1L, System.nanoTime() - phaseStartNanos);
            double averageCompletedFps = completedFrames * 1_000_000_000.0D / elapsedNanos;
            require(
                    averageCompletedFps >= 15.0D,
                    "native dynamic sky scene completed frames below 15 fps floor"
                            + ", averageCompletedFps=" + averageCompletedFps
                            + ", completedFrames=" + completedFrames
                            + ", activity=" + rtCore.runtimeActivity().asLogFragment()
                            + ", readiness=" + rtCore.sceneReadiness().asLogFragment()
                            + ", summary=" + rtCore.summary().asLogFragment()
            );
            require(
                    checksums.size() >= MIN_DISTINCT_CHECKSUMS,
                    "dynamic sky scene did not visibly change across dynamic-only revisions"
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
                    "dynamic scene clear did not reach the RT pipeline; summary=" + summary
            );
            RtNativeStressGuards.assertCommandAndFencePoolReused(rtCore, "native dynamic sky scene");
            return new DynamicSkyResult(
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
        require(meshResult.trianglesInBatch() > 0, "dynamic sky terrain anchor must submit visible triangles");
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

    private static DynamicRenderScene dynamicSkyScene(
            long revision,
            float[] sunDirection,
            int sunRgba8,
            float[] moonDirection,
            int moonRgba8
    ) {
        return new DynamicRenderScene(
                revision,
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new DynamicRenderScene.CelestialBody(
                                DynamicRenderScene.CelestialKind.SUN,
                                sunDirection[0],
                                sunDirection[1],
                                sunDirection[2],
                                0.085F,
                                sunRgba8,
                                0,
                                1.0F
                        ),
                        new DynamicRenderScene.CelestialBody(
                                DynamicRenderScene.CelestialKind.MOON,
                                moonDirection[0],
                                moonDirection[1],
                                moonDirection[2],
                                0.065F,
                                moonRgba8,
                                0,
                                0.85F
                        )
                ),
                List.of(new DynamicRenderScene.SceneLight(
                        1L,
                        DynamicRenderScene.LightKind.SKY,
                        0.0D,
                        0.0D,
                        0.0D,
                        sunDirection[0],
                        sunDirection[1],
                        sunDirection[2],
                        1.0F,
                        1.4F,
                        0x6FA8FF,
                        false
                )),
                List.of(),
                skyEnvironment(revision)
        );
    }

    private static DynamicRenderScene.EnvironmentState skyEnvironment(long revision) {
        return new DynamicRenderScene.EnvironmentState(
                false,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                false,
                0,
                0.0F,
                0,
                0,
                revision * 240L,
                0.5F,
                true
        );
    }

    private static SectionTriangleMesh terrainAnchor(SectionKey key) {
        return new SectionTriangleMesh(
                key,
                new short[]{
                        fixed(0.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(0.0F), fixed(16.0F),
                        fixed(16.0F), fixed(9.0F), fixed(16.0F),
                        fixed(0.0F), fixed(9.0F), fixed(16.0F),
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
        return normalize(
                frameState.cameraForwardX() + frameState.cameraRightX() * ndcX * rayScale.horizontalTan()
                        + frameState.cameraUpX() * ndcY * rayScale.verticalTan(),
                frameState.cameraForwardY() + frameState.cameraRightY() * ndcX * rayScale.horizontalTan()
                        + frameState.cameraUpY() * ndcY * rayScale.verticalTan(),
                frameState.cameraForwardZ() + frameState.cameraRightZ() * ndcX * rayScale.horizontalTan()
                        + frameState.cameraUpZ() * ndcY * rayScale.verticalTan()
        );
    }

    private static float[] normalize(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        require(length > 0.0F && Float.isFinite(length), "direction length must be finite and positive");
        return new float[]{x / length, y / length, z / length};
    }

    private static void assertSkyGradient(RtFrameSnapshot snapshot, String label) {
        int top = RtFrameSnapshot.pixel(snapshot.copyRgba8(), snapshot.width(), snapshot.width() / 2, snapshot.height() / 8);
        int bottom = RtFrameSnapshot.pixel(snapshot.copyRgba8(), snapshot.width(), snapshot.width() / 2, snapshot.height() * 7 / 8);
        require(
                top != bottom,
                label + " did not produce a directional sky gradient: top="
                        + RtFrameSnapshot.hex(top)
                        + ", bottom=" + RtFrameSnapshot.hex(bottom)
                        + ", snapshot=" + snapshot.asLogFragment()
        );
    }

    private static void assertWarmSunAt(RtFrameSnapshot snapshot, int x, int y, String label) {
        require(
                countMatching(snapshot, x, y, 3, RtNativeDynamicSkySceneSelfTest::isWarmSun) >= 3,
                label + " did not render warm sun pixels near sample"
                        + ", sample=(" + x + "," + y + ")"
                        + ", snapshot=" + snapshot.asLogFragment()
                        + ", colors=" + sampleWindow(snapshot, x, y, 2)
        );
    }

    private static void assertCoolMoonAt(RtFrameSnapshot snapshot, int x, int y, String label) {
        require(
                countMatching(snapshot, x, y, 3, RtNativeDynamicSkySceneSelfTest::isCoolMoon) >= 3,
                label + " did not render cool moon pixels near sample"
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

    private static boolean isWarmSun(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red >= 220 && green >= 170 && blue <= 210;
    }

    private static boolean isCoolMoon(int rgba8) {
        int red = rgba8 & 0xff;
        int green = (rgba8 >>> 8) & 0xff;
        int blue = (rgba8 >>> 16) & 0xff;
        return red <= 150 && green >= 120 && blue >= 190;
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

    private static Map<String, String> installDynamicSkyProperties() {
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

    private record DynamicSkyResult(
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
