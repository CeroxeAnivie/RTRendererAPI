package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.BufferBarrier;
import top.ceroxe.rt.renderer.api.CopyBufferCommand;
import top.ceroxe.rt.renderer.api.CopyTextureCommand;
import top.ceroxe.rt.renderer.api.CopyTextureRegionCommand;
import top.ceroxe.rt.renderer.api.BindBindingSetCommand;
import top.ceroxe.rt.renderer.api.BindComputePipelineCommand;
import top.ceroxe.rt.renderer.api.DispatchCommand;
import top.ceroxe.rt.renderer.api.SetPushConstantsCommand;
import top.ceroxe.rt.renderer.api.RenderCommand;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderPipelineStage;
import top.ceroxe.rt.renderer.api.ResourceBarrierCommand;
import top.ceroxe.rt.renderer.api.TextureBarrier;
import top.ceroxe.rt.renderer.api.WriteBufferCommand;
import top.ceroxe.rt.renderer.api.WriteTextureCommand;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.BeginRenderPassCommand;
import top.ceroxe.rt.renderer.api.BindGraphicsPipelineCommand;
import top.ceroxe.rt.renderer.api.BindIndexBufferCommand;
import top.ceroxe.rt.renderer.api.BindVertexBufferCommand;
import top.ceroxe.rt.renderer.api.DrawCommand;
import top.ceroxe.rt.renderer.api.DrawIndexedCommand;
import top.ceroxe.rt.renderer.api.IndirectDrawCommand;
import top.ceroxe.rt.renderer.api.MultiDrawCommand;
import top.ceroxe.rt.renderer.api.MultiDrawIndexedCommand;
import top.ceroxe.rt.renderer.api.EndRenderPassCommand;
import top.ceroxe.rt.renderer.api.GraphicsPipelineState;
import top.ceroxe.rt.renderer.api.RenderPassDescriptor;
import top.ceroxe.rt.renderer.api.ScissorRectangle;
import top.ceroxe.rt.renderer.api.SetScissorCommand;
import top.ceroxe.rt.renderer.api.SetViewportCommand;
import top.ceroxe.rt.renderer.api.SetPushConstantsCommand;
import top.ceroxe.rt.renderer.api.Viewport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Compiles the currently executable transfer-command subset without recording native calls. */
final class VulkanGenericCommandPlan {
    private final List<Action> actions;
    private final List<VulkanGenericResourceRegistry.BufferRecord> writes;
    private final List<VulkanGenericResourceRegistry.BufferRecord> reads;
    private final List<VulkanGenericResourceRegistry.TextureRecord> textureWrites;
    private final List<VulkanGenericResourceRegistry.TextureRecord> textureReads;
    private final Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource;

    private VulkanGenericCommandPlan(
            List<Action> actions,
            List<VulkanGenericResourceRegistry.BufferRecord> writes,
            List<VulkanGenericResourceRegistry.BufferRecord> reads,
            List<VulkanGenericResourceRegistry.TextureRecord> textureWrites,
            List<VulkanGenericResourceRegistry.TextureRecord> textureReads,
            Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource
    ) {
        this.actions = List.copyOf(actions);
        this.writes = distinct(writes);
        this.reads = distinct(reads);
        this.textureWrites = distinctTextures(textureWrites);
        this.textureReads = distinctTextures(textureReads);
        this.outputResource = Objects.requireNonNull(outputResource, "outputResource");
    }

