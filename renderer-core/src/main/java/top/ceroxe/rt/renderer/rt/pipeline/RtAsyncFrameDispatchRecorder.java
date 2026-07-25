package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;

import java.util.Objects;

/**
 * Records exactly one asynchronous RT frame submission.
 *
 * <p>This is intentionally a command-recording boundary, not a frame-lifecycle owner. The caller
 * retains frame-slot state transitions, external semaphore lifetime, and publication bookkeeping.
 * That split mirrors the RHI/RDG distinction: this class owns Vulkan command order, while the
 * pipeline owns the validity of the resources being recorded.</p>
 */
final class RtAsyncFrameDispatchRecorder {
    private static final String[] GPU_TIMESTAMP_CHECKPOINTS = {"start", "traceEnd", "frameEnd"};

    private RtAsyncFrameDispatchRecorder() {
    }

    static Result submit(Request request) {
        Objects.requireNonNull(request, "request");
        RtGpuTimestampPool.Capture gpuTimestamps = request.commandContext.acquireGpuTimestampCapture(
                "frame",
                GPU_TIMESTAMP_CHECKPOINTS
        );
        boolean transferred = false;
        try {
            RtCommandContext.AsyncSubmission submission = request.commandContext.submitOneTimeAsync(
                    (commandBuffer, stack) -> recordCommands(request, gpuTimestamps, commandBuffer, stack),
                    request.signalSemaphore
            );
            request.timing.recordSubmission(submission, request.dispatchStartNanos);
            Result result = new Result(
                    submission,
                    gpuTimestamps,
                    request.captureReadback ? VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL
            );
            transferred = true;
            return result;
        } finally {
            if (!transferred && gpuTimestamps != null) {
                gpuTimestamps.close();
            }
        }
    }

