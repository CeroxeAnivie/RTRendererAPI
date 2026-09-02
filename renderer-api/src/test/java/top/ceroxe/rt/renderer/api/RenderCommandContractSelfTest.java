package top.ceroxe.rt.renderer.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executable contract checks for the generic pass and command algebra.
 *
 * <p>This class deliberately has no test-framework dependency. The build may invoke its public
 * {@link #main(String[])} entry point from an isolated JavaExec task.</p>
 */
public final class RenderCommandContractSelfTest {
    private static final RenderResourceId COLOR_ID = new RenderResourceId(10L);
    private static final ResourceVersion INITIAL = ResourceVersion.initial();

    private RenderCommandContractSelfTest() { }

    /** Runs every static contract assertion and throws on the first regression. */
    public static void main(String[] args) {
        passAttachmentContract();
        shaderLinkageContract();
        commandOrderContract();
        pipelineAndBindingContract();
        drawAndIndirectContract();
        zeroStepVertexRangeContract();
        copyAndBarrierContract();
        transferAndClearContract();
        rayTracingCommandContract();
    }

    private static void passAttachmentContract() {
        TextureResource color = colorTexture(COLOR_ID, 64, 64, 1, 1, 1,
                TextureUsage.COLOR_ATTACHMENT, TextureUsage.COPY_SOURCE, TextureUsage.COPY_DESTINATION);
        TextureView view = colorView(color, 1);
        RenderAttachment cleared = RenderAttachment.cleared(
                view, StoreOp.STORE, new ClearValue.Color(0.1f, 0.2f, 0.3f, 1.0f)
        );
        RenderPassDescriptor pass = RenderPassDescriptor.color(64, 64, List.of(cleared));
        require(pass.width() == 64 && pass.height() == 64, "pass extent was not retained");
        require(pass.sampleCount() == 1, "single-sample pass has the wrong sample count");
        expectFailure(() -> new RenderAttachment(
                view, LoadOp.LOAD, StoreOp.STORE, Optional.of(new ClearValue.Color(0, 0, 0, 1))
        ));
        expectFailure(() -> new RenderPassDescriptor(32, 64, 1, List.of(cleared), Optional.empty(), Optional.empty()));

        TextureResource arrayColor = colorTexture(new RenderResourceId(11L), 32, 16, 1, 1, 2,
                TextureUsage.COLOR_ATTACHMENT);
        RenderPassDescriptor layered = new RenderPassDescriptor(
                32, 16, 2,
                List.of(RenderAttachment.of(colorView(arrayColor, 2), LoadOp.LOAD, StoreOp.DISCARD)),
                Optional.empty(), Optional.empty()
        );
        require(layered.layerCount() == 2, "layered pass lost its layer count");

        TextureResource multisample = colorTexture(new RenderResourceId(12L), 64, 64, 1, 2, 1,
                TextureUsage.COLOR_ATTACHMENT);
        expectFailure(() -> RenderPassDescriptor.color(64, 64, List.of(
                RenderAttachment.of(colorView(multisample, 1), LoadOp.LOAD, StoreOp.STORE), cleared
        )));

        TextureView multisampleView = new TextureView(multisample, TextureViewDimension.TEXTURE_2D_MULTISAMPLED,
                new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
        TextureResource resolved = colorTexture(new RenderResourceId(13L), 64, 64, 1, 1, 1,
                TextureUsage.COLOR_ATTACHMENT);
        TextureView resolvedView = colorView(resolved, 1);
        RenderPassDescriptor resolvePass = new RenderPassDescriptor(
                64, 64, 1, List.of(RenderAttachment.of(multisampleView, LoadOp.LOAD, StoreOp.DISCARD)),
                List.of(Optional.of(resolvedView)), Optional.empty(), Optional.empty()
        );
        require(resolvePass.colorResolveAttachments().getFirst().orElseThrow() == resolvedView,
                "color resolve target was not retained");
        expectFailure(() -> new RenderPassDescriptor(
                64, 64, 1, List.of(RenderAttachment.of(multisampleView, LoadOp.LOAD, StoreOp.DISCARD)),
                List.of(Optional.of(multisampleView)), Optional.empty(), Optional.empty()
        ));
        TextureResource incompatibleResolve = new TextureResource(
                new RenderResourceId(14L), INITIAL, TextureDimension.TEXTURE_2D,
                64, 64, 1, 1, 1, 1, TextureFormat.RGBA16_FLOAT,
                EnumSet.of(TextureUsage.COLOR_ATTACHMENT)
        );
        expectFailure(() -> new RenderPassDescriptor(
                64, 64, 1, List.of(RenderAttachment.of(multisampleView, LoadOp.LOAD, StoreOp.DISCARD)),
                List.of(Optional.of(colorView(incompatibleResolve, 1))), Optional.empty(), Optional.empty()
        ));
    }

    private static void shaderLinkageContract() {
        ShaderInterfaceType float2 = new ShaderInterfaceType(
                ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 2
        );
        ShaderInterfaceVariable output = new ShaderInterfaceVariable(
                3, float2, ShaderInterfaceVariable.Interpolation.SMOOTH
        );
        ShaderInterfaceVariable input = new ShaderInterfaceVariable(
                3, float2, ShaderInterfaceVariable.Interpolation.SMOOTH
        );
        new ShaderProgram(
                new RenderResourceId(15L), INITIAL, ShaderProgram.Kind.GRAPHICS,
                List.of(
                        module(new RenderResourceId(16L), ShaderStage.VERTEX,
                                new ShaderReflection(List.of(), 0, List.of(), List.of(output))),
                        module(new RenderResourceId(17L), ShaderStage.FRAGMENT,
                                new ShaderReflection(List.of(), 0, List.of(input), List.of()))
                ), new BindingLayout(List.of()), 0
        );
        ShaderInterfaceVariable wrongInterpolation = new ShaderInterfaceVariable(
                3, float2, ShaderInterfaceVariable.Interpolation.FLAT
        );
        expectFailure(() -> new ShaderProgram(
                new RenderResourceId(18L), INITIAL, ShaderProgram.Kind.GRAPHICS,
                List.of(
                        module(new RenderResourceId(19L), ShaderStage.VERTEX,
                                new ShaderReflection(List.of(), 0, List.of(), List.of(output))),
                        module(new RenderResourceId(20L), ShaderStage.FRAGMENT,
                                new ShaderReflection(List.of(), 0, List.of(wrongInterpolation), List.of()))
                ), new BindingLayout(List.of()), 0
        ));
    }

    private static void commandOrderContract() {
        RenderPassDescriptor pass = simplePass();
        GraphicsPipelineState pipeline = emptyPipeline();
        List<RenderCommand> valid = List.of(
                new BeginRenderPassCommand(pass),
                new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)),
                new DrawCommand(3, 1, 0, 0),
                new EndRenderPassCommand()
        );
        RenderCommandTransaction transaction = new RenderCommandTransaction(7L, valid);
        require(transaction.commands().size() == valid.size(), "transaction changed command order");
        expectFailure(() -> new RenderCommandTransaction(8L, List.of(new DrawCommand(3, 1, 0, 0))));
        expectFailure(() -> new RenderCommandTransaction(9L, List.of(
                new BeginRenderPassCommand(pass), new BeginRenderPassCommand(pass)
        )));
        expectFailure(() -> new RenderCommandTransaction(10L, List.of(
                new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline)
        )));
        expectFailure(() -> new RenderCommandTransaction(11L, List.of(
                new BeginRenderPassCommand(pass),
                new CopyBufferCommand(bufferSlice(100L, 64, BufferUsage.COPY_SOURCE),
                        bufferSlice(101L, 64, BufferUsage.COPY_DESTINATION))
        )));
        ComputePipelineState compute = new ComputePipelineState(computeProgram(16));
        new RenderCommandTransaction(17L, List.of(
                new BindComputePipelineCommand(compute),
                new SetPushConstantsCommand(Set.of(ShaderStage.COMPUTE), 0, data(16)),
                new DispatchCommand(0, 1, 2),
                new DispatchIndirectCommand(new ResourceSlice.BufferSlice(
                        buffer(18L, 16, BufferUsage.INDIRECT), new ByteRange(0, 12)
                ))
        ));
        expectFailure(() -> new RenderCommandTransaction(18L, List.of(
                new BindComputePipelineCommand(compute), new BeginRenderPassCommand(pass)
        )));
        expectFailure(() -> new RenderCommandTransaction(19L, List.of(
                new BeginRenderPassCommand(pass), new BindComputePipelineCommand(compute)
        )));
        expectFailure(() -> new RenderCommandTransaction(20L, List.of(
                new BindComputePipelineCommand(compute),
                new SetPushConstantsCommand(Set.of(ShaderStage.VERTEX), 0, data(4))
        )));

        ImmediateUniform value = new ImmediateUniform("value",
                new ShaderInterfaceType(ShaderInterfaceType.NumericType.SIGNED_INTEGER, 32, 1),
                1, 0, 8, Set.of(ShaderStage.COMPUTE));
        ShaderProgram immediateProgram = new ShaderProgram(
                new RenderResourceId(21L), INITIAL, ShaderProgram.Kind.COMPUTE,
                List.of(module(new RenderResourceId(22L), ShaderStage.COMPUTE,
                        new ShaderReflection(List.of(), 8, List.of(), List.of(), List.of(value)))),
                new BindingLayout(List.of()), 8);
        ComputePipelineState immediatePipeline = new ComputePipelineState(immediateProgram);
        expectFailure(() -> new RenderCommandTransaction(23L, List.of(
                new BindComputePipelineCommand(immediatePipeline), new DispatchCommand(1, 1, 1))));
        new RenderCommandTransaction(24L, List.of(
                new BindComputePipelineCommand(immediatePipeline),
                new SetPushConstantsCommand(Set.of(ShaderStage.COMPUTE), 4, data(4)),
                new SetPushConstantsCommand(Set.of(ShaderStage.COMPUTE), 0, data(4)),
                new DispatchCommand(1, 1, 1)));
    }

    private static void pipelineAndBindingContract() {
        BindingKey key = new BindingKey(0, 0);
        BindingLayoutEntry entry = new BindingLayoutEntry(
                key, BindingType.UNIFORM_BUFFER, 1, Set.of(ShaderStage.VERTEX), true
        );
        BindingLayout layout = new BindingLayout(List.of(entry));
        BufferResource uniform = buffer(200L, 256, BufferUsage.UNIFORM);
        BindingSet bindings = new BindingSet(layout, Map.of(key, List.of(
                new BindingSet.BufferValue(uniform, new ByteRange(0, 64), BindingType.UNIFORM_BUFFER)
        )));
        BindBindingSetCommand validBinding = new BindBindingSetCommand(bindings, List.of(192L));
        require(validBinding.dynamicOffsets().equals(List.of(192L)), "dynamic offset was not retained");
        expectFailure(() -> new BindBindingSetCommand(bindings, List.of(193L)));
        expectFailure(() -> new BindBindingSetCommand(bindings, List.of(Long.MAX_VALUE)));

        GraphicsPipelineState pipeline = pipelineWithBinding(layout);
        RenderPassDescriptor pass = simplePass();
        new RenderCommandTransaction(12L, List.of(
                new BeginRenderPassCommand(pass),
                new BindGraphicsPipelineCommand(pipeline),
                validBinding,
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)),
                new DrawCommand(0, 1, Integer.MAX_VALUE, 0),
                new EndRenderPassCommand()
        ));
        expectFailure(() -> new RenderCommandTransaction(13L, List.of(
                new BeginRenderPassCommand(pass),
                new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)),
                new DrawCommand(1, 1, 0, 0), new EndRenderPassCommand()
        )));
    }

    private static void drawAndIndirectContract() {
        RenderPassDescriptor pass = simplePass();
        GraphicsPipelineState pipeline = emptyPipeline();
        BufferResource indexResource = buffer(300L, 64, BufferUsage.INDEX);
        BindIndexBufferCommand index = new BindIndexBufferCommand(
                new ResourceSlice.BufferSlice(indexResource, new ByteRange(0, 64)), IndexFormat.UINT16
        );
        List<RenderCommand> indexed = List.of(
                new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)), index,
                new DrawIndexedCommand(0, 1, Integer.MAX_VALUE, 4, 0), new EndRenderPassCommand()
        );
        new RenderCommandTransaction(14L, indexed);
        MultiDrawCommand multi = new MultiDrawCommand(List.of(new DrawCommand(0, 0, 5, 9), new DrawCommand(3, 1, 0, 0)));
        MultiDrawIndexedCommand indexedMulti = new MultiDrawIndexedCommand(List.of(new DrawIndexedCommand(0, 0, 1, -2, 4)));
        new RenderCommandTransaction(15L, List.of(
                new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)), index, multi, indexedMulti,
                new EndRenderPassCommand()
        ));

        BufferResource indirect = buffer(301L, 128, BufferUsage.INDIRECT);
        IndirectDrawCommand fixed = IndirectDrawCommand.fixed(
                IndirectDrawCommand.Kind.DIRECT,
                new ResourceSlice.BufferSlice(indirect, new ByteRange(0, 64)), 4, 16
        );
        expectFailure(() -> IndirectDrawCommand.fixed(
                IndirectDrawCommand.Kind.INDEXED,
                new ResourceSlice.BufferSlice(indirect, new ByteRange(0, 64)), 4, 16
        ));
        expectFailure(() -> IndirectDrawCommand.fixed(
                IndirectDrawCommand.Kind.DIRECT,
                new ResourceSlice.BufferSlice(indirect, new ByteRange(0, 64)), Integer.MAX_VALUE, Integer.MAX_VALUE - 3
        ));
        expectFailure(() -> IndirectDrawCommand.fixed(
                IndirectDrawCommand.Kind.DIRECT,
                new ResourceSlice.BufferSlice(indirect, new ByteRange(0, 64)), -1, 16
        ));
        new RenderCommandTransaction(16L, List.of(
                new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)), fixed,
                new EndRenderPassCommand()
        ));
    }

    private static void zeroStepVertexRangeContract() {
        RenderPassDescriptor pass = simplePass();
        VertexLayout constantInstance = new VertexLayout(
                List.of(VertexBufferLayout.perInstance(0, 0, 0)),
                List.of(new VertexAttribute(0, 0, 12, VertexFormat.FLOAT32))
        );
        GraphicsPipelineState pipeline = GraphicsPipelineState.builder(program(
                new BindingLayout(List.of()), List.of(new ShaderInterfaceVariable(
                        0, new ShaderInterfaceType(ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 1),
                        ShaderInterfaceVariable.Interpolation.SMOOTH
                ))
        ))
                .vertexLayout(constantInstance)
                .colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1))
                .build();
        List<RenderCommand> prefix = List.of(
                new BeginRenderPassCommand(pass),
                new BindGraphicsPipelineCommand(pipeline),
                new SetViewportCommand(new Viewport(0, 0, 64, 64, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64))
        );
        RenderCommandTransaction.builder(17L)
                .addAll(prefix)
                .add(new BindVertexBufferCommand(0, bufferSlice(304L, 16, BufferUsage.VERTEX)))
                .add(new DrawCommand(3, 9, 27, 31))
                .add(new EndRenderPassCommand())
                .build();
        expectFailure(() -> RenderCommandTransaction.builder(18L)
                .addAll(prefix)
                .add(new BindVertexBufferCommand(0, bufferSlice(305L, 15, BufferUsage.VERTEX)))
                .add(new DrawCommand(3, 9, 27, 31))
                .add(new EndRenderPassCommand())
                .build());
    }

    private static void copyAndBarrierContract() {
        ByteBuffer mutable = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
        mutable.putInt(0x12345678).flip();
        ResourceData immutableData = new ResourceData(mutable);
        mutable.putInt(0, 0);
        require(immutableData.bytes().order() == ByteOrder.LITTLE_ENDIAN
                        && immutableData.bytes().getInt(0) == 0x12345678,
                "resource data did not preserve defensive bytes and byte order");
        BufferResource source = buffer(400L, 128, BufferUsage.COPY_SOURCE, BufferUsage.INDIRECT);
        BufferResource destination = buffer(401L, 128, BufferUsage.COPY_DESTINATION);
        new CopyBufferCommand(
                new ResourceSlice.BufferSlice(source, new ByteRange(0, 64)),
                new ResourceSlice.BufferSlice(destination, new ByteRange(16, 64))
        );
        BufferResource same = buffer(402L, 128, BufferUsage.COPY_SOURCE, BufferUsage.COPY_DESTINATION);
        expectFailure(() -> new CopyBufferCommand(
                new ResourceSlice.BufferSlice(same, new ByteRange(0, 64)),
                new ResourceSlice.BufferSlice(same, new ByteRange(32, 64))
        ));
        expectFailure(() -> new CopyBufferCommand(
                new ResourceSlice.BufferSlice(source, new ByteRange(0, 16)),
                new ResourceSlice.BufferSlice(destination, new ByteRange(0, 32))
        ));
        new WriteBufferCommand(
                new ResourceSlice.BufferSlice(destination, new ByteRange(0, 64)), data(64)
        );
        TextureResource textureSource = colorTexture(new RenderResourceId(404L), 8, 8, 1, 1, 1,
                TextureUsage.COPY_SOURCE);
        TextureResource textureDestination = colorTexture(new RenderResourceId(405L), 8, 8, 1, 1, 1,
                TextureUsage.COPY_DESTINATION);
        new CopyTextureCommand(
                new ResourceSlice.TextureSlice(textureSource, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new ResourceSlice.TextureSlice(textureDestination, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1))
        );
        new WriteTextureCommand(
                new ResourceSlice.TextureSlice(textureDestination, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), new TextureExtent(8, 8, 1),
                new TextureDataLayout(0, 32, 8), data(256)
        );
        // A byte pitch need not be divisible by texel size. Vulkan backends must repack rather
        // than truncate it while preserving this portable API contract.
        new WriteTextureCommand(
                new ResourceSlice.TextureSlice(textureDestination, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), new TextureExtent(1, 2, 1),
                new TextureDataLayout(0, 5, 2), data(9)
        );
        TextureResource rg16Destination = new TextureResource(
                new RenderResourceId(408L), INITIAL, TextureDimension.TEXTURE_2D,
                1, 1, 1, 1, 1, 1, TextureFormat.RG16_FLOAT, EnumSet.of(TextureUsage.COPY_DESTINATION)
        );
        new WriteTextureCommand(
                new ResourceSlice.TextureSlice(rg16Destination,
                        new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), new TextureExtent(1, 1, 1),
                new TextureDataLayout(0, 4, 1), data(4)
        );
        TextureResource depthDestination = new TextureResource(
                new RenderResourceId(407L), INITIAL, TextureDimension.TEXTURE_2D,
                1, 1, 1, 1, 1, 1, TextureFormat.D32_FLOAT,
                EnumSet.of(TextureUsage.COPY_DESTINATION, TextureUsage.DEPTH_STENCIL_ATTACHMENT)
        );
        new WriteTextureCommand(
                new ResourceSlice.TextureSlice(depthDestination,
                        new TextureSubresourceRange(TextureAspect.DEPTH, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), new TextureExtent(1, 1, 1),
                new TextureDataLayout(0, 4, 1), data(4)
        );
        new CopyTextureRegionCommand(
                new ResourceSlice.TextureSlice(textureSource, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new ResourceSlice.TextureSlice(textureDestination, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new TextureOrigin(0, 0, 0), new TextureOrigin(0, 0, 0), new TextureExtent(4, 4, 1)
        );
        TextureResource overlappingTexture = colorTexture(new RenderResourceId(406L), 8, 8, 1, 1, 2,
                TextureUsage.COPY_SOURCE, TextureUsage.COPY_DESTINATION);
        expectFailure(() -> new CopyTextureCommand(
                new ResourceSlice.TextureSlice(overlappingTexture, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 2)),
                new ResourceSlice.TextureSlice(overlappingTexture, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 2))
        ));

        BufferBarrier validBarrier = new BufferBarrier(
                new ResourceSlice.BufferSlice(same, new ByteRange(0, 32)),
                Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_READ),
                Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE)
        );
        require(validBarrier.slice().range().lengthBytes() == 32, "barrier range was not retained");
        expectFailure(() -> new BufferBarrier(
                new ResourceSlice.BufferSlice(same, new ByteRange(0, 32)),
                Set.of(RenderPipelineStage.VERTEX_INPUT), Set.of(RenderResourceAccess.SHADER_WRITE),
                Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE)
        ));
        expectFailure(() -> new BufferBarrier(
                new ResourceSlice.BufferSlice(buffer(403L, 32, BufferUsage.VERTEX), new ByteRange(0, 32)),
                Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_READ),
                Set.of(RenderPipelineStage.COPY), Set.of()
        ));
    }

    private static void transferAndClearContract() {
        BufferResource upload = buffer(410L, 256, BufferUsage.COPY_SOURCE);
        BufferResource readback = buffer(411L, 256, BufferUsage.COPY_DESTINATION);
        TextureResource color = colorTexture(new RenderResourceId(412L), 8, 4, 1, 1, 1,
                TextureUsage.COPY_SOURCE, TextureUsage.COPY_DESTINATION, TextureUsage.COLOR_ATTACHMENT);
        ResourceSlice.BufferSlice uploadSlice = new ResourceSlice.BufferSlice(upload, new ByteRange(0, 256));
        ResourceSlice.BufferSlice readbackSlice = new ResourceSlice.BufferSlice(readback, new ByteRange(0, 256));
        ResourceSlice.TextureSlice colorSlice = new ResourceSlice.TextureSlice(
                color, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
        TextureDataLayout tight = new TextureDataLayout(0, 32, 4);
        new CopyBufferToTextureCommand(uploadSlice, colorSlice, new TextureOrigin(0, 0, 0),
                new TextureExtent(8, 4, 1), tight);
        new CopyTextureToBufferCommand(colorSlice, new TextureOrigin(0, 0, 0),
                new TextureExtent(8, 4, 1), readbackSlice, tight);
        new ClearColorCommand(colorSlice, new ClearColorValue(0.0f, 0.5f, 1.0f, 1.0f));
        expectFailure(() -> new ClearColorCommand(
                new ResourceSlice.TextureSlice(color, new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                new ClearColorValue(Float.NaN, 0.0f, 0.0f, 1.0f)));
        expectFailure(() -> new CopyBufferToTextureCommand(
                new ResourceSlice.BufferSlice(upload, new ByteRange(0, 31)), colorSlice,
                new TextureOrigin(0, 0, 0), new TextureExtent(8, 1, 1), new TextureDataLayout(0, 32, 1)));

        TextureResource depth = new TextureResource(new RenderResourceId(413L), INITIAL, TextureDimension.TEXTURE_2D,
                8, 4, 1, 1, 1, 1, TextureFormat.D24_UNORM_S8_UINT,
                EnumSet.of(TextureUsage.COPY_DESTINATION, TextureUsage.DEPTH_STENCIL_ATTACHMENT));
        ResourceSlice.TextureSlice depthSlice = new ResourceSlice.TextureSlice(
                depth, new TextureSubresourceRange(TextureAspect.DEPTH, 0, 1, 0, 1));
        new ClearDepthStencilCommand(depthSlice, 1.0f, 0);
        expectFailure(() -> new ClearDepthStencilCommand(depthSlice, 1.1f, 0));
        expectFailure(() -> new ClearDepthStencilCommand(colorSlice, 1.0f, 0));
    }

    private static void rayTracingCommandContract() {
        ShaderModule rayGeneration = module(new RenderResourceId(420L), ShaderStage.RAY_GENERATION,
                new ShaderReflection(List.of(), 0));
        ShaderProgram program = new ShaderProgram(new RenderResourceId(421L), INITIAL, ShaderProgram.Kind.RAY_TRACING,
                List.of(rayGeneration), new BindingLayout(List.of()), 0);
        RayTracingPipelineState pipeline = new RayTracingPipelineState(program,
                List.of(RayTracingShaderGroup.general(rayGeneration)), 1);
        TextureResource output = colorTexture(new RenderResourceId(422L), 8, 4, 1, 1, 1,
                TextureUsage.STORAGE_READ_WRITE);
        TraceRaysCommand trace = new TraceRaysCommand(colorView(output, 1), 8, 4, 1);
        expectFailure(() -> new RenderCommandTransaction(421L, List.of(trace)));
        new RenderCommandTransaction(422L, List.of(new BindRayTracingPipelineCommand(pipeline), trace));

        BufferResource positions = buffer(423L, 36, BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT);
        AccelerationStructureTriangleGeometry triangles = new AccelerationStructureTriangleGeometry(
                new ResourceSlice.BufferSlice(positions, new ByteRange(0, 36)), 12, 3, null, null, 0);
        AccelerationStructureResource bottom = new AccelerationStructureResource(
                new RenderResourceId(424L), INITIAL, AccelerationStructureKind.BOTTOM_LEVEL, false);
        new BuildBottomLevelAccelerationStructureCommand(bottom, AccelerationStructureBuildMode.BUILD, List.of(triangles));
        expectFailure(() -> new BuildBottomLevelAccelerationStructureCommand(
                bottom, AccelerationStructureBuildMode.UPDATE, List.of(triangles)));

        expectFailure(() -> new AccelerationStructureTriangleGeometry(
                new ResourceSlice.BufferSlice(positions, new ByteRange(0, 35)), 12, 3, null, null, 0));
        expectFailure(() -> new AccelerationStructureTriangleGeometry(
                new ResourceSlice.BufferSlice(positions, new ByteRange(0, 36)), 10, 3, null, null, 0));
        BufferResource ordinary = buffer(425L, 36, BufferUsage.VERTEX);
        expectFailure(() -> new AccelerationStructureTriangleGeometry(
                new ResourceSlice.BufferSlice(ordinary, new ByteRange(0, 36)), 12, 3, null, null, 0));

        AccelerationStructureResource top = new AccelerationStructureResource(
                new RenderResourceId(426L), INITIAL, AccelerationStructureKind.TOP_LEVEL, true);
        AccelerationStructureInstance instance = new AccelerationStructureInstance(
                bottom, AffineTransform3x4.identity(), 0, 0xff, 0, false, false);
        new BuildTopLevelAccelerationStructureCommand(top, AccelerationStructureBuildMode.BUILD, List.of(instance));
        new BuildTopLevelAccelerationStructureCommand(top, AccelerationStructureBuildMode.UPDATE, List.of(instance));
        new DestroyAccelerationStructureCommand(top);
        expectFailure(() -> new BuildBottomLevelAccelerationStructureCommand(
                top, AccelerationStructureBuildMode.BUILD, List.of(triangles)));
        expectFailure(() -> new BuildTopLevelAccelerationStructureCommand(
                bottom, AccelerationStructureBuildMode.BUILD, List.of(instance)));
        expectFailure(() -> new AccelerationStructureInstance(top, AffineTransform3x4.identity(), 0, 0xff, 0, false, false));
        expectFailure(() -> new AccelerationStructureInstance(bottom, AffineTransform3x4.identity(), 0, 0xff, 0, true, true));
        expectFailure(() -> new BindingSet.AccelerationStructureValue(bottom));
        expectFailure(() -> new TraceRaysCommand(colorView(output, 1), 9, 4, 1));

        ShaderModule miss = module(new RenderResourceId(427L), ShaderStage.RAY_MISS,
                new ShaderReflection(List.of(), 0));
        ShaderProgram completeProgram = new ShaderProgram(new RenderResourceId(428L), INITIAL,
                ShaderProgram.Kind.RAY_TRACING, List.of(rayGeneration, miss), new BindingLayout(List.of()), 0);
        new RayTracingPipelineState(completeProgram, List.of(
                RayTracingShaderGroup.general(rayGeneration), RayTracingShaderGroup.general(miss)
        ), 1);
        expectFailure(() -> new RayTracingPipelineState(completeProgram, List.of(
                RayTracingShaderGroup.general(rayGeneration), RayTracingShaderGroup.general(rayGeneration)
        ), 1));
        expectFailure(() -> RayTracingShaderGroup.triangles(rayGeneration, null));
        expectFailure(() -> RayTracingShaderGroup.procedural(null, null, rayGeneration));
    }

    private static RenderPassDescriptor simplePass() {
        TextureResource texture = colorTexture(new RenderResourceId(500L), 64, 64, 1, 1, 1,
                TextureUsage.COLOR_ATTACHMENT);
        return RenderPassDescriptor.color(64, 64, List.of(
                RenderAttachment.of(colorView(texture, 1), LoadOp.LOAD, StoreOp.STORE)
        ));
    }

    private static GraphicsPipelineState emptyPipeline() {
        return GraphicsPipelineState.builder(program(new BindingLayout(List.of()))).colorTargets(
                List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)
        ).build();
    }

    private static GraphicsPipelineState pipelineWithBinding(BindingLayout layout) {
        return GraphicsPipelineState.builder(program(layout)).colorTargets(
                List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)
        ).build();
    }

    private static ShaderProgram program(BindingLayout layout) {
        return program(layout, List.of());
    }

    private static ShaderProgram program(BindingLayout layout, List<ShaderInterfaceVariable> vertexInputs) {
        ShaderReflection vertexReflection = new ShaderReflection(layout.entries(), 0, vertexInputs, List.of());
        ShaderReflection fragmentReflection = new ShaderReflection(List.of(), 0);
        return new ShaderProgram(
                new RenderResourceId(600L + layout.entries().size()), INITIAL,
                ShaderProgram.Kind.GRAPHICS,
                List.of(
                        module(new RenderResourceId(601L + layout.entries().size()), ShaderStage.VERTEX, vertexReflection),
                        module(new RenderResourceId(602L + layout.entries().size()), ShaderStage.FRAGMENT, fragmentReflection)
                ), layout, 0
        );
    }

    private static ShaderProgram computeProgram(int pushConstantByteSize) {
        return new ShaderProgram(
                new RenderResourceId(610L + pushConstantByteSize), INITIAL,
                ShaderProgram.Kind.COMPUTE,
                List.of(module(new RenderResourceId(611L + pushConstantByteSize), ShaderStage.COMPUTE,
                        new ShaderReflection(List.of(), pushConstantByteSize))),
                new BindingLayout(List.of()), pushConstantByteSize
        );
    }

    private static ShaderModule module(RenderResourceId id, ShaderStage stage, ShaderReflection reflection) {
        ByteBuffer spirv = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        spirv.putInt(0x07230203).putInt(0).putInt(0).putInt(0).putInt(0).flip();
        return new ShaderModule(id, INITIAL, stage, "main", spirv, reflection);
    }

    private static TextureView colorView(TextureResource texture, int layers) {
        return new TextureView(texture, layers == 1 ? TextureViewDimension.TEXTURE_2D : TextureViewDimension.TEXTURE_2D_ARRAY,
                new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, layers));
    }

    private static TextureResource colorTexture(
            RenderResourceId id,
            int width,
            int height,
            int depth,
            int samples,
            int layers,
            TextureUsage... usage
    ) {
        return new TextureResource(id, INITIAL, TextureDimension.TEXTURE_2D, width, height, depth,
                1, layers, samples, TextureFormat.RGBA8_UNORM, EnumSet.of(usage[0], usage));
    }

    private static BufferResource buffer(long id, long bytes, BufferUsage... usage) {
        return new BufferResource(new RenderResourceId(id), INITIAL, bytes, EnumSet.of(usage[0], usage));
    }

    private static ResourceSlice.BufferSlice bufferSlice(long id, long bytes, BufferUsage usage) {
        return new ResourceSlice.BufferSlice(buffer(id, bytes, usage), new ByteRange(0, bytes));
    }

    private static ResourceData data(int size) {
        return new ResourceData(ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected an IllegalArgumentException");
    }
}
