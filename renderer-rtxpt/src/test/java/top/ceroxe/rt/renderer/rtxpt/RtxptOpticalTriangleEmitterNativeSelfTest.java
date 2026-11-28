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

/** GPU readback of frame-scoped Lambertian emission through a retained dielectric. */
public final class RtxptOpticalTriangleEmitterNativeSelfTest {
    private static final int SIZE = 96, INDIRECT_CAPTURE = 1, NORMAL_CAPTURE = 2;
    private static final long FLOOR = 1, GLASS = 2, BLOCKER = 3;
    private static final float EXIT_Z = .60f, ENTRY_Z = 1.20f, IOR = 1.5f, EDGE = .16f, REACH = 8f;
    private static final double IMMERSED_Z = .90, EXTERIOR_Z = 1.40, CAMERA_Z = .50, PROBE_RADIUS = .24;
    private static final double ZERO_TOLERANCE = .0001, MEAN_RELATIVE_TOLERANCE = .25;
    private static final Rgb ABSORPTION = new Rgb(.35f, .9f, 1.5f);
    private static final Source LEFT = new Source(-.43, .33, IMMERSED_Z, new Rgb(10, 5, 2.5f));
    private static final Source RIGHT = new Source(.43, -.33, IMMERSED_Z, new Rgb(2.5f, 5, 10));
    private static final Source EXTERIOR = new Source(LEFT.x, LEFT.y, EXTERIOR_Z, LEFT.radiance);
    private static final CameraState CAMERA = CameraState.lookAt(0, 0, CAMERA_Z, 0, 0, 0)
            .verticalFieldOfViewDegrees(118).aspectRatio(1).build();
    private static final Set<NativeLightingPlan.Signal> SIGNALS = Set.of(
            NativeLightingPlan.Signal.INDIRECT_DIFFUSE, NativeLightingPlan.Signal.NORMAL_DISTANCE);

    private RtxptOpticalTriangleEmitterNativeSelfTest() {}

    public static void main(String[] args) throws Exception {
        boolean exteriorOnly = args.length == 2 && args[1].equals("--exterior-only");
        if (args.length != 1 && !exteriorOnly)
            throw new IllegalArgumentException("Usage: <capture directory> [--exterior-only]");
        var output = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        var plan = new NativeLightingPlan(SIZE, SIZE, SIGNALS, 0, REACH, 1,
                NativeLightingStyle.defaults(), null, null, false);
        try (var renderer = (RtxptRenderer) RtxptRenderers.openNativeLighting(plan)) {
            var lighting = renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var fixture = new Fixture(renderer, renderer.extension(RtxptAccess.class).orElseThrow(), lighting, output);
            fixture.apply(SceneTransaction.builder(1)
                    .upsert(MaterialAsset.builder(FLOOR).baseColorRgba8(0xffffffff).roughness(1).build())
                    .upsert(quad(FLOOR, 1.5f, 0))
                    .upsert(SceneInstance.builder(FLOOR, FLOOR).build())
                    .upsert(MaterialAsset.builder(GLASS).baseColorRgba8(0xffffffff).roughness(0).transmission(1)
                            .pathDecomposition(MaterialAsset.PathDecomposition.TRANSMISSION).build())
                    .upsert(glassBox())
                    .upsert(SceneInstance.builder(GLASS, GLASS)
                            .mediumBoundary(MediumBoundary.surface(new Medium(ABSORPTION, IOR))).build())
                    .build(), false);

            fixture.capture("empty-baseline", List.of()).expectDark();
            fixture.capture("exterior", List.of(EXTERIOR.emitter(false))).expect(EXTERIOR);
            fixture.capture("empty-clears-exterior", List.of()).expectDark();
            System.out.println("OPTICAL_TRIANGLE_EMITTER_EXTERIOR PASS output=" + output);
            if (exteriorOnly) return;

            fixture.capture("left", List.of(LEFT.emitter(false))).expect(LEFT);
            fixture.capture("right-replaces-left", List.of(RIGHT.emitter(false))).expect(RIGHT);
            fixture.capture("both", List.of(LEFT.emitter(false), RIGHT.emitter(false))).expect(LEFT, RIGHT);
            fixture.capture("empty-clears-both", List.of()).expectDark();
            fixture.capture("back-face", List.of(LEFT.emitter(true))).expectDark();

            // The blocker sits between the exit and the camera, behind the camera's view of the floor.
            fixture.apply(SceneTransaction.builder(2)
                    .upsert(MaterialAsset.builder(BLOCKER).doubleSided(true).build())
                    .upsert(quad(BLOCKER, 1.2f, .55f))
                    .upsert(SceneInstance.builder(BLOCKER, BLOCKER).build()).build(), true);
            fixture.capture("blocked-after-exit", List.of(RIGHT.emitter(false))).expectDark();
            fixture.apply(SceneTransaction.builder(3).removeInstance(BLOCKER).build(), false);
            fixture.capture("blocker-removed", List.of(RIGHT.emitter(false))).expect(RIGHT);
            fixture.capture("empty-after-restore", List.of()).expectDark();
            System.out.println("OPTICAL_TRIANGLE_EMITTER_INDIRECT_DIFFUSE PASS output=" + output);
        }
    }