    private static void recordCommands(
            Request request,
            RtGpuTimestampPool.Capture gpuTimestamps,
            VkCommandBuffer commandBuffer,
            MemoryStack stack
    ) {
        if (gpuTimestamps != null) {
            gpuTimestamps.begin(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        }
        long stageStartNanos = stageStart(request);
        request.frameUniformRecorder.record(commandBuffer, stack);
        request.timing.record(RtFrameDispatchTiming.Stage.FRAME_UNIFORM, stageStartNanos);

        DynamicSceneUploadTiming dynamicSceneUploadTiming = request.dynamicSceneUploadRecorder.record(commandBuffer, stack);
        request.timing.recordValue(RtFrameDispatchTiming.Stage.DYNAMIC_SCENE_PACK, dynamicSceneUploadTiming.packNanos());
        request.timing.recordValue(RtFrameDispatchTiming.Stage.DYNAMIC_SCENE_COMMANDS, dynamicSceneUploadTiming.commandNanos());

        stageStartNanos = stageStart(request);
        if (request.traceImageLayout != VK10.VK_IMAGE_LAYOUT_GENERAL) {
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    stack,
                    request.traceImage.image(),
                    request.traceImageLayout,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    request.traceImageLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    request.traceImageLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED
                            ? VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                            : VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
            );
        }
        request.timing.record(RtFrameDispatchTiming.Stage.PRE_TRACE_BARRIERS, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordTraceRays(
                commandBuffer,
                stack,
                request.pipelineLayout,
                request.pipeline,
                request.descriptorSet,
                request.shaderBindingTableBuffer,
                request.shaderBindingTableBaseOffsetBytes,
                request.shaderBindingTableLayout,
                request.traceImage.width(),
                request.traceImage.height()
        );
        if (gpuTimestamps != null) {
            gpuTimestamps.write(
                    commandBuffer,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
            );
        }
        request.timing.record(RtFrameDispatchTiming.Stage.TRACE_COMMAND, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer, stack, request.traceImage.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        request.timing.record(RtFrameDispatchTiming.Stage.TRACE_TO_TRANSFER, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer, stack, request.outputImage.image(), request.outputImageLayout,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, outputSourceAccessMask(request.outputImageLayout),
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, outputSourceStageMask(request.outputImageLayout),
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        request.timing.record(RtFrameDispatchTiming.Stage.OUTPUT_TO_TRANSFER, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordImageBlit(commandBuffer, stack, request.traceImage, request.outputImage);
        request.timing.record(RtFrameDispatchTiming.Stage.IMAGE_BLIT, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer, stack, request.outputImage.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        );
        request.timing.record(RtFrameDispatchTiming.Stage.OUTPUT_TO_GENERAL, stageStartNanos);

        stageStartNanos = stageStart(request);
        RtFrameDispatchCommands.recordImageLayoutTransition(
                commandBuffer, stack, request.traceImage.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        );
        request.timing.record(RtFrameDispatchTiming.Stage.TRACE_TO_GENERAL, stageStartNanos);

        if (request.captureReadback) {
            stageStartNanos = stageStart(request);
            RtFrameDispatchCommands.recordFrameReadback(commandBuffer, stack, request.outputImage, request.frameSlot.readbackBuffer());
            request.timing.record(RtFrameDispatchTiming.Stage.READBACK_COMMAND, stageStartNanos);
        }
        if (request.captureGBuffer) {
            RtFrameDispatchCommands.recordDiagnosticGBufferReadback(
                    commandBuffer,
                    stack,
                    request.frameSlot.diagnosticGBuffer(),
                    request.frameSlot.diagnosticGBufferReadback()
            );
        }
        if (gpuTimestamps != null) {
            gpuTimestamps.write(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        }
    }

    private static long stageStart(Request request) {
        return request.profileDispatch ? System.nanoTime() : 0L;
    }

    private static int requireOutputLayout(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                 VK10.VK_IMAGE_LAYOUT_GENERAL -> layout;
            default -> throw new IllegalArgumentException("unsupported RT presentation output image layout: " + layout);
        };
    }

    private static int requireTraceLayout(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                 VK10.VK_IMAGE_LAYOUT_GENERAL -> layout;
            default -> throw new IllegalArgumentException("unsupported RT trace image layout: " + layout);
        };
    }

    private static int outputSourceAccessMask(int outputImageLayout) {
        return switch (outputImageLayout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> VK10.VK_ACCESS_SHADER_WRITE_BIT;
            default ->
                    throw new IllegalArgumentException("unsupported RT presentation output image layout: " + outputImageLayout);
        };
    }

    private static int outputSourceStageMask(int outputImageLayout) {
        return switch (outputImageLayout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
            default ->
                    throw new IllegalArgumentException("unsupported RT presentation output image layout: " + outputImageLayout);
        };
    }

    @FunctionalInterface
    interface FrameUniformRecorder {
        void record(VkCommandBuffer commandBuffer, MemoryStack stack);
    }

    @FunctionalInterface
    interface DynamicSceneUploadRecorder {
        DynamicSceneUploadTiming record(VkCommandBuffer commandBuffer, MemoryStack stack);
    }

    /**
     * CPU timing returned by the slot-local dynamic scene uploader.
     */
    record DynamicSceneUploadTiming(long packNanos, long commandNanos) {
        DynamicSceneUploadTiming {
            if (packNanos < 0L || commandNanos < 0L) {
                throw new IllegalArgumentException("dynamic scene upload timings must not be negative");
            }
        }
    }

    /**
     * Immutable command-recording input captured after the pipeline has admitted a frame slot.
     */
    static final class Request {
        private final RtCommandContext commandContext;
        private final RtPipelineFrameSlot frameSlot;
        private final RtGpuImage outputImage;
        private final RtGpuImage traceImage;
        private final int outputImageLayout;
        private final int traceImageLayout;
        private final long descriptorSet;
        private final long pipelineLayout;
        private final long pipeline;
        private final RtGpuBuffer shaderBindingTableBuffer;
        private final int shaderBindingTableBaseOffsetBytes;
        private final RtRayTracingPipelineProperties.ShaderBindingTableLayout shaderBindingTableLayout;
        private final boolean captureReadback;
        private final boolean captureGBuffer;
        private final boolean profileDispatch;
        private final long dispatchStartNanos;
        private final VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore;
        private final RtFrameDispatchTiming timing;
        private final FrameUniformRecorder frameUniformRecorder;
        private final DynamicSceneUploadRecorder dynamicSceneUploadRecorder;

        Request(
                RtCommandContext commandContext,
                RtPipelineFrameSlot frameSlot,
                RtGpuImage outputImage,
                RtGpuImage traceImage,
                int outputImageLayout,
                int traceImageLayout,
                long descriptorSet,
                long pipelineLayout,
                long pipeline,
                RtGpuBuffer shaderBindingTableBuffer,
                int shaderBindingTableBaseOffsetBytes,
                RtRayTracingPipelineProperties.ShaderBindingTableLayout shaderBindingTableLayout,
                boolean captureReadback,
                boolean captureGBuffer,
                boolean profileDispatch,
                long dispatchStartNanos,
                VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore,
                RtFrameDispatchTiming timing,
                FrameUniformRecorder frameUniformRecorder,
                DynamicSceneUploadRecorder dynamicSceneUploadRecorder
        ) {
            this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
            this.frameSlot = Objects.requireNonNull(frameSlot, "frameSlot");
            this.outputImage = Objects.requireNonNull(outputImage, "outputImage");
            this.traceImage = Objects.requireNonNull(traceImage, "traceImage");
            this.outputImageLayout = requireOutputLayout(outputImageLayout);
            this.traceImageLayout = requireTraceLayout(traceImageLayout);
            if (descriptorSet == 0L || pipelineLayout == 0L || pipeline == 0L) {
                throw new IllegalArgumentException("descriptor set, pipeline layout, and pipeline must be non-null handles");
            }
            this.descriptorSet = descriptorSet;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
            this.shaderBindingTableBuffer = Objects.requireNonNull(shaderBindingTableBuffer, "shaderBindingTableBuffer");
            if (shaderBindingTableBaseOffsetBytes < 0) {
                throw new IllegalArgumentException("shaderBindingTableBaseOffsetBytes must not be negative");
            }
            this.shaderBindingTableBaseOffsetBytes = shaderBindingTableBaseOffsetBytes;
            this.shaderBindingTableLayout = Objects.requireNonNull(shaderBindingTableLayout, "shaderBindingTableLayout");
            this.captureReadback = captureReadback;
            this.captureGBuffer = captureGBuffer;
            this.profileDispatch = profileDispatch;
            if (dispatchStartNanos < 0L) {
                throw new IllegalArgumentException("dispatchStartNanos must not be negative");
            }
            this.dispatchStartNanos = dispatchStartNanos;
            this.signalSemaphore = signalSemaphore;
            this.timing = Objects.requireNonNull(timing, "timing");
            this.frameUniformRecorder = Objects.requireNonNull(frameUniformRecorder, "frameUniformRecorder");
            this.dynamicSceneUploadRecorder = Objects.requireNonNull(dynamicSceneUploadRecorder, "dynamicSceneUploadRecorder");
        }
    }

    /**
     * The recorder result deliberately exposes only command submission facts and final image layouts.
     */
    record Result(
            RtCommandContext.AsyncSubmission submission,
            RtGpuTimestampPool.Capture gpuTimestamps,
            int outputImageLayout,
            int traceImageLayout
    ) {
        Result {
            Objects.requireNonNull(submission, "submission");
        }
    }
}
