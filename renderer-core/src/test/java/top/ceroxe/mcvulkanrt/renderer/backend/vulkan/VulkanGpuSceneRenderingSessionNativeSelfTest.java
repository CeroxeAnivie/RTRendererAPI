package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.CameraState;
import top.ceroxe.mcvulkanrt.renderer.api.EnvironmentState;
import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** End-to-end public-contract render gate for the standalone GPUScene renderer. */
public final class VulkanGpuSceneRenderingSessionNativeSelfTest {
    private static final long TIMEOUT_NANOS = 15_000_000_000L;
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    private VulkanGpuSceneRenderingSessionNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "complex GPUScene gate requires hardware RT: " + capability.summary());
        RayTracingRendererConfig configuration = new RayTracingRendererConfig(3, true, true);
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        try (VulkanRendererHost renderer = new VulkanRendererHost(configuration, session)) {
            renderer.apply(complexScene());
            RenderFrameRequest frame = new RenderFrameRequest(
                    0L, 0L, WIDTH, HEIGHT, camera(), environment()
            );
            awaitFrameAdmission(renderer, frame);
            VulkanGpuSceneRenderingSession.DiagnosticFrame diagnostic = awaitDiagnostic(session);
            ImageStatistics statistics = statistics(diagnostic.rgba8());
            require(statistics.nonBlackPixels() > (long) WIDTH * HEIGHT / 6L,
                    "complex scene did not produce enough visible coverage: " + statistics);
            require(statistics.uniqueSampledColors() >= 24,
                    "complex scene lost material/light variation: " + statistics);

            Path png = Path.of("build", "reports", "gpuscene-complex-scene.png")
                    .toAbsolutePath().normalize();
            writePng(diagnostic, png);
            try (GpuFrameLease lease = awaitLease(renderer)) {
                require(lease.descriptor().frameSequence() == 0L
                                && lease.descriptor().renderedSceneRevision() == 0L
                                && lease.memoryHandle().state() == GpuFrameLease.HandleState.EXPORTED,
                        "public frame lease does not describe the rendered generation");
                // No consumer import occurred in this gate; close safely discards the fresh handle.
            }

            renderer.apply(animatedSceneUpdate());
            RenderFrameRequest updatedFrame = new RenderFrameRequest(
                    1L, 1L, WIDTH, HEIGHT, camera(), environment()
            );
            awaitFrameAdmission(renderer, updatedFrame);
            VulkanGpuSceneRenderingSession.DiagnosticFrame updatedDiagnostic = awaitDiagnostic(session);
            ImageStatistics updatedStatistics = statistics(updatedDiagnostic.rgba8());
            require(updatedStatistics.nonBlackPixels() > (long) WIDTH * HEIGHT / 6L,
                    "dynamic scene update lost visible coverage: " + updatedStatistics);
            require(updatedStatistics.checksum() != statistics.checksum(),
                    "material/instance/light update did not change the rendered image");
            Path updatedPng = Path.of("build", "reports", "gpuscene-complex-scene-updated.png")
                    .toAbsolutePath().normalize();
            writePng(updatedDiagnostic, updatedPng);
            try (GpuFrameLease lease = awaitLease(renderer)) {
                require(lease.descriptor().frameSequence() == 1L
                                && lease.descriptor().renderedSceneRevision() == 1L,
                        "updated public frame lease lost scene causality");
            }
            System.out.println("VulkanGpuSceneRenderingSessionNativeSelfTest passed: device="
                    + capability.preferredDevice().name()
                    + ", statistics=" + statistics
                    + ", updatedStatistics=" + updatedStatistics
                    + ", diagnosticPng=" + png
                    + ", updatedDiagnosticPng=" + updatedPng);
        }
    }

    private static void awaitFrameAdmission(VulkanRendererHost renderer, RenderFrameRequest frame)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (true) {
            try {
                renderer.submit(frame);
                return;
            } catch (SubmissionRejectedException converging) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("GPUScene did not converge before frame admission", converging);
                }
                renderer.diagnostics();
                Thread.sleep(1L);
            }
        }
    }

    private static VulkanGpuSceneRenderingSession.DiagnosticFrame awaitDiagnostic(
            VulkanGpuSceneRenderingSession session
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        VulkanGpuSceneRenderingSession.DiagnosticFrame diagnostic;
        do {
            diagnostic = session.captureLatestForAcceptance();
            if (diagnostic != null) return diagnostic;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("GPUScene frame did not complete before timeout");
    }

    private static GpuFrameLease awaitLease(VulkanRendererHost renderer) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        GpuFrameLease lease;
        do {
            lease = renderer.acquireLatestFrame();
            if (lease != null) return lease;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("completed public GPU frame lease was unavailable");
    }

    static SceneTransaction complexScene() {
        TextureAsset checker = checkerTexture(100L, 64, false);
        TextureAsset cutout = checkerTexture(101L, 32, true);
        MaterialAsset floor = material(200L, MaterialAsset.BlendMode.OPAQUE,
                0xffffffff, checker.id(), 0.72F, 0.05F, 0.0F, 1.5F, true);
        MaterialAsset red = material(201L, MaterialAsset.BlendMode.OPAQUE,
                0xff3030d8, -1L, 0.38F, 0.05F, 0.0F, 1.5F, true);
        MaterialAsset metal = material(202L, MaterialAsset.BlendMode.OPAQUE,
                0xffd0a040, -1L, 0.18F, 0.92F, 0.0F, 1.5F, true);
        MaterialAsset emissive = new MaterialAsset(
                203L, MaterialAsset.BlendMode.OPAQUE, 0xff202020,
                -1L, -1L, -1L, -1L, 0xff40a0ff,
                8.0F, 0.0F, 0.45F, 0.0F, 0.0F, 1.5F, true
        );
        MaterialAsset masked = new MaterialAsset(
                204L, MaterialAsset.BlendMode.MASKED, 0xffffffff,
                cutout.id(), -1L, -1L, -1L, 0,
                0.0F, 0.5F, 0.65F, 0.0F, 0.0F, 1.5F, true
        );
        MaterialAsset glass = material(205L, MaterialAsset.BlendMode.TRANSLUCENT,
                0x8060d8f0, -1L, 0.08F, 0.0F, 0.82F, 1.45F, true);

        MeshAsset ground = quad(300L, floor.id(), 8.0F, 8.0F, Plane.XZ);
        MeshAsset cube = cube(301L, red.id(), metal.id(), emissive.id());
        MeshAsset cutoutPanel = quad(302L, masked.id(), 2.6F, 2.6F, Plane.XY);
        MeshAsset glassPanel = quad(303L, glass.id(), 2.8F, 2.2F, Plane.XY);
        List<SceneInstance> instances = List.of(
                instance(400L, ground.id(), transform(0.0F, -1.2F, 0.0F, 1.0F, 1.0F, 1.0F)),
                instance(401L, cube.id(), transform(-2.4F, 0.0F, 0.0F, 1.15F, 1.15F, 1.15F)),
                instance(402L, cube.id(), transform(0.0F, -0.25F, -1.4F, 0.85F, 0.85F, 0.85F)),
                instance(403L, cube.id(), transform(2.3F, 0.35F, 0.3F, 1.5F, 1.5F, 1.5F)),
                instance(404L, cutoutPanel.id(), transform(-0.9F, 0.4F, 1.6F, 1.0F, 1.0F, 1.0F)),
                instance(405L, glassPanel.id(), transform(1.25F, 0.25F, 2.0F, 1.0F, 1.0F, 1.0F))
        );
        List<SceneLight> lights = List.of(
                new SceneLight(
                        500L, SceneLight.Type.DIRECTIONAL,
                        0.0D, 0.0D, 0.0D,
                        -0.350508F, -0.851234F, -0.390566F,
                        1.0F, 0.92F, 0.78F, 3.0F, 0.0F,
                        0.0F, 0.0F, true
                ),
                new SceneLight(
                        501L, SceneLight.Type.POINT,
                        -3.0D, 3.5D, 3.0D,
                        0.0F, 0.0F, 0.0F,
                        1.0F, 0.2F, 0.08F, 180.0F, 12.0F,
                        0.0F, 0.0F, true
                ),
                new SceneLight(
                        502L, SceneLight.Type.POINT,
                        3.5D, 2.5D, 1.0D,
                        0.0F, 0.0F, 0.0F,
                        0.08F, 0.25F, 1.0F, 220.0F, 13.0F,
                        0.0F, 0.0F, true
                )
        );
        return new SceneTransaction(
                0L,
                true,
                new SceneTransaction.Upserts(
                        List.of(checker, cutout),
                        List.of(floor, red, metal, emissive, masked, glass),
                        List.of(ground, cube, cutoutPanel, glassPanel),
                        instances,
                        lights
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static SceneTransaction animatedSceneUpdate() {
        MaterialAsset changedMaterial = material(
                201L, MaterialAsset.BlendMode.OPAQUE,
                0xffd84828, -1L, 0.24F, 0.18F, 0.0F, 1.5F, true
        );
        SceneInstance movedInstance = instance(
                402L, 301L, transform(0.25F, 0.15F, -0.75F, 1.05F, 1.05F, 1.05F)
        );
        SceneLight movedLight = new SceneLight(
                501L, SceneLight.Type.POINT,
                -1.5D, 4.25D, 2.0D,
                0.0F, 0.0F, 0.0F,
                0.25F, 1.0F, 0.18F, 240.0F, 14.0F,
                0.0F, 0.0F, true
        );
        return new SceneTransaction(
                1L,
                false,
                new SceneTransaction.Upserts(
                        List.of(),
                        List.of(changedMaterial),
                        List.of(),
                        List.of(movedInstance),
                        List.of(movedLight)
                ),
                SceneTransaction.Removals.empty()
        );
    }

    private static TextureAsset checkerTexture(long id, int extent, boolean cutout) {
        byte[] pixels = new byte[extent * extent * 4];
        for (int y = 0; y < extent; y++) {
            for (int x = 0; x < extent; x++) {
                boolean alternate = ((x / 8) ^ (y / 8)) % 2 != 0;
                int offset = (y * extent + x) * 4;
                pixels[offset] = (byte) (alternate ? 235 : 45);
                pixels[offset + 1] = (byte) (alternate ? 235 : 70);
                pixels[offset + 2] = (byte) (alternate ? 235 : 95);
                pixels[offset + 3] = (byte) (cutout && alternate ? 0 : 255);
            }
        }
        return new TextureAsset(
                id, extent, extent, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.LINEAR, pixels
        );
    }

    private static MaterialAsset material(
            long id,
            MaterialAsset.BlendMode blendMode,
            int baseColor,
            long texture,
            float roughness,
            float metallic,
            float transmission,
            float ior,
            boolean doubleSided
    ) {
        return new MaterialAsset(
                id, blendMode, baseColor,
                texture, -1L, -1L, -1L, 0,
                0.0F, blendMode == MaterialAsset.BlendMode.MASKED ? 0.5F : 0.0F,
                roughness, metallic, transmission, ior, doubleSided
        );
    }

    private static MeshAsset quad(long id, long material, float width, float height, Plane plane) {
        float halfWidth = width * 0.5F;
        float halfHeight = height * 0.5F;
        float[] positions;
        float[] normals;
        if (plane == Plane.XZ) {
            positions = new float[]{
                    -halfWidth, 0, -halfHeight, halfWidth, 0, -halfHeight,
                    halfWidth, 0, halfHeight, -halfWidth, 0, halfHeight
            };
            normals = repeat3(4, 0, 1, 0);
        } else {
            positions = new float[]{
                    -halfWidth, -halfHeight, 0, halfWidth, -halfHeight, 0,
                    halfWidth, halfHeight, 0, -halfWidth, halfHeight, 0
            };
            normals = repeat3(4, 0, 0, 1);
        }
        return new MeshAsset(
                id,
                positions,
                normals,
                new float[0],
                new float[]{0, 0, 4, 0, 4, 4, 0, 4},
                new int[]{0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff},
                new int[]{0, 1, 2, 0, 2, 3},
                new long[]{material, material}
        );
    }

    private static MeshAsset cube(long id, long sideMaterial, long topMaterial, long frontMaterial) {
        ArrayList<Float> positions = new ArrayList<>();
        ArrayList<Float> normals = new ArrayList<>();
        ArrayList<Float> uvs = new ArrayList<>();
        ArrayList<Integer> indices = new ArrayList<>();
        ArrayList<Long> materials = new ArrayList<>();
        addFace(positions, normals, uvs, indices, materials,
                new float[]{-1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1}, 0, 0, 1, frontMaterial);
        addFace(positions, normals, uvs, indices, materials,
                new float[]{1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1}, 0, 0, -1, sideMaterial);
        addFace(positions, normals, uvs, indices, materials,
                new float[]{-1, 1, 1, 1, 1, 1, 1, 1, -1, -1, 1, -1}, 0, 1, 0, topMaterial);
        addFace(positions, normals, uvs, indices, materials,
                new float[]{-1, -1, -1, 1, -1, -1, 1, -1, 1, -1, -1, 1}, 0, -1, 0, sideMaterial);
        addFace(positions, normals, uvs, indices, materials,
                new float[]{1, -1, 1, 1, -1, -1, 1, 1, -1, 1, 1, 1}, 1, 0, 0, topMaterial);
        addFace(positions, normals, uvs, indices, materials,
                new float[]{-1, -1, -1, -1, -1, 1, -1, 1, 1, -1, 1, -1}, -1, 0, 0, sideMaterial);
        return new MeshAsset(
                id,
                floats(positions),
                floats(normals),
                new float[0],
                floats(uvs),
                new int[0],
                integers(indices),
                longs(materials)
        );
    }

    private static void addFace(
            List<Float> positions,
            List<Float> normals,
            List<Float> uvs,
            List<Integer> indices,
            List<Long> materials,
            float[] face,
            float nx,
            float ny,
            float nz,
            long material
    ) {
        int base = positions.size() / 3;
        for (float value : face) positions.add(value);
        for (float value : repeat3(4, nx, ny, nz)) normals.add(value);
        for (float value : new float[]{0, 0, 1, 0, 1, 1, 0, 1}) uvs.add(value);
        for (int value : new int[]{0, 1, 2, 0, 2, 3}) indices.add(base + value);
        materials.add(material);
        materials.add(material);
    }

    private static SceneInstance instance(long id, long mesh, AffineTransform transform) {
        return new SceneInstance(id, mesh, transform, SceneInstance.Mobility.STATIC, 0xff, true);
    }

    private static AffineTransform transform(float x, float y, float z, float sx, float sy, float sz) {
        return new AffineTransform(new float[]{
                sx, 0, 0, x,
                0, sy, 0, y,
                0, 0, sz, z
        });
    }

    static CameraState camera() {
        float forwardY = -0.24253562F;
        float forwardZ = -0.9701425F;
        return new CameraState(
                0.0D, 3.0D, 9.0D,
                0.0F, forwardY, forwardZ,
                1.0F, 0.0F, 0.0F,
                0.0F, -forwardZ, forwardY,
                1.0F, 0.5625F
        );
    }

    static EnvironmentState environment() {
        return new EnvironmentState(
                0.08F, 0.14F, 0.24F, 0.65F,
                -0.350508F, -0.851234F, -0.390566F,
                1.0F, 0.94F, 0.82F, 1.8F,
                new EnvironmentState.Medium(
                        0.03F, 0.018F, 0.012F,
                        0.006F, 0.009F, 0.014F,
                        0.025F, 1.0F
                )
        );
    }

    private static ImageStatistics statistics(byte[] rgba8) {
        long nonBlack = 0L;
        long checksum = 0xcbf29ce484222325L;
        java.util.HashSet<Integer> sampled = new java.util.HashSet<>();
        for (int offset = 0; offset < rgba8.length; offset += 4) {
            int color = (Byte.toUnsignedInt(rgba8[offset]) << 16)
                    | (Byte.toUnsignedInt(rgba8[offset + 1]) << 8)
                    | Byte.toUnsignedInt(rgba8[offset + 2]);
            if (color != 0) nonBlack++;
            if ((offset & 255) == 0) sampled.add(color);
            for (int component = 0; component < 4; component++) {
                checksum ^= Byte.toUnsignedInt(rgba8[offset + component]);
                checksum *= 0x100000001b3L;
            }
        }
        return new ImageStatistics(nonBlack, sampled.size(), checksum);
    }

    private static void writePng(VulkanGpuSceneRenderingSession.DiagnosticFrame frame, Path path)
            throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
        byte[] rgba8 = frame.rgba8();
        int offset = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int red = Byte.toUnsignedInt(rgba8[offset++]);
                int green = Byte.toUnsignedInt(rgba8[offset++]);
                int blue = Byte.toUnsignedInt(rgba8[offset++]);
                int alpha = Byte.toUnsignedInt(rgba8[offset++]);
                image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        require(ImageIO.write(image, "png", path.toFile()), "PNG writer is unavailable");
    }

    private static float[] repeat3(int count, float x, float y, float z) {
        float[] result = new float[count * 3];
        for (int index = 0; index < count; index++) {
            result[index * 3] = x;
            result[index * 3 + 1] = y;
            result[index * 3 + 2] = z;
        }
        return result;
    }

    private static float[] floats(List<Float> values) {
        float[] result = new float[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = values.get(index);
        return result;
    }

    private static int[] integers(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = values.get(index);
        return result;
    }

    private static long[] longs(List<Long> values) {
        long[] result = new long[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = values.get(index);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Plane {
        XY,
        XZ
    }

    private record ImageStatistics(long nonBlackPixels, int uniqueSampledColors, long checksum) {
        @Override
        public String toString() {
            return "image{nonBlackPixels=" + nonBlackPixels
                    + ", uniqueSampledColors=" + uniqueSampledColors
                    + ", checksum=0x" + Long.toHexString(checksum) + "}";
        }
    }
}