    private static final class Fixture {
        private final RtxptRenderer renderer;
        private final RtxptAccess access;
        private final NativeLightingControl lighting;
        private final Path output;
        private long revision, sequence;

        Fixture(RtxptRenderer renderer, RtxptAccess access, NativeLightingControl lighting, Path output) {
            this.renderer = renderer;
            this.access = access;
            this.lighting = lighting;
            this.output = output;
        }

        void apply(SceneTransaction change, boolean blocked) {
            revision = change.revision();
            require(renderer.apply(change).acceptedSceneRevision() == revision, "Rejected scene revision " + revision);
            var roles = new ArrayList<>(List.of(NativeLightingPublication.Instance.receiver(FLOOR),
                    new NativeLightingPublication.Instance(GLASS,
                            NativeLightingPublication.Instance.DIELECTRIC_BOUNDARY, 1, 0, 0, 0, 0, 0)));
            if (blocked) roles.add(NativeLightingPublication.Instance.receiver(BLOCKER));
            lighting.publishLightingAttributes(new NativeLightingPublication(revision, roles, List.of(), null, null));
        }

        Capture capture(String name, List<OpticalTriangleEmitter> emitters) throws Exception {
            renderer.submit(RenderFrameRequest.builder(++sequence, SIZE, SIZE, CAMERA)
                    .minimumSceneRevision(revision).environment(EnvironmentState.neutral())
                    .opticalTriangleEmitters(emitters).build());
            access.awaitIdle();
            try (var lease = lighting.acquireLightingFrame().orElseThrow()) {
                var frame = lease.frame();
                require(frame.sequence() == sequence && frame.sceneRevision() == revision
                        && frame.attributeGeneration() == revision && frame.width() == SIZE && frame.height() == SIZE,
                        name + ": stale or mismatched GPU frame " + frame);
                require(frame.planes().size() == 2 && frame.planes().stream().anyMatch(p ->
                                p.signal() == NativeLightingPlan.Signal.INDIRECT_DIFFUSE
                                        && p.format() == LightingFrameLease.Format.RGBA16_FLOAT)
                        && frame.planes().stream().anyMatch(p -> p.signal() == NativeLightingPlan.Signal.NORMAL_DISTANCE
                                        && p.format() == LightingFrameLease.Format.RGBA32_FLOAT),
                        name + ": missing HDR indirect or receiver geometry plane");
            }
            var indirect = output.resolve(name + "-indirect.pfm");
            var normals = output.resolve(name + "-receiver-normal.pfm");
            renderer.captureNativeLighting(indirect, INDIRECT_CAPTURE);
            renderer.captureNativeLighting(normals, NORMAL_CAPTURE);
            float[] normal = readPfm(normals);
            for (int i = 0; i < normal.length; i += 3) {
                require(Math.abs(normal[i]) <= .001 && Math.abs(normal[i + 1]) <= .001 && Math.abs(normal[i + 2] - 1) <= .001,
                        name + ": camera must still see the floor at pixel " + i / 3);
            }
            return new Capture(name, readPfm(indirect));
        }
    }

    private static MeshAsset quad(long id, float half, float z) {
        return MeshAsset.builder(id, new float[]{-half,-half,z, half,-half,z, half,half,z, -half,half,z},
                new int[]{0,1,2, 0,2,3}, new long[]{id,id}).build();
    }

    private static MeshAsset glassBox() {
        float h = 1.5f, a = EXIT_Z, b = ENTRY_Z;
        float[] vertices = {-h,-h,a, h,-h,a, h,h,a, -h,h,a, -h,-h,b, h,-h,b, h,h,b, -h,h,b};
        int[] triangles = {4,5,6, 4,6,7, 0,2,1, 0,3,2, 0,4,7, 0,7,3,
                1,2,6, 1,6,5, 3,7,6, 3,6,2, 0,1,5, 0,5,4};
        long[] materials = new long[triangles.length / 3];
        Arrays.fill(materials, GLASS);
        var mesh = MeshAsset.builder(GLASS, vertices, triangles, materials).build();
        require(mesh.isClosedManifold(), "Glass must be a closed, outward-wound medium");
        return mesh;
    }

    private static float[] readPfm(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        byte[] header = ("PF\n" + SIZE + " " + SIZE + "\n-1.0\n").getBytes(StandardCharsets.US_ASCII);
        float[] values = new float[SIZE * SIZE * 3];
        require(bytes.length == header.length + values.length * Float.BYTES
                && Arrays.equals(bytes, 0, header.length, header, 0, header.length), "Unexpected PFM: " + path);
        ByteBuffer.wrap(bytes, header.length, bytes.length - header.length)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        for (float value : values) require(Float.isFinite(value), "Non-finite capture: " + path);
        return values;
    }

    private record Capture(String name, float[] rgb) {
        void expectDark() {
            double peak = 0;
            for (float value : rgb) peak = Math.max(peak, Math.abs(value));
            require(peak <= ZERO_TOLERANCE, name + ": expected no additive flux anywhere, peak=" + peak);
        }

