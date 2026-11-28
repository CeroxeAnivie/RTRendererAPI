package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Numerical GPU oracles for official beauty and the native dielectric bridge. */
public final class RtxptDielectricTransportNativeSelfTest {
    private static final int SIZE = 320, SAMPLES_PER_PIXEL = 8, CAPTURES = 3;
    // Diagnostic signal 22 is ProcessedOutputColor: scene-linear, before tone mapping and UI.
    private static final int LINEAR_BEAUTY = 22;
    private static final int BRIDGE_RADIANCE = 23, BRIDGE_DISTANCE = 24;
    private static final double FOV_DEGREES = 46;
    private static final float CAMERA_X = -8, CAMERA_Z = 4, TARGET_Z = -4, SLAB_FRONT_Z = 1;
    private static final float THIN = .9f, THICK = 3, LOW_IOR = 1.15f, HIGH_IOR = 1.7f, ABSORPTION_IOR = 1.33f;
    private static final Rgb STAIN = new Rgb(.08f, .45f, .75f);
    private static final float EMITTER_RADIANCE = .75f;
    private static final long BLUE_TARGET = 1, RED_TARGET = 2, WHITE_TARGET = 3, SLAB = 4;
    private static final CameraState CAMERA = CameraState.lookAt(CAMERA_X, 0, CAMERA_Z, 0, 0, TARGET_Z)
            .verticalFieldOfViewDegrees((float) FOV_DEGREES).aspectRatio(1).build();

    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            switch (args[0]) {
                case "bridge" -> verifyBridge(Files.createDirectories(Path.of(args[1]).toAbsolutePath()));
                case "bridge-fresnel" -> verifyFresnelBridge(Files.createDirectories(Path.of(args[1]).toAbsolutePath()));
                case "bridge-coincident" -> verifyCoincidentBridge(
                        Files.createDirectories(Path.of(args[1]).toAbsolutePath()), false);
                case "bridge-coincident-inside" -> verifyCoincidentBridge(
                        Files.createDirectories(Path.of(args[1]).toAbsolutePath()), true);
                case "bridge-coincident-receiver" -> verifyCoincidentReceiverBridge(
                        Files.createDirectories(Path.of(args[1]).toAbsolutePath()));
                case "bridge-immersed" -> verifyImmersedBridge(Files.createDirectories(Path.of(args[1]).toAbsolutePath()));
                case "bridge-environment" -> verifyEnvironmentBridge(Files.createDirectories(Path.of(args[1]).toAbsolutePath()));
                default -> throw new IllegalArgumentException("Unknown dielectric test mode: " + args[0]);
            }
            return;
        }
        if (args.length > 2) throw new IllegalArgumentException("Expected an optional test mode and report directory");
        Path output = Path.of(args.length == 0 ? "build/reports/dielectric-transport" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        // Same official-only initialization as openOfficialHiddenWindow. Use the
        // package-private delegate solely to read the floating GPU diagnostic.
        try (var renderer = new RtxptRenderer(configuration(), true, true)) {
            var fixture = new Fixture(renderer, output);
            double vacuumEdge = edge(fixture.capture("vacuum-edge", background()));
            double lowEdge = edge(fixture.capture("low-ior-edge", slab(2, THICK, LOW_IOR, Rgb.ZERO)));
            double highEdge = edge(fixture.capture("high-ior-edge", slab(3, THICK, HIGH_IOR, Rgb.ZERO)));
            System.out.printf(Locale.ROOT, "Dielectric edge x: vacuum=%.3f lowIOR=%.3f highIOR=%.3f%n",
                    vacuumEdge, lowEdge, highEdge);
            near("vacuum marker projection", vacuumEdge, expectedEdge(0, 1), 2);
            near("low-IOR displacement", lowEdge - vacuumEdge, expectedEdge(THICK, LOW_IOR) - SIZE / 2.0, 3);
            near("high-IOR displacement", highEdge - vacuumEdge, expectedEdge(THICK, HIGH_IOR) - SIZE / 2.0, 3);

            var white = slab(4, THIN, ABSORPTION_IOR, Rgb.ZERO).removeInstance(BLUE_TARGET)
                    .removeInstance(RED_TARGET).upsert(SceneInstance.builder(WHITE_TARGET, WHITE_TARGET).build());
            double[] clearThin = center(fixture.capture("clear-thin", white));
            double[] stainedThin = center(fixture.capture("stained-thin", slab(5, THIN, ABSORPTION_IOR, STAIN)));
            double[] clearThick = center(fixture.capture("clear-thick", slab(6, THICK, ABSORPTION_IOR, Rgb.ZERO)));
            double[] stainedThick = center(fixture.capture("stained-thick", slab(7, THICK, ABSORPTION_IOR, STAIN)));
            verifyAbsorption(clearThin, stainedThin, clearThick, stainedThick);

            var state = renderer.executionState();
            require(state.officialOnly() && state.officialPipelineFrames() >= fixture.frames
                    && state.analyticBeautyFrames() == 0 && state.independentLightingFrames() == 0,
                    "The dielectric fixture did not run exclusively through official RTXPT beauty: " + state);
            System.out.printf("DIELECTRIC_TRANSPORT_NATIVE PASS cases=7 frames=%d output=%s%n", fixture.frames, output);
        }
    }

    private static void verifyBridge(Path output) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            var fixture = new BridgeFixture(renderer, lighting, output);
            fixture.capture("no-medium", background(), false, true);
            fixture.expectDistance("no-medium", 0);
            var air = slab(2, THICK, 1, Rgb.ZERO);
            double vacuumEdge = edge(fixture.capture("index-one", air, true, true));
            double lowEdge = edge(fixture.capture("low-ior", slab(3, THICK, LOW_IOR, Rgb.ZERO), true, true));
            double highEdge = edge(fixture.capture("high-ior", slab(4, THICK, HIGH_IOR, Rgb.ZERO), true, true));
            System.out.printf(Locale.ROOT, "Bridge dielectric edge x: indexOne=%.3f lowIOR=%.3f highIOR=%.3f%n",
                    vacuumEdge, lowEdge, highEdge);
            near("index-one edge", vacuumEdge, expectedEdge(0, 1), 1);
            near("low-IOR displacement", lowEdge - vacuumEdge, expectedEdge(THICK, LOW_IOR) - SIZE / 2.0, 2);
            near("high-IOR displacement", highEdge - vacuumEdge, expectedEdge(THICK, HIGH_IOR) - SIZE / 2.0, 2);
            fixture.expectDistance("interface-distance", (CAMERA_Z - SLAB_FRONT_Z) * Math.sqrt(2));

            var white = slab(5, THIN, ABSORPTION_IOR, Rgb.ZERO).removeInstance(BLUE_TARGET).removeInstance(RED_TARGET);
            double[] clearThin = center(fixture.capture("clear-thin", white, true, false));
            double[] stainedThin = center(fixture.capture("stained-thin", slab(6, THIN, ABSORPTION_IOR, STAIN), true, false));
            double[] clearThick = center(fixture.capture("clear-thick", slab(7, THICK, ABSORPTION_IOR, Rgb.ZERO), true, false));
            double[] stainedThick = center(fixture.capture("stained-thick", slab(8, THICK, ABSORPTION_IOR, STAIN), true, false));
            verifyAbsorption(clearThin, stainedThin, clearThick, stainedThick);

            long blocker = 5;
            var blocked = SceneTransaction.builder(9).upsert(emitter(blocker, Rgb.ZERO))
                    .upsert(target(blocker, -64, 64, 2, -64, 64))
                    .upsert(SceneInstance.builder(blocker, blocker).build());
            fixture.capture("blocked", blocked, true, false);
            fixture.expectDistance("blocked", 0);
            fixture.capture("boundary-role-disabled", SceneTransaction.builder(10).removeInstance(blocker), false, false);
            fixture.expectDistance("boundary-role-disabled", 0);
            var control = new NativeLightingPlan(SIZE, SIZE, Set.of(NativeLightingPlan.Signal.DIRECT_GAIN), 0, 32, 0, plan.style());
            lighting.nativeLightingPlan(control);
            fixture.submit(11, 10);
            fixture.checkLease(11, control.signals());
            if ((lighting.nativeLightingExecution().actualSignalMask() & plan.signalMask()) != 0)
                throw new AssertionError("Disabled dielectric plane still dispatched");
            System.out.printf("DIELECTRIC_BRIDGE_NATIVE PASS cases=11 output=%s%n", output);
        }
    }

    private static void verifyFresnelBridge(Path output) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        float ior = 1.52f;
        double reflectance = Math.pow((ior - 1) / (ior + 1), 2);
        double twiceTransmitted = Math.pow(1 - reflectance, 2);
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            var fixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(CAMERA_Z, false));
            var scene = SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(SLAB).baseColorRgba8(0xffffffff).roughness(0).transmission(1).build())
                    .upsert(slabMesh())
                    .upsert(MaterialAsset.builder(RED_TARGET).baseColorRgba8(0xff000000).doubleSided(true)
                            .emissive(new Rgb(EMITTER_RADIANCE, 0, 0)).build())
                    .upsert(emitter(BLUE_TARGET, new Rgb(0, 0, EMITTER_RADIANCE)))
                    .upsert(target(RED_TARGET, -64, 64, CAMERA_Z + 1, -64, 64))
                    .upsert(target(BLUE_TARGET, -64, 64, -1, -64, 64))
                    .upsert(SceneInstance.builder(RED_TARGET, RED_TARGET).build())
                    .upsert(SceneInstance.builder(BLUE_TARGET, BLUE_TARGET).build())
                    .upsert(slab(SLAB, 1, 1, ior, Rgb.ZERO, 2));
            var roles = new ArrayList<NativeLightingPublication.Instance>();
            roles.add(NativeLightingPublication.Instance.receiver(RED_TARGET));
            roles.add(NativeLightingPublication.Instance.receiver(BLUE_TARGET));
            roles.add(BridgeFixture.volumeRole(SLAB, BridgeFixture.BOUNDARY_ROLE));
            var pixels = fixture.capture("fresnel-both-lobes", scene.build(), roles, List.of());
            fixture.expectDistance("fresnel-both-lobes", CAMERA_Z - 1);
            // Check each ray, not the image mean: a one-lobe Monte Carlo estimate can
            // have the right mean while substituting red/blue pinholes for clear glass.
            for (int y = SIZE / 2 - 6; y < SIZE / 2 + 6; y++) {
                for (int x = SIZE / 2 - 6; x < SIZE / 2 + 6; x++) {
                    int i = (y * SIZE + x) * 3;
                    String pixel = " at " + x + "," + y;
                    near("front and internal red reflection" + pixel, pixels[i],
                            EMITTER_RADIANCE * reflectance * (1 + twiceTransmitted), .002);
                    near("blue transmission" + pixel, pixels[i + 2], EMITTER_RADIANCE * twiceTransmitted, .002);
                }
            }
            // Let the diagnostic read back without forcing the optical frame to wait for the CPU.
            fixture.submit(2, 1, List.of());
            fixture.checkLease(2, Set.of(signal));
        }
        System.out.printf("DIELECTRIC_BRIDGE_FRESNEL_NATIVE PASS rays=144 output=%s%n", output);
    }

    private static void verifyCoincidentBridge(Path output, boolean inside) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            int cases = 0;
            if (!inside) {
                var fixture = new BridgeFixture(renderer, lighting, output);
                float nearThickness = 1.25f, farThickness = 1.75f;
                var adjacent = background(++cases, true)
                        .upsert(slab(SLAB, SLAB_FRONT_Z, nearThickness, LOW_IOR, Rgb.ZERO, 6))
                        .upsert(slab(SLAB + 1, SLAB_FRONT_Z - nearThickness, farThickness, HIGH_IOR, Rgb.ZERO, 4));
                double actual = edge(fixture.captureVolumes("adjacent-slabs", adjacent, true, SLAB, SLAB + 1));
                double expected = expectedEdge(nearThickness, LOW_IOR, farThickness, HIGH_IOR);
                System.out.printf(Locale.ROOT, "Adjacent dielectric edge: measured %.3f px, expected %.3f px%n", actual, expected);
                near("adjacent slabs Snell displacement", actual, expected, 2);
                fixture.expectDistance("adjacent-slabs", (CAMERA_Z - SLAB_FRONT_Z) * Math.sqrt(2));
            }

            float cameraZ = inside ? 0 : CAMERA_Z;
            var fixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(cameraZ, false));
            Rgb lowerNear = new Rgb(.18f, .65f, .22f), upperNear = new Rgb(.31f, .11f, .45f);
            Rgb upperFar = new Rgb(.14f, .50f, .23f), lowerFar = new Rgb(.62f, .16f, .37f);
            for (boolean reverse : new boolean[]{false, true}) {
                // Reverse volume IDs while holding physical layers and priorities fixed.
                // At z=-.5 there are two exits and two entries; at z=.5 only the near
                // high-priority layer starts, and at z=-1.5 only the far one ends.
                long a = SLAB + (reverse ? 3 : 0), b = SLAB + (reverse ? 2 : 1);
                long c = SLAB + (reverse ? 1 : 2), d = SLAB + (reverse ? 0 : 3);
                var scene = background(++cases, false)
                        .upsert(slab(a, 1, 1.5f, 1, lowerNear, 5))
                        .upsert(slab(b, .5f, 1, 1, upperNear, 10))
                        .upsert(slab(c, -.5f, 1, 1, upperFar, 8))
                        .upsert(slab(d, -.5f, 2, 1, lowerFar, 2));
                String name = reverse ? "four-way-reversed-ids" : "four-way";
                double[] rgb = center(fixture.captureVolumes(name, scene, false, a, b, c, d));
                // Unit IOR isolates stack membership: no Fresnel randomness. The
                // lower far layer resumes after the upper layer exits. A stale
                // near layer would outrank it and tint that segment incorrectly.
                Rgb opticalDepth = lowerNear.multiply(inside ? 0 : .5f).add(upperNear.multiply(inside ? .5f : 1))
                        .add(upperFar).add(lowerFar);
                double[] depth = {opticalDepth.red(), opticalDepth.green(), opticalDepth.blue()};
                double[] expected = Arrays.stream(depth).map(tau -> EMITTER_RADIANCE * Math.exp(-tau)).toArray();
                System.out.printf(Locale.ROOT, "%s: linear RGB %s, expected %s%n", name,
                        Arrays.toString(rgb), Arrays.toString(expected));
                for (int channel = 0; channel < rgb.length; channel++)
                    near(name + " Beer-Lambert channel " + channel, rgb[channel], expected[channel], .003);
                fixture.expectDistance(name, cameraZ - (inside ? -.5 : 1));
            }
            System.out.printf("DIELECTRIC_BRIDGE_COINCIDENT_NATIVE PASS inside=%s cases=%d output=%s%n", inside, cases, output);
        }
    }

    private static void verifyCoincidentReceiverBridge(Path output) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        var sky = EnvironmentState.builder().skyRadiance(0, 0, 1).build();
        var receiverColor = new Rgb(EMITTER_RADIANCE, .25f, 0);
        float front = 1, thickness = 2, back = front - thickness;
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            var fixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(CAMERA_Z, false));
            long revision = 0;
            for (boolean receiverFirst : new boolean[]{false, true}) {
                long volumeId = receiverFirst ? SLAB + 1 : SLAB;
                long receiverId = receiverFirst ? SLAB : SLAB + 1;
                for (boolean alternateCap : new boolean[]{false, true}) {
                    for (boolean alternateReceiver : new boolean[]{false, true}) {
                        var volume = slab(volumeId, front, thickness, 1, Rgb.ZERO, 2);
                        var receiver = SceneInstance.builder(receiverId, WHITE_TARGET).build();
                        var volumeRole = BridgeFixture.volumeRole(volumeId, BridgeFixture.BOUNDARY_ROLE);
                        var receiverRole = NativeLightingPublication.Instance.receiver(receiverId);
                        var scene = SceneTransaction.builder(++revision).resetScene()
                                .upsert(MaterialAsset.builder(SLAB).baseColorRgba8(0xffffffff).roughness(0).transmission(1).build())
                                .upsert(slabMesh(alternateCap))
                                .upsert(emitter(WHITE_TARGET, receiverColor))
                                .upsert(target(WHITE_TARGET, -32, 32, back, -24, 24, alternateReceiver));
                        var roles = receiverFirst ? List.of(receiverRole, volumeRole) : List.of(volumeRole, receiverRole);
                        if (receiverFirst) scene.upsert(receiver).upsert(volume);
                        else scene.upsert(volume).upsert(receiver);
                        String name = "back-cap-receiver-" + (receiverFirst ? "receiver-first" : "volume-first")
                                + "-cap" + (alternateCap ? "1" : "0") + "-receiver" + (alternateReceiver ? "1" : "0");
                        // The camera starts explicitly in air. Unit IOR isolates the tie: neither
                        // Fresnel reflection nor absorption can legitimately put blue sky in the signal.
                        var rgb = fixture.capture(name, scene.build(), roles, List.of(), sky);
                        fixture.expectDistance(name, CAMERA_Z - front);
                        // The slab and receiver overfill the frustum, so the entire captured grid
                        // samples their interiors, with rays on both sides of each diagonal.
                        for (int i = 0; i < rgb.length; i += 3) {
                            if (Math.abs(rgb[i] - receiverColor.red()) > .002
                                    || Math.abs(rgb[i + 1] - receiverColor.green()) > .002 || rgb[i + 2] > .002) {
                                int pixel = i / 3;
                                throw new AssertionError("%s pixel (%d,%d): expected receiver RGB %s, got (%.5f, %.5f, %.5f)"
                                        .formatted(name, pixel % SIZE, pixel / SIZE, receiverColor, rgb[i], rgb[i + 1], rgb[i + 2]));
                            }
                        }
                    }
                }
            }
            System.out.printf("DIELECTRIC_BRIDGE_COINCIDENT_RECEIVER_NATIVE PASS cases=%d rays=%d output=%s%n",
                    revision, revision * SIZE * SIZE, output);
        }
    }

    private static void verifyImmersedBridge(Path output) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        long lower = SLAB + 1, overlay = SLAB + 2;
        var overlayAbsorption = new Rgb(.3f, .22f, .02f);
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            var fixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(.5f, false));
            for (int test = 0; test < 4; ++test) {
                String name = switch (test) {
                    case 0 -> "opaque-before-interface";
                    case 1 -> "opaque-after-identical-cap";
                    case 2 -> "opaque-after-overlap-exit";
                    default -> "explicit-exterior";
                };
                var builder = background(test + 1, false, -.5f)
                        .upsert(slab(SLAB, test == 3 ? -1 : 1, test == 1 ? 1 : 2, ABSORPTION_IOR, STAIN, 2));
                var attributes = BridgeFixture.targetRoles(false);
                attributes.add(BridgeFixture.volumeRole(SLAB, BridgeFixture.BOUNDARY_ROLE));
                if (test == 1) {
                    builder.upsert(slab(lower, 0, 1, ABSORPTION_IOR, STAIN, 2));
                    attributes.add(BridgeFixture.volumeRole(lower, BridgeFixture.BOUNDARY_ROLE));
                } else if (test == 2) {
                    builder.upsert(slab(overlay, 1, 1, ABSORPTION_IOR, overlayAbsorption, 4));
                    attributes.add(BridgeFixture.volumeRole(overlay, BridgeFixture.BOUNDARY_ROLE));
                }
                var rgb = center(fixture.capture(name, builder.build(), attributes,
                        test == 2 ? List.of(overlay, SLAB) : test == 3 ? List.of() : List.of(SLAB)));
                fixture.expectDistance(name, test == 3 ? 0 : test == 2 ? .5 : -1);
                if (test != 2) {
                    var absorption = new double[]{STAIN.red(), STAIN.green(), STAIN.blue()};
                    for (int channel = 0; channel < rgb.length; ++channel)
                        near(name + " transmittance channel " + channel, rgb[channel],
                                test == 3 ? 0 : Math.exp(-absorption[channel]), .003);
                } else {
                    var depth = new double[]{STAIN.red()+overlayAbsorption.red(), STAIN.green()+overlayAbsorption.green(),
                            STAIN.blue()+overlayAbsorption.blue()};
                    for (int channel = 0; channel < rgb.length; ++channel)
                        near(name + " radiance channel " + channel, rgb[channel],
                                EMITTER_RADIANCE * Math.exp(-.5 * depth[channel]), .003);
                }
                System.out.printf(Locale.ROOT, "%s: RGB %s%n", name, Arrays.toString(rgb));
            }
            // At 45 degrees, 1.52-to-air transmission is impossible. Only the
            // reflected path reaches a target behind the camera's initial ray.
            long mirrorTarget = 20;
            var tirFixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(.5f, true));
            var tir = background(5, false)
                    .upsert(MaterialAsset.builder(mirrorTarget).baseColorRgba8(0xff000000).doubleSided(true)
                            .emissive(new Rgb(EMITTER_RADIANCE, EMITTER_RADIANCE, EMITTER_RADIANCE)).build())
                    .upsert(target(mirrorTarget, -64, 64, .8f, -64, 64))
                    .upsert(SceneInstance.builder(mirrorTarget, mirrorTarget).build())
                    .upsert(slab(SLAB, 1, 1, 1.52f, STAIN, 2));
            var tirAttributes = BridgeFixture.targetRoles(false);
            tirAttributes.add(NativeLightingPublication.Instance.receiver(mirrorTarget));
            tirAttributes.add(BridgeFixture.volumeRole(SLAB, BridgeFixture.BOUNDARY_ROLE));
            var tirRgb = center(tirFixture.capture("total-internal-reflection", tir.build(), tirAttributes, List.of(SLAB)));
            tirFixture.expectDistance("total-internal-reflection", .5 * Math.sqrt(2));
            var absorption = new double[]{STAIN.red(), STAIN.green(), STAIN.blue()};
            for (int channel = 0; channel < tirRgb.length; ++channel)
                near("total-internal-reflection channel " + channel, tirRgb[channel],
                        EMITTER_RADIANCE * Math.exp(-1.3 * Math.sqrt(2) * absorption[channel]), .003);
            var sky = SceneTransaction.builder(6).resetScene()
                    .upsert(MaterialAsset.builder(SLAB).baseColorRgba8(0xffffffff).roughness(0).transmission(1).build())
                    .upsert(slabMesh())
                    .upsert(slab(SLAB, 1, 1, 1, new Rgb(.04f, .04f, .04f), 2));
            var skyScene = sky.build();
            require(renderer.apply(skyScene).acceptedSceneRevision() == 6, "Sky scene not accepted");
            lighting.publishLightingAttributes(new NativeLightingPublication(6,
                    List.of(BridgeFixture.volumeRole(SLAB, BridgeFixture.BOUNDARY_ROLE)), List.of(), null, null));
            fixture.submit(6, 6, List.of(SLAB), EnvironmentState.builder().skyRadiance(.12f, .37f, .81f).build());
            fixture.checkLease(6, Set.of(signal));
            fixture.expectDistance("colored-sky", .5);
            var skyRgb = center(fixture.read("colored-sky", BRIDGE_RADIANCE));
            near("sky red", skyRgb[0], .12 * Math.exp(-.02), .0001);
            near("sky green", skyRgb[1], .37 * Math.exp(-.02), .0001);
            near("sky blue", skyRgb[2], .81 * Math.exp(-.02), .0001);
            System.out.printf("DIELECTRIC_BRIDGE_IMMERSED_NATIVE PASS cases=6 output=%s%n", output);
        }
    }

    private static EnvironmentMap directionalMap(int refractedChannel) {
        int width = 256, height = 32;
        float[] rgb = new float[width * height * 3];
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                double u = (x + .5) / width;
                // At 45 degrees the camera direction is at u=.625. Snell's
                // law with 1.33-to-air puts the transmitted direction at u=.695.
                if (u >= .665 && u <= .74) {
                    double v = (y + .5) / height;
                    rgb[(y * width + x) * 3 + refractedChannel] = v < .45 ? 2f : v > .55 ? .75f : 1.5f;
                }
                else if (u >= .59 && u <= .655) rgb[(y * width + x) * 3] = 1.25f;
            }
        }
        return new EnvironmentMap(width, height, rgb);
    }

    private static void verifyEnvironmentBridge(Path output) throws Exception {
        var signal = NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan = new NativeLightingPlan(SIZE, SIZE, Set.of(signal), 0, 32, 0, NativeLightingStyle.defaults());
        var constant = EnvironmentState.builder().skyRadiance(.12f, .37f, .81f).ambientIntensity(3).build();
        var mapped = constant.toBuilder().environmentMap(directionalMap(1)).build();
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            var fixture = new BridgeFixture(renderer, lighting, output, bridgeCamera(.5f, true));
            var scene = SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(SLAB).baseColorRgba8(0xffffffff).roughness(0).transmission(1).build())
                    .upsert(slabMesh()).upsert(slab(SLAB, 1, 1, ABSORPTION_IOR, Rgb.ZERO, 2)).build();
            require(renderer.apply(scene).acceptedSceneRevision() == 1, "Environment scene not accepted");
            lighting.publishLightingAttributes(new NativeLightingPublication(1,
                    List.of(BridgeFixture.volumeRole(SLAB, 0)), List.of(), null, null));
            fixture.submit(1, 1, List.of(), mapped);
            fixture.expectDistance("mapped-dry", 0);
            lighting.publishLightingAttributes(new NativeLightingPublication(2,
                    List.of(BridgeFixture.volumeRole(SLAB, BridgeFixture.BOUNDARY_ROLE)), List.of(), null, null));
            fixture.submit(2, 1, List.of(SLAB), mapped);
            fixture.checkLease(2, Set.of(signal));
            fixture.expectDistance("mapped-green", .5 * Math.sqrt(2));
            var green = fixture.read("mapped-green", BRIDGE_RADIANCE);
            // Central pixel is a first-interface transmission, and the HDR map
            // is sampled without fallback color or ambient scaling.
            int center = ((SIZE / 2) * SIZE + SIZE / 2) * 3;
            int north = center - SIZE / 4 * SIZE * 3, south = center + SIZE / 4 * SIZE * 3;
            double etaSquared = (double) ABSORPTION_IOR * ABSORPTION_IOR;
            double transmitted = 1.5 * etaSquared;
            near("refracted green", green[center + 1], transmitted, .001);
            near("+Y samples upper map rows", green[north + 1], 2 * etaSquared, .001);
            near("-Y samples lower map rows", green[south + 1], .75 * etaSquared, .001);
            near("no incident red", green[center], 0, .0001);
            near("no constant blue", green[center + 2], 0, .0001);
            var relit = mapped.toBuilder().environmentMap(directionalMap(1)).sunIntensity(.25f).build();
            fixture.submit(3, 1, List.of(SLAB), relit);
            require(Arrays.equals(green, fixture.read("mapped-retained", BRIDGE_RADIANCE)),
                    "Equal resident map changed with independent sun input");
            fixture.submit(4, 1, List.of(SLAB), mapped.toBuilder().environmentMap(directionalMap(2)).build());
            var blue = fixture.read("mapped-replaced", BRIDGE_RADIANCE);
            near("replaced blue", blue[center + 2], transmitted, .001);
            near("no stale green", blue[center + 1], 0, .0001);
            fixture.submit(5, 1, List.of(SLAB), constant);
            var fallback = fixture.read("mapped-cleared", BRIDGE_RADIANCE);
            near("restored constant red", fallback[center], constant.skyRed() * etaSquared, .0001);
            near("restored constant green", fallback[center + 1], constant.skyGreen() * etaSquared, .0001);
            near("restored constant blue", fallback[center + 2], constant.skyBlue() * etaSquared, .0001);
        }
        System.out.printf("DIELECTRIC_BRIDGE_ENVIRONMENT_NATIVE PASS frames=5 output=%s%n", output);
    }

    private static final class BridgeFixture {
        private static final int BOUNDARY_ROLE = NativeLightingPublication.Instance.DIELECTRIC_BOUNDARY;
        private final RtxptRenderer renderer;
        private final NativeLightingControl lighting;
        private final Path output;
        private final CameraState camera;

        BridgeFixture(RtxptRenderer renderer, NativeLightingControl lighting, Path output) {
            this(renderer, lighting, output, bridgeCamera(CAMERA_Z, true));
        }

        BridgeFixture(RtxptRenderer renderer, NativeLightingControl lighting, Path output, CameraState camera) {
            this.renderer = renderer;
            this.lighting = lighting;
            this.output = output;
            this.camera = camera;
        }

        double[] capture(String name, SceneTransaction.Builder builder, boolean medium, boolean colorTargets) throws Exception {
            var transaction = builder.build();
            var attributes = targetRoles(colorTargets);
            if (transaction.revision() > 1) attributes.add(volumeRole(SLAB, medium ? BOUNDARY_ROLE : 0));
            return capture(name, transaction, attributes);
        }

        double[] captureVolumes(String name, SceneTransaction.Builder builder, boolean colorTargets, long... volumes) throws Exception {
            var attributes = targetRoles(colorTargets);
            for (long volume : volumes) attributes.add(volumeRole(volume, BOUNDARY_ROLE));
            return capture(name, builder.build(), attributes);
        }

        private double[] capture(String name, SceneTransaction transaction, List<NativeLightingPublication.Instance> attributes) throws Exception {
            return capture(name, transaction, attributes, null);
        }

        private double[] capture(String name, SceneTransaction transaction, List<NativeLightingPublication.Instance> attributes,
                List<Long> cameraMedia) throws Exception {
            return capture(name, transaction, attributes, cameraMedia, EnvironmentState.neutral());
        }

        private double[] capture(String name, SceneTransaction transaction, List<NativeLightingPublication.Instance> attributes,
                List<Long> cameraMedia, EnvironmentState environment) throws Exception {
            long revision = transaction.revision();
            require(renderer.apply(transaction).acceptedSceneRevision() == revision, "Wrong accepted revision: " + name);
            lighting.publishLightingAttributes(new NativeLightingPublication(revision, List.copyOf(attributes), List.of(), null, null));
            submit(revision, revision, cameraMedia, environment);
            checkLease(revision, Set.of(NativeLightingPlan.Signal.DIELECTRIC_RADIANCE));
            return read(name, BRIDGE_RADIANCE);
        }

        private static NativeLightingPublication.Instance volumeRole(long volume, int role) {
            return new NativeLightingPublication.Instance(volume, role, 1, 0, 0, 0, 0, 0);
        }

        private static List<NativeLightingPublication.Instance> targetRoles(boolean colorTargets) {
            var attributes = new ArrayList<NativeLightingPublication.Instance>();
            if (colorTargets) {
                attributes.add(NativeLightingPublication.Instance.receiver(BLUE_TARGET));
                attributes.add(NativeLightingPublication.Instance.receiver(RED_TARGET));
            }
            attributes.add(NativeLightingPublication.Instance.receiver(WHITE_TARGET));
            return attributes;
        }

        void submit(long sequence, long revision) {
            submit(sequence, revision, null);
        }

        void submit(long sequence, long revision, List<Long> cameraMedia) {
            submit(sequence, revision, cameraMedia, EnvironmentState.neutral());
        }

        void submit(long sequence, long revision, List<Long> cameraMedia, EnvironmentState environment) {
            var request = RenderFrameRequest.builder(sequence, SIZE, SIZE, camera)
                    .environment(environment).minimumSceneRevision(revision);
            if (cameraMedia != null) request.cameraMediumInstanceIds(cameraMedia);
            renderer.submit(request.build());
            renderer.extension(RtxptAccess.class).orElseThrow().awaitIdle();
        }

        void checkLease(long sequence, Set<NativeLightingPlan.Signal> signals) {
            try (var lease = lighting.acquireLightingFrame().orElseThrow()) {
                var frame = lease.frame();
                require(frame.sequence() == sequence && frame.width() == SIZE && frame.height() == SIZE
                        && frame.fenceHandle() != 0 && frame.readyValue() < frame.completionValue()
                        && frame.planes().size() == signals.size(), "Invalid bridge frame packet: " + frame);
                for (var plane : frame.planes()) {
                    require(signals.contains(plane.signal()) && plane.memoryHandle() != 0 && plane.allocationBytes() > 0,
                            "Invalid bridge plane: " + plane);
                    if (plane.signal() == NativeLightingPlan.Signal.DIELECTRIC_RADIANCE)
                        require(plane.format() == LightingFrameLease.Format.RGBA32_FLOAT, "Distance lost precision: " + plane);
                }
            }
        }

        void expectDistance(String name, double expected) throws Exception {
            double[] mean = center(read(name, BRIDGE_DISTANCE));
            near(name, mean[0], expected, expected == 0 ? 0 : .03);
        }

        private double[] read(String name, int signal) throws Exception {
            Path path = output.resolve(name + (signal == BRIDGE_DISTANCE ? "-distance.pfm" : ".pfm"));
            Files.deleteIfExists(path);
            renderer.captureNativeLighting(path, signal);
            double[] rgb = new double[SIZE * SIZE * 3];
            accumulatePfm(path, rgb, 1, signal == BRIDGE_DISTANCE);
            return rgb;
        }
    }

    private static CameraState bridgeCamera(float z, boolean oblique) {
        double c = oblique ? Math.sqrt(.5) : 1, s = oblique ? Math.sqrt(.5) : 0;
        double f = 1 / Math.tan(Math.toRadians(FOV_DEGREES / 2));
        return CameraState.exactProjection(ExactProjectionState.builder(SIZE, SIZE)
                .matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
                .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD)
                .depthConvention(ExactProjectionState.DepthConvention.ZERO_TO_ONE)
                .cameraToWorld(new double[]{c,0,-s,oblique ? CAMERA_X : 0, 0,1,0,0, s,0,c,z, 0,0,0,1})
                .clipFromView(new double[]{f,0,0,0, 0,f,0,0, 0,0,-1.0001,-.10001, 0,0,-1,0}).build());
    }

    private static RendererConfig configuration() {
        var disabled = RendererFeaturePreference.DISABLED;
        var quality = PathTracingQualityOptions.defaults().withPostProcessing(
                new PathTracingQualityOptions.PostProcessing(false, 0, 0, false));
        // The target is hit at vertex three (entry, exit, emitter). Longer
        // internal reflection paths are excluded from this direct-path oracle.
        var path = new PathTracingOptions(SAMPLES_PER_PIXEL, 3, 0, disabled, disabled, disabled, quality);
        return RendererConfig.builder().frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled()).denoising(DenoisingOptions.disabled())
                .lowLatency(LowLatencyOptions.disabled()).rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .pathTracing(path).build();
    }

    private static SceneTransaction.Builder background() {
        return background(1, true);
    }

    private static SceneTransaction.Builder background(long revision, boolean colorTargets) {
        return background(revision, colorTargets, TARGET_Z - 1);
    }

    private static SceneTransaction.Builder background(long revision, boolean colorTargets, float whiteZ) {
        var scene = SceneTransaction.builder(revision).resetScene()
                .upsert(emitter(WHITE_TARGET, new Rgb(EMITTER_RADIANCE, EMITTER_RADIANCE, EMITTER_RADIANCE)))
                .upsert(target(WHITE_TARGET, -64, 64, whiteZ, -64, 64))
                .upsert(MaterialAsset.builder(SLAB).baseColorRgba8(0xffffffff).roughness(0).transmission(1)
                        .pathDecomposition(MaterialAsset.PathDecomposition.TRANSMISSION).build())
                .upsert(slabMesh())
                .upsert(SceneInstance.builder(WHITE_TARGET, WHITE_TARGET).build());
        if (colorTargets) {
            scene.upsert(emitter(BLUE_TARGET, new Rgb(0, 0, EMITTER_RADIANCE)))
                    .upsert(emitter(RED_TARGET, new Rgb(EMITTER_RADIANCE, 0, 0)))
                    .upsert(target(BLUE_TARGET, -64, 0, TARGET_Z, -32, 32))
                    .upsert(target(RED_TARGET, 0, 64, TARGET_Z, -32, 32))
                    .upsert(SceneInstance.builder(BLUE_TARGET, BLUE_TARGET).build())
                    .upsert(SceneInstance.builder(RED_TARGET, RED_TARGET).build());
        }
        return scene;
    }

    private static MaterialAsset emitter(long id, Rgb color) {
        return MaterialAsset.builder(id).baseColorRgba8(0xff000000).emissive(color).build();
    }

    private static MeshAsset target(long id, float left, float right, float z, float bottom, float top) {
        return target(id, left, right, z, bottom, top, false);
    }

    private static MeshAsset target(long id, float left, float right, float z, float bottom, float top, boolean alternateDiagonal) {
        return MeshAsset.builder(id,
                new float[]{left, bottom, z, right, bottom, z, right, top, z, left, top, z},
                alternateDiagonal ? new int[]{0, 1, 3, 1, 2, 3} : new int[]{0, 1, 2, 0, 2, 3},
                new long[]{id, id}).build();
    }

    private static MeshAsset slabMesh() {
        return slabMesh(false);
    }

    private static MeshAsset slabMesh(boolean alternateBackDiagonal) {
        // Shared corners, two incident triangles per edge, outward CCW. The
        // managed bridge generates geometric flat normals for missing normals.
        float[] vertices = {-32,-24,-1, 32,-24,-1, 32,24,-1, -32,24,-1,
                            -32,-24, 0, 32,-24, 0, 32,24, 0, -32,24, 0};
        int[] triangles = {4,5,6, 4,6,7, 0,2,1, 0,3,2, 0,4,7, 0,7,3,
                           1,2,6, 1,6,5, 3,7,6, 3,6,2, 0,1,5, 0,5,4};
        if (alternateBackDiagonal) System.arraycopy(new int[]{0,3,1, 1,3,2}, 0, triangles, 6, 6);
        long[] materials = new long[triangles.length / 3];
        Arrays.fill(materials, SLAB);
        var mesh = MeshAsset.builder(SLAB, vertices, triangles, materials).build();
        require(mesh.isClosedManifold(), "The dielectric fixture must be a closed outward-wound mesh");
        return mesh;
    }

    private static SceneTransaction.Builder slab(long revision, float thickness, float ior, Rgb absorption) {
        return SceneTransaction.builder(revision).upsert(slab(SLAB, SLAB_FRONT_Z, thickness, ior, absorption, 2));
    }

    private static SceneInstance slab(long instance, float front, float thickness, float ior, Rgb absorption, int priority) {
        return SceneInstance.builder(instance, SLAB)
                .transform(new AffineTransform(new float[]{1,0,0,0, 0,1,0,0, 0,0,thickness,front}))
                .mediumBoundary(MediumBoundary.surface(new Medium(absorption, ior)).withPriority(priority)).build();
    }

    private static double expectedEdge(double thickness, double ior) {
        return expectedEdge(thickness, ior, 0, 1);
    }

    private static double expectedEdge(double nearThickness, double nearIor, double farThickness, double farIor) {
        // Central scanline: the incident and emerging rays are parallel. Find
        // the incident slope whose Snell-law path arrives at the target's x=0 edge.
        double lo = 0, hi = 3;
        double airDepth = CAMERA_Z - TARGET_Z - nearThickness - farThickness;
        for (int i = 0; i < 48; i++) {
            double slope = (lo + hi) / 2;
            double nearSlope = slope / Math.sqrt(nearIor * nearIor + (nearIor * nearIor - 1) * slope * slope);
            double farSlope = slope / Math.sqrt(farIor * farIor + (farIor * farIor - 1) * slope * slope);
            if (CAMERA_X + airDepth * slope + nearThickness * nearSlope + farThickness * farSlope < 0) lo = slope;
            else hi = slope;
        }
        double projectedSlope = Math.tan(Math.atan((lo + hi) / 2) - Math.PI / 4);
        return SIZE / 2.0 * (1 + projectedSlope / Math.tan(Math.toRadians(FOV_DEGREES / 2)));
    }

    private static double edge(double[] rgb) {
        int left = SIZE / 2 - 64, right = SIZE / 2 + 88;
        double previous = redFraction(rgb, left);
        require(previous < .05 && redFraction(rgb, right) > .95, "Missing blue/red edge endpoints");
        double crossing = Double.NaN;
        for (int x = left + 1; x <= right; x++) {
            double current = redFraction(rgb, x);
            if (previous < .5 && current >= .5) {
                require(Double.isNaN(crossing), "Multiple red/blue edge crossings");
                crossing = x - .5 + (.5 - previous) / (current - previous);
            }
            previous = current;
        }
        require(Double.isFinite(crossing), "No red/blue edge crossing");
        return crossing;
    }

    private static double redFraction(double[] rgb, int x) {
        double red = 0, blue = 0;
        for (int y = SIZE / 2 - 12; y < SIZE / 2 + 12; y++) {
            int offset = (y * SIZE + x) * 3;
            red += rgb[offset];
            blue += rgb[offset + 2];
        }
        require(red + blue > 1, "Dark or missing emissive background at x=" + x);
        return red / (red + blue);
    }

    private static double[] center(double[] rgb) {
        double[] mean = new double[3];
        int radius = 12;
        for (int y = SIZE / 2 - radius; y < SIZE / 2 + radius; y++) {
            for (int x = SIZE / 2 - radius; x < SIZE / 2 + radius; x++) {
                int offset = (y * SIZE + x) * 3;
                for (int c = 0; c < 3; c++) mean[c] += rgb[offset + c] / (4 * radius * radius);
            }
        }
        return mean;
    }

    private static void verifyAbsorption(double[] clearThin, double[] stainedThin, double[] clearThick, double[] stainedThick) {
        double[] sigma = {STAIN.red(), STAIN.green(), STAIN.blue()};
        // The center ray enters at 45 degrees. Compare against distance inside
        // the refracted medium, not normal thickness or distance in air.
        double cosInside = Math.sqrt(1 - .5 / (ABSORPTION_IOR * ABSORPTION_IOR));
        double shortPath = THIN / cosInside, longPath = THICK / cosInside;
        require(stainedThick[0] > 2 * stainedThick[1] && stainedThick[1] > 2 * stainedThick[2],
                "Thick stained medium did not tint linear white: " + Arrays.toString(stainedThick));
        for (int c = 0; c < 3; c++) {
            require(clearThin[c] > .2 && clearThick[c] > .2, "Clear control did not transmit channel " + c);
            near("clear-thin RGB neutrality " + c, clearThin[c] / clearThin[0], 1, .03);
            near("clear-thick RGB neutrality " + c, clearThick[c] / clearThick[0], 1, .03);
            // Normalize by the same geometry and IOR to remove Fresnel loss.
            double thinTransmission = stainedThin[c] / clearThin[c];
            double thickTransmission = stainedThick[c] / clearThick[c];
            System.out.printf(Locale.ROOT, "Dielectric channel %d: short %.4f (%.4f), long %.4f (%.4f)%n",
                    c, thinTransmission, Math.exp(-sigma[c] * shortPath),
                    thickTransmission, Math.exp(-sigma[c] * longPath));
            near("thin Beer–Lambert optical depth channel " + c, -Math.log(thinTransmission), sigma[c] * shortPath, .09);
            near("thick Beer–Lambert optical depth channel " + c, -Math.log(thickTransmission), sigma[c] * longPath, .09);
            near("added optical depth channel " + c, Math.log(thinTransmission / thickTransmission),
                    sigma[c] * (longPath - shortPath), .12);
        }
    }

    private static final class Fixture {
        private final RtxptRenderer renderer;
        private final Path output;
        private long frames;

        Fixture(RtxptRenderer renderer, Path output) {
            this.renderer = renderer;
            this.output = output;
        }

        double[] capture(String name, SceneTransaction.Builder builder) throws Exception {
            var transaction = builder.build();
            long revision = transaction.revision();
            require(renderer.apply(transaction).acceptedSceneRevision() == revision, name + ": wrong accepted revision");
            submit(revision); // Warm the native material and path state before measuring.
            var state = renderer.renderingState();
            require(state.reconstruction() == RtxptAccess.Reconstruction.NONE && !state.standaloneDenoiser()
                    && state.samplesPerPixel() == SAMPLES_PER_PIXEL && !state.restirDi() && !state.restirGi()
                    && state.radianceCacheMode() == 0 && !state.analyticBeauty(), "Unexpected beauty settings: " + state);
            double[] rgb = new double[SIZE * SIZE * 3];
            // Unreconstructed realtime beauty does not accumulate frames.
            // Average actual successive readbacks instead of waiting on a single noisy frame.
            for (int i = 0; i < CAPTURES; i++) {
                submit(revision);
                Path pfm = output.resolve(name + "-" + (i + 1) + ".pfm");
                Files.deleteIfExists(pfm);
                renderer.captureNativeLighting(pfm, LINEAR_BEAUTY);
                accumulatePfm(pfm, rgb, CAPTURES, false);
            }
            return rgb;
        }

        private void submit(long revision) {
            long sequence = ++frames;
            var frame = renderer.submit(RenderFrameRequest.builder(sequence, SIZE, SIZE, CAMERA)
                    .environment(EnvironmentState.neutral()).minimumSceneRevision(revision).build());
            require(frame.frameSequence() == sequence && frame.scheduledSceneRevision() == revision,
                    "Wrong native beauty frame identity: " + frame);
        }
    }

    private static void accumulatePfm(Path path, double[] rgb, int captures, boolean signedDistance) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        byte[] header = ("PF\n" + SIZE + " " + SIZE + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII);
        require(bytes.length == header.length + rgb.length * Float.BYTES
                && Arrays.equals(header, 0, header.length, bytes, 0, header.length), "Invalid linear PFM: " + path);
        var values = ByteBuffer.wrap(bytes, header.length, bytes.length - header.length)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (int y = SIZE - 1; y >= 0; y--) {
            for (int x = 0; x < SIZE * 3; x++) {
                float value = values.get();
                if (!Float.isFinite(value) || (!signedDistance && value < 0))
                    throw new AssertionError("Invalid floating signal in " + path);
                rgb[y * SIZE * 3 + x] += value / (double) captures;
            }
        }
    }

    private static void near(String name, double actual, double expected, double tolerance) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                "%s: measured %.5f, expected %.5f ± %.5f".formatted(name, actual, expected, tolerance));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
