package top.ceroxe.rt.renderer.backend.vulkan;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.MediumRayQueryPipeline;
import static org.lwjgl.vulkan.VK10.*;

/** Real primary RT queries, packaged camera kernels and CUDA output consumed by Vulkan copy. */
public final class CudaCameraMediumNativeSelfTest {
    private static final int CAPACITY = 1024;
    private static final int WIDTH = 65, HEIGHT = 33;
    private static final float MAX_DISTANCE = 2.75f;
    private static final byte GUARD = 0x5a;

    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        var camera = CameraState.lookAt(0, 0, -2, 0, 0, 0).build();
        var medium = new Medium(new Rgb(.1f, .2f, .3f), Rgb.ZERO, Rgb.ZERO, 2, 1, 0);
        var request = RenderFrameRequest.builder(0, WIDTH, HEIGHT, camera)
                .environment(EnvironmentState.builder().cameraMedium(medium).build())
                .mediumTransport(MediumTransport.balanced().withMaxDistance(MAX_DISTANCE)).build();
        String source = CudaMediumSource.resource("camera_absorption.cu");
        long values = 0, hits = 0, misses = 0;
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, false);
             var cuda = CudaMediumRuntime.open(0);
             var scene = new Scene(device);
             var init = cuda.compileBatchKernel(source, "camera_medium_init");
             var resolve = cuda.compileBatchKernel(source, "camera_medium_resolve")) {
            for (int session = 0; session < 2; session++) {
                long outputBytes = CudaCameraMediumAbi.outputBytes(CAPACITY + 2);
                try (var rays = new CudaSharedRayFrame(device, cuda, CAPACITY, 2L << 20);
                     var output = CudaSharedRayFrame.storage(device, cuda, outputBytes, 2L << 20);
                     var query = new MediumRayQueryPipeline(device, scene.tlas(), rays);
                     var constants = cuda.allocate(CudaCameraMediumAbi.CONSTANT_BYTES);
                     var scratchOutput = cuda.allocate(CudaCameraMediumAbi.outputBytes(CAPACITY));
                     var readback = RtGpuBuffer.createHostVisibleBuffer(device.device(), device.allocator(),
                             outputBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
                     var arena = Arena.ofConfined()) {
                    require(output.capacity() == 0 && output.byteSize() == outputBytes, "storage extent/capacity");
                    require(output.deviceAddress() != 0 && rays.deviceAddress() != 0, "shared BDA");
                    for (int[] tile : new int[][]{{0, 258}, {600, 517}, {WIDTH * HEIGHT - 257, 257}}) {
                        int first = tile[0], count = tile[1];
                        byte[] packed = CudaCameraMediumAbi.constants(request, first, count, .125f, -.25f, .0001f);
                        var constantSlice = constants.slice(0, packed.length);
                        cuda.upload(constantSlice, arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, packed));
                        var scratchSlice = scratchOutput.slice(0, CudaCameraMediumAbi.outputBytes(count));
                        rays.exchangeBlocking(records -> cuda.launchBlocking(init, records, count, 128,
                                        constantSlice, scratchSlice), command -> query.record(command, count));
                        var rawRays = arena.allocate(MediumRayQueryAbi.bytes(count));
                        rays.nativeOnlyBlocking(records -> cuda.download(records, rawRays));
                        var data = rawRays.asByteBuffer().order(ByteOrder.nativeOrder());
                        double[] distances = new double[count];
                        for (int i = 0; i < count; i++) {
                            int at = i * 128, pixel = first + i;
                            require(data.getInt(at + 40) == pixel && data.getInt(at + 44) == 1
                                    && data.getInt(at + 76) == 1, "pixel identity and returned epoch");
                            double dx = data.getFloat(at + 16), dy = data.getFloat(at + 20), dz = data.getFloat(at + 24);
                            double expectedX = ((pixel % WIDTH + .5 + .125) / WIDTH * 2 - 1) * camera.tanHalfFovX();
                            double expectedY = -((pixel / WIDTH + .5 - .25) / HEIGHT * 2 - 1) * camera.tanHalfFovY();
                            double norm = Math.sqrt(expectedX * expectedX + expectedY * expectedY + 1);
                            require(Math.abs(dx + expectedX / norm) < 5e-6 && Math.abs(dy - expectedY / norm) < 5e-6
                                    && Math.abs(dz - 1 / norm) < 5e-6, "camera ray basis/FOV/jitter");
                            double planeT = 2 / dz;
                            boolean expectedHit = planeT <= MAX_DISTANCE && Math.abs(dx * planeT) <= 1.5
                                    && Math.abs(dy * planeT) <= 1;
                            float hit = data.getFloat(at + 48);
                            require((hit >= 0) == expectedHit, "analytic plane intersection classification");
                            if (expectedHit) {
                                require(Math.abs(hit - planeT) < 3e-5, "analytic plane distance"); hits++;
                            } else misses++;
                            distances[i] = expectedHit ? planeT : MAX_DISTANCE;
                        }
                        byte[] guards = new byte[(int) outputBytes]; Arrays.fill(guards, GUARD);
                        var guardMemory = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, guards);
                        output.exchangeBlocking(storage -> {
                            cuda.upload(storage, guardMemory);
                            var tileOutput = storage.slice(16, CudaCameraMediumAbi.outputBytes(count));
                            rays.nativeOnlyBlocking(records -> {
                                expectIllegal(() -> cuda.launchBlocking(resolve, records, count, 127, constantSlice, tileOutput));
                                expectIllegal(() -> cuda.launchBlocking(resolve, records, count, 128,
                                        constantSlice.slice(0, 95), tileOutput));
                                expectIllegal(() -> cuda.launchBlocking(resolve, records, count, 128,
                                        constantSlice, tileOutput.slice(0, tileOutput.byteSize() - 1)));
                                expectIllegal(() -> cuda.launchBlocking(resolve, records, count, 128, constantSlice, records));
                                expectIllegal(() -> cuda.launchBlocking(resolve, records, count, 128, constantSlice,
                                        storage.slice(17, CudaCameraMediumAbi.outputBytes(count))));
                                expectIllegal(() -> storage.slice(storage.byteSize(), 1));
                                cuda.launchBlocking(resolve, records, count, 128, constantSlice, tileOutput);
                            });
                        }, command -> {
                            try (var stack = MemoryStack.stackPush()) {
                                vkCmdCopyBuffer(command, output.buffer(), readback.buffer(),
                                        VkBufferCopy.calloc(1, stack).size(outputBytes));
                            }
                        });
                        byte[] result = readback.readBytes();
                        for (int i = 0; i < result.length; i++)
                            if (i < 16 || i >= 16 + count * 16) require(result[i] == GUARD, "output tile guard");
                        var rgba = ByteBuffer.wrap(result).order(ByteOrder.nativeOrder());
                        for (int i = 0; i < count; i++) {
                            for (int channel = 0; channel < 3; channel++) {
                                float actual = rgba.getFloat(16 + i * 16 + channel * 4);
                                double expected = Math.exp(-.2 * (channel + 1) * distances[i]);
                                require(Float.isFinite(actual) && Math.abs(actual - expected) < 4e-5, "Beer-Lambert output"); values++;
                            }
                            require(rgba.getFloat(16 + i * 16 + 12) == 1, "output alpha");
                        }
                    }
                }
                require(cuda.residentBytes() == 0, "shared/ordinary allocations released");
            }
        }
        require(hits > 0 && misses > 0, "hit/miss coverage");
        System.out.println("CudaCameraMediumNativeSelfTest passed: sessions=2 tiles=6 hits=" + hits
                + " misses=" + misses + " rgbValues=" + values + "; real RT, Vulkan output copy, guard, bounds, BDA");
    }

    private static final class Scene implements AutoCloseable {
        private final VulkanGpuScene scene;
        private final VulkanSceneAcceleration acceleration;
        Scene(VulkanDeviceRuntime device) throws Exception {
            scene = new VulkanGpuScene(new VulkanGpuSceneBuffers(device.device(), device.allocator(), device.buildCommands()));
            acceleration = new VulkanSceneAcceleration(device, scene);
            try {
                var material = MaterialAsset.builder(20).blendMode(MaterialAsset.BlendMode.OPAQUE)
                        .baseColorRgba8(-1).roughness(.5f).doubleSided(true).build();
                var mesh = MeshAsset.triangles(30, new float[]{-1.5f,-1,0, 1.5f,-1,0, 1.5f,1,0, -1.5f,1,0},
                        new int[]{0,1,2, 0,2,3}, 20);
                var update = new VulkanSceneResidency().prepare(SceneTransaction.builder(0).resetScene()
                        .upsert(material).upsert(mesh).upsert(SceneInstance.builder(40, 30).build()).build());
                scene.submit(update.changeSet(), 0);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (scene.poll(0).activeRevision() != 0) {
                    if (System.nanoTime() > deadline) throw new AssertionError("scene timeout"); Thread.sleep(1);
                }
                acceleration.submit(update.changeSet(), 0);
                while (acceleration.poll(0).activeRevision() != 0) {
                    if (System.nanoTime() > deadline) throw new AssertionError("TLAS timeout"); Thread.sleep(1);
                }
            } catch (Exception | Error failure) { close(); throw failure; }
        }
        long tlas() { return acceleration.requireActiveTlas(0).handle(); }
        public void close() { try { acceleration.close(); } finally { scene.close(); } }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expectIllegal(Runnable operation) {
        try { operation.run(); throw new AssertionError("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
