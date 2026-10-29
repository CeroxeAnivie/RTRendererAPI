package demo;

import java.util.HashSet;

/** CPU-only route and scene-contract checks; does not open Vulkan, CUDA or a native window. */
public final class MediumCertificationSceneSelfTest {
    private MediumCertificationSceneSelfTest() { }

    public static void main(String[] args) {
        var options = MediumCertificationDemo.parseOptions(new String[]{"--width=1280", "--height=720",
                "--spp=4", "--preset=OPTICAL_ROUGH_NESTED"});
        require(options.size() == 4 && options.get("width").equals("1280")
                        && options.get("height").equals("720") && options.get("spp").equals("4")
                        && options.get("preset").equals("OPTICAL_ROUGH_NESTED"),
                "combined CLI options must parse independently");
        expectInvalid(() -> MediumCertificationDemo.parseOptions(new String[]{"--width=1280", "--width=640"}),
                "duplicate CLI options must be rejected");
        expectInvalid(() -> MediumCertificationDemo.parseOptions(new String[]{"--unknown=value"}),
                "unknown CLI options must be rejected");
        var keys = new HashSet<String>();
        require(MediumCertificationDemo.Preset.values().length == MediumCertificationDemo.Route.values().length,
                "every advertised route must have one selectable preset");
        for (var preset : MediumCertificationDemo.Preset.values()) {
            require(keys.add(preset.key()), "preset hotkeys must be unique");
            require(MediumCertificationDemo.Preset.byKey(preset.key()) == preset, "hotkey mapping is unstable");
            var fixture = MediumCertificationDemo.Preset.fixture(preset, 7, 12, 640, 360, 1.25, .016);
            var frame = fixture.frame(12, 640, 360, 1.25, .016, rig(preset));
            require(fixture.route() == preset.route() && fixture.scene().revision() == 7,
                    "fixture route or scene revision mismatch: " + preset);
            boolean integrated = preset == MediumCertificationDemo.Preset.SCATTER_GLASS_CAUSTICS;
            require(fixture.causticsEnabled() == integrated && frame.caustics().enabled() == integrated,
                    "caustics must only be enabled for the integrated route: " + preset);
            require(frame.minimumSceneRevision() == 7 && frame.antiAliasing().samplesPerPixel() == 1,
                    "fixture frame must be pinned to its isolated scene and default SPP");
            require(frame.clock().timeNanos() == 1_250_000_000L && frame.clock().deltaNanos() == 16_000_000L,
                    "frame clock must preserve the supplied monotonic time and per-frame delta");
            require(frame.shaderData().isEmpty(), "fixtures must not depend on expert shader data");
            switch (fixture.route()) {
                case CAMERA_ABSORPTION -> require(fixture.cameraMedium().scattering().isZero()
                                && fixture.cameraMedium().indexOfRefraction() == 1
                                && !fixture.cameraMedium().emission().isZero(), "camera absorption/emission profile");
                case BOUNDARY_DENSITY -> require(fixture.boundary() != null
                                && fixture.boundary().medium().scattering().isZero()
                                && !fixture.boundary().medium().densityField().isUniform()
                                && !fixture.cameraMedium().isVacuum(), "density boundary profile");
                case CAMERA_SINGLE_SCATTER -> require(!fixture.heterogeneous() && fixture.scatteringBounces() == 0
                                && fixture.boundary() == null && !fixture.cameraMedium().scattering().isZero(),
                        "homogeneous direct-scattering profile");
                case CAMERA_MULTIPLE_SCATTER -> require(!fixture.heterogeneous() && fixture.scatteringBounces() > 0
                                && fixture.boundary() == null, "homogeneous multiple-scattering profile");
                case HETEROGENEOUS_SINGLE_SCATTER -> require(fixture.heterogeneous() && fixture.scatteringBounces() == 0
                                && !fixture.cameraMedium().densityField().isUniform(), "heterogeneous direct-scattering profile");
                case HETEROGENEOUS_MULTIPLE_SCATTER -> require(fixture.heterogeneous() && fixture.scatteringBounces() > 0
                                && !fixture.cameraMedium().densityField().isUniform(), "heterogeneous multiple-scattering profile");
                case BOUNDARY_SCATTER -> require(fixture.boundary() != null
                                && !fixture.boundary().medium().scattering().isZero()
                                && fixture.boundary().medium().indexOfRefraction() == 1
                                && frame.primitiveBatch().mediumBoundaryIndices().size() == 1,
                        "frame-local IOR-one boundary scattering profile");
                case PBR_SINGLE_SIDED -> require(fixture.pbrSurface() && !fixture.doubleSided()
                                && fixture.cameraMedium().scattering().isZero(), "single-sided nonmetal PBR profile");
                case PBR_DOUBLE_SIDED -> require(fixture.pbrSurface() && fixture.doubleSided()
                                && fixture.cameraMedium().scattering().isZero(), "double-sided nonmetal PBR profile");
                case OPTICAL_SMOOTH_NESTED, OPTICAL_ROUGH_NESTED -> require(fixture.optical()
                                && frame.primitiveBatch().mediumBoundaryIndices().size() == 2
                                && fixture.boundary() == null && fixture.cameraMedium().scattering().isZero(),
                        "isolated nested optical profile");
                case SCATTER_GLASS_CAUSTICS -> verifyIntegratedFixture(fixture, frame);
            }
        }
        for (int i = 0; i < MediumCertificationDemo.Preset.values().length; i++) {
            var current = MediumCertificationDemo.Preset.values()[i];
            require(MediumCertificationDemo.Preset.next(current, 1)
                            == MediumCertificationDemo.Preset.values()[(i + 1) % MediumCertificationDemo.Preset.values().length],
                    "next preset must wrap in enum order");
            require(MediumCertificationDemo.Preset.next(current, -1)
                            == MediumCertificationDemo.Preset.values()[Math.floorMod(i - 1, MediumCertificationDemo.Preset.values().length)],
                    "previous preset must wrap in enum order");
        }
        System.out.println("MediumCertificationSceneSelfTest passed: 11 isolated routes plus integrated scatter/glass/caustics scene, scene/frame contracts, unique hotkeys and cyclic controls");
    }

