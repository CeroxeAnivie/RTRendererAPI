package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.AffineTransform;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDeviceTriangleBlasBuilder;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDeviceTlasBuilder;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.List;

/** GPUScene device-address geometry to native BLAS completion gate. */
public final class VulkanGenericBlasNativeSelfTest {
    private static final long COMPLETION_TIMEOUT_NANOS = 10_000_000_000L;

    private VulkanGenericBlasNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "generic BLAS gate requires hardware RT: " + capability.summary());

        try (VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);
             VulkanGpuScene scene = new VulkanGpuScene(new VulkanGpuSceneBuffers(
                     device.device(), device.allocator(), device.buildCommands()))) {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(initialScene());
            VulkanGpuScene.Admission admission = scene.submit(prepared.changeSet(), 0L);
            residency.commit(prepared);
            awaitActive(scene, admission.acceptedRevision());

            VulkanGpuSceneTransferQueue.BufferBinding positions = scene.requireBuffer(
                    VulkanGpuSceneUploadPlanner.Target.POSITIONS, 0L
            );
            VulkanGpuSceneTransferQueue.BufferBinding indices = scene.requireBuffer(
                    VulkanGpuSceneUploadPlanner.Target.INDICES, 0L
            );
            RtDeviceTriangleBlasBuilder.Geometry geometry = new RtDeviceTriangleBlasBuilder.Geometry(
                    positions.deviceAddress(),
                    indices.deviceAddress(),
                    3,
                    1,
                    true
            );
            try (RtDeviceTriangleBlasBuilder.PendingBuild pending = RtDeviceTriangleBlasBuilder.submit(
                    device.device(),
                    device.allocator(),
                    device.buildCommands(),
                    device.accelerationStructureScratchAlignment(),
                    List.of(geometry)
            ); RtAccelerationStructure blas = awaitBlas(pending)) {
                require(blas.handle() != 0L && blas.deviceAddress() != 0L,
                        "completed generic BLAS has invalid Vulkan handles");
                require(pending.geometryCount() == 1 && pending.primitiveCount() == 1L,
                        "generic BLAS submission statistics diverged");
                RtDeviceTlasBuilder.CompletedBuild completedTlas = buildTlas(device, blas);
                try (RtAccelerationStructure tlas = completedTlas.accelerationStructure()) {
                    require(tlas.handle() != 0L && tlas.deviceAddress() != 0L
                                    && completedTlas.instanceCount() == 3,
                            "completed generic TLAS has invalid native state");
                System.out.println("VulkanGenericBlasNativeSelfTest passed: device="
                        + capability.preferredDevice().name()
                        + ", blas=0x" + Long.toHexString(blas.handle())
                            + ", blasAddress=0x" + Long.toHexString(blas.deviceAddress())
                            + ", tlas=0x" + Long.toHexString(tlas.handle())
                            + ", tlasAddress=0x" + Long.toHexString(tlas.deviceAddress()));
                }
            }
        }
    }

    private static void awaitActive(VulkanGpuScene scene, long revision) throws InterruptedException {
        long deadline = System.nanoTime() + COMPLETION_TIMEOUT_NANOS;
        do {
            if (scene.poll(0L).activeRevision() >= revision) return;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("GPUScene geometry transfer did not complete before BLAS build");
    }

    private static RtAccelerationStructure awaitBlas(RtDeviceTriangleBlasBuilder.PendingBuild pending)
            throws InterruptedException {
        long deadline = System.nanoTime() + COMPLETION_TIMEOUT_NANOS;
        do {
            RtAccelerationStructure completed = pending.completeIfReady();
            if (completed != null) return completed;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("generic device-address BLAS did not complete");
    }

    private static RtDeviceTlasBuilder.CompletedBuild buildTlas(
            VulkanDeviceRuntime device,
            RtAccelerationStructure blas
    ) throws InterruptedException {
        List<RtDeviceTlasBuilder.Instance> instances = List.of(
                instance(blas, -2.0F),
                instance(blas, 0.0F),
                instance(blas, 2.0F)
        );
        try (RtDeviceTlasBuilder.PendingBuild pending = RtDeviceTlasBuilder.submit(
                device.device(),
                device.allocator(),
                device.buildCommands(),
                device.accelerationStructureScratchAlignment(),
                instances
        )) {
            long deadline = System.nanoTime() + COMPLETION_TIMEOUT_NANOS;
            do {
                RtDeviceTlasBuilder.CompletedBuild completed = pending.completeIfReady();
                if (completed != null) return completed;
                Thread.sleep(1L);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("generic affine-instance TLAS did not complete");
        }
    }

    private static RtDeviceTlasBuilder.Instance instance(RtAccelerationStructure blas, float x) {
        return new RtDeviceTlasBuilder.Instance(
                blas.deviceAddress(),
                new AffineTransform(new float[]{
                        1.0F, 0.0F, 0.0F, x,
                        0.0F, 1.0F, 0.0F, 0.0F,
                        0.0F, 0.0F, 1.0F, 0.0F
                }),
                (int) (x + 2.0F),
                0xff
        );
    }

    private static SceneTransaction initialScene() {
        MaterialAsset material = new MaterialAsset(
                20L,
                MaterialAsset.BlendMode.OPAQUE,
                0xffc08040,
                -1L,
                -1L,
                -1L,
                -1L,
                0xff000000,
                1.0F,
                0.0F,
                0.65F,
                0.0F,
                0.0F,
                1.5F,
                true
        );
        MeshAsset mesh = new MeshAsset(
                30L,
                new float[]{-1.0F, -1.0F, 0.0F, 1.0F, -1.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                new float[]{0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                new float[0],
                new float[0],
                new int[0],
                new int[]{0, 1, 2},
                new long[]{20L}
        );
        return new SceneTransaction(
                0L,
                true,
                new SceneTransaction.Upserts(List.of(), List.of(material), List.of(mesh), List.of(), List.of()),
                SceneTransaction.Removals.empty()
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
