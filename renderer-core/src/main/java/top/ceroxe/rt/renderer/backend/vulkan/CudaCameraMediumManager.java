package top.ceroxe.rt.renderer.backend.vulkan;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalWallTimeline;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumRuntime;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPrimaryEmissionAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaPrimaryEmissionSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaSharedRayFrame;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.VulkanQueueHostSync;
import top.ceroxe.rt.renderer.rt.acceleration.RtTlasBuildPlan;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline;

/**
 * Session-owned blocking native primary transport. The shared storage is reused only after a
 * release on the frame queue, which follows the preceding frame's consumer on that same queue.
 * Frame slots borrow addresses until their trace submission completes; they never own imports.
 */
final class CudaCameraMediumManager implements AutoCloseable {
    private static final long ALLOCATION_ALLOWANCE = 65_536;
    private final ExecutorService nativeThread = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RTRenderer-native-medium");
        thread.setDaemon(true);
        return thread;
    });
    private final VulkanDeviceRuntime device;
    private final CudaMediumRuntime cuda;
    private final CudaMediumRuntime.BatchKernel resolve;
    private CudaSharedRayFrame transport;
    private int capacity;
    private boolean closed;

    CudaCameraMediumManager(VulkanDeviceRuntime device) {
        this.device = Objects.requireNonNull(device, "device");
        NativeState initialized;
        try {
            initialized = onNative(() -> {
                CudaMediumRuntime opened = CudaMediumRuntime.open(
                        Integer.getInteger("top.ceroxe.rt.medium.cuda.deviceOrdinal", 0));
                try {
                    return new NativeState(opened,
                            opened.compileBatchKernel(CudaPrimaryEmissionSource.source(),
                                    "primary_medium_emission_resolve"));
                } catch (RuntimeException | Error failure) {
                    try { opened.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            nativeThread.shutdown();
            throw failure;
        }
        cuda = initialized.cuda();
        resolve = initialized.resolve();
    }

    static int pixels(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > Integer.MAX_VALUE)
            throw unsupported("the native primary frame extent exceeds its integer ABI");
        return Math.toIntExact((long) width * height);
    }

    static int paths(int width, int height, int samples) {
        if (samples < 1 || samples > 8)
            throw unsupported("native primary transport requires one to eight spatial samples");
        long paths = (long) pixels(width, height) * samples;
        if (paths > Integer.MAX_VALUE)
            throw unsupported("the complete native primary sample batch exceeds its integer ABI");
        return (int) paths;
    }

    /** Conservative Vulkan admission before creating the dedicated exported allocations. */
    static long requiredBytes(int width, int height, int samples) {
        int count = paths(width, height, samples);
        return Math.addExact(transportBytes(count), ALLOCATION_ALLOWANCE);
    }

    private static long transportBytes(int count) {
        return Math.addExact(constantsOffset(count), CudaPrimaryEmissionAbi.CONSTANT_BYTES);
    }

    private static long constantsOffset(int count) {
        return Math.addExact(CudaPrimaryEmissionAbi.primaryBytes(count), CudaPrimaryEmissionAbi.outputBytes(count));
    }

    synchronized long requiredGrowthBytes(int width, int height, int samples) {
        checkOpen();
        return onNative(() -> paths(width, height, samples) <= capacity ? 0 : requiredBytes(width, height, samples));
    }

    synchronized long allocatedBytes() {
        checkOpen();
        return onNative(() -> transport == null ? 0 : transport.allocationBytes());
    }

    synchronized Prepared prepare(RenderFrameRequest frame, int width, int height,
                     GpuSceneRayTracingPipeline producer, int slot, RtTlasBuildPlan buildPlan) {
        checkOpen();
        return onNative(() -> prepareOnNative(frame, width, height, producer, slot, buildPlan));
    }

    private Prepared prepareOnNative(RenderFrameRequest frame, int width, int height,
                                     GpuSceneRayTracingPipeline producer, int slot, RtTlasBuildPlan buildPlan) {
        return prepareOnNative(frame, width, height, producer, slot, buildPlan, null);
    }

    @FunctionalInterface
    interface FinalConsumer {
        RtCommandContext.AsyncSubmission submit(Prepared prepared, long semaphore,
                Consumer<VkCommandBuffer> acquireOwnership);
    }

    synchronized RtCommandContext.AsyncSubmission submitBlocking(RenderFrameRequest frame, int width, int height,
            GpuSceneRayTracingPipeline producer, int slot, RtTlasBuildPlan buildPlan, FinalConsumer consumer) {
        checkOpen(); Objects.requireNonNull(consumer, "consumer");
        return onNative(() -> {
            RtCommandContext.AsyncSubmission[] submitted = new RtCommandContext.AsyncSubmission[1];
            try {
                prepareOnNative(frame, width, height, producer, slot, buildPlan, (prepared, semaphore, acquire) -> {
                    submitted[0] = consumer.submit(prepared, semaphore, acquire);
                    submitted[0].close();
                    return submitted[0];
                });
                return submitted[0];
            } catch (RuntimeException | Error failure) {
                if (submitted[0] != null) try { submitted[0].close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        });
    }

    private Prepared prepareOnNative(RenderFrameRequest frame, int width, int height,
            GpuSceneRayTracingPipeline producer, int slot, RtTlasBuildPlan buildPlan, FinalConsumer finalConsumer) {
        int pixels = pixels(width, height);
        int samples = frame.antiAliasing().samplesPerPixel();
        int count = paths(width, height, samples);
        ensureCapacity(count);
        byte[] frameConstants = CudaPrimaryEmissionAbi.constants(frame);
        long constantOffset = constantsOffset(count);
        long primaryBytes = CudaPrimaryEmissionAbi.primaryBytes(count);
        long primaryAddress = transport.deviceAddress();
        long attenuationAddress = Math.addExact(primaryAddress, primaryBytes);
        long emissionAddress = Math.addExact(attenuationAddress, CudaPrimaryEmissionAbi.emissionOffset(count));
        // One producer/release/native/acquire cycle owns disjoint hit and result ranges in one
        // allocation. The same queue orders this producer after every preceding frame consumer.
        Prepared prepared = new Prepared(primaryAddress, attenuationAddress, emissionAddress, pixels, samples);
        Consumer<VkCommandBuffer> produce = command -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // The tiny per-frame constants travel in the existing producer submission.
                // The ownership release below makes both this transfer and primary hits
                // visible to CUDA, without a separate host copy or default-stream wait.
                var upload = stack.malloc(frameConstants.length).put(frameConstants).flip();
                VK10.vkCmdUpdateBuffer(command, transport.buffer(), constantOffset, upload);
                // A frame-local destination must be built before this first real ray query.
                // Its transaction is still committed only after final frame publication.
                if (buildPlan != null) buildPlan.record(command, stack);
                // This allocation contains the complete frame, unlike the old 65536-item
                // scratch queue. One dispatch preserves every sample without rebinding
                // the same pipeline and descriptors for dozens of artificial tiles.
                producer.recordPrimaryQueueTrace(command, stack, slot, primaryAddress, 0, pixels);
            }
        };
        Consumer<CudaMediumRuntime.Slice> nativeStep = shared -> cuda.launchPrimaryEmissionBlocking(resolve,
                shared.slice(0, primaryBytes), count,
                shared.slice(constantOffset, CudaPrimaryEmissionAbi.CONSTANT_BYTES),
                shared.slice(primaryBytes, CudaPrimaryEmissionAbi.outputBytes(count)));
        if (finalConsumer == null) transport.produceThenExchangeBlocking(produce, nativeStep, ignored -> {});
        else transport.produceThenConsumeBlocking(produce, nativeStep,
                (semaphore, acquire) -> finalConsumer.submit(prepared, semaphore, acquire));
        return prepared;
    }

    private void ensureCapacity(int count) {
        if (count <= capacity) return;
        long requested = Math.addExact(transportBytes(count), ALLOCATION_ALLOWANCE);
        if (requested > cuda.maxResidentBytes())
            throw unsupported("the complete native primary frame exceeds the CUDA resident budget; "
                    + "paths are never truncated");
        // Old addresses can still belong to a previously submitted consumer. Drain before resize.
        if (transport != null) drain();
        closeStorage();
        try {
            transport = CudaSharedRayFrame.storage(device, cuda, transportBytes(count),
                    cuda.maxResidentBytes() - cuda.residentBytes());
            capacity = count;
        } catch (RuntimeException | Error failure) {
            try { closeStorage(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void drain() {
        int result = VulkanQueueHostSync.waitIdle(device.frameQueue());
        if (result != VK10.VK_SUCCESS)
            throw new IllegalStateException("drain native primary consumers: VkResult=" + result);
    }

    private void closeStorage() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
        capacity = 0;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("native primary manager is closed");
    }

    private <T> T onNative(Callable<T> operation) {
        int wallTask=OpticalWallTimeline.enter(OpticalWallTimeline.Stage.PRIMARY_MEDIUM_NATIVE);
        try {
        var submitted = nativeThread.submit(OpticalWallTimeline.awaited(operation));
        boolean interrupted = false;
        try {
            for (;;) {
                try { return submitted.get(); }
                catch (InterruptedException interruption) {
                    // A caller cancellation cannot release imports while the blocking GPU work
                    // is in progress. Restore its interrupt only after native completion.
                    interrupted = true;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    if (failure.getCause() instanceof Error error) throw error;
                    throw new IllegalStateException("native primary operation failed", failure.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }

        }finally{OpticalWallTimeline.leave(wallTask);}
    }

    private static FrameValidationException unsupported(String message) {
        return new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT, message);
    }

    @Override public synchronized void close() {
        if (closed) return;
        try {
            onNative(() -> {
                Throwable failure = null;
                try { drain(); }
                catch (RuntimeException | Error exception) { failure = exception; }
                try { closeStorage(); }
                catch (RuntimeException | Error exception) {
                    if (failure == null) failure = exception; else failure.addSuppressed(exception);
                }
                try { cuda.close(); }
                catch (RuntimeException | Error exception) {
                    if (failure == null) failure = exception; else failure.addSuppressed(exception);
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                return null;
            });
        } finally {
            closed = true;
            nativeThread.shutdown();
        }
    }

    private record NativeState(CudaMediumRuntime cuda, CudaMediumRuntime.BatchKernel resolve) { }

    record Prepared(long primaryAddress, long attenuationAddress, long emissionAddress, int pixels, int samples,
                    boolean externalPrimary) {
        Prepared(long primaryAddress,long attenuationAddress,long emissionAddress,int pixels,int samples) {
            this(primaryAddress,attenuationAddress,emissionAddress,pixels,samples,false);
        }
        void recordConsumer(VkCommandBuffer command, MemoryStack stack,
                            GpuSceneRayTracingPipeline consumer, int slot) {
            if(externalPrimary) {
                var dependency=org.lwjgl.vulkan.VkMemoryBarrier.calloc(1,stack).sType$Default()
                        .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
                VK10.vkCmdPipelineBarrier(command,
                        org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        0,dependency,null,null);
            }
            consumer.recordPrimaryQueueTrace(command, stack, slot, primaryAddress,
                    0, pixels, attenuationAddress, emissionAddress);
        }
    }
}
