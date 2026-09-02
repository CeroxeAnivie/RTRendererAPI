package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stateful single-pass validator kept separate from the immutable transaction value. */
final class RenderCommandSequenceValidator {
    private RenderPassDescriptor pass;
    private GraphicsPipelineState pipeline;
    private ComputePipelineState computePipeline;
    private RayTracingPipelineState rayTracingPipeline;
    private Viewport viewport;
    private ScissorRectangle scissor;
    private BindingSet bindingSet;
    private final Map<Integer, ResourceSlice.BufferSlice> vertexBuffers = new HashMap<>();
    private final List<PushConstantWrite> pushConstantWrites = new ArrayList<>();
    private BindIndexBufferCommand indexBuffer;

    private RenderCommandSequenceValidator() { }

    static void validate(List<RenderCommand> commands) {
        RenderCommandSequenceValidator validator = new RenderCommandSequenceValidator();
        for (int index = 0; index < commands.size(); index++) {
            try {
                validator.accept(commands.get(index));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid render command at index " + index + ": " + exception.getMessage(), exception);
            }
        }
        if (validator.pass != null) {
            throw new IllegalArgumentException("render command transaction ends with an open render pass");
        }
    }

    private void accept(RenderCommand command) {
        switch (command) {
            case BeginRenderPassCommand begin -> begin(begin.descriptor());
            case EndRenderPassCommand ignored -> end();
            case BindGraphicsPipelineCommand bind -> {
                requireInsidePass("bind graphics pipeline");
                if (computePipeline != null || rayTracingPipeline != null) {
                    throw new IllegalArgumentException("graphics, compute, and ray-tracing pipeline state cannot be active together");
                }
                requirePipelineCompatible(bind.pipeline());
                pipeline = bind.pipeline();
                bindingSet = null;
                pushConstantWrites.clear();
            }
            case BindComputePipelineCommand bind -> {
                requireOutsidePass("bind compute pipeline");
                if (pipeline != null || rayTracingPipeline != null) {
                    throw new IllegalArgumentException("graphics, compute, and ray-tracing pipeline state cannot be active together");
                }
                computePipeline = bind.pipeline();
                bindingSet = null;
                pushConstantWrites.clear();
            }
            case BindRayTracingPipelineCommand bind -> {
                requireOutsidePass("bind ray-tracing pipeline");
                if (pipeline != null || computePipeline != null) {
                    throw new IllegalArgumentException("graphics, compute, and ray-tracing pipeline state cannot be active together");
                }
                rayTracingPipeline = bind.pipeline();
                bindingSet = null;
                pushConstantWrites.clear();
            }
            case BindBindingSetCommand bind -> {
                requireActiveProgram("bind binding set");
                if (!bind.bindingSet().layout().entries().equals(activeProgram().bindingLayout().entries())) {
                    throw new IllegalArgumentException("binding set layout does not match the bound pipeline program");
                }
                bindingSet = bind.bindingSet();
            }
            case SetViewportCommand set -> {
                requireInsidePass("set viewport");
                viewport = set.viewport();
            }
            case SetScissorCommand set -> {
                requireInsidePass("set scissor");
                requireScissorContained(set.scissor());
                scissor = set.scissor();
            }
            case BindVertexBufferCommand bind -> {
                requireInsidePass("bind vertex buffer");
                vertexBuffers.put(bind.binding(), bind.slice());
            }
            case BindIndexBufferCommand bind -> {
                requireInsidePass("bind index buffer");
                indexBuffer = bind;
            }
            case DrawCommand draw -> {
                requireDrawState(false);
                requireImmediateUniforms();
                requireDirectVertexRanges(draw);
            }
            case DrawIndexedCommand draw -> {
                requireDrawState(true);
                requireImmediateUniforms();
                requireIndexRange(draw);
                requireInstanceRanges(draw.firstInstance(), draw.instanceCount());
            }
            case MultiDrawCommand multi -> {
                requireDrawState(false);
                requireImmediateUniforms();
                for (DrawCommand draw : multi.draws()) requireDirectVertexRanges(draw);
            }
            case MultiDrawIndexedCommand multi -> {
                requireDrawState(true);
                requireImmediateUniforms();
                for (DrawIndexedCommand draw : multi.draws()) {
                    requireIndexRange(draw);
                    requireInstanceRanges(draw.firstInstance(), draw.instanceCount());
                }
            }
            case IndirectDrawCommand indirect -> {
                requireDrawState(indirect.kind().indexed());
                requireImmediateUniforms();
            }
            case DispatchCommand ignored -> { requireComputeState("dispatch"); requireImmediateUniforms(); }
            case DispatchIndirectCommand ignored -> { requireComputeState("dispatch indirect"); requireImmediateUniforms(); }
            case TraceRaysCommand ignored -> { requireRayTracingState(); requireImmediateUniforms(); }
            case SetPushConstantsCommand set -> requirePushConstants(set);
            case WriteBufferCommand ignored -> requireOutsidePass("write buffer");
            case WriteTextureCommand ignored -> requireOutsidePass("write texture");
            case CopyBufferCommand ignored -> requireOutsidePass("copy buffer");
            case CopyTextureCommand ignored -> requireOutsidePass("copy texture");
            case CopyTextureRegionCommand ignored -> requireOutsidePass("copy texture region");
            case CopyBufferToTextureCommand ignored -> requireOutsidePass("copy buffer to texture");
            case CopyTextureToBufferCommand ignored -> requireOutsidePass("copy texture to buffer");
            case ClearColorCommand ignored -> requireOutsidePass("clear color");
            case ClearDepthStencilCommand ignored -> requireOutsidePass("clear depth/stencil");
            case BuildBottomLevelAccelerationStructureCommand ignored -> requireOutsidePass("build bottom-level AS");
            case BuildTopLevelAccelerationStructureCommand ignored -> requireOutsidePass("build top-level AS");
            case DestroyAccelerationStructureCommand ignored -> requireOutsidePass("destroy acceleration structure");
            case ResourceBarrierCommand ignored -> requireOutsidePass("apply resource barrier");
        }
    }

