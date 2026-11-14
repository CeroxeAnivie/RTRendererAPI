package top.ceroxe.rt.renderer.feature;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneTemporalFrameResources;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;
import java.util.Optional;

/**
 * Borrowed resources exposed while a Vulkan feature records work after ray tracing.
 *
 * <p>The context is valid only for the duration of the callback. Feature implementations must not
 * retain the command buffer, stack, or image objects after returning; ownership remains with the
 * renderer frame slot and command recorder.</p>
 *
 * @param commandBuffer borrowed command buffer currently recording the frame
 * @param stack caller-owned temporary native allocation stack
 * @param traceOutput internal render-resolution ray-tracing output
 * @param reconstructionOutput linear-HDR output-resolution destination written by reconstruction
 * @param publishedOutput externally visible destination in the caller-requested output format
 * @param temporalResources borrowed temporal history images
 * @param denoisingResources optional validated denoising signal contract
 * @param reconstructionResources optional validated reconstruction input contract
 * @param frameGenerationResources optional validated presentation-time generation inputs
 * @param temporalInput optional exact camera facts shared by native temporal integrations
 * @param frameSequence non-negative frame identity
 * @param sceneRevision non-negative scene identity used by this frame
 * @param historyReset whether temporal consumers must discard prior history
 * @param extents paired internal render and published output dimensions
 */
public record VulkanFeatureFrameContext(
        VkCommandBuffer commandBuffer,
        MemoryStack stack,
        RtGpuImage traceOutput,
        RtGpuImage reconstructionOutput,
        RtGpuImage publishedOutput,
        GpuSceneTemporalFrameResources temporalResources,
        Optional<VulkanDenoisingResourceContract> denoisingResources,
        Optional<VulkanFrameReconstructionResourceContract> reconstructionResources,
        Optional<VulkanFrameGenerationResourceContract> frameGenerationResources,
        Optional<VulkanTemporalFrameInput> temporalInput,
        long frameSequence,
        long sceneRevision,
        boolean historyReset,
        VulkanFrameExtents extents
) {
    /** Validates ownership, identity, extent, and optional-resource dependencies. */
    public VulkanFeatureFrameContext {
        commandBuffer = Objects.requireNonNull(commandBuffer, "commandBuffer");
        stack = Objects.requireNonNull(stack, "stack");
        traceOutput = Objects.requireNonNull(traceOutput, "traceOutput");
        reconstructionOutput = Objects.requireNonNull(reconstructionOutput, "reconstructionOutput");
        publishedOutput = Objects.requireNonNull(publishedOutput, "publishedOutput");
        temporalResources = Objects.requireNonNull(temporalResources, "temporalResources");
        denoisingResources = Objects.requireNonNull(denoisingResources, "denoisingResources");
        reconstructionResources = Objects.requireNonNull(reconstructionResources, "reconstructionResources");
        frameGenerationResources = Objects.requireNonNull(frameGenerationResources, "frameGenerationResources");
        temporalInput = Objects.requireNonNull(temporalInput, "temporalInput");
        extents = Objects.requireNonNull(extents, "extents");
        if (frameSequence < 0L || sceneRevision < 0L) {
            throw new IllegalArgumentException("feature frame identity must not be negative");
        }
        if (traceOutput.width() != extents.renderWidth() || traceOutput.height() != extents.renderHeight()) {
            throw new IllegalArgumentException("feature trace output does not match the render extent");
        }
        if (publishedOutput.width() != extents.outputWidth() || publishedOutput.height() != extents.outputHeight()) {
            throw new IllegalArgumentException("feature published output does not match the output extent");
        }
        if (reconstructionOutput.width() != extents.outputWidth()
                || reconstructionOutput.height() != extents.outputHeight()) {
            throw new IllegalArgumentException("feature reconstruction output does not match the output extent");
        }
        if (reconstructionResources.isPresent()
                && reconstructionOutput.format() != org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
            throw new IllegalArgumentException("reconstruction output must use linear HDR RGBA16F");
        }
        if (denoisingResources.isPresent()) {
            VulkanDenoisingResourceContract resources = denoisingResources.orElseThrow();
            VulkanTemporalFrameInput input = requireTemporalInput(temporalInput, frameSequence, "denoising");
            if (!input.historyValid() && !historyReset) {
                throw new IllegalArgumentException("invalid temporal history requires a frame history reset");
            }
            if (resources.normalRoughness().width() != extents.renderWidth()
                    || resources.normalRoughness().height() != extents.renderHeight()) {
                throw new IllegalArgumentException("denoising resources do not match the dispatch extent");
            }
        }
        if (reconstructionResources.isPresent()) {
            VulkanFrameReconstructionResourceContract resources = reconstructionResources.orElseThrow();
            requireTemporalInput(temporalInput, frameSequence, "reconstruction");
            if (resources.inputColor().width() != extents.renderWidth()
                    || resources.inputColor().height() != extents.renderHeight()) {
                throw new IllegalArgumentException("reconstruction resources do not match the render extent");
            }
            if (resources.inputColor().handle() != traceOutput.image()
                    && denoisingResources.isEmpty()) {
                throw new IllegalArgumentException("reconstruction input color must be the ray-tracing output");
            }
        }
        if (frameGenerationResources.isPresent()) {
            VulkanFrameGenerationResourceContract resources = frameGenerationResources.orElseThrow();
            requireTemporalInput(temporalInput, frameSequence, "frame-generation");
            if (resources.depth().width() != extents.renderWidth()
                    || resources.depth().height() != extents.renderHeight()) {
                throw new IllegalArgumentException("frame-generation resources do not match the render extent");
            }
        }
    }

    private static VulkanTemporalFrameInput requireTemporalInput(
            Optional<VulkanTemporalFrameInput> input,
            long frameSequence,
            String consumer
    ) {
        VulkanTemporalFrameInput checked = input.orElseThrow(() ->
                new IllegalArgumentException(consumer + " resources require temporal frame input")
        );
        if (checked.request().sequence() != frameSequence) {
            throw new IllegalArgumentException(consumer + " input sequence does not match feature context");
        }
        return checked;
    }
}
