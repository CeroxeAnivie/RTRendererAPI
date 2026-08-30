package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.BufferBarrier;
import top.ceroxe.rt.renderer.api.CopyBufferCommand;
import top.ceroxe.rt.renderer.api.CopyTextureCommand;
import top.ceroxe.rt.renderer.api.CopyTextureRegionCommand;
import top.ceroxe.rt.renderer.api.CopyBufferToTextureCommand;
import top.ceroxe.rt.renderer.api.CopyTextureToBufferCommand;
import top.ceroxe.rt.renderer.api.ClearColorCommand;
import top.ceroxe.rt.renderer.api.ClearDepthStencilCommand;
import top.ceroxe.rt.renderer.api.BindBindingSetCommand;
import top.ceroxe.rt.renderer.api.BindComputePipelineCommand;
import top.ceroxe.rt.renderer.api.BindRayTracingPipelineCommand;
import top.ceroxe.rt.renderer.api.BuildBottomLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.BuildTopLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.DispatchCommand;
import top.ceroxe.rt.renderer.api.DestroyAccelerationStructureCommand;
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
import top.ceroxe.rt.renderer.api.TraceRaysCommand;

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
    private final Optional<VulkanGenericResourceRegistry.TextureRecord> outputRecord;
    private final VulkanGenericAccelerationStructures.Compilation accelerationStructures;

    private VulkanGenericCommandPlan(
            List<Action> actions,
            List<VulkanGenericResourceRegistry.BufferRecord> writes,
            List<VulkanGenericResourceRegistry.BufferRecord> reads,
            List<VulkanGenericResourceRegistry.TextureRecord> textureWrites,
            List<VulkanGenericResourceRegistry.TextureRecord> textureReads,
            Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource,
            Optional<VulkanGenericResourceRegistry.TextureRecord> outputRecord,
            VulkanGenericAccelerationStructures.Compilation accelerationStructures
    ) {
        this.actions = List.copyOf(actions);
        this.writes = distinct(writes);
        this.reads = distinct(reads);
        this.textureWrites = distinctTextures(textureWrites);
        this.textureReads = distinctTextures(textureReads);
        this.outputResource = Objects.requireNonNull(outputResource, "outputResource");
        this.outputRecord = Objects.requireNonNull(outputRecord, "outputRecord");
        this.accelerationStructures = Objects.requireNonNull(accelerationStructures, "accelerationStructures");
    }

    static VulkanGenericCommandPlan compile(
            VulkanGenericResourceRegistry resources,
            VulkanGenericComputePipelines pipelines,
            VulkanGenericGraphicsPipelines graphicsPipelines,
            VulkanGenericRayTracingPipelines rayTracingPipelines,
            VulkanGenericAccelerationStructures accelerationStructures,
            RenderCommandTransaction transaction
    ) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(rayTracingPipelines, "rayTracingPipelines");
        Objects.requireNonNull(accelerationStructures, "accelerationStructures");
        Objects.requireNonNull(transaction, "transaction");
        VulkanGenericAccelerationStructures.Compilation asCompilation = accelerationStructures.beginCompilation();
        try {
        ArrayList<Action> actions = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.BufferRecord> writes = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.BufferRecord> reads = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.BufferRecord> locallyReadableBuffers = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.TextureRecord> textureWrites = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.TextureRecord> textureReads = new ArrayList<>();
        ArrayList<VulkanGenericResourceRegistry.TextureRecord> locallyReadableTextures = new ArrayList<>();
        top.ceroxe.rt.renderer.api.RenderResourceId outputResource = null;
        VulkanGenericResourceRegistry.TextureRecord outputRecord = null;
        VulkanGenericComputePipelines.Compiled computePipeline = null;
        VulkanGenericGraphicsPipelines.Compiled graphicsPipeline = null;
        VulkanGenericRayTracingPipelines.Compiled rayTracingPipeline = null;
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
                            outputRecord = record;
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
                case BindRayTracingPipelineCommand bind -> {
                    rayTracingPipeline = rayTracingPipelines.require(bind.pipeline());
                    actions.add(new BindRayTracing(rayTracingPipeline));
                }
                case BindBindingSetCommand bind -> {
                    if (computePipeline == null && graphicsPipeline == null && rayTracingPipeline == null) {
                        throw new IllegalArgumentException("binding set has no active pipeline in generic backend");
                    }
                    validateBindingResources(resources, bind.bindingSet(), reads, textureReads, writes, textureWrites,
                            locallyReadableBuffers, locallyReadableTextures, actions, asCompilation);
                    for (long offset : bind.dynamicOffsets()) {
                        if (offset > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException("dynamic descriptor offset exceeds Vulkan uint32 command range");
                        }
                    }
                    if (computePipeline != null) actions.add(new BindComputeBindings(computePipeline, bind));
                    else if (graphicsPipeline != null) actions.add(new BindGraphicsBindings(graphicsPipeline, bind));
                    else actions.add(new BindRayTracingBindings(rayTracingPipeline, bind));
                }
                case SetPushConstantsCommand push -> {
                    if (computePipeline == null && graphicsPipeline == null && rayTracingPipeline == null) {
                        throw new IllegalArgumentException("push constants have no active pipeline in generic backend");
                    }
                    if (computePipeline != null) actions.add(new PushConstants(computePipeline, push));
                    else if (graphicsPipeline != null) actions.add(new GraphicsPushConstants(graphicsPipeline, push));
                    else actions.add(new RayTracingPushConstants(rayTracingPipeline, push));
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
                    requireReadable(resources, record, writes, locallyReadableBuffers, actions);
                    reads.add(record);
                    actions.add(new BindVertex(graphicsPipeline, bind, record));
                }
                case BindIndexBufferCommand bind -> {
                    if (graphicsPipeline == null) throw new IllegalArgumentException("index buffer has no active graphics pipeline");
                    var record = resources.requireBuffer(bind.slice().resource());
                    requireReadable(resources, record, writes, locallyReadableBuffers, actions);
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
                    requireReadable(resources, arguments, writes, locallyReadableBuffers, actions);
                    reads.add(arguments);
                    VulkanGenericResourceRegistry.BufferRecord count = null;
                    if (indirect.count().isPresent()) {
                        count = resources.requireBuffer(indirect.count().orElseThrow().resource());
                        requireReadable(resources, count, writes, locallyReadableBuffers, actions);
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
                    requireReadable(resources, source, writes, locallyReadableBuffers, actions);
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
                    requireReadable(resources, source, locallyReadableTextures);
                    resources.requireWritable(destination);
                    actions.add(new CopyTexture(source, destination, copy));
                    textureReads.add(source);
                    textureWrites.add(destination);
                }
                case CopyTextureRegionCommand copy -> {
                    VulkanGenericResourceRegistry.TextureRecord source = resources.requireTexture(copy.source().resource());
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            copy.destination().resource());
                    requireReadable(resources, source, locallyReadableTextures);
                    resources.requireWritable(destination);
                    actions.add(new CopyTextureRegion(source, destination, copy));
                    textureReads.add(source);
                    textureWrites.add(destination);
                }
                case CopyBufferToTextureCommand copy -> {
                    VulkanGenericResourceRegistry.BufferRecord source = resources.requireBuffer(copy.source().resource());
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            copy.destination().resource());
                    requireVulkanBufferImageCopy(copy.source().range().offsetBytes(), copy.sourceLayout(), destination,
                            copy.destination().range(), copy.destinationOrigin(), copy.extent());
                    requireReadable(resources, source, writes, locallyReadableBuffers, actions);
                    resources.requireWritable(destination);
                    actions.add(new CopyBufferToTexture(source, destination, copy));
                    reads.add(source);
                    textureWrites.add(destination);
                }
                case CopyTextureToBufferCommand copy -> {
                    VulkanGenericResourceRegistry.TextureRecord source = resources.requireTexture(copy.source().resource());
                    VulkanGenericResourceRegistry.BufferRecord destination = resources.requireBuffer(
                            copy.destination().resource());
                    requireVulkanBufferImageCopy(copy.destination().range().offsetBytes(), copy.destinationLayout(), source,
                            copy.source().range(), copy.sourceOrigin(), copy.extent());
                    requireReadable(resources, source, locallyReadableTextures);
                    resources.requireWritable(destination);
                    actions.add(new CopyTextureToBuffer(source, destination, copy));
                    textureReads.add(source);
                    writes.add(destination);
                }
                case ClearColorCommand clear -> {
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            clear.destination().resource());
                    resources.requireWritable(destination);
                    actions.add(new ClearColor(destination, clear));
                    textureWrites.add(destination);
                }
                case ClearDepthStencilCommand clear -> {
                    VulkanGenericResourceRegistry.TextureRecord destination = resources.requireTexture(
                            clear.destination().resource());
                    resources.requireWritable(destination);
                    actions.add(new ClearDepthStencil(destination, clear));
                    textureWrites.add(destination);
                }
                case BuildBottomLevelAccelerationStructureCommand build -> {
                    ArrayList<VulkanGenericAccelerationStructures.TriangleInput> inputs = new ArrayList<>();
                    for (var geometry : build.geometries()) {
                        VulkanGenericResourceRegistry.BufferRecord vertices = resources.requireBuffer(geometry.vertices().resource());
                        requireAccelerationStructureInputReadable(
                                resources, vertices, writes, locallyReadableBuffers, actions
                        );
                        reads.add(vertices);
                        VulkanGenericResourceRegistry.BufferRecord indices = geometry.indices().isPresent()
                                ? resources.requireBuffer(geometry.indices().orElseThrow().resource()) : null;
                        if (indices != null) {
                            requireAccelerationStructureInputReadable(
                                    resources, indices, writes, locallyReadableBuffers, actions
                            );
                            reads.add(indices);
                        }
                        inputs.add(new VulkanGenericAccelerationStructures.TriangleInput(geometry, vertices, indices));
                    }
                    actions.add(new BuildAccelerationStructure(asCompilation.prepareBottom(build, inputs)));
                }
                case BuildTopLevelAccelerationStructureCommand build ->
                        actions.add(new BuildAccelerationStructure(asCompilation.prepareTop(build)));
                case DestroyAccelerationStructureCommand destroy -> asCompilation.destroy(destroy.target());
                case TraceRaysCommand trace -> {
                    if (rayTracingPipeline == null) {
                        throw new IllegalArgumentException("trace rays has no active generic ray-tracing pipeline");
                    }
                    VulkanGenericResourceRegistry.TextureRecord output = resources.requireTexture(trace.output().texture());
                    resources.requireWritable(output);
                    actions.add(new TraceRays(rayTracingPipeline, trace, output));
                    textureWrites.add(output);
                    outputResource = output.generation().id();
                    outputRecord = output;
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
                        if (wasWrittenEarlier(record, writes)) locallyReadableBuffers.add(record);
                        else resources.requireReadable(record);
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
                        if (wasWrittenEarlier(record, textureWrites)) locallyReadableTextures.add(record);
                        else resources.requireReadable(record);
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
                Optional.ofNullable(outputResource), Optional.ofNullable(outputRecord), asCompilation);
        } catch (RuntimeException | Error failure) {
            asCompilation.close();
            throw failure;
        }
    }

    private static void validateBindingResources(
            VulkanGenericResourceRegistry resources,
            BindingSet bindings,
            List<VulkanGenericResourceRegistry.BufferRecord> reads,
            List<VulkanGenericResourceRegistry.TextureRecord> textureReads,
            List<VulkanGenericResourceRegistry.BufferRecord> priorBufferWrites,
            List<VulkanGenericResourceRegistry.TextureRecord> priorTextureWrites,
            List<VulkanGenericResourceRegistry.BufferRecord> locallyReadableBuffers,
            List<VulkanGenericResourceRegistry.TextureRecord> locallyReadableTextures,
            List<Action> actions,
            VulkanGenericAccelerationStructures.Compilation accelerationStructures
    ) {
        for (List<BindingSet.Value> values : bindings.values().values()) {
            for (BindingSet.Value value : values) {
                switch (value) {
                    case BindingSet.BufferValue buffer -> {
                        VulkanGenericResourceRegistry.BufferRecord record = resources.requireBuffer(buffer.buffer());
                        requireReadable(resources, record, priorBufferWrites, locallyReadableBuffers, actions);
                        reads.add(record);
                    }
                    case BindingSet.TextureValue texture -> {
                        VulkanGenericResourceRegistry.TextureRecord record = resources.requireTexture(texture.view().texture());
                        requireReadable(resources, record, priorTextureWrites, locallyReadableTextures, actions,
                                texture.view().range(), texture.type() == top.ceroxe.rt.renderer.api.BindingType.READ_WRITE_STORAGE_TEXTURE);
                        textureReads.add(record);
                    }
                    case BindingSet.CombinedImageSamplerValue combined -> {
                        VulkanGenericResourceRegistry.TextureRecord record =
                                resources.requireTexture(combined.view().texture());
                        requireReadable(resources, record, priorTextureWrites, locallyReadableTextures, actions,
                                combined.view().range(), false);
                        textureReads.add(record);
                    }
                    case BindingSet.AccelerationStructureValue accelerationStructure ->
                            accelerationStructures.requireTopLevel(accelerationStructure.accelerationStructure());
                    case BindingSet.SamplerValue ignored -> { }
                }
            }
        }
    }

    private static void requireReadable(
            VulkanGenericResourceRegistry resources,
            VulkanGenericResourceRegistry.BufferRecord record,
            List<VulkanGenericResourceRegistry.BufferRecord> priorWrites,
            List<VulkanGenericResourceRegistry.BufferRecord> locallyReadable,
            List<Action> actions
    ) {
        if (wasWrittenEarlier(record, locallyReadable)) return;
        if (!wasWrittenEarlier(record, priorWrites)) {
            resources.requireReadable(record);
            return;
        }
        // A transfer write earlier in this one command buffer is ordered only after a matching
        // memory dependency. This is internal planning, not fabricated cross-submission readiness.
        actions.add(new AutoBufferVisibility(record));
        locallyReadable.add(record);
    }

    /**
     * Uses the exact Vulkan AS-build consumer stage for a same-submission transfer upload.
     * Other producer classes remain explicit because this plan does not invent shader-write
     * visibility from a descriptor declaration.
     */
    private static void requireAccelerationStructureInputReadable(
            VulkanGenericResourceRegistry resources,
            VulkanGenericResourceRegistry.BufferRecord record,
            List<VulkanGenericResourceRegistry.BufferRecord> priorWrites,
            List<VulkanGenericResourceRegistry.BufferRecord> locallyReadable,
            List<Action> actions
    ) {
        if (wasWrittenEarlier(record, locallyReadable)) return;
        if (!wasWrittenEarlier(record, priorWrites)) {
            resources.requireReadable(record);
            return;
        }
        actions.add(new AutoAccelerationStructureInputVisibility(record));
        locallyReadable.add(record);
    }

    private static void requireReadable(
            VulkanGenericResourceRegistry resources,
            VulkanGenericResourceRegistry.BufferRecord record,
            List<VulkanGenericResourceRegistry.BufferRecord> locallyReadable
    ) {
        if (!wasWrittenEarlier(record, locallyReadable)) resources.requireReadable(record);
    }

    private static void requireReadable(
            VulkanGenericResourceRegistry resources,
            VulkanGenericResourceRegistry.TextureRecord record,
            List<VulkanGenericResourceRegistry.TextureRecord> priorWrites,
            List<VulkanGenericResourceRegistry.TextureRecord> locallyReadable,
            List<Action> actions,
            top.ceroxe.rt.renderer.api.TextureSubresourceRange range,
            boolean writable
    ) {
        if (wasWrittenEarlier(record, locallyReadable)) return;
        if (!wasWrittenEarlier(record, priorWrites)) {
            resources.requireReadable(record);
            return;
        }
        actions.add(new AutoTextureVisibility(record, range, writable));
        locallyReadable.add(record);
    }

    private static void requireReadable(
            VulkanGenericResourceRegistry resources,
            VulkanGenericResourceRegistry.TextureRecord record,
            List<VulkanGenericResourceRegistry.TextureRecord> locallyReadable
    ) {
        if (!wasWrittenEarlier(record, locallyReadable)) resources.requireReadable(record);
    }

    List<Action> actions() { return actions; }
    List<VulkanGenericResourceRegistry.BufferRecord> writes() { return writes; }
    List<VulkanGenericResourceRegistry.BufferRecord> reads() { return reads; }
    List<VulkanGenericResourceRegistry.TextureRecord> textureWrites() { return textureWrites; }
    List<VulkanGenericResourceRegistry.TextureRecord> textureReads() { return textureReads; }
    Optional<top.ceroxe.rt.renderer.api.RenderResourceId> outputResource() { return outputResource; }
    Optional<VulkanGenericResourceRegistry.TextureRecord> outputRecord() { return outputRecord; }
    VulkanGenericAccelerationStructures.Compilation accelerationStructures() { return accelerationStructures; }

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
            VulkanGenericResourceRegistry.BufferRecord target,
            List<VulkanGenericResourceRegistry.BufferRecord> priorWrites
    ) {
        return priorWrites.contains(target);
    }

    private static boolean wasWrittenEarlier(
            VulkanGenericResourceRegistry.TextureRecord target,
            List<VulkanGenericResourceRegistry.TextureRecord> priorWrites
    ) {
        return priorWrites.stream().anyMatch(written -> written.generation().equals(target.generation()));
    }

    /**
     * Rejects portable byte layouts that Vulkan cannot encode without an intermediate repack.
     *
     * <p>Unlike host uploads, these commands name an already-resident caller buffer. Rounding a
     * pitch to a texel count or silently allocating an unobservable scratch buffer would change
     * the ordered transaction. The generic lane therefore admits only the exact native subset.</p>
     */
    private static void requireVulkanBufferImageCopy(
            long sliceOffset,
            top.ceroxe.rt.renderer.api.TextureDataLayout layout,
            VulkanGenericResourceRegistry.TextureRecord texture,
            top.ceroxe.rt.renderer.api.TextureSubresourceRange range,
            top.ceroxe.rt.renderer.api.TextureOrigin origin,
            top.ceroxe.rt.renderer.api.TextureExtent extent
    ) {
        if (texture.descriptor().sampleCount() != 1) {
            throw new UnsupportedOperationException("Vulkan buffer-image copies require single-sample textures");
        }
        int bytesPerTexel = bytesPerTexel(texture.descriptor().format());
        if (layout.bytesPerRow() % bytesPerTexel != 0L) {
            throw new UnsupportedOperationException(
                    "Vulkan buffer-image copies require bytesPerRow divisible by the texture texel size"
            );
        }
        long bufferOffset;
        try {
            bufferOffset = Math.addExact(sliceOffset, layout.offsetBytes());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("buffer-image copy offset overflows long", overflow);
        }
        if ((bufferOffset & 3L) != 0L) {
            throw new UnsupportedOperationException("Vulkan buffer-image copy offset must be four-byte aligned");
        }
        long rowLength = layout.bytesPerRow() / bytesPerTexel;
        if (rowLength > Integer.MAX_VALUE || layout.rowsPerImage() > Integer.MAX_VALUE) {
            throw new UnsupportedOperationException("Vulkan buffer-image copy pitch exceeds uint32 limits");
        }
        // The portable command constructor has already proved containment. Keep these values in
        // the signature so every native-only restriction is checked with the exact request.
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(extent, "extent");
    }

    private static int bytesPerTexel(top.ceroxe.rt.renderer.api.TextureFormat format) {
        return switch (format) {
            case R8_UNORM -> 1;
            case RG8_UNORM, R16_FLOAT -> 2;
            case RGBA8_UNORM, RGBA8_SRGB, R32_FLOAT, D32_FLOAT, D24_UNORM_S8_UINT, RG16_FLOAT -> 4;
            case RGBA16_FLOAT, RG32_FLOAT -> 8;
            case RGBA32_FLOAT -> 16;
        };
    }

    sealed interface Action permits BeginPass, EndPass, BindGraphics, BindGraphicsBindings, GraphicsPushConstants,
            BindVertex, BindIndex, ViewportAction, ScissorAction, Draw, DrawIndexed, MultiDraw, MultiDrawIndexed, Indirect,
            BindCompute, BindComputeBindings, PushConstants, Dispatch, BindRayTracing, BindRayTracingBindings,
            RayTracingPushConstants, BuildAccelerationStructure, TraceRays, Write, WriteTexture, Copy, CopyTexture,
            CopyTextureRegion, CopyBufferToTexture, CopyTextureToBuffer, ClearColor, ClearDepthStencil,
            AutoBufferVisibility, AutoAccelerationStructureInputVisibility, AutoTextureVisibility, Barrier { }

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

    record BindRayTracing(VulkanGenericRayTracingPipelines.Compiled pipeline) implements Action { }

    record BindRayTracingBindings(
            VulkanGenericRayTracingPipelines.Compiled pipeline,
            BindBindingSetCommand command
    ) implements Action { }

    record RayTracingPushConstants(
            VulkanGenericRayTracingPipelines.Compiled pipeline,
            SetPushConstantsCommand command
    ) implements Action { }

    record BuildAccelerationStructure(VulkanGenericAccelerationStructures.PreparedBuild build) implements Action { }

    record TraceRays(
            VulkanGenericRayTracingPipelines.Compiled pipeline,
            TraceRaysCommand command,
            VulkanGenericResourceRegistry.TextureRecord output
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

    record CopyBufferToTexture(
            VulkanGenericResourceRegistry.BufferRecord source,
            VulkanGenericResourceRegistry.TextureRecord destination,
            CopyBufferToTextureCommand command
    ) implements Action { }

    record CopyTextureToBuffer(
            VulkanGenericResourceRegistry.TextureRecord source,
            VulkanGenericResourceRegistry.BufferRecord destination,
            CopyTextureToBufferCommand command
    ) implements Action { }

    record ClearColor(VulkanGenericResourceRegistry.TextureRecord destination, ClearColorCommand command)
            implements Action { }

    record ClearDepthStencil(
            VulkanGenericResourceRegistry.TextureRecord destination,
            ClearDepthStencilCommand command
    ) implements Action { }

    /** Submission-local visibility edge for an earlier transfer write consumed later in this command buffer. */
    record AutoBufferVisibility(VulkanGenericResourceRegistry.BufferRecord resource) implements Action { }

    /** Exact transfer-write to AS-build-read visibility edge for declared addressable triangle input. */
    record AutoAccelerationStructureInputVisibility(VulkanGenericResourceRegistry.BufferRecord resource) implements Action { }

    /** Submission-local layout and visibility edge for an earlier write consumed through a descriptor. */
    record AutoTextureVisibility(
            VulkanGenericResourceRegistry.TextureRecord resource,
            top.ceroxe.rt.renderer.api.TextureSubresourceRange range,
            boolean writable
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