    private void begin(RenderPassDescriptor descriptor) {
        requireOutsidePass("begin render pass");
        if (computePipeline != null || rayTracingPipeline != null) {
            throw new IllegalArgumentException("cannot begin a graphics pass while a compute or ray-tracing pipeline is active");
        }
        pass = descriptor;
        pipeline = null;
        viewport = null;
        scissor = null;
        bindingSet = null;
        pushConstantWrites.clear();
        vertexBuffers.clear();
        indexBuffer = null;
        computePipeline = null;
        rayTracingPipeline = null;
    }

    private void end() {
        requireInsidePass("end render pass");
        pass = null;
        pipeline = null;
        viewport = null;
        scissor = null;
        bindingSet = null;
        pushConstantWrites.clear();
        vertexBuffers.clear();
        indexBuffer = null;
    }

    private void requireDrawState(boolean indexed) {
        requireInsidePass(indexed ? "draw indexed" : "draw");
        requirePipeline(indexed ? "draw indexed" : "draw");
        if (viewport == null) throw new IllegalArgumentException("draw requires an explicit viewport");
        if (scissor == null) throw new IllegalArgumentException("draw requires an explicit scissor");
        if (!pipeline.program().bindingLayout().entries().isEmpty() && bindingSet == null) {
            throw new IllegalArgumentException("draw requires the bound pipeline's complete binding set");
        }
        for (VertexBufferLayout layout : pipeline.vertexLayout().buffers()) {
            if (!vertexBuffers.containsKey(layout.binding())) {
                throw new IllegalArgumentException("draw requires vertex-buffer binding " + layout.binding());
            }
        }
        if (indexed && indexBuffer == null) throw new IllegalArgumentException("indexed draw requires an index buffer");
    }

    private void requireDirectVertexRanges(DrawCommand draw) {
        if (draw.vertexCount() == 0 || draw.instanceCount() == 0) return;
        for (VertexBufferLayout layout : pipeline.vertexLayout().buffers()) {
            long lastElement;
            if (layout.stepMode() == VertexBufferLayout.StepMode.VERTEX) {
                lastElement = Math.addExact((long) draw.firstVertex(), (long) draw.vertexCount()) - 1L;
            } else if (layout.stepRate() == 0) {
                lastElement = 0L;
            } else {
                long instanceEnd = Math.addExact((long) draw.firstInstance(), (long) draw.instanceCount());
                lastElement = Math.floorDiv(instanceEnd - 1L, layout.stepRate());
            }
            long requiredBytes = requiredVertexBytes(layout, lastElement);
            if (requiredBytes > vertexBuffers.get(layout.binding()).range().lengthBytes()) {
                throw new IllegalArgumentException("draw exceeds vertex-buffer binding " + layout.binding());
            }
        }
    }

