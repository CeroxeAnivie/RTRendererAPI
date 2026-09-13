package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.module;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.require;

/** Transfer-to-geometry and geometry-to-vertex hazards with exact output and mutation evidence. */
public final class VulkanGenericGeometryBarrierNativeSelfTest {
    private static final int SIZE = 16;
    private static final TextureSubresourceRange RANGE = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);
    private static final ShaderInterfaceVariable COLOR = new ShaderInterfaceVariable(0,
            new ShaderInterfaceType(ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4),
            ShaderInterfaceVariable.Interpolation.SMOOTH);

    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        var config = RendererConfig.expertBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            require(device.geometryShaderEnabled() && device.vertexPipelineStoresAndAtomicsEnabled(), "geometry/write features unavailable");
            var upload = buffer(82_001, BufferUsage.COPY_SOURCE);
            var uniform = buffer(82_002, BufferUsage.UNIFORM);
            var storage = buffer(82_003, BufferUsage.STORAGE_READ_WRITE);
            var source = texture(82_004, 1, Set.of(TextureUsage.COPY_DESTINATION, TextureUsage.COPY_SOURCE));
            var sampled = texture(82_005, 1, Set.of(TextureUsage.COPY_DESTINATION, TextureUsage.SAMPLED));
            var output = texture(82_006, SIZE, Set.of(TextureUsage.COLOR_ATTACHMENT));
            require(session.submitResources(new RenderResourceTransaction(0, List.of(upload, uniform, storage),
                    List.of(source, sampled, output), List.of())).outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED,
                    "resource transaction rejected");
            var ubo = entry(0, BindingType.UNIFORM_BUFFER, ShaderStage.GEOMETRY);
            var image = entry(1, BindingType.COMBINED_IMAGE_SAMPLER, ShaderStage.GEOMETRY);
            var writable = entry(2, BindingType.READ_WRITE_STORAGE_BUFFER, ShaderStage.GEOMETRY);
            var vertex = module(82_010, ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader, """
                    #version 450
                    void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}
                    """, List.of(), List.of(), List.of());
            var geometry = module(82_011, ShaderStage.GEOMETRY, Shaderc.shaderc_geometry_shader, """
                    #version 450
                    layout(triangles) in; layout(triangle_strip,max_vertices=3) out;
                    layout(set=0,binding=0) uniform UniformData {vec4 value;} u;
                    layout(set=0,binding=1) uniform sampler2D image;
                    layout(set=0,binding=2) buffer StorageData {vec4 value;} s;
                    layout(location=0) out vec4 color;
                    void main(){vec4 result=u.value+texelFetch(image,ivec2(0),0);s.value=result;
                      for(int i=0;i<3;i++){gl_Position=gl_in[i].gl_Position;color=result;EmitVertex();}EndPrimitive();}
                    """, List.of(), List.of(COLOR), List.of(ubo, image, writable));
            var fragment = module(82_012, ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader, """
                    #version 450
                    layout(location=0) in vec4 color;layout(location=0) out vec4 outputColor;
                    void main(){outputColor=color;}
                    """, List.of(COLOR), List.of(COLOR), List.of());
            var producer = pipeline(82_013, List.of(vertex, geometry, fragment), List.of(ubo, image, writable));
            var reader = entry(0, BindingType.READ_ONLY_STORAGE_BUFFER, ShaderStage.VERTEX);
            var readerVertex = module(82_014, ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader, """
                    #version 450
                    layout(set=0,binding=0) readonly buffer StorageData {vec4 value;} s;
                    layout(location=0) out vec4 color;
                    void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
                    gl_Position=vec4(p*2-1,0,1);color=s.value;}
                    """, List.of(), List.of(COLOR), List.of(reader));
            var consumer = pipeline(82_015, List.of(readerVertex, fragment), List.of(reader));
            var producerBindings = new BindingSet(producer.program().bindingLayout(), Map.of(
                    ubo.key(), List.of(new BindingSet.BufferValue(uniform, new ByteRange(0, 16), BindingType.UNIFORM_BUFFER)),
                    image.key(), List.of(new BindingSet.CombinedImageSamplerValue(view(sampled), SamplerState.builder().build())),
                    writable.key(), List.of(new BindingSet.BufferValue(storage, new ByteRange(0, 16), BindingType.READ_WRITE_STORAGE_BUFFER))));
            var consumerBindings = new BindingSet(consumer.program().bindingLayout(), Map.of(reader.key(),
                    List.of(new BindingSet.BufferValue(storage, new ByteRange(0, 16), BindingType.READ_ONLY_STORAGE_BUFFER))));
            var pass = RenderPassDescriptor.color(SIZE, SIZE, List.of(RenderAttachment.cleared(view(output), StoreOp.STORE,
                    new ClearValue.Color(0, 0, 0, 0))));
            for (int frame = 0; frame < 24; frame++) {
                int[] texel = {13 + frame, 29 + frame * 2, 47 + frame, 80};
                int[] u = {23 + frame, 41, 31 + frame, 93};
                var payload = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
                for (int value : u) payload.putFloat(value / 255f);
                payload.flip();
                var pixels = ByteBuffer.allocate(4);
                for (int value : texel) pixels.put((byte)value);
                pixels.flip();
                List<RenderCommand> commands = new ArrayList<>();
                if (frame > 0) {
                    // Explicit read-to-write hazards for the same allocation on the next frame.
                    commands.add(new ResourceBarrierCommand(List.of(new BufferBarrier(slice(uniform),
                            Set.of(RenderPipelineStage.GEOMETRY_SHADER), Set.of(RenderResourceAccess.SHADER_READ),
                            Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE))),
                            List.of(new TextureBarrier(slice(sampled), Set.of(RenderPipelineStage.GEOMETRY_SHADER),
                                    Set.of(RenderResourceAccess.SHADER_READ), Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE)))));
                }
                commands.add(new WriteBufferCommand(slice(upload), new ResourceData(payload)));
                commands.add(new CopyBufferCommand(slice(upload), slice(uniform)));
                commands.add(new WriteBufferCommand(slice(storage), new ResourceData(ByteBuffer.allocate(16))));
                commands.add(new WriteTextureCommand(slice(source), new TextureOrigin(0, 0, 0), new TextureExtent(1, 1, 1),
                        new TextureDataLayout(0, 4, 1), new ResourceData(pixels)));
                commands.add(new ResourceBarrierCommand(List.of(), List.of(new TextureBarrier(slice(source),
                        Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE),
                        Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_READ)))));
                commands.add(new CopyTextureCommand(slice(source), slice(sampled)));
                commands.add(new ResourceBarrierCommand(List.of(
                        barrier(uniform, RenderPipelineStage.COPY, RenderResourceAccess.COPY_WRITE, RenderPipelineStage.GEOMETRY_SHADER, RenderResourceAccess.SHADER_READ),
                        barrier(storage, RenderPipelineStage.COPY, RenderResourceAccess.COPY_WRITE, RenderPipelineStage.GEOMETRY_SHADER, RenderResourceAccess.SHADER_WRITE)),
                        List.of(new TextureBarrier(slice(sampled), Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE),
                                Set.of(RenderPipelineStage.GEOMETRY_SHADER), Set.of(RenderResourceAccess.SHADER_READ)))));
                draw(commands, pass, producer, producerBindings);
                commands.add(new ResourceBarrierCommand(List.of(barrier(storage, RenderPipelineStage.GEOMETRY_SHADER,
                        RenderResourceAccess.SHADER_WRITE, RenderPipelineStage.VERTEX_SHADER, RenderResourceAccess.SHADER_READ)), List.of()));
                draw(commands, pass, consumer, consumerBindings);
                var recorded = session.submit(new RenderCommandTransaction(frame, commands));
                require(recorded.outcome().recorded(), "geometry command rejected: " + recorded.reason() + " " + recorded.detail());
                VulkanGenericCommandNativeSelfTest.await(session, frame, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                var cpu = session.captureLatestCpuFrame(frame - 1);
                require(cpu != null && cpu.frameSequence() == frame && cpu.outputResource().orElseThrow().equals(output.id()), "output evidence mismatch");
                var actual = cpu.pixelsRgba8();
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    for (int channel = 0; channel < 4; channel++) {
                        int value = Byte.toUnsignedInt(actual.get());
                        require(value == u[channel] + texel[channel], "geometry pixel mismatch frame=" + frame + " channel=" + channel + " actual=" + value);
                    }
                }
                for (ResourceGenerationKey key : List.of(ResourceGenerationKey.of(uniform), ResourceGenerationKey.of(sampled),
                        ResourceGenerationKey.of(storage), ResourceGenerationKey.of(output))) {
                    var evidence = session.resourceEvidence(key).orElseThrow();
                    require(evidence.transactionRevision() == 0 && evidence.mutationKey().orElseThrow().commandSequence() == frame,
                            "geometry resource mutation/revision mismatch");
                }
            }
            System.out.println("VulkanGenericGeometryBarrierNativeSelfTest passed: exactFrames=24 transfer/geometry/vertex hazards");
        }
    }

    private static BufferResource buffer(long id, BufferUsage usage) {
        return new BufferResource(new RenderResourceId(id), ResourceVersion.initial(), 16,
                usage == BufferUsage.STORAGE_READ_WRITE
                        ? Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.STORAGE_READ, BufferUsage.STORAGE_READ_WRITE)
                        : Set.of(BufferUsage.COPY_DESTINATION, usage));
    }
    private static TextureResource texture(long id, int size, Set<TextureUsage> usage) {
        return new TextureResource(new RenderResourceId(id), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                size, size, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM, usage);
    }
    private static TextureView view(TextureResource texture) { return new TextureView(texture, TextureViewDimension.TEXTURE_2D, RANGE); }
    private static ResourceSlice.TextureSlice slice(TextureResource texture) { return new ResourceSlice.TextureSlice(texture, RANGE); }
    private static ResourceSlice.BufferSlice slice(BufferResource buffer) { return new ResourceSlice.BufferSlice(buffer, new ByteRange(0, 16)); }
    private static BindingLayoutEntry entry(int binding, BindingType type, ShaderStage stage) {
        return new BindingLayoutEntry(new BindingKey(0, binding), type, 1, Set.of(stage), false);
    }
    private static GraphicsPipelineState pipeline(long id, List<ShaderModule> modules, List<BindingLayoutEntry> layout) {
        return GraphicsPipelineState.builder(new ShaderProgram(new RenderResourceId(id), ResourceVersion.initial(),
                ShaderProgram.Kind.GRAPHICS, modules, new BindingLayout(layout), 0))
                .rasterState(new RasterState(false, false, RasterState.PolygonMode.FILL, RasterState.CullMode.NONE,
                        RasterState.FrontFace.COUNTER_CLOCKWISE, false, 0, 0, 0, 1))
                .colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)).build();
    }
    private static BufferBarrier barrier(BufferResource buffer, RenderPipelineStage from, RenderResourceAccess source,
                                         RenderPipelineStage to, RenderResourceAccess destination) {
        return new BufferBarrier(slice(buffer), Set.of(from), Set.of(source), Set.of(to), Set.of(destination));
    }
    private static void draw(List<RenderCommand> commands, RenderPassDescriptor pass, GraphicsPipelineState pipeline, BindingSet bindings) {
        commands.addAll(List.of(new BeginRenderPassCommand(pass), new BindGraphicsPipelineCommand(pipeline), BindBindingSetCommand.fixed(bindings),
                new SetViewportCommand(new Viewport(0, 0, SIZE, SIZE, 0, 1)), new SetScissorCommand(new ScissorRectangle(0, 0, SIZE, SIZE)),
                new DrawCommand(3, 1, 0, 0), new EndRenderPassCommand()));
    }
}

