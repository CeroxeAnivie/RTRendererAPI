package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import top.ceroxe.rt.renderer.api.BufferBarrier;
import top.ceroxe.rt.renderer.api.BeginRenderPassCommand;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderPipelineStage;
import top.ceroxe.rt.renderer.api.RenderResourceAccess;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.RenderingSemanticCapabilities;
import top.ceroxe.rt.renderer.api.RendererDeviceException;
import top.ceroxe.rt.renderer.api.ResourceGenerationKey;
import top.ceroxe.rt.renderer.api.ResourceResidencyEvidence;
import top.ceroxe.rt.renderer.api.ResourceTransactionEvidence;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Fence-backed executor for the deliberately narrow first Vulkan generic-command subset.
 *
 * <p>It owns neither the device nor the retained-scene frame ring. Each accepted transaction owns
 * a dedicated one-time submission and bounded staging buffers until its fence completes, so a
 * command sequence is never overwritten by a later submission.</p>
 */
final class VulkanGenericCommandSession implements AutoCloseable {
    private static final String TIMING_LABEL = "genericBufferCommands";

    private final VulkanDeviceRuntime device;
    private final int maximumInFlightTransactions;
    private final VulkanGenericResourceRegistry resources;
    private final VulkanGenericComputePipelines computePipelines;
    private final VulkanGenericGraphicsPipelines graphicsPipelines;
    private final Map<Long, CommandExecutionEvidence> commandEvidence = new LinkedHashMap<>();
    private final Map<Long, PendingSubmission> pending = new LinkedHashMap<>();
    private long latestCommandSequence = -1L;
    private long latestCompletedSequence = -1L;
    private boolean deviceFailed;
    private boolean closed;

    VulkanGenericCommandSession(VulkanDeviceRuntime device, int maximumInFlightTransactions) {
        this.device = Objects.requireNonNull(device, "device");
        if (maximumInFlightTransactions <= 0) {
            throw new IllegalArgumentException("maximumInFlightTransactions must be positive");
        }
        this.maximumInFlightTransactions = maximumInFlightTransactions;
        this.resources = new VulkanGenericResourceRegistry(device);
        this.computePipelines = new VulkanGenericComputePipelines(device, resources);
        this.graphicsPipelines = new VulkanGenericGraphicsPipelines(
                device.device(), resources, device.maxBoundDescriptorSets()
        );
    }

