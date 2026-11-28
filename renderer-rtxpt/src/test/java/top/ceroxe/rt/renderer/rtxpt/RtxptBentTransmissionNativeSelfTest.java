package top.ceroxe.rt.renderer.rtxpt;

import static top.ceroxe.rt.renderer.rtxpt.RtxptBentTransmissionReferenceSelfTest.*;

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

/** Captures additive linear INDIRECT_DIFFUSE; verification is separate from GPU capture. */
public final class RtxptBentTransmissionNativeSelfTest {
    private static final int SIZE = 128, INDIRECT_CAPTURE = 1, GEOMETRY_CAPTURE = 2;
    private static final double CAMERA_Z = 1.45, CAMERA_FOV = 60, CAMERA_X = prismX(0);
    private static final CameraState CAMERA = CameraState.lookAt(CAMERA_X, 0, CAMERA_Z, CAMERA_X, 0, 0)
            .verticalFieldOfViewDegrees(CAMERA_FOV).aspectRatio(16.0 / 9.0).build();
    private static final double VIEW_HALF_WIDTH = CAMERA_Z * CAMERA.tanHalfFovX();
    private static final double VIEW_HALF_HEIGHT = CAMERA_Z * CAMERA.tanHalfFovY();
    private static final double OPTIC_HALF_HEIGHT = .5, MASK_Z = 4.5, PROBE_HALF_HEIGHT = .43;
    private static final double FOOTPRINT_HALF_HEIGHT = OPTIC_HALF_HEIGHT + .08;
    private static final double FLOOR_TEXEL_AREA = (2 * VIEW_HALF_WIDTH / SIZE) * (2 * VIEW_HALF_HEIGHT / SIZE);
    // Applies to the integrated spot, not individual sparse bins. Allow sampling at
    // the aperture boundary and rejection of deposits across the floor's triangle seam.
    private static final double FOOTPRINT_FLUX_RELATIVE_TOLERANCE = .10;
    private static final long RECEIVER = 1, OPAQUE = 2, OPTIC = 3, MASK_LEFT = 4, MASK_RIGHT = 5, BLOCKER = 6;
    private static final int WHITE_RGBA8 = 0xffffffff, RED_RGBA8 = 0xff0000ff;
    private static final double ZERO_TOLERANCE = .0005;
    private static final Rgb ABSORPTION = new Rgb(.2f, .45f, .85f);
    private static final Set<NativeLightingPlan.Signal> SIGNALS = Set.of(
            NativeLightingPlan.Signal.INDIRECT_DIFFUSE, NativeLightingPlan.Signal.NORMAL_DISTANCE);
    private enum Occluder { NONE, AFTER_EXIT, BEFORE_ENTRY }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--pre-entry")))
            throw new IllegalArgumentException("Usage: capture|verify-indirect <PFM directory> [--pre-entry]");
        Path directory = Path.of(args[1]).toAbsolutePath();
        boolean preEntry = args.length == 3;
        switch (args[0]) {
            case "capture" -> capture(Files.createDirectories(directory), preEntry);
            case "verify-indirect" -> verifyIndirect(directory, preEntry);
            default -> throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    private static void capture(Path output, boolean preEntry) throws Exception {
        // Zero diffuse-bounce samples, unit strength, no reconstruction: isolate light-side transmission.
        var plan = new NativeLightingPlan(SIZE, SIZE, SIGNALS, 0, 6, 1, NativeLightingStyle.defaults(),
                new NativeLightingPlan.Directional(0, 0, 1, 1, 1, 1, 1), null, false).withSunAngularRadius(0);
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var access = renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);

            var fixture = new Fixture(renderer, access, lighting, output);
            fixture.capture("aperture", 1, false, 0, 1, Rgb.ZERO, Occluder.NONE);
            fixture.capture("matched-index", 2, true, PRISM_TILT, 1, Rgb.ZERO, Occluder.NONE);
            fixture.capture("parallel-slab", 3, true, 0, IOR, Rgb.ZERO, Occluder.NONE);
            fixture.capture("redirected", 4, true, PRISM_TILT, IOR, Rgb.ZERO, Occluder.NONE);
            fixture.capture("blocked-after-exit", 5, true, PRISM_TILT, IOR, Rgb.ZERO, Occluder.AFTER_EXIT);
            fixture.capture("total-internal-reflection", 6, true, TIR_TILT, IOR, Rgb.ZERO, Occluder.NONE);
            fixture.capture("absorption", 7, true, PRISM_TILT, IOR, ABSORPTION, Occluder.NONE);
            fixture.capture("red-receiver", 8, true, PRISM_TILT, IOR, Rgb.ZERO, Occluder.NONE, RED_RGBA8);
            if (preEntry) fixture.capture("blocked-before-entry", 9, true, PRISM_TILT, IOR, Rgb.ZERO, Occluder.BEFORE_ENTRY);
            System.out.println("BENT_TRANSMISSION_NATIVE CAPTURED cases=" + (preEntry ? 9 : 8) + " output=" + output);
        }
    }

    private static void verifyIndirect(Path output, boolean preEntry) throws Exception {
        // Wider than the analytic +/- .066 footprint to allow bounded hit splatting.
        double bentHalf = .16, straightHalf = .048;
        var aperture = Capture.read(output, "aperture");
        double[] apertureBent = aperture.floorPatch(CAMERA_X, bentHalf, null).mean();
        double[] apertureStraight = aperture.floorPatch(0, straightHalf, null).mean();
        var clearPrism = Capture.read(output, "redirected");
        Patch redirected = clearPrism.floorPatch(CAMERA_X, bentHalf, aperture);
        // Integrate a padded footprint, not the central strip used for shape and color.
        Patch fullFootprint = clearPrism.floorPatch(CAMERA_X, bentHalf, FOOTPRINT_HALF_HEIGHT, aperture);
        double expectedFlux = clearPrismFlux(2 * OPTIC_HALF_HEIGHT);
        double[] afterBlocker = Capture.read(output, "blocked-after-exit").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        Patch beforeEntry = preEntry ? Capture.read(output, "blocked-before-entry")
                .floorPatch(CAMERA_X, bentHalf, FOOTPRINT_HALF_HEIGHT, aperture) : null;
        double[] afterTir = Capture.read(output, "total-internal-reflection").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        double[] afterAbsorption = Capture.read(output, "absorption").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        double[] matchedBent = Capture.read(output, "matched-index").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        double[] slabBent = Capture.read(output, "parallel-slab").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        double[] redReceiver = Capture.read(output, "red-receiver").floorPatch(CAMERA_X, bentHalf, aperture).mean();
        double[] sigma = {ABSORPTION.red(), ABSORPTION.green(), ABSORPTION.blue()};
        System.out.printf(Locale.ROOT, "BENT_TRANSMISSION_NATIVE bentX=%.5f apertureBent=%s apertureStraight=%s redirectedDelta=%s"
                        + " blockedDelta=%s tirDelta=%s absorptionDelta=%s redReceiverDelta=%s%n", redirected.centroidX(),
                Arrays.toString(apertureBent), Arrays.toString(apertureStraight),
                Arrays.toString(redirected.mean()), Arrays.toString(afterBlocker), Arrays.toString(afterTir),
                Arrays.toString(afterAbsorption), Arrays.toString(redReceiver));
        System.out.printf(Locale.ROOT, "BENT_TRANSMISSION_FLUX expected=%.6f relativeTolerance=%.2f rgb=%s preEntryRgb=%s%n",
                expectedFlux, FOOTPRINT_FLUX_RELATIVE_TOLERANCE, Arrays.toString(fullFootprint.incidentFlux()),
                beforeEntry == null ? "not requested" : Arrays.toString(beforeEntry.incidentFlux()));
        for (int c = 0; c < 3; c++) {
            near("no indirect without an optic, straight", apertureStraight[c], 0, ZERO_TOLERANCE);
            near("no indirect without an optic, bent", apertureBent[c], 0, ZERO_TOLERANCE);
            near("matched-index bent delta", matchedBent[c], 0, ZERO_TOLERANCE);
            near("parallel slab bent delta", slabBent[c], 0, ZERO_TOLERANCE);
            require(redirected.mean()[c] > .005, "Prism deposited no measurable energy in channel " + c);
            near("total clear footprint flux channel " + c, fullFootprint.incidentFlux()[c], expectedFlux,
                    FOOTPRINT_FLUX_RELATIVE_TOLERANCE * expectedFlux);
            near("clear optic preserves a white source", redirected.mean()[c] / redirected.mean()[0], 1, .02);
            double suppressionLimit = ZERO_TOLERANCE + .05 * redirected.mean()[c];
            near("post-exit blocker delta", afterBlocker[c], 0, suppressionLimit);
            if (beforeEntry != null) near("pre-entry occluder flux channel " + c, beforeEntry.incidentFlux()[c],
                    0, .03 * expectedFlux);
            near("TIR in the redirected footprint", afterTir[c], 0, suppressionLimit);
            near("absorption channel " + c, afterAbsorption[c] / redirected.mean()[c],
                    Math.exp(-sigma[c] * (ENTRY_Z - EXIT_Z)), .05);
            near("receiver albedo channel " + c, redReceiver[c], c == 0 ? redirected.mean()[c] : 0,
                    c == 0 ? ZERO_TOLERANCE + .03 * redirected.mean()[c] : ZERO_TOLERANCE);
        }
        near("redirected delta centroid", redirected.centroidX(), CAMERA_X, .07);
        System.out.println("BENT_TRANSMISSION_INDIRECT_DIFFUSE PASS cases=" + (preEntry ? 9 : 8) + " output=" + output);
    }

    private record Fixture(RtxptRenderer renderer, RtxptAccess access, NativeLightingControl lighting, Path output) {
        void capture(String name, long revision, boolean medium, double tilt, double ior, Rgb sigma,
                     Occluder occluder) throws Exception {
            capture(name, revision, medium, tilt, ior, sigma, occluder, WHITE_RGBA8);
        }

        void capture(String name, long revision, boolean medium, double tilt, double ior, Rgb sigma,
                     Occluder occluder, int receiverColor) throws Exception {
            var scene = SceneTransaction.builder(revision).resetScene()
                    .upsert(MaterialAsset.builder(RECEIVER).baseColorRgba8(receiverColor).roughness(1).build())
                    .upsert(MaterialAsset.builder(OPAQUE).doubleSided(true).build())
                    .upsert(quad(RECEIVER, RECEIVER, -2, 2, 2, 0))
                    .upsert(SceneInstance.builder(RECEIVER, RECEIVER).build())
                    .upsert(quad(MASK_LEFT, OPAQUE, -4, -HALF_APERTURE, 4, MASK_Z))
                    .upsert(SceneInstance.builder(MASK_LEFT, MASK_LEFT).build())
                    .upsert(quad(MASK_RIGHT, OPAQUE, HALF_APERTURE, 4, 4, MASK_Z))
                    .upsert(SceneInstance.builder(MASK_RIGHT, MASK_RIGHT).build());
            var roles = new ArrayList<>(List.of(NativeLightingPublication.Instance.receiver(RECEIVER),
                    NativeLightingPublication.Instance.shadowOnly(MASK_LEFT),
                    NativeLightingPublication.Instance.shadowOnly(MASK_RIGHT)));
            if (medium) {
                scene.upsert(MaterialAsset.builder(OPTIC).baseColorRgba8(0xffffffff).roughness(0).transmission(1)
                                .pathDecomposition(MaterialAsset.PathDecomposition.TRANSMISSION).build())
                        .upsert(prismMesh(tilt))
                        .upsert(SceneInstance.builder(OPTIC, OPTIC)
                                .mediumBoundary(MediumBoundary.surface(new Medium(sigma, (float) ior))).build());
                var shadow = NativeLightingPublication.Instance.shadowOnly(OPTIC);
                roles.add(new NativeLightingPublication.Instance(OPTIC,
                        shadow.roles() | NativeLightingPublication.Instance.DIELECTRIC_BOUNDARY, 1, 0, 0, 0, 0, 0));
            }
            if (occluder != Occluder.NONE) {
                // The upstream blocker caps the aperture half a world unit above entry,
                // well outside the prism bounds; the camera remains below it.
                double z = occluder == Occluder.BEFORE_ENTRY ? MASK_Z : BLOCKER_Z;
                double x = occluder == Occluder.BEFORE_ENTRY ? 0 : prismX(z);
                scene.upsert(quad(BLOCKER, OPAQUE, x - BLOCKER_HALF_WIDTH, x + BLOCKER_HALF_WIDTH, 2, z))
                        .upsert(SceneInstance.builder(BLOCKER, BLOCKER).build());
                // The opaque receiver intercepts transport without covering the bent-floor probe.
                roles.add(NativeLightingPublication.Instance.receiver(BLOCKER));
            }
            require(renderer.apply(scene.build()).acceptedSceneRevision() == revision, "Scene rejected: " + name);
            lighting.publishLightingAttributes(new NativeLightingPublication(revision, roles, List.of(), null, null));
            renderer.submit(RenderFrameRequest.builder(revision, SIZE, SIZE, CAMERA).minimumSceneRevision(revision).build());
            access.awaitIdle();
            try (var lease = lighting.acquireLightingFrame().orElseThrow()) {
                var frame = lease.frame();
                require(frame.sequence() == revision && frame.sceneRevision() == revision
                            && frame.attributeGeneration() == revision,
                        "Wrong frame or attribute generation: " + name + " " + frame);
                require(frame.planes().size() == 2 && frame.planes().stream().anyMatch(plane ->
                                plane.signal() == NativeLightingPlan.Signal.INDIRECT_DIFFUSE
                                        && plane.format() == LightingFrameLease.Format.RGBA16_FLOAT)
                            && frame.planes().stream().anyMatch(plane ->
                                plane.signal() == NativeLightingPlan.Signal.NORMAL_DISTANCE
                                        && plane.format() == LightingFrameLease.Format.RGBA32_FLOAT),
                        "Expected only HDR indirect and receiver geometry: " + name + " " + frame.planes());
            }
            renderer.captureNativeLighting(indirectPath(output, name), INDIRECT_CAPTURE);
            renderer.captureNativeLighting(normalPath(output, name), GEOMETRY_CAPTURE);
        }
    }

    private static Path indirectPath(Path directory, String name) {
        return directory.resolve(name + "-indirect.pfm");
    }

    private static Path normalPath(Path directory, String name) {
        return directory.resolve(name + "-receiver-normal.pfm");
    }

    private static MeshAsset quad(long id, long material, double left, double right, double y, double z) {
        float l = (float) left, r = (float) right, h = (float) y, depth = (float) z;
        return MeshAsset.builder(id, new float[]{l,-h,depth, r,-h,depth, r,h,depth, l,h,depth},
                new int[]{0,1,2, 0,2,3}, new long[]{material, material}).build();
    }

    private static MeshAsset prismMesh(double tilt) {
        float x = (float) HALF_APERTURE, y = (float) OPTIC_HALF_HEIGHT, front = (float) ENTRY_Z;
        float backLeft = (float) (EXIT_Z + x * Math.tan(tilt)), backRight = (float) (EXIT_Z - x * Math.tan(tilt));
        float[] vertices = {-x,-y,backLeft, x,-y,backRight, x,y,backRight, -x,y,backLeft,
                -x,-y,front, x,-y,front, x,y,front, -x,y,front};
        int[] triangles = {4,5,6, 4,6,7, 0,2,1, 0,3,2, 0,4,7, 0,7,3,
                1,2,6, 1,6,5, 3,7,6, 3,6,2, 0,1,5, 0,5,4};
        long[] materials = new long[triangles.length / 3];
        Arrays.fill(materials, OPTIC);
        var mesh = MeshAsset.builder(OPTIC, vertices, triangles, materials).build();
        require(mesh.isClosedManifold(), "Prism must be a closed outward-wound manifold");
        return mesh;
    }

    private record Patch(double[] mean, double centroidX, double[] incidentFlux) {}

    private record Capture(String name, float[] indirect, float[] normal) {
        static Capture read(Path directory, String name) throws Exception {
            Path path = indirectPath(directory, name);
            float[] indirect = readPfm(path);
            for (float value : indirect) require(value >= 0, "Negative indirect radiance in " + path);
            return new Capture(name, indirect, readPfm(normalPath(directory, name)));
        }

        private static float[] readPfm(Path path) throws Exception {
            byte[] bytes = Files.readAllBytes(path);
            byte[] header = ("PF\n" + SIZE + " " + SIZE + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII);
            float[] rgb = new float[SIZE * SIZE * 3];
            require(bytes.length == header.length + rgb.length * Float.BYTES
                    && Arrays.equals(bytes, 0, header.length, header, 0, header.length), "Unexpected PFM: " + path);
            ByteBuffer.wrap(bytes, header.length, bytes.length - header.length)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(rgb);
            for (float value : rgb) require(Float.isFinite(value), "Nonfinite capture in " + path);
            return rgb;
        }

        Patch floorPatch(double xCenter, double halfWidth, Capture baseline) {
            return floorPatch(xCenter, halfWidth, PROBE_HALF_HEIGHT, baseline);
        }

        Patch floorPatch(double xCenter, double halfWidth, double halfHeight, Capture baseline) {
            double[] mean = new double[3];
            double weightedX = 0, weight = 0;
            int pixels = 0;
            for (int y = 0; y < SIZE; y++) {
                if (Math.abs(coordinate(y, VIEW_HALF_HEIGHT)) >= halfHeight) continue;
                for (int x = 0; x < SIZE; x++) {
                    double worldX = CAMERA_X + coordinate(x, VIEW_HALF_WIDTH);
                    if (Math.abs(worldX - xCenter) >= halfWidth) continue;
                    double signal = 0;
                    int offset = (y * SIZE + x) * 3;
                    // The PFM capture exports NORMAL_DISTANCE.rgb (normal), not .a (distance).
                    if (Math.abs(normal[offset]) > .001 || Math.abs(normal[offset + 1]) > .001
                            || Math.abs(normal[offset + 2] - 1) > .001)
                        throw new AssertionError(name + " does not see the floor at PFM pixel " + x + "," + y);
                    for (int c = 0; c < 3; c++) {
                        double delta = indirect[offset + c] - (baseline == null ? 0 : baseline.indirect[offset + c]);
                        mean[c] += delta;
                        signal += Math.max(delta, 0);
                    }
                    weight += signal;
                    weightedX += signal * worldX;
                    pixels++;
                }
            }
            require(pixels > 0, "Empty receiver probe");
            double[] incidentFlux = new double[3];
            for (int c = 0; c < 3; c++) {
                // A unit-albedo Lambertian floor has radiance E/pi; all texels
                // project to equal world-area rectangles on this fronto-parallel floor.
                incidentFlux[c] = mean[c] * Math.PI * FLOOR_TEXEL_AREA;
                mean[c] /= pixels;
            }
            return new Patch(mean, weightedX / weight, incidentFlux);
        }

        private static double coordinate(int pixel, double halfExtent) {
            return ((pixel + .5) * 2 / SIZE - 1) * halfExtent;
        }
    }

    private static void near(String label, double actual, double expected, double tolerance) {
        require(Math.abs(actual - expected) <= tolerance, label + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
