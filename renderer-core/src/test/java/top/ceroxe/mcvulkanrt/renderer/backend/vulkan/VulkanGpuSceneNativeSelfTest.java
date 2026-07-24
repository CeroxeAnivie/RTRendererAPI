package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Arrays;
import java.util.List;

/** Real device allocation, sparse upload, fence activation, and descriptor bootstrap gate. */
public final class VulkanGpuSceneNativeSelfTest {
    private static final byte[] EXPECTED_PIXEL = {1, 2, 3, 4};
    private static final long COMPLETION_TIMEOUT_NANOS = 10_000_000_000L;

    private VulkanGpuSceneNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "native GPUScene gate requires hardware RT: " + capability.summary());

        try (VulkanDeviceRuntime device = VulkanDeviceRuntime.open(capability);
             VulkanGpuScene scene = new VulkanGpuScene(new VulkanGpuSceneBuffers(
                     device.device(), device.allocator(), device.buildCommands()))) {
            VulkanSceneResidency residency = new VulkanSceneResidency();
            VulkanSceneResidency.PreparedUpdate prepared = residency.prepare(initialScene());
            VulkanGpuScene.Admission admission = scene.submit(prepared.changeSet(), 0L);
            residency.commit(prepared);
            VulkanGpuScene.Snapshot active = awaitActive(scene, admission.acceptedRevision());

            for (VulkanGpuSceneUploadPlanner.Target target : VulkanGpuSceneUploadPlanner.Target.values()) {
                VulkanGpuSceneTransferQueue.BufferBinding binding = scene.requireBuffer(target, 0L);
                require(binding.capacityBytes() >= 4_096L,
                        "descriptor target did not retain bootstrap capacity: " + target);
            }
            VulkanGpuSceneTransferQueue.BufferBinding texturePixels = scene.requireBuffer(
                    VulkanGpuSceneUploadPlanner.Target.TEXTURE_PIXELS, 0L
            );
            byte[] uploaded = readDeviceBytes(device, texturePixels.buffer(), EXPECTED_PIXEL.length);
            require(Arrays.equals(uploaded, EXPECTED_PIXEL),
                    "device-local texture upload differs: " + Arrays.toString(uploaded));
            require(active.transfers().activeBuffers() == VulkanGpuSceneUploadPlanner.Target.values().length,
                    "not every GPUScene descriptor target became active");
            System.out.println("VulkanGpuSceneNativeSelfTest passed: device="
                    + capability.preferredDevice().name()
                    + ", activeRevision=" + active.activeRevision()
                    + ", buffers=" + active.transfers().activeBuffers()
                    + ", bytes=" + active.transfers().activeBytes());
        }
    }

    private static VulkanGpuScene.Snapshot awaitActive(VulkanGpuScene scene, long revision)
            throws InterruptedException {
        long deadline = System.nanoTime() + COMPLETION_TIMEOUT_NANOS;
        VulkanGpuScene.Snapshot snapshot;
        do {
            snapshot = scene.poll(0L);
            if (snapshot.activeRevision() >= revision) return snapshot;
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("GPUScene transfer did not complete: " + snapshot);
    }

    private static byte[] readDeviceBytes(
            VulkanDeviceRuntime device,
            long sourceBuffer,
            int byteCount
    ) {
        try (RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(
                device.device(), device.allocator(), byteCount,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
        )) {
            device.buildCommands().submitOneTime((commandBuffer, stack) -> {
                VkMemoryBarrier.Buffer sourceReady = VkMemoryBarrier.calloc(1, stack);
                sourceReady.get(0).sType$Default()
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT);
                VK10.vkCmdPipelineBarrier(
                        commandBuffer,
                        VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, sourceReady, null, null
                );
                VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
                copy.get(0).srcOffset(0L).dstOffset(0L).size(byteCount);
                VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, readback.buffer(), copy);
                VkMemoryBarrier.Buffer hostReady = VkMemoryBarrier.calloc(1, stack);
                hostReady.get(0).sType$Default()
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
                VK10.vkCmdPipelineBarrier(
                        commandBuffer,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_HOST_BIT,
                        0, hostReady, null, null
                );
            });
            return readback.readBytes(byteCount);
        }
    }

    private static SceneTransaction initialScene() {
        TextureAsset texture = new TextureAsset(
                10L, 1, 1, TextureAsset.ColorSpace.SRGB,
                TextureAsset.AddressMode.REPEAT, TextureAsset.AddressMode.REPEAT,
                TextureAsset.Filter.NEAREST, EXPECTED_PIXEL
        );
        return new SceneTransaction(
                0L, true,
                new SceneTransaction.Upserts(List.of(texture), List.of(), List.of(), List.of(), List.of()),
                SceneTransaction.Removals.empty()
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