    RenderingSemanticCapabilities capabilities() {
        RenderingSemanticCapabilities.Builder result = RenderingSemanticCapabilities.builder();
        executable(result, RenderingSemanticCapabilities.Feature.VERSIONED_BUFFERS,
                "Vulkan buffer generations, staging upload, copy and fence evidence are implemented");
        executable(result, RenderingSemanticCapabilities.Feature.VERSIONED_TEXTURES,
                "Vulkan texture generations use VMA-backed storage with typed residency and safe unused retirement");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_UPLOAD,
                "texture upload uses staging copies and image-layout transitions retained through fence completion");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_COPY,
                "texture-to-texture copy is executable for GPU-ready source and uninitialized destination generations");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_BARRIERS,
                "typed texture barriers record exact aspect/mip/layer image barriers with submission-local layout planning");
        executable(result, RenderingSemanticCapabilities.Feature.COMPUTE_PIPELINES,
                "SPIR-V compute modules, descriptor bindings, push constants and dispatch are recorded on the generic queue");
        if (device.dynamicRenderingEnabled()) {
            executable(result, RenderingSemanticCapabilities.Feature.GRAPHICS_PIPELINES,
                    "SPIR-V graphics modules and fixed-function pipeline state compile into Vulkan graphics pipelines");
            executable(result, RenderingSemanticCapabilities.Feature.RENDER_PASSES,
                    "dynamic-rendering passes consume typed attachments, load/store operations, and explicit resolves");
            executable(result, RenderingSemanticCapabilities.Feature.DIRECT_DRAW,
                    "direct non-indexed draws are recorded with exact vertex and instance parameters");
            executable(result, RenderingSemanticCapabilities.Feature.INDEXED_DRAW,
                    "direct indexed draws are recorded with exact index format, base vertex, and instance parameters");
            executable(result, RenderingSemanticCapabilities.Feature.INSTANCED_DRAW,
                    "direct draws preserve explicit instance counts, first instance, and per-instance vertex bindings");
        }
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_UPLOAD,
                "buffer upload uses staging copies retained through fence completion");
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_COPY,
                "buffer-to-buffer copy is executable for GPU-ready exact generations");
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_BARRIERS,
                "buffer memory barriers are executable without queue-family ownership transfer");
        return result.build();
    }

    ResourceTransactionEvidence submitResources(RenderResourceTransaction transaction) {
        requireOpen();
        pump();
        return resources.apply(transaction, latestCompletedSequence);
    }

    Optional<ResourceResidencyEvidence> resourceEvidence(ResourceGenerationKey generation) {
        requireOpen();
        pump();
        return Optional.ofNullable(resources.evidence(generation));
    }

    CommandExecutionEvidence submit(RenderCommandTransaction transaction) {
        requireOpen();
        RenderCommandTransaction checked = Objects.requireNonNull(transaction, "transaction");
        if (deviceFailed) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.DEVICE_LOST,
                    "generic Vulkan command lane is terminal after a device failure; recreate the renderer");
        }
        if (!device.dynamicRenderingEnabled()
                && checked.commands().stream().anyMatch(command -> command instanceof BeginRenderPassCommand)) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,
                    "Vulkan dynamic rendering was not enabled on this device");
        }
        pump();
        if (checked.sequence() <= latestCommandSequence || commandEvidence.containsKey(checked.sequence())) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.COMMAND_VALIDATION_FAILED,
                    "command transaction sequence must strictly advance: latest=" + latestCommandSequence);
        }
        if (pending.size() >= maximumInFlightTransactions) {
            return blocked(checked.sequence(), "generic command frame ring is full");
        }
        final VulkanGenericCommandPlan plan;
        try {
            plan = VulkanGenericCommandPlan.compile(resources, computePipelines, graphicsPipelines, checked);
        } catch (UnsupportedOperationException unsupported) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE, unsupported.getMessage());
        } catch (VulkanGenericPipelineCompilationException pipelineFailure) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.PIPELINE_COMPILATION_FAILED,
                    message(pipelineFailure, "generic Vulkan pipeline compilation failed"));
        } catch (RuntimeException invalid) {
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.COMMAND_VALIDATION_FAILED,
                    message(invalid, "generic command validation failed"));
        }

        ArrayList<StagingUpload> staging = new ArrayList<>();
        RtCommandContext.AsyncSubmission submission = null;
        VulkanGenericTextureLayoutUpdates textureLayouts = new VulkanGenericTextureLayoutUpdates();
        try {
            for (VulkanGenericCommandPlan.Action action : plan.actions()) {
                if (action instanceof VulkanGenericCommandPlan.Write write) {
                    staging.add(StagingUpload.create(device, write));
                } else if (action instanceof VulkanGenericCommandPlan.WriteTexture write) {
                    staging.add(StagingUpload.createTexture(device, write));
                } else if (action instanceof VulkanGenericCommandPlan.BindComputeBindings bindings) {
                    computePipelines.updateBindings(bindings.pipeline(), bindings.command().bindingSet());
                } else if (action instanceof VulkanGenericCommandPlan.BindGraphicsBindings bindings) {
                    graphicsPipelines.updateBindings(bindings.pipeline(), bindings.command().bindingSet());
                }
            }
            List<StagingUpload> immutableStaging = List.copyOf(staging);
            submission = device.frameCommands().submitTimedOneTimeAsync(
                    TIMING_LABEL, (commandBuffer, stack) -> record(
                            commandBuffer, stack, plan, immutableStaging, textureLayouts
                    )
            );
            resources.markRecorded(plan.writes(), checked.sequence());
            resources.markTextureRecorded(plan.textureWrites(), checked.sequence());
            resources.noteReadUse(plan.reads(), checked.sequence());
            resources.noteTextureReadUse(plan.textureReads(), checked.sequence());
            textureLayouts.commit();
            latestCommandSequence = checked.sequence();
            CommandExecutionEvidence evidence = new CommandExecutionEvidence(
                    checked.sequence(), CommandExecutionEvidence.Outcome.RECORDED,
                    CommandExecutionEvidence.Reason.NONE, OptionalLong.of(checked.sequence()), Optional.empty(), 0L,
                    "generic Vulkan buffer commands recorded and submitted"
            );
            commandEvidence.put(checked.sequence(), evidence);
            pending.put(checked.sequence(), new PendingSubmission(
                    checked.sequence(), submission, immutableStaging, plan.writes(), plan.textureWrites(), plan.outputResource()));
            submission = null;
            staging = null;
            return evidence;
        } catch (RuntimeException failure) {
            if (failure instanceof RendererDeviceException deviceFailure) {
                deviceFailed = true;
                return rejected(checked.sequence(), CommandExecutionEvidence.Reason.DEVICE_LOST,
                        deviceFailure.operation() + " failed with native result " + deviceFailure.nativeResult()
                                + "; recovery=" + deviceFailure.recoveryAction());
            }
            return rejected(checked.sequence(), CommandExecutionEvidence.Reason.SYNCHRONIZATION_FAILED,
                    message(failure, "Vulkan generic command submission failed"));
        } finally {
            if (submission != null) submission.close();
            if (staging != null) closeStaging(staging);
        }
    }

    Optional<CommandExecutionEvidence> commandEvidence(long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("command sequence must not be negative");
        requireOpen();
        pump();
        return Optional.ofNullable(commandEvidence.get(sequence));
    }

    void pump() {
        requireOpen();
        Iterator<PendingSubmission> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            PendingSubmission current = iterator.next();
            final boolean complete;
            try {
                complete = current.submission().pollComplete();
            } catch (RendererDeviceException deviceFailure) {
                deviceFailed = true;
                commandEvidence.put(current.sequence(), rejected(current.sequence(), CommandExecutionEvidence.Reason.DEVICE_LOST,
                        deviceFailure.operation() + " failed with native result " + deviceFailure.nativeResult()
                                + "; recovery=" + deviceFailure.recoveryAction()));
                closeStaging(current.staging());
                iterator.remove();
                continue;
            }
            if (!complete) continue;
            try {
                resources.markCompleted(current.writes(), current.sequence());
                resources.markTextureCompleted(current.textureWrites(), current.sequence());
                latestCompletedSequence = Math.max(latestCompletedSequence, current.sequence());
                boolean output = current.outputResource().isPresent();
                commandEvidence.put(current.sequence(), new CommandExecutionEvidence(
                        current.sequence(), output
                                ? CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED
                                : CommandExecutionEvidence.Outcome.GPU_COMPLETED,
                        CommandExecutionEvidence.Reason.NONE, OptionalLong.of(current.sequence()),
                        current.outputResource(), 0L,
                        output ? "generic Vulkan render pass fence completed and a stored attachment is available"
                                : "generic Vulkan command fence completed"
                ));
            } finally {
                closeStaging(current.staging());
                iterator.remove();
            }
        }
    }

    private void record(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan plan,
            List<StagingUpload> staging,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        int writeIndex = 0;
        for (VulkanGenericCommandPlan.Action action : plan.actions()) {
            if (action instanceof VulkanGenericCommandPlan.BeginPass
                    || action instanceof VulkanGenericCommandPlan.EndPass
                    || action instanceof VulkanGenericCommandPlan.BindGraphics
                    || action instanceof VulkanGenericCommandPlan.BindGraphicsBindings
                    || action instanceof VulkanGenericCommandPlan.GraphicsPushConstants
                    || action instanceof VulkanGenericCommandPlan.BindVertex
                    || action instanceof VulkanGenericCommandPlan.BindIndex
                    || action instanceof VulkanGenericCommandPlan.ViewportAction
                    || action instanceof VulkanGenericCommandPlan.ScissorAction
                    || action instanceof VulkanGenericCommandPlan.Draw
                    || action instanceof VulkanGenericCommandPlan.DrawIndexed
                    || action instanceof VulkanGenericCommandPlan.MultiDraw
                    || action instanceof VulkanGenericCommandPlan.MultiDrawIndexed
                    || action instanceof VulkanGenericCommandPlan.Indirect) {
                VulkanGenericGraphicsRecorder.record(commandBuffer, stack, List.of(action), textureLayouts);
                continue;
            }
            switch (action) {
                case VulkanGenericCommandPlan.BindCompute bind -> VK10.vkCmdBindPipeline(
                        commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bind.pipeline().pipeline()
                );
                case VulkanGenericCommandPlan.BindComputeBindings bind -> recordComputeBindings(
                        commandBuffer, stack, bind
                );
                case VulkanGenericCommandPlan.PushConstants push -> VK10.vkCmdPushConstants(
                        commandBuffer, push.pipeline().layout(), VK10.VK_SHADER_STAGE_COMPUTE_BIT,
                        push.command().offsetBytes(), push.command().data().bytes()
                );
                case VulkanGenericCommandPlan.Dispatch dispatch -> VK10.vkCmdDispatch(
                        commandBuffer, dispatch.command().groupsX(), dispatch.command().groupsY(), dispatch.command().groupsZ()
                );
                case VulkanGenericCommandPlan.Write write -> {
                    StagingUpload upload = staging.get(writeIndex++);
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                            .srcOffset(0L)
                            .dstOffset(write.destinationOffset())
                            .size(upload.byteCount());
                    VK10.vkCmdCopyBuffer(commandBuffer, upload.buffer().buffer(), write.destination().buffer().buffer(), region);
                }
                case VulkanGenericCommandPlan.WriteTexture write -> recordTextureWrite(
                        commandBuffer, stack, write, staging.get(writeIndex++), textureLayouts
                );
                case VulkanGenericCommandPlan.CopyTexture copy -> recordTextureCopy(
                        commandBuffer, stack, copy, textureLayouts
                );
                case VulkanGenericCommandPlan.CopyTextureRegion copy -> recordTextureRegionCopy(
                        commandBuffer, stack, copy, textureLayouts
                );
                case VulkanGenericCommandPlan.Copy copy -> {
                    VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                            .srcOffset(copy.sourceOffset())
                            .dstOffset(copy.destinationOffset())
                            .size(copy.byteCount());
                    VK10.vkCmdCopyBuffer(
                            commandBuffer, copy.source().buffer().buffer(), copy.destination().buffer().buffer(), region
                    );
                }
                case VulkanGenericCommandPlan.Barrier barrier -> {
                    recordBarriers(commandBuffer, stack, barrier.buffers());
                    recordTextureBarriers(commandBuffer, stack, barrier.textures(), textureLayouts);
                }
                default -> throw new IllegalStateException("unhandled generic Vulkan command action: "
                        + action.getClass().getSimpleName());
            }
        }
    }

    private static void recordComputeBindings(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.BindComputeBindings bind
    ) {
        VulkanGenericDescriptorSetBank descriptors = bind.pipeline().descriptors();
        if (descriptors == null) return;
        List<Integer> groups = descriptors.groups();
        java.nio.LongBuffer sets = stack.mallocLong(groups.size());
        for (int group : groups) sets.put(descriptors.set(group));
        sets.flip();
        java.nio.IntBuffer dynamicOffsets = stack.mallocInt(bind.command().dynamicOffsets().size());
        for (long offset : bind.command().dynamicOffsets()) dynamicOffsets.put((int) offset);
        dynamicOffsets.flip();
        VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                bind.pipeline().layout(), 0, sets, dynamicOffsets);
    }

    private static void recordTextureWrite(
            VkCommandBuffer commandBuffer, MemoryStack stack, VulkanGenericCommandPlan.WriteTexture write,
            StagingUpload upload, VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var record = write.destination();
        var command = write.command();
        TextureSubresourceRange range = uploadRange(record, command.destination().range(), command.origin().z(),
                command.extent().depth());
        transitionTextureForTransfer(commandBuffer, stack, record, range, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                // The API permits byte pitches which Vulkan cannot express as a texel count. The
                // staging owner has therefore compacted this one region row-by-row; zero requests
                // Vulkan's tightly-packed interpretation and never truncates a caller pitch.
                .bufferOffset(0L)
                .bufferRowLength(0)
                .bufferImageHeight(0);
        region.imageSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(command.destination().range().aspect()))
                .mipLevel(command.destination().range().baseMipLevel())
                .baseArrayLayer(command.destination().range().baseArrayLayer() + command.origin().z())
                .layerCount(record.descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : command.extent().depth());
        region.imageOffset().set(command.origin().x(), command.origin().y(),
                record.descriptor().dimension() == TextureDimension.TEXTURE_3D ? command.origin().z() : 0);
        region.imageExtent().set(command.extent().width(), command.extent().height(),
                record.descriptor().dimension() == TextureDimension.TEXTURE_3D ? command.extent().depth() : 1);
        VK10.vkCmdCopyBufferToImage(commandBuffer, upload.buffer().buffer(), record.image().image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private static void recordTextureCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.CopyTexture copy,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var sourceRange = copy.command().source().range();
        var destinationRange = copy.command().destination().range();
        transitionTextureForTransfer(commandBuffer, stack, copy.source(), sourceRange,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_ACCESS_TRANSFER_READ_BIT, textureLayouts);
        transitionTextureForTransfer(commandBuffer, stack, copy.destination(), destinationRange,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        VkImageCopy.Buffer regions = VkImageCopy.calloc(sourceRange.mipLevelCount(), stack);
        for (int index = 0; index < sourceRange.mipLevelCount(); index++) {
            int sourceMip = sourceRange.baseMipLevel() + index;
            int destinationMip = destinationRange.baseMipLevel() + index;
            VkImageCopy region = regions.get(index);
            region.srcSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(sourceRange.aspect()))
                    .mipLevel(sourceMip).baseArrayLayer(sourceRange.baseArrayLayer())
                    .layerCount(copy.source().descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : sourceRange.arrayLayerCount());
            region.dstSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(destinationRange.aspect()))
                    .mipLevel(destinationMip).baseArrayLayer(destinationRange.baseArrayLayer())
                    .layerCount(copy.destination().descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : destinationRange.arrayLayerCount());
            region.srcOffset().set(0, 0, 0);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(mipExtent(copy.source().descriptor().width(), sourceMip),
                    mipExtent(copy.source().descriptor().height(), sourceMip),
                    copy.source().descriptor().dimension() == TextureDimension.TEXTURE_3D
                            ? mipExtent(copy.source().descriptor().depth(), sourceMip) : 1);
        }
        VK10.vkCmdCopyImage(commandBuffer, copy.source().image().image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                copy.destination().image().image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
    }

    private static void recordTextureRegionCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.CopyTextureRegion copy,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var command = copy.command();
        var sourceRange = command.source().range();
        var destinationRange = command.destination().range();
        transitionTextureForTransfer(commandBuffer, stack, copy.source(),
                uploadRange(copy.source(), sourceRange, command.sourceOrigin().z(), command.extent().depth()),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_ACCESS_TRANSFER_READ_BIT, textureLayouts);
        transitionTextureForTransfer(commandBuffer, stack, copy.destination(),
                uploadRange(copy.destination(), destinationRange, command.destinationOrigin().z(), command.extent().depth()),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(sourceRange.aspect()))
                .mipLevel(sourceRange.baseMipLevel())
                .baseArrayLayer(baseArrayLayer(copy.source(), sourceRange.baseArrayLayer(), command.sourceOrigin().z()))
                .layerCount(copy.source().descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : command.extent().depth());
        region.dstSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(destinationRange.aspect()))
                .mipLevel(destinationRange.baseMipLevel())
                .baseArrayLayer(baseArrayLayer(copy.destination(), destinationRange.baseArrayLayer(), command.destinationOrigin().z()))
                .layerCount(copy.destination().descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : command.extent().depth());
        region.srcOffset().set(command.sourceOrigin().x(), command.sourceOrigin().y(),
                copy.source().descriptor().dimension() == TextureDimension.TEXTURE_3D ? command.sourceOrigin().z() : 0);
        region.dstOffset().set(command.destinationOrigin().x(), command.destinationOrigin().y(),
                copy.destination().descriptor().dimension() == TextureDimension.TEXTURE_3D ? command.destinationOrigin().z() : 0);
        region.extent().set(command.extent().width(), command.extent().height(),
                copy.source().descriptor().dimension() == TextureDimension.TEXTURE_3D ? command.extent().depth() : 1);
        VK10.vkCmdCopyImage(commandBuffer, copy.source().image().image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                copy.destination().image().image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private static TextureSubresourceRange uploadRange(
            VulkanGenericResourceRegistry.TextureRecord record,
            TextureSubresourceRange source,
            int originZ,
            int depth
    ) {
        return new TextureSubresourceRange(source.aspect(), source.baseMipLevel(), 1,
                baseArrayLayer(record, source.baseArrayLayer(), originZ),
                record.descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : depth);
    }

    private static int baseArrayLayer(
            VulkanGenericResourceRegistry.TextureRecord record, int baseArrayLayer, int originZ
    ) {
        return baseArrayLayer + (record.descriptor().dimension() == TextureDimension.TEXTURE_3D ? 0 : originZ);
    }

    private static void transitionTextureForTransfer(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record,
            TextureSubresourceRange range,
            int newLayout,
            int destinationAccess,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        forEachSubresource(range, (aspect, mip, layer) -> {
            int oldLayout = textureLayouts.layout(record, aspect, mip, layer);
            if (oldLayout != newLayout) {
                recordTextureBarrier(commandBuffer, stack, record, aspect, mip, layer, oldLayout, newLayout,
                        textureStageMask(oldLayout), textureAccessMask(oldLayout), VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        destinationAccess);
            }
        });
        textureLayouts.set(record, range, newLayout);
    }

    private static void recordTextureBarriers(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            List<VulkanGenericCommandPlan.ResolvedTextureBarrier> barriers,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        for (VulkanGenericCommandPlan.ResolvedTextureBarrier resolved : barriers) {
            var barrier = resolved.barrier();
            int newLayout = VulkanGenericTextureMappings.layoutFor(
                    barrier.destinationAccess(), resolved.resource().descriptor().format()
            );
            forEachSubresource(barrier.slice().range(), (aspect, mip, layer) -> {
                int oldLayout = textureLayouts.layout(resolved.resource(), aspect, mip, layer);
                // Explicit barriers also carry memory dependencies when a layout is unchanged.
                recordTextureBarrier(commandBuffer, stack, resolved.resource(), aspect, mip, layer, oldLayout, newLayout,
                        stageMask(barrier.sourceStages()), accessMask(barrier.sourceAccess()),
                        stageMask(barrier.destinationStages()), accessMask(barrier.destinationAccess()));
            });
            textureLayouts.set(resolved.resource(), barrier.slice().range(), newLayout);
        }
    }

    private static void recordTextureBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record,
            top.ceroxe.rt.renderer.api.TextureAspect aspect,
            int mipLevel,
            int arrayLayer,
            int oldLayout,
            int newLayout,
            int sourceStages,
            int sourceAccess,
            int destinationStages,
            int destinationAccess
    ) {
        VkImageMemoryBarrier.Buffer nativeBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default().srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(record.image().image());
        nativeBarrier.subresourceRange().aspectMask(VulkanGenericTextureMappings.aspectMask(aspect))
                .baseMipLevel(mipLevel).levelCount(1).baseArrayLayer(arrayLayer).layerCount(1);
        VK10.vkCmdPipelineBarrier(commandBuffer, sourceStages, destinationStages, 0, null, null, nativeBarrier);
    }

    private static void forEachSubresource(TextureSubresourceRange range, SubresourceConsumer consumer) {
        for (int mip = range.baseMipLevel(); mip < range.mipEndExclusive(); mip++) {
            for (int layer = range.baseArrayLayer(); layer < range.arrayLayerEndExclusive(); layer++) {
                consumer.accept(range.aspect(), mip, layer);
            }
        }
    }

    private static int textureStageMask(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ->
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL ->
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            default -> throw new UnsupportedOperationException("generic texture layout is not owned by this executor: " + layout);
        };
    }

    private static int textureAccessMask(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK10.VK_ACCESS_SHADER_READ_BIT;
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL ->
                    VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            default -> throw new UnsupportedOperationException("generic texture layout is not owned by this executor: " + layout);
        };
    }

    private static int mipExtent(int baseExtent, int mipLevel) {
        return Math.max(1, baseExtent >> Math.min(mipLevel, Integer.SIZE - 1));
    }

    @FunctionalInterface
    private interface SubresourceConsumer {
        void accept(top.ceroxe.rt.renderer.api.TextureAspect aspect, int mipLevel, int arrayLayer);
    }

    private static void recordBarriers(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            List<VulkanGenericCommandPlan.ResolvedBufferBarrier> barriers
    ) {
        if (barriers.isEmpty()) return;
        VkBufferMemoryBarrier.Buffer nativeBarriers = VkBufferMemoryBarrier.calloc(barriers.size(), stack);
        int sourceStages = 0;
        int destinationStages = 0;
        for (int index = 0; index < barriers.size(); index++) {
            VulkanGenericCommandPlan.ResolvedBufferBarrier resolved = barriers.get(index);
            BufferBarrier barrier = resolved.barrier();
            sourceStages |= stageMask(barrier.sourceStages());
            destinationStages |= stageMask(barrier.destinationStages());
            nativeBarriers.get(index)
                    .sType$Default()
                    .srcAccessMask(accessMask(barrier.sourceAccess()))
                    .dstAccessMask(accessMask(barrier.destinationAccess()))
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(resolved.resource().buffer().buffer())
                    .offset(barrier.slice().range().offsetBytes())
                    .size(barrier.slice().range().lengthBytes());
        }
        VK10.vkCmdPipelineBarrier(
                commandBuffer, sourceStages, destinationStages, 0, null, nativeBarriers, null
        );
    }

    private static int stageMask(java.util.Set<RenderPipelineStage> stages) {
        int result = 0;
        for (RenderPipelineStage stage : stages) {
            result |= switch (stage) {
                case HOST -> VK10.VK_PIPELINE_STAGE_HOST_BIT;
                case COPY -> VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
                case INDIRECT -> VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
                case VERTEX_INPUT -> VK10.VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
                case VERTEX_SHADER -> VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
                case FRAGMENT_SHADER -> VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                case EARLY_DEPTH_STENCIL -> VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
                case LATE_DEPTH_STENCIL -> VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
                case COLOR_ATTACHMENT_OUTPUT -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                case COMPUTE_SHADER -> VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
                case RAY_TRACING_SHADER -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
                case PRESENT -> throw new UnsupportedOperationException("presentation barriers are not owned by this session");
            };
        }
        return result;
    }

    private static int accessMask(java.util.Set<RenderResourceAccess> accesses) {
        int result = 0;
        for (RenderResourceAccess access : accesses) {
            result |= switch (access) {
                case HOST_READ -> VK10.VK_ACCESS_HOST_READ_BIT;
                case HOST_WRITE -> VK10.VK_ACCESS_HOST_WRITE_BIT;
                case COPY_READ -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
                case COPY_WRITE -> VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
                case INDIRECT_READ -> VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
                case VERTEX_READ -> VK10.VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
                case INDEX_READ -> VK10.VK_ACCESS_INDEX_READ_BIT;
                case UNIFORM_READ -> VK10.VK_ACCESS_UNIFORM_READ_BIT;
                case SHADER_READ -> VK10.VK_ACCESS_SHADER_READ_BIT;
                case SHADER_WRITE -> VK10.VK_ACCESS_SHADER_WRITE_BIT;
                case COLOR_ATTACHMENT_READ -> VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
                case COLOR_ATTACHMENT_WRITE -> VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                case DEPTH_STENCIL_READ -> VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
                case DEPTH_STENCIL_WRITE -> VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                case PRESENT_READ -> throw new UnsupportedOperationException("presentation access is not owned by this session");
            };
        }
        return result;
    }

    private static void executable(RenderingSemanticCapabilities.Builder target,
                                   RenderingSemanticCapabilities.Feature feature, String detail) {
        target.feature(feature, new RenderingSemanticCapabilities.Entry(
                RenderingSemanticCapabilities.Status.EXECUTABLE, detail
        ));
    }

    private static CommandExecutionEvidence rejected(long sequence, CommandExecutionEvidence.Reason reason, String detail) {
        return new CommandExecutionEvidence(
                sequence, CommandExecutionEvidence.Outcome.REJECTED, reason,
                OptionalLong.empty(), Optional.empty(), 0L, nonBlank(detail)
        );
    }

    private static CommandExecutionEvidence blocked(long sequence, String detail) {
        return new CommandExecutionEvidence(
                sequence, CommandExecutionEvidence.Outcome.BLOCKED,
                CommandExecutionEvidence.Reason.BOUNDED_BACKPRESSURE,
                OptionalLong.empty(), Optional.empty(), 0L, nonBlank(detail)
        );
    }

    private static String message(RuntimeException failure, String fallback) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? "generic Vulkan command rejected" : value;
    }

    private static void closeStaging(List<StagingUpload> staging) {
        for (StagingUpload upload : staging) {
            try {
                upload.close();
            } catch (RuntimeException ignored) {
                // The command fence outcome remains the primary diagnostic; all staging owners are independent.
            }
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic Vulkan command session is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (PendingSubmission submission : pending.values()) {
            try {
                submission.submission().close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                closeStaging(submission.staging());
            }
        }
        pending.clear();
        computePipelines.close();
        graphicsPipelines.close();
        resources.close();
        if (failure != null) throw failure;
    }

    private record PendingSubmission(
            long sequence,
            RtCommandContext.AsyncSubmission submission,
            List<StagingUpload> staging,
            List<VulkanGenericResourceRegistry.BufferRecord> writes,
            List<VulkanGenericResourceRegistry.TextureRecord> textureWrites,
            java.util.Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource
    ) { }

    private static final class StagingUpload implements AutoCloseable {
        private final RtGpuBuffer buffer;
        private final long byteCount;

        private StagingUpload(RtGpuBuffer buffer, long byteCount) {
            this.buffer = Objects.requireNonNull(buffer, "buffer");
            this.byteCount = byteCount;
        }

        static StagingUpload create(VulkanDeviceRuntime device, VulkanGenericCommandPlan.Write write) {
            return create(device, write.data());
        }

        static StagingUpload create(VulkanDeviceRuntime device, ByteBuffer sourceData) {
            ByteBuffer source = sourceData.duplicate();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            RtGpuBuffer staging = RtGpuBuffer.createHostVisibleUploadBuffer(
                    device.device(), device.allocator(), bytes.length, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            );
            try {
                staging.writeBytes(bytes);
                return new StagingUpload(staging, bytes.length);
            } catch (RuntimeException failure) {
                staging.close();
                throw failure;
            }
        }

        /**
         * Re-packs one API texture region into Vulkan's tight buffer-image-copy layout.
         *
         * <p>{@link org.lwjgl.vulkan.VkBufferImageCopy#bufferRowLength()} accepts texels rather
         * than bytes, whereas the public API deliberately accepts arbitrary byte pitches. Passing
         * a non-integral pitch through integer division would silently shift every following row.
         * Compacting here preserves the public layout exactly and keeps the native copy contract
         * unambiguous.</p>
         */
        static StagingUpload createTexture(
                VulkanDeviceRuntime device,
                VulkanGenericCommandPlan.WriteTexture write
        ) {
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(write, "write");
            return create(device, VulkanGenericTextureUploadPacker.compact(
                    write.destination().descriptor().format(), write.command()
            ));
        }

        RtGpuBuffer buffer() { return buffer; }
        long byteCount() { return byteCount; }

        @Override
        public void close() { buffer.close(); }
    }
}