        void expect(Source... emitters) {
            for (Source probe : List.of(LEFT, RIGHT)) {
                double[] actual = new double[3], expected = new double[3];
                int count = 0;
                // PFM rows are bottom-up, so world Y grows with the stored row index.
                for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++) {
                    double x = floorCoordinate(col, CAMERA.tanHalfFovX());
                    double y = floorCoordinate(row, CAMERA.tanHalfFovY());
                    if (Math.hypot(x - probe.x, y - probe.y) >= PROBE_RADIUS) continue;
                    count++;
                    int offset = (row * SIZE + col) * 3;
                    for (int c = 0; c < 3; c++) {
                        require(rgb[offset + c] >= 0, name + ": negative indirect radiance");
                        actual[c] += rgb[offset + c];
                    }
                    for (Source emitter : emitters) emitter.integrateAt(x, y, expected);
                }
                require(count > 100, "Probe projects to too few pixels");
                for (int c = 0; c < 3; c++) {
                    actual[c] /= count;
                    expected[c] /= count;
                    require(Math.abs(actual[c] - expected[c]) <= MEAN_RELATIVE_TOLERANCE * expected[c],
                            String.format(Locale.ROOT, "%s probe=(%.2f,%.2f) channel=%d expected %.6f got %.6f",
                                    name, probe.x, probe.y, c, expected[c], actual[c]));
                }
                System.out.printf(Locale.ROOT, "%s probe=(%.2f,%.2f) actual=%s expected=%s%n",
                        name, probe.x, probe.y, Arrays.toString(actual), Arrays.toString(expected));
            }
        }

        private static double floorCoordinate(int pixel, float halfFov) {
            return CAMERA_Z * halfFov * ((pixel + .5) * 2 / SIZE - 1);
        }
    }

    private record Source(double x, double y, double z, Rgb radiance) {
        private static final int AREA_STEPS = 8;
        private static final double AREA = .5 * EDGE * EDGE;

        private boolean exterior() { return z > ENTRY_Z; }

        OpticalTriangleEmitter emitter(boolean flip) {
            // U along +Y and V along +X give a front normal of -Z.
            return new OpticalTriangleEmitter(x - EDGE / 3.0, y - EDGE / 3.0, z,
                    flip ? EDGE : 0, flip ? 0 : EDGE, 0, flip ? 0 : EDGE, flip ? EDGE : 0, 0,
                    radiance, REACH, exterior() ? List.of() : List.of(GLASS));
        }

        void integrateAt(double floorX, double floorY, double[] result) {
            // Integrate uniform area samples of the triangle. For each sample, invert Snell's
            // radial map using s=sin(theta_glass). Lambertian flux is proportional to
            // sin(theta_source) * d(sin(theta_source)); in air this gives IOR^2 * s ds.
            // The floor area is r dr dphi. The white floor contributes 1/pi.
            boolean outside = exterior();
            double glassHeight = Math.min(z, ENTRY_Z) - EXIT_Z;
            double airHeight = EXIT_Z + Math.max(0, z - ENTRY_Z);
            for (int i = 0; i < AREA_STEPS; i++) for (int j = 0; j < AREA_STEPS; j++) {
                double a = Math.sqrt((i + .5) / AREA_STEPS), b = (j + .5) / AREA_STEPS;
                double sx = x + EDGE * (a * b - 1.0 / 3), sy = y + EDGE * (a * (1 - b) - 1.0 / 3);
                double r = Math.hypot(floorX - sx, floorY - sy);
                double lo = 0, hi = 1.0 / IOR;
                for (int step = 0; step < 24; step++) {
                    double s = (lo + hi) * .5;
                    double air = IOR * s;
                    double offset = glassHeight * s / Math.sqrt(1 - s * s)
                            + airHeight * air / Math.sqrt(1 - air * air);
                    if (offset < r) lo = s; else hi = s;
                }
                double s = (lo + hi) * .5, air = IOR * s;
                double cg = Math.sqrt(1 - s * s), ca = Math.sqrt(1 - air * air);
                double drds = glassHeight / (cg * cg * cg) + airHeight * IOR / (ca * ca * ca);
                // s/r by the original radial equation, including its finite limit at r=0.
                double sinOverRadius = 1 / (glassHeight / cg + airHeight * IOR / ca);
                double rs = (IOR * cg - ca) / (IOR * cg + ca), rp = (cg - IOR * ca) / (cg + IOR * ca);
                double transmission = 1 - .5 * (rs * rs + rp * rp);
                // The exterior ray also crosses the entrance. Reciprocity gives the same Fresnel loss.
                double sourceWeight = outside ? IOR * IOR * transmission : 1;
                double scale = AREA * sinOverRadius * transmission * sourceWeight / (Math.PI * drds * AREA_STEPS * AREA_STEPS);
                double distance = glassHeight / cg;
                result[0] += radiance.red() * scale * Math.exp(-ABSORPTION.red() * distance);
                result[1] += radiance.green() * scale * Math.exp(-ABSORPTION.green() * distance);
                result[2] += radiance.blue() * scale * Math.exp(-ABSORPTION.blue() * distance);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
