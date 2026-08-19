package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
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
    private final VulkanGenericAccelerationStructures accelerationStructures;
    private final VulkanGenericRayTracingPipelines rayTracingPipelines;
    private final Map<Long, CommandExecutionEvidence> commandEvidence = new LinkedHashMap<>();
    private final Map<Long, PendingSubmission> pending = new LinkedHashMap<>();
    private long latestCommandSequence = -1L;
    private long latestCompletedSequence = -1L;
    private boolean deviceFailed;
    private boolean closed;
    private RuntimeException closeFailure;

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
        this.accelerationStructures = new VulkanGenericAccelerationStructures(device);
        this.rayTracingPipelines = new VulkanGenericRayTracingPipelines(device, resources);
    }

    RenderingSemanticCapabilities capabilities() {
        RenderingSemanticCapabilities.Builder result = RenderingSemanticCapabilities.builder();
        executable(result, RenderingSemanticCapabilities.Feature.VERSIONED_BUFFERS,
                "Vulkan buffer generations, staging upload, copy and fence evidence are implemented");
        executable(result, RenderingSemanticCapabilities.Feature.VERSIONED_TEXTURES,
                "Vulkan texture generations use VMA-backed storage with typed residency and safe unused retirement");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_VIEWS,
                "resident texture generations expose validated full and subresource Vulkan image views");
        executable(result, RenderingSemanticCapabilities.Feature.SAMPLERS,
                "validated immutable sampler descriptors are cached and bound by generic pipeline records");
        executable(result, RenderingSemanticCapabilities.Feature.SPIRV_SHADER_MODULES,
                "generic compute, graphics, and ray-tracing pipelines validate and compile caller SPIR-V modules");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_UPLOAD,
                "texture upload uses staging copies and image-layout transitions retained through fence completion");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_COPY,
                "texture-to-texture copy is executable for GPU-ready source and uninitialized destination generations");
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_TO_TEXTURE_COPY,
                "buffer-to-texture copy records exact regions when the portable byte pitch is Vulkan-representable");
        executable(result, RenderingSemanticCapabilities.Feature.TEXTURE_TO_BUFFER_COPY,
                "texture-to-buffer copy records exact regions when the portable byte pitch is Vulkan-representable");
        executable(result, RenderingSemanticCapabilities.Feature.COLOR_CLEAR,
                "color image clear records exact color subresource ranges outside render passes");
        executable(result, RenderingSemanticCapabilities.Feature.DEPTH_STENCIL_CLEAR,
                "depth/stencil image clear records exact aspect subresource ranges outside render passes");
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
            executable(result, RenderingSemanticCapabilities.Feature.MULTI_DRAW,
                    "multi-draw and multi-indexed-draw commands preserve every draw range and instance parameter");
            executable(result, RenderingSemanticCapabilities.Feature.INDIRECT_DRAW,
                    "indirect draw commands validate exact argument buffers and record the requested draw count");
        }
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_UPLOAD,
                "buffer upload uses staging copies retained through fence completion");
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_COPY,
                "buffer-to-buffer copy is executable for GPU-ready exact generations");
        executable(result, RenderingSemanticCapabilities.Feature.BUFFER_BARRIERS,
                "buffer memory barriers are executable without queue-family ownership transfer");
        executable(result, RenderingSemanticCapabilities.Feature.EXPLICIT_BARRIERS,
                "explicit buffer and texture barriers are recorded with exact stage, access, and subresource scopes");
        executable(result, RenderingSemanticCapabilities.Feature.ACCELERATION_STRUCTURE_BUILDS,
                "generic BLAS and TLAS builds own exact input generations, scratch allocations, and fence completion");
        executable(result, RenderingSemanticCapabilities.Feature.RAY_TRACING_PIPELINES,
                "generic RT SPIR-V programs compile with explicit shader groups and aligned shader-binding tables");
        executable(result, RenderingSemanticCapabilities.Feature.RAY_TRACING_DISPATCH,
                "trace-rays commands bind generic RT descriptors and dispatch into explicit storage textures");
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
            plan = VulkanGenericCommandPlan.compile(
                    resources, computePipelines, graphicsPipelines, rayTracingPipelines, accelerationStructures, checked
            );
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
        boolean submitted = false;
        boolean submissionOwnedByPending = false;
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
                } else if (action instanceof VulkanGenericCommandPlan.BindRayTracingBindings bindings) {
                    rayTracingPipelines.updateBindings(
                            bindings.pipeline(), bindings.command().bindingSet(), plan.accelerationStructures()
                    );
                }
            }
            List<StagingUpload> immutableStaging = List.copyOf(staging);
            submission = device.frameCommands().submitTimedOneTimeAsync(
                    TIMING_LABEL, (commandBuffer, stack) -> record(
                            commandBuffer, stack, plan, immutableStaging, textureLayouts
                    )
            );
            submitted = true;
            plan.accelerationStructures().commit(checked.sequence());
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
                    checked.sequence(), submission, immutableStaging, plan.writes(), plan.textureWrites(), plan.outputResource(),
                    plan.accelerationStructures()));
            submissionOwnedByPending = true;
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
            if (!submissionOwnedByPending) {
                if (submitted) plan.accelerationStructures().discardAfterDeviceFailure();
                else plan.accelerationStructures().close();
            }
        }
    }

    Optional<CommandExecutionEvidence> commandEvidence(long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("command sequence must not be negative");
        requireOpen();
        pump();
        return Optional.ofNullable(commandEvidence.get(sequence));
    }

    VulkanGenericCompositionSource requireCompositionSource(top.ceroxe.rt.renderer.api.ResourceMutationKey mutation) {
        requireOpen();
        top.ceroxe.rt.renderer.api.ResourceMutationKey checked = Objects.requireNonNull(mutation, "mutation");
        pump();
        CommandExecutionEvidence evidence = commandEvidence.get(checked.commandSequence());
        if (evidence == null || !evidence.outcome().outputProduced()
                || evidence.outputResource().isEmpty()
                || !evidence.outputResource().get().equals(checked.generation().id())) {
            throw new IllegalStateException("composition source command has no completed exact output: " + checked);
        }
        return resources.requireCompositionSource(checked);
    }

    VulkanGenericTextureLayoutUpdates beginCompositionLayoutUpdates() {
        requireOpen();
        return resources.beginCompositionLayoutUpdates();
    }

    int stageCompositionRead(
            VulkanGenericCompositionSource source,
            VulkanGenericTextureLayoutUpdates layoutUpdates
    ) {
        requireOpen();
        return resources.stageCompositionRead(
                Objects.requireNonNull(source, "source").mutation(),
                Objects.requireNonNull(layoutUpdates, "layoutUpdates")
        );
    }

    void commitCompositionLayoutUpdates(VulkanGenericTextureLayoutUpdates layoutUpdates) {
        requireOpen();
        resources.commitCompositionLayoutUpdates(Objects.requireNonNull(layoutUpdates, "layoutUpdates"));
    }

    VulkanGenericResourceRegistry.CompositionPinLease pinComposition(
            VulkanGenericCompositionSource[] sources
    ) {
        requireOpen();
        return resources.acquireCompositionPins(Objects.requireNonNull(sources, "sources"));
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
                current.accelerationStructures().discardAfterDeviceFailure();
                iterator.remove();
                continue;
            }
            if (!complete) continue;
            try {
                resources.markCompleted(current.writes(), current.sequence());
                resources.markTextureCompleted(current.textureWrites(), current.sequence());
                current.accelerationStructures().complete(current.sequence());
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
                current.accelerationStructures().close();
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
                case VulkanGenericCommandPlan.BindRayTracing bind -> VK10.vkCmdBindPipeline(
                        commandBuffer, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                        bind.pipeline().pipeline()
                );
                case VulkanGenericCommandPlan.BindRayTracingBindings bind -> recordRayTracingBindings(
                        commandBuffer, stack, bind
                );
                case VulkanGenericCommandPlan.RayTracingPushConstants push -> VK10.vkCmdPushConstants(
                        commandBuffer, push.pipeline().layout(), push.pipeline().shaderStageFlags(),
                        push.command().offsetBytes(), push.command().data().bytes()
                );
                case VulkanGenericCommandPlan.BuildAccelerationStructure build ->
                        plan.accelerationStructures().recordBuild(commandBuffer, stack, build.build());
                case VulkanGenericCommandPlan.TraceRays trace -> recordTraceRays(
                        commandBuffer, stack, trace, textureLayouts
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
                case VulkanGenericCommandPlan.CopyBufferToTexture copy -> recordBufferToTextureCopy(
                        commandBuffer, stack, copy, textureLayouts
                );
                case VulkanGenericCommandPlan.CopyTextureToBuffer copy -> recordTextureToBufferCopy(
                        commandBuffer, stack, copy, textureLayouts
                );
                case VulkanGenericCommandPlan.ClearColor clear -> recordColorClear(
                        commandBuffer, stack, clear, textureLayouts
                );
                case VulkanGenericCommandPlan.ClearDepthStencil clear -> recordDepthStencilClear(
                        commandBuffer, stack, clear, textureLayouts
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
                case VulkanGenericCommandPlan.AutoBufferVisibility visibility -> recordAutomaticBufferVisibility(
                        commandBuffer, stack, visibility.resource()
                );
                case VulkanGenericCommandPlan.AutoAccelerationStructureInputVisibility visibility ->
                        recordAutomaticAccelerationStructureInputVisibility(commandBuffer, stack, visibility.resource());
                case VulkanGenericCommandPlan.AutoTextureVisibility visibility -> recordAutomaticTextureVisibility(
                        commandBuffer, stack, visibility, textureLayouts
                );
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

    private static void recordRayTracingBindings(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.BindRayTracingBindings bind
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
        VK10.vkCmdBindDescriptorSets(commandBuffer, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                bind.pipeline().layout(), 0, sets, dynamicOffsets);
    }

    private static void recordTraceRays(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.TraceRays trace,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var output = trace.output();
        var range = trace.command().output().range();
        transitionTextureForRayTracing(commandBuffer, stack, output, range, textureLayouts);
        VulkanGenericRayTracingPipelines.Sbt sbt = trace.pipeline().sbt();
        VkMemoryBarrier.Buffer hostVisibility = VkMemoryBarrier.calloc(1, stack);
        hostVisibility.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
        VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_HOST_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0,
                hostVisibility, null, null);
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer,
                sbtRegion(stack, sbt, sbt.raygen()),
                sbtRegion(stack, sbt, sbt.miss()),
                sbtRegion(stack, sbt, sbt.hit()),
                sbtRegion(stack, sbt, sbt.callable()),
                trace.command().width(), trace.command().height(), trace.command().depth()
        );
    }

    private static VkStridedDeviceAddressRegionKHR sbtRegion(
            MemoryStack stack, VulkanGenericRayTracingPipelines.Sbt sbt, VulkanGenericRayTracingPipelines.Region region
    ) {
        if (region.size() == 0) return VkStridedDeviceAddressRegionKHR.calloc(stack);
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(Math.addExact(Math.addExact(sbt.buffer().deviceAddress(), sbt.baseOffset()), region.offset()))
                .stride(region.stride()).size(region.size());
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

    private static void recordBufferToTextureCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.CopyBufferToTexture copy,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var command = copy.command();
        var destination = copy.destination();
        TextureSubresourceRange range = uploadRange(destination, command.destination().range(), command.destinationOrigin().z(),
                command.extent().depth());
        transitionTextureForTransfer(commandBuffer, stack, destination, range, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        populateBufferImageCopy(region, command.source().range().offsetBytes(), command.sourceLayout(), destination,
                command.destination().range(), command.destinationOrigin(), command.extent());
        VK10.vkCmdCopyBufferToImage(commandBuffer, copy.source().buffer().buffer(), destination.image().image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private static void recordTextureToBufferCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.CopyTextureToBuffer copy,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var command = copy.command();
        var source = copy.source();
        TextureSubresourceRange range = uploadRange(source, command.source().range(), command.sourceOrigin().z(),
                command.extent().depth());
        transitionTextureForTransfer(commandBuffer, stack, source, range, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, textureLayouts);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        populateBufferImageCopy(region, command.destination().range().offsetBytes(), command.destinationLayout(), source,
                command.source().range(), command.sourceOrigin(), command.extent());
        VK10.vkCmdCopyImageToBuffer(commandBuffer, source.image().image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                copy.destination().buffer().buffer(), region);
    }

    private static void populateBufferImageCopy(
            VkBufferImageCopy.Buffer region,
            long sliceOffset,
            top.ceroxe.rt.renderer.api.TextureDataLayout layout,
            VulkanGenericResourceRegistry.TextureRecord texture,
            TextureSubresourceRange textureRange,
            top.ceroxe.rt.renderer.api.TextureOrigin origin,
            top.ceroxe.rt.renderer.api.TextureExtent extent
    ) {
        int bytesPerTexel = bytesPerTexel(texture.descriptor().format());
        if (layout.bytesPerRow() % bytesPerTexel != 0L) {
            throw new UnsupportedOperationException("Vulkan buffer-image copy requires bytesPerRow divisible by the texture texel size");
        }
        long rowLength = layout.bytesPerRow() / bytesPerTexel;
        if (rowLength > Integer.MAX_VALUE || layout.rowsPerImage() > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("Vulkan buffer-image copy pitch exceeds uint32 limits");
        }
        long offset;
        try {
            offset = Math.addExact(sliceOffset, layout.offsetBytes());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("buffer-image copy offset overflows long", overflow);
        }
        region.bufferOffset(offset).bufferRowLength((int) rowLength).bufferImageHeight((int) layout.rowsPerImage());
        region.imageSubresource().aspectMask(VulkanGenericTextureMappings.aspectMask(textureRange.aspect()))
                .mipLevel(textureRange.baseMipLevel())
                .baseArrayLayer(baseArrayLayer(texture, textureRange.baseArrayLayer(), origin.z()))
                .layerCount(texture.descriptor().dimension() == TextureDimension.TEXTURE_3D ? 1 : extent.depth());
        region.imageOffset().set(origin.x(), origin.y(),
                texture.descriptor().dimension() == TextureDimension.TEXTURE_3D ? origin.z() : 0);
        region.imageExtent().set(extent.width(), extent.height(),
                texture.descriptor().dimension() == TextureDimension.TEXTURE_3D ? extent.depth() : 1);
    }

    private static void recordColorClear(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.ClearColor clear,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var command = clear.command();
        TextureSubresourceRange range = command.destination().range();
        transitionTextureForTransfer(commandBuffer, stack, clear.destination(), range, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        org.lwjgl.vulkan.VkClearColorValue value = org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
        value.float32(0, command.value().red());
        value.float32(1, command.value().green());
        value.float32(2, command.value().blue());
        value.float32(3, command.value().alpha());
        org.lwjgl.vulkan.VkImageSubresourceRange.Buffer nativeRange = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
        populateNativeRange(nativeRange.get(0), range);
        VK10.vkCmdClearColorImage(commandBuffer, clear.destination().image().image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, nativeRange);
    }

    private static void recordDepthStencilClear(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.ClearDepthStencil clear,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        var command = clear.command();
        TextureSubresourceRange range = command.destination().range();
        transitionTextureForTransfer(commandBuffer, stack, clear.destination(), range, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, textureLayouts);
        org.lwjgl.vulkan.VkClearDepthStencilValue value = org.lwjgl.vulkan.VkClearDepthStencilValue.calloc(stack)
                .depth(command.depth()).stencil(command.stencil());
        org.lwjgl.vulkan.VkImageSubresourceRange.Buffer nativeRange = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, stack);
        populateNativeRange(nativeRange.get(0), range);
        VK10.vkCmdClearDepthStencilImage(commandBuffer, clear.destination().image().image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, nativeRange);
    }

    private static void populateNativeRange(org.lwjgl.vulkan.VkImageSubresourceRange range, TextureSubresourceRange source) {
        range.aspectMask(VulkanGenericTextureMappings.aspectMask(source.aspect()))
                .baseMipLevel(source.baseMipLevel()).levelCount(source.mipLevelCount())
                .baseArrayLayer(source.baseArrayLayer()).layerCount(source.arrayLayerCount());
    }

    private static int bytesPerTexel(top.ceroxe.rt.renderer.api.TextureFormat format) {
        return switch (format) {
            case R8_UNORM -> 1;
            case RG8_UNORM, R16_FLOAT -> 2;
            case RGBA8_UNORM, RGBA8_SRGB, R32_FLOAT, D32_FLOAT, D24_UNORM_S8_UINT -> 4;
            case RG16_FLOAT -> 4;
            case RGBA16_FLOAT -> 8;
            case RG32_FLOAT -> 8;
            case RGBA32_FLOAT -> 16;
        };
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

    private static void transitionTextureForRayTracing(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record,
            TextureSubresourceRange range,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        forEachSubresource(range, (aspect, mip, layer) -> {
            int oldLayout = textureLayouts.layout(record, aspect, mip, layer);
            recordTextureBarrier(commandBuffer, stack, record, aspect, mip, layer, oldLayout,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, textureStageMask(oldLayout), textureAccessMask(oldLayout),
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        });
        textureLayouts.set(record, range, VK10.VK_IMAGE_LAYOUT_GENERAL);
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

    private static void recordAutomaticBufferVisibility(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericResourceRegistry.BufferRecord record
    ) {
        VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
        barrier.sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(record.buffer().buffer()).offset(0L).size(record.descriptor().byteSize());
        VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, barrier, null);
    }

    private static void recordAutomaticAccelerationStructureInputVisibility(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericResourceRegistry.BufferRecord record
    ) {
        VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(record.buffer().buffer()).offset(0L).size(VK10.VK_WHOLE_SIZE);
        VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0, null, barrier, null);
    }

    private static void recordAutomaticTextureVisibility(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.AutoTextureVisibility visibility,
            VulkanGenericTextureLayoutUpdates textureLayouts
    ) {
        int newLayout = visibility.writable() ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        int destinationAccess = visibility.writable()
                ? VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT
                : VK10.VK_ACCESS_SHADER_READ_BIT;
        forEachSubresource(visibility.range(), (aspect, mip, layer) -> {
            int oldLayout = textureLayouts.layout(visibility.resource(), aspect, mip, layer);
            recordTextureBarrier(commandBuffer, stack, visibility.resource(), aspect, mip, layer, oldLayout, newLayout,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, destinationAccess);
        });
        textureLayouts.set(visibility.resource(), visibility.range(), newLayout);
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
        if (closed && closeFailure == null) return;
        closed = true;
        RuntimeException failure = null;
        for (PendingSubmission submission : pending.values()) {
            try {
                submission.submission().close();
            } catch (RuntimeException closeFailure) {
                failure = mergeCloseFailure(failure, closeFailure);
            }
            closeStaging(submission.staging());
            try {
                submission.accelerationStructures().discardAfterDeviceFailure();
            } catch (RuntimeException closeFailure) {
                failure = mergeCloseFailure(failure, closeFailure);
            }
        }
        pending.clear();
        failure = closeCollecting(failure, computePipelines);
        failure = closeCollecting(failure, graphicsPipelines);
        failure = closeCollecting(failure, rayTracingPipelines);
        failure = closeCollecting(failure, accelerationStructures);
        failure = closeCollecting(failure, resources);
        closeFailure = failure;
        if (closeFailure != null) throw closeFailure;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            return mergeCloseFailure(failure, closeFailure);
        } catch (Exception impossible) {
            return mergeCloseFailure(
                    failure,
                    new IllegalStateException("unexpected checked generic-resource close failure", impossible)
            );
        }
        return failure;
    }

    private static RuntimeException mergeCloseFailure(
            RuntimeException failure,
            RuntimeException closeFailure
    ) {
        if (failure == null) return closeFailure;
        if (failure != closeFailure) failure.addSuppressed(closeFailure);
        return failure;
    }

    private record PendingSubmission(
            long sequence,
            RtCommandContext.AsyncSubmission submission,
            List<StagingUpload> staging,
            List<VulkanGenericResourceRegistry.BufferRecord> writes,
            List<VulkanGenericResourceRegistry.TextureRecord> textureWrites,
            java.util.Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource,
            VulkanGenericAccelerationStructures.Compilation accelerationStructures
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
