package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Exact RGBA8 boolean oracle across every operation, blending state, mask and repeated mutation. */
public final class VulkanGenericLogicOperationNativeSelfTest {
    private static final int SIZE = 16;
    private static final ShaderInterfaceVariable COLOR = new ShaderInterfaceVariable(0,
            new ShaderInterfaceType(ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4), ShaderInterfaceVariable.Interpolation.SMOOTH);

    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        var config = RendererConfig.expertBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            require(session.capabilities().feature(RenderingSemanticCapabilities.Feature.LOGIC_OPERATIONS).executable(),
                    "device did not enable logicOp");
            var uniform = new BufferResource(new RenderResourceId(81_001), ResourceVersion.initial(), 16,
                    Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.UNIFORM));
            var output = new TextureResource(new RenderResourceId(81_002), ResourceVersion.initial(),
                    TextureDimension.TEXTURE_2D, SIZE, SIZE, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COLOR_ATTACHMENT));
            require(session.submitResources(new RenderResourceTransaction(0, List.of(uniform), List.of(output), List.of()))
                    .outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "resource publication failed");
            var entry = new BindingLayoutEntry(new BindingKey(0, 0), BindingType.UNIFORM_BUFFER, 1,
                    Set.of(ShaderStage.FRAGMENT), false);
            var vertex = module(81_010, ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader, """
                    #version 450
                    void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                    gl_Position=vec4(p*2.0-1.0,0,1);}
                    """, List.of(), List.of(), List.of());
            var fragment = module(81_011, ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader, """
                    #version 450
                    layout(set=0,binding=0) uniform Source {vec4 color;} source;
                    layout(location=0) out vec4 color;
                    void main(){color=source.color;}
                    """, List.of(), List.of(COLOR), List.of(entry));
            var program = new ShaderProgram(new RenderResourceId(81_012), ResourceVersion.initial(),
                    ShaderProgram.Kind.GRAPHICS, List.of(vertex, fragment), new BindingLayout(List.of(entry)), 0);
            var binding = new BindingSet(program.bindingLayout(), Map.of(entry.key(), List.of(
                    new BindingSet.BufferValue(uniform, new ByteRange(0, 16), BindingType.UNIFORM_BUFFER))));
            var view = new TextureView(output, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            List<ColorWriteMask> masks = List.of(ColorWriteMask.all(),
                    new ColorWriteMask(Set.of(ColorWriteMask.Component.RED, ColorWriteMask.Component.BLUE)), ColorWriteMask.none());
            long sequence = 0;
            for (boolean blend : new boolean[]{false, true}) {
                for (int factors = 0; factors < 2; factors++) {
                    for (ColorWriteMask mask : masks) {
                        for (LogicOperation operation : LogicOperation.values()) {
                            var target = new ColorTargetBlendState(blend,
                                    factors == 0 ? BlendFactor.ONE : BlendFactor.SOURCE_ALPHA,
                                    factors == 0 ? BlendFactor.ZERO : BlendFactor.ONE_MINUS_SOURCE_COLOR,
                                    BlendOperation.ADD, BlendFactor.DESTINATION_ALPHA, BlendFactor.ONE, BlendOperation.ADD, mask);
                            var pipeline = pipeline(program, new BlendState(List.of(target), operation, 0, 0, 0, 0));
                            var copyPipeline = pipeline(program, new BlendState(List.of(target), LogicOperation.COPY, 0, 0, 0, 0));
                            for (int frame = 0; frame < 2; frame++) {
                                int[] source = frame == 0 ? new int[]{0x39, 0xa6, 0x5c, 0xc3} : new int[]{0xe1, 0x17, 0xb2, 0x68};
                                int[] destination = frame == 0 ? new int[]{0x96, 0x53, 0xc5, 0x3a} : new int[]{0x1e, 0xf0, 0x49, 0x87};
                                var bytes = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
                                for (int value : source) bytes.putFloat(value / 255f);
                                bytes.flip();
                                var pass = RenderPassDescriptor.color(SIZE, SIZE, List.of(RenderAttachment.cleared(view,
                                        StoreOp.STORE, new ClearValue.Color(destination[0]/255f, destination[1]/255f,
                                                destination[2]/255f, destination[3]/255f))));
                                // Bind away and back before drawing to catch cached-state/pipeline switching defects.
                                var evidence = session.submit(new RenderCommandTransaction(sequence, List.of(
                                        new WriteBufferCommand(new ResourceSlice.BufferSlice(uniform, new ByteRange(0, 16)), new ResourceData(bytes)),
                                        new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline),
                                        new BindGraphicsPipelineCommand(copyPipeline), new BindGraphicsPipelineCommand(pipeline),
                                        BindBindingSetCommand.fixed(binding),
                                        new SetViewportCommand(new Viewport(0, 0, SIZE, SIZE, 0, 1)),
                                        new SetScissorCommand(new ScissorRectangle(0, 0, SIZE, SIZE)),
                                        new DrawCommand(3, 1, 0, 0), new EndRenderPassCommand())));
                                require(evidence.outcome().recorded(), "logic pipeline rejected: " + evidence.reason() + " " + evidence.detail());
                                VulkanGenericCommandNativeSelfTest.await(session, sequence, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                                var cpu = session.captureLatestCpuFrame(sequence - 1);
                                require(cpu != null && cpu.frameSequence() == sequence && cpu.outputResource().orElseThrow().equals(output.id()),
                                        "output completion identity mismatch");
                                var pixels = cpu.pixelsRgba8();
                                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                                    for (int channel = 0; channel < 4; channel++) {
                                        int expected = mask.contains(ColorWriteMask.Component.values()[channel])
                                                ? reference(operation, source[channel], destination[channel]) : destination[channel];
                                        int actual = Byte.toUnsignedInt(pixels.get());
                                        require(actual == expected, operation + " blend=" + blend + " frame=" + frame
                                                + " channel=" + channel + " expected=" + expected + " actual=" + actual);
                                    }
                                }
                                for (ResourceGenerationKey key : List.of(ResourceGenerationKey.of(uniform), ResourceGenerationKey.of(output))) {
                                    var residency = session.resourceEvidence(key).orElseThrow();
                                    require(residency.transactionRevision() == 0 && residency.mutationKey().orElseThrow().commandSequence() == sequence,
                                            "generation mutation or publication revision mismatch");
                                }
                                sequence++;
                            }
                        }
                    }
                }
            }
            // Feature and attachment-format failures use the provider's existing typed rejection path.
            var unsupported = GraphicsPipelineState.builder(program).vertexLayout(new VertexLayout(List.of(), List.of()))
                    .colorTargets(List.of(TextureFormat.RGBA16_FLOAT), new BlendState(
                            List.of(ColorTargetBlendState.replace(ColorWriteMask.all())), LogicOperation.COPY, 0, 0, 0, 0)).build();
            expectUnsupported(() -> VulkanGenericGraphicsPipelines.validateFeatures(unsupported, true, true));
            expectUnsupported(() -> VulkanGenericGraphicsPipelines.validateFeatures(pipeline(program, new BlendState(
                    List.of(ColorTargetBlendState.replace(ColorWriteMask.all())), LogicOperation.CLEAR, 0, 0, 0, 0)), false, true));
            System.out.println("VulkanGenericLogicOperationNativeSelfTest passed: operations=16 exactFrames=" + sequence);
        }
    }

    static ShaderModule module(long id, ShaderStage stage, int kind, String source,
                               List<ShaderInterfaceVariable> inputs, List<ShaderInterfaceVariable> outputs,
                               List<BindingLayoutEntry> bindings) {
        return VulkanGenericCommandNativeSelfTest.module(id, stage, source, inputs, outputs, bindings, kind);
    }

    private static GraphicsPipelineState pipeline(ShaderProgram program, BlendState blend) {
        return GraphicsPipelineState.builder(program).vertexLayout(new VertexLayout(List.of(), List.of()))
                .rasterState(new RasterState(false, false, RasterState.PolygonMode.FILL, RasterState.CullMode.NONE,
                        RasterState.FrontFace.COUNTER_CLOCKWISE, false, 0, 0, 0, 1))
                .colorTargets(List.of(TextureFormat.RGBA8_UNORM), blend).build();
    }

    /** Independent bit truth table; no native constants or enum ordinals enter the oracle. */
    static int reference(LogicOperation operation, int source, int destination) {
        return switch (operation) {
            case CLEAR -> 0; case AND -> source & destination; case AND_REVERSE -> source & ~destination;
            case COPY -> source; case AND_INVERTED -> ~source & destination; case NO_OP -> destination;
            case XOR -> source ^ destination; case OR -> source | destination; case NOR -> ~(source | destination) & 255;
            case EQUIVALENT -> ~(source ^ destination) & 255; case INVERT -> ~destination & 255;
            case OR_REVERSE -> (source | ~destination) & 255; case COPY_INVERTED -> ~source & 255;
            case OR_INVERTED -> (~source | destination) & 255; case NAND -> ~(source & destination) & 255; case SET -> 255;
        };
    }

    private static void expectUnsupported(Runnable action) {
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("unsupported pipeline was accepted");
    }

    static void require(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