    static VulkanGenericCommandPlan compile(
            VulkanGenericResourceRegistry resources,
            VulkanGenericComputePipelines pipelines,
            VulkanGenericGraphicsPipelines graphicsPipelines,
            RenderCommandTransaction transaction
    ) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(transaction, "transaction");
        ArrayList<Action> actions = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.BufferRecord> writes = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.BufferRecord> reads = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.TextureRecord> textureWrites = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.TextureRecord> textureReads = new ArrayList<>();
        top.ceroxe.rt.renderer.api.RenderResourceId outputResource = null;
        VulkanGenericComputePipelines.Compiled computePipeline = null;
        VulkanGenericGraphicsPipelines.Compiled graphicsPipeline = null;
        for (RenderCommand command : transaction.commands()) {
            switch (command) {
                case BeginRenderPassCommand begin -> {
                    RenderPassDescriptor pass = begin.descriptor();
                    List<ResolvedAttachment> colors = new ArrayList<>();
                    for (int index = 0; index < pass.colorAttachments().size(); index++) {
                        var attachment = pass.colorAttachments().get(index);
                        var record = resources.requireTexture(attachment.view().texture());
                        resources.requireAttachmentWritable(record, attachment.loadOperation());
                        var resolve = pass.colorResolveAttachments().get(index);
                        var resolveRecord = resolve.map(view -> resources.requireTexture(view.texture())).orElse(null);
                        if (resolveRecord != null) resources.requireWritable(resolveRecord);
                        colors.add(new ResolvedAttachment(record, attachment, resolve.orElse(null), resolveRecord));
                        textureWrites.add(record);
                        if (resolveRecord != null) textureWrites.add(resolveRecord);
                        if (attachment.storeOperation() == top.ceroxe.rt.renderer.api.StoreOp.STORE && outputResource == null) {
                            outputResource = record.generation().id();
                        }
                    }
                    ResolvedAttachment depth = resolveAttachment(resources, pass.depthAttachment().orElse(null), false);
                    ResolvedAttachment stencil = resolveAttachment(resources, pass.stencilAttachment().orElse(null), false);
                    if (depth != null) textureWrites.add(depth.record());
                    if (stencil != null) textureWrites.add(stencil.record());
                    actions.add(new BeginPass(pass, List.copyOf(colors), depth, stencil));
                    graphicsPipeline = null;
                }
                case EndRenderPassCommand ignored -> {
                    actions.add(new EndPass());
                    graphicsPipeline = null;
                }
                case BindGraphicsPipelineCommand bind -> {
                    graphicsPipeline = graphicsPipelines.require(bind.pipeline());
                    actions.add(new BindGraphics(graphicsPipeline));
                }
                case BindComputePipelineCommand bind -> {
                    computePipeline = pipelines.require(bind.pipeline());
                    actions.add(new BindCompute(computePipeline));
                }
                case BindBindingSetCommand bind -> {
                    if (computePipeline == null && graphicsPipeline == null) {
                        throw new IllegalArgumentException("binding set has no active pipeline in generic backend");
                    }
                    validateBindingResources(resources, bind.bindingSet(), reads, textureReads);
                    for (long offset : bind.dynamicOffsets()) {
                        if (offset > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException("dynamic descriptor offset exceeds Vulkan uint32 command range");
                        }
                    }
                    if (computePipeline != null) actions.add(new BindComputeBindings(computePipeline, bind));
                    else actions.add(new BindGraphicsBindings(graphicsPipeline, bind));
                }
                case SetPushConstantsCommand push -> {
                    if (computePipeline == null && graphicsPipeline == null) {
                        throw new IllegalArgumentException("push constants have no active pipeline in generic backend");
                    }
                    if (computePipeline != null) actions.add(new PushConstants(computePipeline, push));
                    else actions.add(new GraphicsPushConstants(graphicsPipeline, push));
                }
                case DispatchCommand dispatch -> {
                    if (computePipeline == null) {
                        throw new IllegalArgumentException("dispatch has no active compute pipeline in generic backend");
                    }
                    actions.add(new Dispatch(computePipeline, dispatch));
                }
                case BindVertexBufferCommand bind -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("vertex buffer has no active graphics pipeline");
                    var record = resources.requireBuffer(bind.slice().resource());
                    resources.requireReadable(record);
                    reads.add(record);
                    actions.add(new BindVertex(graphicsPipeline, bind, record));
                }
                case BindIndexBufferCommand bind -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("index buffer has no active graphics pipeline");
                    var record = resources.requireBuffer(bind.slice().resource());
                    resources.requireReadable(record);
                    reads.add(record);
                    actions.add(new BindIndex(graphicsPipeline, bind, record));
                }
                case SetViewportCommand set -> actions.add(new ViewportAction(set.viewport()));
                case SetScissorCommand set -> actions.add(new ScissorAction(set.scissor()));
                case DrawCommand draw -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("draw has no active graphics pipeline");
                    actions.add(new Draw(graphicsPipeline, draw));
                }
                case DrawIndexedCommand draw -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("indexed draw has no active graphics pipeline");
                    actions.add(new DrawIndexed(graphicsPipeline, draw));
                }
                case MultiDrawCommand multi -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("multi-draw has no active graphics pipeline");
                    actions.add(new MultiDraw(graphicsPipeline, multi));
                }
                case MultiDrawIndexedCommand multi -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("indexed multi-draw has no active graphics pipeline");
                    actions.add(new MultiDrawIndexed(graphicsPipeline, multi));
                }
                case IndirectDrawCommand indirect -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("indirect draw has no active graphics pipeline");
                    var arguments = resources.requireBuffer(indirect.arguments().resource());
                    resources.requireReadable(arguments);
                    reads.add(arguments);
                    VulkanGenericResourceRegistry.BufferRecord count = null;
                    if (indirect.count().isPresent()) {
                        count = resources.requireBuffer(indirect.count().orElseThrow().resource());
                        resources.requireReadable(count);
                        reads.add(count);
                        throw new UnsupportedOperationException("counted indirect graphics draws require VK_KHR_draw_indirect_count");
                    }
                    actions.add(new Indirect(graphicsPipeline, indirect, arguments, count));
                }
                case WriteBufferCommand write -> {
                    VulkanGenericResourceRegistry.BufferRecord destination = resources.requireBuffer(
                            write.destination().resource()
                    );
                    resources.requireWritable(destination);
                    actions.add(new Write(destination, write.destination().range().offsetBytes(), write.data().bytes()));
                    writes.add(destination);
                }
                case CopyBufferCommand copy -> {
                    VulkanGenericResourceRegistry.BufferRecord source = resources.requireBuffer(copy.source().resource());
                    VulkanGenericResourceRegistry.BufferRecord destination = resources.requireBuffer(
                            copy.destination().resource()
                    );
                    resources.requireReadable(source);
                    resources.requireWritable(destination);
                    actions.add(new Copy(
                            source, copy.source().range().offsetBytes(), destination,
                            copy.destination().range().offsetBytes(), copy.source().range().lengthBytes()
                    ));
                    reads.add(source);
                    writes.add(destination);
                }
                case WriteTextureCommand write -> {
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            write.destination().resource());
                    resources.requireWritable(destination);
                    actions.add(new WriteTexture(destination, write));
                    textureWrites.add(destination);
                }
                case CopyTextureCommand copy -> {
                    VulkanGenericResourceRegistry.TextureRecord source = resources.requireTexture(copy.source().resource());
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            copy.destination().resource());
                    resources.requireReadable(source);
                    resources.requireWritable(destination);
                    actions.add(new CopyTexture(source, destination, copy));
                    textureReads.add(source);
                    textureWrites.add(destination);
                }
                case CopyTextureRegionCommand copy -> {
                    VulkanGenericResourceRegistry.TextureRecord source = resources.requireTexture(copy.source().resource());
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            copy.destination().resource());
                    resources.requireReadable(source);
                    resources.requireWritable(destination);
                    actions.add(new CopyTextureRegion(source, destination, copy));
                    textureReads.add(source);
                    textureWrites.add(destination);
                }
                case ResourceBarrierCommand barrier -> {
                    ArrayList<ResolvedBufferBarrier> resolved = new ArrayList<>();
                    for (BufferBarrier source : barrier.bufferBarriers()) {
                        if (source.sourceStages().contains(RenderPipelineStage.PRESENT)
                                || source.destinationStages().contains(RenderPipelineStage.PRESENT)) {
                            throw new UnsupportedOperationException(
                                    "generic Vulkan command path does not own presentation queue-family transfers"
                            );
                        }
                        VulkanGenericResourceRegistry.BufferRecord record = resources.requireBuffer(
                                source.slice().resource()
                        );
                        resolved.add(new ResolvedBufferBarrier(record, source));
                        reads.add(record);
                    }
                    ArrayList<ResolvedTextureBarrier> resolvedTextures = new ArrayList<>();
                    for (TextureBarrier source : barrier.textureBarriers()) {
                        if (source.sourceStages().contains(RenderPipelineStage.PRESENT)
                                || source.destinationStages().contains(RenderPipelineStage.PRESENT)) {
                            throw new UnsupportedOperationException(
                                    "generic Vulkan command path does not own presentation queue-family transfers"
                            );
                        }
                        VulkanGenericResourceRegistry.TextureRecord record = resources.requireTexture(
                                source.slice().resource());
                        // A barrier may be the exact transition from an earlier write in this
                        // transaction to a later shader read. That generation is deliberately
                        // not GPU_READY until this submission's fence completes. Requiring ready
                        // here would make the normal write -> barrier -> use sequence impossible.
                        // Without a preceding write, retain the cross-transaction readiness gate.
                        if (!wasWrittenEarlier(record, textureWrites)) resources.requireReadable(record);
                        resolvedTextures.add(new ResolvedTextureBarrier(record, source));
                        textureReads.add(record);
                    }
                    actions.add(new Barrier(List.copyOf(resolved), List.copyOf(resolvedTextures)));
                }
                default -> throw new UnsupportedOperationException(
                        "generic Vulkan command is not implemented: " + command.getClass().getSimpleName()
                );
            }
        }
        return new VulkanGenericCommandPlan(actions, writes, reads, textureWrites, textureReads,
                Optional.ofNullable(outputResource));
    }

    private static void validateBindingResources(
            VulkanGenericResourceRegistry resources,
            BindingSet bindings,
            List<VulkanGenericResourceRegistry.BufferRecord> reads,
            List<VulkanGenericResourceRegistry.TextureRecord> textureReads
    ) {
        for (List<BindingSet.Value> values : bindings.values().values()) {
            for (BindingSet.Value value : values) {
                switch (value) {
                    case BindingSet.BufferValue buffer -> {
                        VulkanGenericResourceRegistry.BufferRecord record = resources.requireBuffer(buffer.buffer());
                        resources.requireReadable(record);
                        reads.add(record);
                    }
                    case BindingSet.TextureValue texture -> {
                        VulkanGenericResourceRegistry.TextureRecord record = resources.requireTexture(texture.view().texture());
                        resources.requireReadable(record);
                        textureReads.add(record);
                    }
                    case BindingSet.CombinedImageSamplerValue combined -> {
                        VulkanGenericResourceRegistry.TextureRecord record =
                                resources.requireTexture(combined.view().texture());
                        resources.requireReadable(record);
                        textureReads.add(record);
                    }
                    case BindingSet.SamplerValue ignored -> { }
                }
            }
        }
    }

    List<Action> actions() { return actions; }
    List<VulkanGenericResourceRegistry.BufferRecord> writes() { return writes; }
    List<VulkanGenericResourceRegistry.BufferRecord> reads() { return reads; }
    List<VulkanGenericResourceRegistry.TextureRecord> textureWrites() { return textureWrites; }
    List<VulkanGenericResourceRegistry.TextureRecord> textureReads() { return textureReads; }
    Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource() { return outputResource; }

    private static List<VulkanGenericResourceRegistry.BufferRecord> distinct(
            List<VulkanGenericResourceRegistry.BufferRecord> source
    ) {
        LinkedHashMap<Object, VulkanGenericResourceRegistry.BufferRecord> distinct = new LinkedHashMap<>();
        for (VulkanGenericResourceRegistry.BufferRecord record : source) {
            distinct.put(record.generation(), record);
        }
        return List.copyOf(distinct.values());
    }
    private static List<VulkanGenericResourceRegistry.TextureRecord> distinctTextures(
            List<VulkanGenericResourceRegistry.TextureRecord> source
    ) {
        LinkedHashMap<Object, VulkanGenericResourceRegistry.TextureRecord> distinct = new LinkedHashMap<>();
        for (VulkanGenericResourceRegistry.TextureRecord record : source) distinct.put(record.generation(), record);
        return List.copyOf(distinct.values());
    }

    private static boolean wasWrittenEarlier(
            VulkanGenericResourceRegistry.TextureRecord target,
            List<VulkanGenericResourceRegistry.TextureRecord> priorWrites
    ) {
        return priorWrites.stream().anyMatch(written -> written.generation().equals(target.generation()));
    }

    sealed interface Action permits BeginPass, EndPass, BindGraphics, BindGraphicsBindings, GraphicsPushConstants,
            BindVertex, BindIndex, ViewportAction, ScissorAction, Draw, DrawIndexed, MultiDraw, MultiDrawIndexed, Indirect,
            BindCompute, BindComputeBindings, PushConstants, Dispatch, Write, WriteTexture, Copy, CopyTexture,
            CopyTextureRegion, Barrier { }

    record BeginPass(RenderPassDescriptor descriptor, List<ResolvedAttachment> colors,
                     ResolvedAttachment depth, ResolvedAttachment stencil) implements Action { }
    record EndPass() implements Action { }
    record BindGraphics(VulkanGenericGraphicsPipelines.Compiled pipeline) implements Action { }
    record BindGraphicsBindings(VulkanGenericGraphicsPipelines.Compiled pipeline, BindBindingSetCommand command) implements Action { }
    record GraphicsPushConstants(VulkanGenericGraphicsPipelines.Compiled pipeline, SetPushConstantsCommand command) implements Action { }
    record BindVertex(VulkanGenericGraphicsPipelines.Compiled pipeline, BindVertexBufferCommand command,
                      VulkanGenericResourceRegistry.BufferRecord record) implements Action { }
    record BindIndex(VulkanGenericGraphicsPipelines.Compiled pipeline, BindIndexBufferCommand command,
                     VulkanGenericResourceRegistry.BufferRecord record) implements Action { }
    record ViewportAction(Viewport viewport) implements Action { }
    record ScissorAction(ScissorRectangle scissor) implements Action { }
    record Draw(VulkanGenericGraphicsPipelines.Compiled pipeline, DrawCommand command) implements Action { }
    record DrawIndexed(VulkanGenericGraphicsPipelines.Compiled pipeline, DrawIndexedCommand command) implements Action { }
    record MultiDraw(VulkanGenericGraphicsPipelines.Compiled pipeline, MultiDrawCommand command) implements Action { }
    record MultiDrawIndexed(VulkanGenericGraphicsPipelines.Compiled pipeline, MultiDrawIndexedCommand command) implements Action { }
    record Indirect(VulkanGenericGraphicsPipelines.Compiled pipeline, IndirectDrawCommand command,
                    VulkanGenericResourceRegistry.BufferRecord arguments,
                    VulkanGenericResourceRegistry.BufferRecord count) implements Action { }
    record ResolvedAttachment(VulkanGenericResourceRegistry.TextureRecord record,
                              top.ceroxe.rt.renderer.api.RenderAttachment attachment,
                              top.ceroxe.rt.renderer.api.TextureView resolveView,
                              VulkanGenericResourceRegistry.TextureRecord resolveRecord) { }

    record BindCompute(VulkanGenericComputePipelines.Compiled pipeline) implements Action { }

    record BindComputeBindings(
            VulkanGenericComputePipelines.Compiled pipeline,
            BindBindingSetCommand command
    ) implements Action { }

    record PushConstants(
            VulkanGenericComputePipelines.Compiled pipeline,
            SetPushConstantsCommand command
    ) implements Action { }

    record Dispatch(
            VulkanGenericComputePipelines.Compiled pipeline,
            DispatchCommand command
    ) implements Action { }

    record Write(
            VulkanGenericResourceRegistry.BufferRecord destination,
            long destinationOffset,
            java.nio.ByteBuffer data
    ) implements Action { }

    record WriteTexture(VulkanGenericResourceRegistry.TextureRecord destination, WriteTextureCommand command)
            implements Action { }

    record CopyTexture(
            VulkanGenericResourceRegistry.TextureRecord source,
            VulkanGenericResourceRegistry.TextureRecord destination,
            CopyTextureCommand command
    ) implements Action { }

    record CopyTextureRegion(
            VulkanGenericResourceRegistry.TextureRecord source,
            VulkanGenericResourceRegistry.TextureRecord destination,
            CopyTextureRegionCommand command
    ) implements Action { }

    record Copy(
            VulkanGenericResourceRegistry.BufferRecord source,
            long sourceOffset,
            VulkanGenericResourceRegistry.BufferRecord destination,
            long destinationOffset,
            long byteCount
    ) implements Action { }

    record Barrier(List<ResolvedBufferBarrier> buffers, List<ResolvedTextureBarrier> textures) implements Action { }

    private static ResolvedAttachment resolveAttachment(
            VulkanGenericResourceRegistry resources,
            top.ceroxe.rt.renderer.api.RenderAttachment attachment,
            boolean writable
    ) {
        if (attachment == null) return null;
        VulkanGenericResourceRegistry.TextureRecord record = resources.requireTexture(attachment.view().texture());
        resources.requireAttachmentWritable(record, attachment.loadOperation());
        return new ResolvedAttachment(record, attachment, null, null);
    }

    record ResolvedBufferBarrier(
            VulkanGenericResourceRegistry.BufferRecord resource,
            BufferBarrier barrier
    ) { }

    record ResolvedTextureBarrier(
            VulkanGenericResourceRegistry.TextureRecord resource,
            TextureBarrier barrier
    ) { }
}