    private void requireInstanceRanges(int firstInstance, int instanceCount) {
        if (instanceCount == 0) return;
        long instanceEnd = Math.addExact((long) firstInstance, (long) instanceCount);
        for (VertexBufferLayout layout : pipeline.vertexLayout().buffers()) {
            if (layout.stepMode() != VertexBufferLayout.StepMode.INSTANCE) continue;
            long lastElement = layout.stepRate() == 0
                    ? 0L
                    : Math.floorDiv(instanceEnd - 1L, layout.stepRate());
            long requiredBytes = requiredVertexBytes(layout, lastElement);
            if (requiredBytes > vertexBuffers.get(layout.binding()).range().lengthBytes()) {
                throw new IllegalArgumentException("draw exceeds instance-buffer binding " + layout.binding());
            }
        }
    }

    private long requiredVertexBytes(VertexBufferLayout layout, long lastElement) {
        int attributeEnd = pipeline.vertexLayout().attributes().stream()
                .filter(attribute -> attribute.bufferBinding() == layout.binding())
                .mapToInt(VertexAttribute::byteEndExclusive)
                .max()
                .orElse(0);
        if (attributeEnd == 0) return 0L;
        try {
            return Math.addExact(
                    Math.multiplyExact(lastElement, (long) layout.strideBytes()),
                    attributeEnd
            );
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("draw vertex-buffer range overflows its address domain", overflow);
        }
    }

    private void requireIndexRange(DrawIndexedCommand draw) {
        if (draw.indexCount() == 0 || draw.instanceCount() == 0) return;
        long finalIndex;
        long requiredBytes;
        try {
            finalIndex = Math.addExact((long) draw.firstIndex(), (long) draw.indexCount());
            requiredBytes = Math.multiplyExact(finalIndex, (long) indexBuffer.format().byteSize());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("indexed draw range overflows its address domain", overflow);
        }
        if (requiredBytes > indexBuffer.slice().range().lengthBytes()) {
            throw new IllegalArgumentException("indexed draw exceeds the bound index-buffer slice");
        }
    }

    private void requireScissorContained(ScissorRectangle candidate) {
        if (candidate.endXExclusive() > pass.width() || candidate.endYExclusive() > pass.height()) {
            throw new IllegalArgumentException("scissor rectangle exceeds the active render area");
        }
    }

    private void requireComputeState(String operation) {
        requireOutsidePass(operation);
        if (computePipeline == null) throw new IllegalArgumentException(operation + " requires a bound compute pipeline");
        if (!computePipeline.program().bindingLayout().entries().isEmpty() && bindingSet == null) {
            throw new IllegalArgumentException(operation + " requires the bound compute pipeline's complete binding set");
        }
    }

    private void requireRayTracingState() {
        requireOutsidePass("trace rays");
        if (rayTracingPipeline == null) throw new IllegalArgumentException("trace rays requires a bound ray-tracing pipeline");
        if (!rayTracingPipeline.program().bindingLayout().entries().isEmpty() && bindingSet == null) {
            throw new IllegalArgumentException("trace rays requires the bound pipeline's complete binding set");
        }
    }

    private void requirePushConstants(SetPushConstantsCommand command) {
        requireActiveProgram("set push constants");
        ShaderProgram program = activeProgram();
        long end;
        try {
            end = Math.addExact((long) command.offsetBytes(), command.data().byteSize());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("push-constant range overflows long", overflow);
        }
        if (end > program.pushConstantByteSize()) {
            throw new IllegalArgumentException("push-constant range exceeds the active program declaration");
        }
        for (ShaderStage stage : command.stages()) {
            if (program.modules().stream().noneMatch(module -> module.stage() == stage)) {
                throw new IllegalArgumentException("push-constant stage is not present in the active program: " + stage);
            }
        }
        pushConstantWrites.add(new PushConstantWrite(command.offsetBytes(), Math.toIntExact(end), command.stages()));
    }