    private static void verifyIntegratedFixture(MediumCertificationDemo.Fixture fixture,
            top.ceroxe.rt.renderer.api.RenderFrameRequest frame) {
        var scene = fixture.scene().upserts();
        var glass = scene.materials().stream().filter(material -> material.id() == 4).findFirst()
                .orElseThrow(() -> new AssertionError("integrated scene must publish its refractive glass material"));
        var glassMesh = scene.meshes().stream().filter(mesh -> mesh.id() == 3).findFirst()
                .orElseThrow(() -> new AssertionError("integrated scene must publish the glass sphere mesh"));
        boolean glassInstance = scene.instances().stream().anyMatch(instance -> instance.id() == 3 && instance.meshAssetId() == 3);
        var materialIds = glassMesh.triangleMaterialIds();
        require(glass.transmission() > 0 && glass.indexOfRefraction() > 1,
                "integrated route must use transmissive glass with an IOR change");
        require(glassInstance && materialIds.hasRemaining(), "integrated glass mesh must be instanced with triangles");
        while (materialIds.hasRemaining()) require(materialIds.get() == glass.id(),
                "integrated sphere triangles must reference the refractive glass material");
        require(!frame.environment().cameraMedium().scattering().isZero()
                        && fixture.scatteringBounces() > 0 && frame.caustics().photons() > 0
                        && frame.minimumSceneRevision() == fixture.scene().revision(),
                "the submitted frame must combine scattering, refractive glass and enabled caustics");
    }

    private static MediumCertificationDemo.Rig rig(MediumCertificationDemo.Preset preset) {
        return preset.rig();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectInvalid(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected validation path.
        }
    }
}
