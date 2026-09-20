package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.module;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.require;

/** Exact integer GPU values are compared in shaders, before any float or RGBA8 conversion. */
public final class VulkanGenericIntegerTextureNativeSelfTest {
    private static final TextureSubresourceRange RANGE = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);
    private static final SamplerState NEAREST = SamplerState.builder().minFilter(SamplerState.Filter.NEAREST)
            .magFilter(SamplerState.Filter.NEAREST).mipFilter(SamplerState.MipFilter.NEAREST).build();
    private static final ShaderInterfaceVariable COLOR = new ShaderInterfaceVariable(0,
            new ShaderInterfaceType(ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4), ShaderInterfaceVariable.Interpolation.SMOOTH);
    private static final BindingLayoutEntry IMAGE = new BindingLayoutEntry(new BindingKey(0, 0),
            BindingType.COMBINED_IMAGE_SAMPLER, 1, Set.of(ShaderStage.FRAGMENT), false);
    private static long id = 90_000;
    private static long sequence;

    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), "hardware RT device required");
        var config = RendererConfig.expertBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            var output = texture(TextureFormat.RGBA8_UNORM, false);
            publish(session, output);
            int formats = 0;
            for (int components : new int[] { 1, 2, 4 }) for (int width : new int[] { 8, 16, 32 }) {
                for (boolean signed : new boolean[] { true, false }) {
                    String name = (components == 1 ? "R" : components == 2 ? "RG" : "RGBA") + width + (signed ? "_SINT" : "_UINT");
                    var format = TextureFormat.valueOf(name);
                    require(format.bytesPerTexel() == components * width / 8, "incorrect texel size " + format);
                    var source = texture(format, false);
                    publish(session, source);
                    long maximum = signed ? (1L << (width - 1)) - 1 : (1L << width) - 1;
                    long minimum = signed ? -(1L << (width - 1)) : 0;
                    long[] values = { minimum, maximum, signed ? -37 : 37, signed ? -1 : maximum - 1 };
                    ClearValue clear = signed ? new ClearValue.SignedColor((int) values[0], (int) values[1], (int) values[2], (int) values[3])
                            : new ClearValue.UnsignedColor((int) values[0], (int) values[1], (int) values[2], (int) values[3]);
                    expect(IllegalArgumentException.class, () -> RenderAttachment.cleared(view(source), StoreOp.STORE,
                            new ClearValue.Color(0, 0, 0, 1)));
                    expect(IllegalArgumentException.class, () -> new BindingSet.CombinedImageSamplerValue(view(source), SamplerState.builder().build()));
                    run(session, List.of(new BeginRenderPassCommand(RenderPassDescriptor.color(1, 1,
                            List.of(RenderAttachment.cleared(view(source), StoreOp.STORE, clear)))), new EndRenderPassCommand()), true);
                    expect(UnsupportedOperationException.class, () -> session.captureLatestCpuFrame(sequence - 2));
                    sample(session, source, output, components, signed, values, false);

                    var integerColor = new ShaderInterfaceVariable(0, new ShaderInterfaceType(signed
                            ? ShaderInterfaceType.NumericType.SIGNED_INTEGER : ShaderInterfaceType.NumericType.UNSIGNED_INTEGER, 32, 4),
                            ShaderInterfaceVariable.Interpolation.FLAT);
                    var fragment = module(++id, ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader,
                            "#version 450\nlayout(location=0) out " + (signed ? "ivec4" : "uvec4") + " value;\nvoid main(){value="
                                    + vector(values, 4, signed) + ";}", List.of(), List.of(integerColor), List.of());
                    var producer = pipeline(fragment, format, List.of());
                    var commands = new ArrayList<RenderCommand>();
                    commands.add(barrier(source, RenderPipelineStage.FRAGMENT_SHADER, RenderResourceAccess.SHADER_READ,
                            RenderPipelineStage.COLOR_ATTACHMENT_OUTPUT, RenderResourceAccess.COLOR_ATTACHMENT_WRITE));
                    draw(commands, source, producer, null);
                    run(session, commands, true);
                    sample(session, source, output, components, signed, values, false);

                    var volume = texture(format, true);
                    publish(session, volume);
                    ByteBuffer full = ByteBuffer.allocate(format.bytesPerTexel() * 2).order(ByteOrder.nativeOrder());
                    ByteBuffer patch = ByteBuffer.allocate(format.bytesPerTexel()).order(ByteOrder.nativeOrder());
                    for (int component = 0; component < components; component++) put(patch, width, values[component]);
                    patch.flip();
                    var uploads = List.<RenderCommand>of(write(volume, 0, 2, full),
                            barrier(volume, RenderPipelineStage.COPY, RenderResourceAccess.COPY_WRITE,
                                    RenderPipelineStage.COPY, RenderResourceAccess.COPY_WRITE), write(volume, 1, 1, patch));
                    run(session, uploads, false);
                    sample(session, volume, output, components, signed, values, true);
                    formats++;
                }
            }
            require(formats == 18, "integer format coverage changed");
            System.out.println("VulkanGenericIntegerTextureNativeSelfTest passed: formats=" + formats + " exactFrames=54 nonzeroZ=18");
        }
    }

    private static void sample(VulkanGenericCommandSession session, TextureResource source, TextureResource output,
                               int components, boolean signed, long[] values, boolean volume) throws Exception {
        String fetch = "texelFetch(image," + (volume ? "ivec3(0,0,1)" : "ivec2(0)") + ",0)";
        String check = "all(equal(" + fetch + "," + vector(values, components, signed) + "))";
        if (volume) check += "&&all(equal(texelFetch(image,ivec3(0),0)," + vector(new long[4], components, signed) + "))";
        var fragment = module(++id, ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader,
                "#version 450\nlayout(set=0,binding=0) uniform " + (signed ? "isampler" : "usampler") + (volume ? "3D" : "2D")
                        + " image;\nlayout(location=0) out vec4 color;\nvoid main(){color=" + check + "?vec4(1):vec4(0,0,0,1);}",
                List.of(), List.of(COLOR), List.of(IMAGE));
        var pipeline = pipeline(fragment, output.format(), List.of(IMAGE));
        var bindings = new BindingSet(pipeline.program().bindingLayout(), Map.of(IMAGE.key(),
                List.of(new BindingSet.CombinedImageSamplerValue(view(source), NEAREST))));
        VulkanSpirvBindingValidator.requireBoundImages(List.of(fragment), bindings);
        var mismatch = new BindingSet(pipeline.program().bindingLayout(), Map.of(IMAGE.key(),
                List.of(new BindingSet.CombinedImageSamplerValue(view(output), NEAREST))));
        expect(VulkanGenericPipelineCompilationException.class, () -> VulkanSpirvBindingValidator.requireBoundImages(List.of(fragment), mismatch));
        var commands = new ArrayList<RenderCommand>();
        commands.add(barrier(source, volume ? RenderPipelineStage.COPY : RenderPipelineStage.COLOR_ATTACHMENT_OUTPUT,
                volume ? RenderResourceAccess.COPY_WRITE : RenderResourceAccess.COLOR_ATTACHMENT_WRITE,
                RenderPipelineStage.FRAGMENT_SHADER, RenderResourceAccess.SHADER_READ));
        draw(commands, output, pipeline, bindings);
        long rendered = run(session, commands, true);
        var cpu = session.captureLatestCpuFrame(rendered - 1);
        require(cpu != null && cpu.frameSequence() == rendered && cpu.outputResource().orElseThrow().equals(output.id()), "missing output evidence");
        ByteBuffer bytes = cpu.pixelsRgba8();
        while (bytes.hasRemaining()) require(Byte.toUnsignedInt(bytes.get()) == 255, "integer GPU mismatch " + source.format() + " volume=" + volume);
    }

    private static String vector(long[] values, int components, boolean signed) {
        var result = new StringBuilder(signed ? "ivec4(" : "uvec4(");
        for (int index = 0; index < 4; index++) {
            if (index != 0) result.append(',');
            long value = index < components ? values[index] : index == 3 ? 1 : 0;
            if (signed && value == Integer.MIN_VALUE) result.append("(-2147483647-1)");
            else result.append(value).append(signed ? "" : "u");
        }
        return result.append(')').toString();
    }

    private static GraphicsPipelineState pipeline(ShaderModule fragment, TextureFormat target, List<BindingLayoutEntry> bindings) {
        var vertex = module(++id, ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader,
                "#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}",
                List.of(), List.of(), List.of());
        var program = new ShaderProgram(new RenderResourceId(++id), ResourceVersion.initial(), ShaderProgram.Kind.GRAPHICS,
                List.of(vertex, fragment), new BindingLayout(bindings), 0);
        return GraphicsPipelineState.builder(program).rasterState(new RasterState(false, false, RasterState.PolygonMode.FILL,
                RasterState.CullMode.NONE, RasterState.FrontFace.COUNTER_CLOCKWISE, false, 0, 0, 0, 1))
                .colorTargets(List.of(target), BlendState.replace(1)).build();
    }

    private static void draw(List<RenderCommand> commands, TextureResource target, GraphicsPipelineState pipeline, BindingSet bindings) {
        commands.add(new BeginRenderPassCommand(RenderPassDescriptor.color(1, 1,
                List.of(RenderAttachment.of(view(target), LoadOp.DISCARD, StoreOp.STORE)))));
        commands.add(new BindGraphicsPipelineCommand(pipeline));
        if (bindings != null) commands.add(BindBindingSetCommand.fixed(bindings));
        commands.addAll(List.of(new SetViewportCommand(new Viewport(0, 0, 1, 1, 0, 1)),
                new SetScissorCommand(new ScissorRectangle(0, 0, 1, 1)), new DrawCommand(3, 1, 0, 0), new EndRenderPassCommand()));
    }

    private static TextureResource texture(TextureFormat format, boolean volume) {
        return new TextureResource(new RenderResourceId(++id), ResourceVersion.initial(),
                volume ? TextureDimension.TEXTURE_3D : TextureDimension.TEXTURE_2D, 1, 1, volume ? 2 : 1, 1, 1, 1, format,
                volume ? Set.of(TextureUsage.SAMPLED, TextureUsage.COPY_DESTINATION)
                        : Set.of(TextureUsage.SAMPLED, TextureUsage.COLOR_ATTACHMENT));
    }
    private static TextureView view(TextureResource texture) {
        return new TextureView(texture, texture.dimension() == TextureDimension.TEXTURE_3D ? TextureViewDimension.TEXTURE_3D
                : TextureViewDimension.TEXTURE_2D, RANGE);
    }
    private static void publish(VulkanGenericCommandSession session, TextureResource texture) {
        require(session.submitResources(new RenderResourceTransaction(texture.id().value(), List.of(), List.of(texture), List.of())).outcome()
                == ResourceTransactionEvidence.Outcome.ACCEPTED, "texture publication failed " + texture.format());
    }
    private static WriteTextureCommand write(TextureResource texture, int z, int depth, ByteBuffer bytes) {
        return new WriteTextureCommand(new ResourceSlice.TextureSlice(texture, RANGE), new TextureOrigin(0, 0, z),
                new TextureExtent(1, 1, depth), new TextureDataLayout(0, texture.format().bytesPerTexel(), 1), new ResourceData(bytes));
    }
    private static ResourceBarrierCommand barrier(TextureResource texture, RenderPipelineStage from, RenderResourceAccess source,
                                                   RenderPipelineStage to, RenderResourceAccess destination) {
        return new ResourceBarrierCommand(List.of(), List.of(new TextureBarrier(new ResourceSlice.TextureSlice(texture, RANGE),
                Set.of(from), Set.of(source), Set.of(to), Set.of(destination))));
    }
    private static long run(VulkanGenericCommandSession session, List<RenderCommand> commands, boolean output) throws Exception {
        long current = sequence++;
        var recorded = session.submit(new RenderCommandTransaction(current, commands));
        require(recorded.outcome().recorded(), "integer commands rejected: " + recorded);
        VulkanGenericCommandNativeSelfTest.await(session, current, output ? CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED
                : CommandExecutionEvidence.Outcome.GPU_COMPLETED);
        return current;
    }
    private static void put(ByteBuffer bytes, int width, long value) {
        switch (width) { case 8 -> bytes.put((byte) value); case 16 -> bytes.putShort((short) value); case 32 -> bytes.putInt((int) value);
            default -> throw new AssertionError("invalid test width"); }
    }
    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); } catch (RuntimeException failure) { if (type.isInstance(failure)) return; throw failure; }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