    private void requireImmediateUniforms() {
        for (ImmediateUniform uniform : activeProgram().immediateUniforms()) {
            for (ShaderStage stage : uniform.stages()) {
                if (!isCovered(uniform.offsetBytes(), uniform.endBytes(), stage)) {
                    throw new IllegalArgumentException("active immediate uniform has not been initialized: " + uniform.name());
                }
            }
        }
    }

    private boolean isCovered(int start, int end, ShaderStage stage) {
        int coveredUntil = start;
        for (PushConstantWrite write : pushConstantWrites.stream()
                .filter(value -> value.stages().contains(stage))
                .sorted(java.util.Comparator.comparingInt(PushConstantWrite::offsetBytes))
                .toList()) {
            if (write.endBytes() <= coveredUntil || write.offsetBytes() > coveredUntil) {
                continue;
            }
            coveredUntil = Math.max(coveredUntil, write.endBytes());
            if (coveredUntil >= end) return true;
        }
        return false;
    }

    private record PushConstantWrite(int offsetBytes, int endBytes, Set<ShaderStage> stages) {
        private PushConstantWrite {
            stages = Set.copyOf(stages);
        }
    }

    private void requireActiveProgram(String operation) {
        if (pipeline == null && computePipeline == null && rayTracingPipeline == null) {
            throw new IllegalArgumentException(operation + " requires an active graphics, compute, or ray-tracing pipeline");
        }
        int active = (pipeline != null ? 1 : 0) + (computePipeline != null ? 1 : 0) + (rayTracingPipeline != null ? 1 : 0);
        if (active != 1) {
            throw new IllegalArgumentException("graphics, compute, and ray-tracing pipeline state cannot be active together");
        }
    }

    private ShaderProgram activeProgram() {
        requireActiveProgram("active program");
        if (pipeline != null) return pipeline.program();
        if (computePipeline != null) return computePipeline.program();
        return rayTracingPipeline.program();
    }

    private void requirePipelineCompatible(GraphicsPipelineState candidate) {
        if (candidate.multisampleState().sampleCount() != pass.sampleCount()) {
            throw new IllegalArgumentException("pipeline sample count does not match the active render pass");
        }
        if (candidate.colorTargetFormats().size() != pass.colorAttachments().size()) {
            throw new IllegalArgumentException("pipeline color-target count does not match the active render pass");
        }
        for (int index = 0; index < candidate.colorTargetFormats().size(); index++) {
            TextureFormat passFormat = pass.colorAttachments().get(index).view().texture().format();
            if (candidate.colorTargetFormats().get(index) != passFormat) {
                throw new IllegalArgumentException("pipeline color-target format does not match attachment " + index);
            }
        }
        TextureFormat passDepthStencilFormat = pass.depthAttachment()
                .or(() -> pass.stencilAttachment())
                .map(attachment -> attachment.view().texture().format())
                .orElse(null);
        if (!candidate.depthStencilFormat().equals(java.util.Optional.ofNullable(passDepthStencilFormat))) {
            throw new IllegalArgumentException("pipeline depth/stencil format does not match the active render pass");
        }
        if (candidate.depthStencilState().map(DepthStencilState::stencilTestEnabled).orElse(false)
                && pass.stencilAttachment().isEmpty()) {
            throw new IllegalArgumentException("stencil-enabled pipeline requires a stencil attachment");
        }
        if (candidate.depthStencilState()
                .map(state -> state.depthTestEnabled() || state.depthWriteEnabled() || state.depthBoundsTestEnabled())
                .orElse(false) && pass.depthAttachment().isEmpty()) {
            throw new IllegalArgumentException("depth-enabled pipeline requires a depth attachment");
        }
    }

    private void requireInsidePass(String operation) {
        if (pass == null) throw new IllegalArgumentException(operation + " requires an active render pass");
    }

    private void requireOutsidePass(String operation) {
        if (pass != null) throw new IllegalArgumentException(operation + " is not valid inside a render pass");
    }

    private void requirePipeline(String operation) {
        if (pipeline == null) throw new IllegalArgumentException(operation + " requires a bound graphics pipeline");
    }
}
