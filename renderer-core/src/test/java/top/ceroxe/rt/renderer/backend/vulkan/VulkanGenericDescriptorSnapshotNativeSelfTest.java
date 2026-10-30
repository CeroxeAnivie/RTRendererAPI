package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact per-bind and per-submission ownership checks across graphics, compute and RT. */
final class VulkanGenericDescriptorSnapshotNativeSelfTest {
    private static final int WIDTH = 8, HEIGHT = 4;
    private static final TextureSubresourceRange RANGE = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);
    private static long id = 940_000;

    static void verify(VulkanDeviceRuntime device) throws Exception {
        verifyLeases(device);
        verifyGraphics(device);
        verifyStorage(device, false);
        verifyStorage(device, true);
        System.out.println("VulkanGenericDescriptorSnapshotNativeSelfTest passed: snapshot leases/reuse, graphics/compute/RT multi-bind, adjacent submissions");
    }

    private static void verifyLeases(VulkanDeviceRuntime device) {
        try (var resources = new VulkanGenericResourceRegistry(device);
             var stack = MemoryStack.stackPush()) {
            var source = uniform();
            require(resources.apply(new RenderResourceTransaction(0, List.of(source), List.of(), List.of()), -1)
                    .outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "snapshot fixture rejected");
            var entry = entry(0, BindingType.UNIFORM_BUFFER, ShaderStage.COMPUTE);
            var layout = new BindingLayout(List.of(entry));
            var bindings = new BindingSet(layout, Map.of(entry.key(), List.of(
                    new BindingSet.BufferValue(source, new ByteRange(0, 16), BindingType.UNIFORM_BUFFER))));
            try (var bank = VulkanGenericDescriptorSetBank.create(stack, device.device(), layout, device.maxBoundDescriptorSets())) {
                var first = bank.snapshot(bindings, resources, resources.samplers(), null);
                var second = bank.snapshot(bindings, resources, resources.samplers(), null);
                long firstSet = first.set(0), secondSet = second.set(0);
                require(firstSet != secondSet, "in-flight command bindings share one mutable native set");
                first.close();
                first.close();
                var reused = bank.snapshot(bindings, resources, resources.samplers(), null);
                first.close(); // An old lease must never release the allocation's new owner.
                require(reused.set(0) == firstSet && second.set(0) == secondSet,
                        "snapshot reuse changed a still-leased native set");
                second.close();
                reused.close();
            }
        }
    }

    private static void verifyGraphics(VulkanDeviceRuntime device) throws Exception {
        try (var session = new VulkanGenericCommandSession(device, 2)) {
            var sources = List.of(uniform(), uniform());
            var outputs = List.of(output(), output());
            publish(session, sources, outputs);
            var entry = entry(0, BindingType.UNIFORM_BUFFER, ShaderStage.FRAGMENT);
            var vertex = module(ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader,
                    "#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2-1,0,1);}",
                    List.of(), List.of());
            var color = new ShaderInterfaceVariable(0, new ShaderInterfaceType(
                    ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4), ShaderInterfaceVariable.Interpolation.SMOOTH);
            var fragment = module(ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader,
                    "#version 450\nlayout(set=0,binding=0) uniform Source {vec4 color;} source;layout(location=0) out vec4 color;void main(){color=source.color;}",
                    List.of(entry), List.of(color));
            var program = new ShaderProgram(nextId(), ResourceVersion.initial(), ShaderProgram.Kind.GRAPHICS,
                    List.of(vertex, fragment), new BindingLayout(List.of(entry)), 0);
            var pipeline = GraphicsPipelineState.builder(program).rasterState(new RasterState(false, false,
                    RasterState.PolygonMode.FILL, RasterState.CullMode.NONE, RasterState.FrontFace.COUNTER_CLOCKWISE,
                    false, 0, 0, 0, 1)).colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)).build();
            uploadSources(session, sources);
            for (int frame = 0; frame < 2; frame++) {
                var commands = new ArrayList<RenderCommand>();
                commands.add(new BeginRenderPassCommand(RenderPassDescriptor.color(WIDTH, HEIGHT, List.of(
                        RenderAttachment.cleared(view(outputs.get(frame)), StoreOp.STORE, new ClearValue.Color(0, 0, 0, 1))))));
                commands.add(new BindGraphicsPipelineCommand(pipeline));
                commands.add(new SetViewportCommand(new Viewport(0, 0, WIDTH, HEIGHT, 0, 1)));
                for (int half = 0; half < 2; half++) {
                    int source = half ^ frame;
                    var bindings = new BindingSet(program.bindingLayout(), Map.of(entry.key(), List.of(
                            new BindingSet.BufferValue(sources.get(source), new ByteRange(0, 16), BindingType.UNIFORM_BUFFER))));
                    commands.add(BindBindingSetCommand.fixed(bindings));
                    commands.add(new SetScissorCommand(new ScissorRectangle(half * WIDTH / 2, 0, WIDTH / 2, HEIGHT)));
                    commands.add(new DrawCommand(3, 1, 0, 0));
                }
                commands.add(new EndRenderPassCommand());
                require(session.submit(new RenderCommandTransaction(frame + 1, commands)).outcome().recorded(), "graphics snapshot rejected");
            }
            await(session, 2, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            // Read both allocations after adjacent submissions; latest-frame-only delivery must
            // not conceal corruption of the first submission's descriptor contents.
            for (int frame = 0; frame < 2; frame++) {
                var image = readOutput(session, outputs.get(frame), 3 + frame);
                assertPixels(image.pixels(), frame, true);
            }
        }
    }

    private static void verifyStorage(VulkanDeviceRuntime device, boolean rayTracing) throws Exception {
        try (var session = new VulkanGenericCommandSession(device, 2)) {
            var sources = List.of(uniform(), uniform());
            var outputs = List.of(output(), output(), output(), output());
            var indirect = rayTracing ? null : new BufferResource(nextId(), ResourceVersion.initial(), 16,
                    Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.INDIRECT));
            var buffers = new ArrayList<>(sources);
            if (indirect != null) buffers.add(indirect);
            publish(session, buffers, outputs);
            ShaderStage stage = rayTracing ? ShaderStage.RAY_GENERATION : ShaderStage.COMPUTE;
            var destination = entry(0, BindingType.READ_WRITE_STORAGE_TEXTURE, stage);
            var source = entry(1, BindingType.UNIFORM_BUFFER, stage);
            String prefix = rayTracing ? "#version 460\n#extension GL_EXT_ray_tracing : require\n"
                    : "#version 450\nlayout(local_size_x=1,local_size_y=1) in;\n";
            var shader = module(stage, rayTracing ? Shaderc.shaderc_raygen_shader : Shaderc.shaderc_compute_shader,
                    prefix + "layout(set=0,binding=0,rgba8) uniform image2D target;\n"
                    + "layout(set=0,binding=1) uniform Source {vec4 color;} source;\n"
                    + "void main(){imageStore(target,ivec2(" + (rayTracing ? "gl_LaunchIDEXT.xy" : "gl_GlobalInvocationID.xy")
                    + "),source.color);}", List.of(destination, source), List.of());
            var program = new ShaderProgram(nextId(), ResourceVersion.initial(), rayTracing ? ShaderProgram.Kind.RAY_TRACING
                    : ShaderProgram.Kind.COMPUTE, List.of(shader), new BindingLayout(List.of(destination, source)), 0);
            var compute = rayTracing ? null : new ComputePipelineState(program);
            var rays = rayTracing ? new RayTracingPipelineState(program, List.of(RayTracingShaderGroup.general(shader)), 1) : null;
            var setup = new ArrayList<RenderCommand>();
            if (indirect != null) {
                var arguments = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putInt(0)
                        .putInt(WIDTH).putInt(HEIGHT).putInt(1).flip();
                setup.add(new WriteBufferCommand(new ResourceSlice.BufferSlice(indirect, new ByteRange(0, 16)), new ResourceData(arguments)));
            }
            uploadSources(session, sources, setup);
            for (int frame = 0; frame < 2; frame++) {
                var commands = new ArrayList<RenderCommand>();
                for (int item = 0; item < 2; item++) commands.add(new ClearColorCommand(slice(outputs.get(frame * 2 + item)),
                        new ClearColorValue(0, 0, 0, 1)));
                commands.add(rayTracing ? new BindRayTracingPipelineCommand(rays) : new BindComputePipelineCommand(compute));
                for (int item = 0; item < 2; item++) {
                    var target = outputs.get(frame * 2 + item);
                    var bindings = new BindingSet(program.bindingLayout(), Map.of(
                            source.key(), List.of(new BindingSet.BufferValue(sources.get(item ^ frame), new ByteRange(0, 16), BindingType.UNIFORM_BUFFER)),
                            destination.key(), List.of(new BindingSet.TextureValue(view(target), BindingType.READ_WRITE_STORAGE_TEXTURE))));
                    commands.add(BindBindingSetCommand.fixed(bindings));
                    commands.add(rayTracing ? new TraceRaysCommand(view(target), WIDTH, HEIGHT, 1)
                            : item == 0 ? new DispatchCommand(WIDTH, HEIGHT, 1)
                            : new DispatchIndirectCommand(new ResourceSlice.BufferSlice(indirect, new ByteRange(4, 12))));
                }
                var evidence = session.submit(new RenderCommandTransaction(frame + 1, commands));
                require(evidence.outcome().recorded(), "storage snapshot rejected: rays=" + rayTracing
                        + " frame=" + frame + " reason=" + evidence.reason() + " detail=" + evidence.detail());
            }
            await(session, 2, rayTracing ? CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED : CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            for (int output = 0; output < outputs.size(); output++) {
                var image = readOutput(session, outputs.get(output), 3 + output);
                assertPixels(image.pixels(), (output % 2) ^ (output / 2), false);
            }
        }
    }

    private static void assertPixels(ByteBuffer pixels, int source, boolean split) {
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int expected = source ^ (split && x >= WIDTH / 2 ? 1 : 0);
            require(Byte.toUnsignedInt(pixels.get()) == (expected == 0 ? 255 : 0)
                    && Byte.toUnsignedInt(pixels.get()) == (expected == 1 ? 255 : 0)
                    && pixels.get() == 0 && Byte.toUnsignedInt(pixels.get()) == 255,
                    "descriptor snapshot pixel mismatch at " + x + "," + y + " source=" + expected);
        }
    }

    private static FrameSnapshot readOutput(VulkanGenericCommandSession session, TextureResource target, long sequence) throws Exception {
        var pass = RenderPassDescriptor.color(WIDTH, HEIGHT, List.of(RenderAttachment.of(view(target), LoadOp.LOAD, StoreOp.STORE)));
        require(session.submit(new RenderCommandTransaction(sequence, List.of(new BeginRenderPassCommand(pass),
                new EndRenderPassCommand()))).outcome().recorded(), "snapshot readback rejected");
        await(session, sequence, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
        var image = session.captureLatestCpuFrame(sequence - 1);
        require(image != null && image.outputResource().orElseThrow().equals(target.id()), "snapshot readback identity mismatch");
        return image;
    }

    private static void uploadSources(VulkanGenericCommandSession session, List<BufferResource> sources) throws Exception {
        uploadSources(session, sources, List.of());
    }
    private static void uploadSources(VulkanGenericCommandSession session, List<BufferResource> sources, List<RenderCommand> additional) throws Exception {
        var commands = new ArrayList<RenderCommand>(additional);
        for (int index = 0; index < sources.size(); index++) {
            var data = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putFloat(index == 0 ? 1 : 0)
                    .putFloat(index == 1 ? 1 : 0).putFloat(0).putFloat(1).flip();
            commands.add(new WriteBufferCommand(new ResourceSlice.BufferSlice(sources.get(index), new ByteRange(0, 16)), new ResourceData(data)));
        }
        require(session.submit(new RenderCommandTransaction(0, commands)).outcome().recorded(), "source upload rejected");
        await(session, 0, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
    }
    private static void publish(VulkanGenericCommandSession session, List<BufferResource> buffers, List<TextureResource> textures) {
        require(session.submitResources(new RenderResourceTransaction(0, buffers, textures, List.of())).outcome()
                == ResourceTransactionEvidence.Outcome.ACCEPTED, "snapshot resources rejected");
    }
    private static void await(VulkanGenericCommandSession session, long sequence, CommandExecutionEvidence.Outcome outcome) throws Exception {
        VulkanGenericCommandNativeSelfTest.await(session, sequence, outcome);
    }
    private static BindingLayoutEntry entry(int binding, BindingType type, ShaderStage stage) {
        return new BindingLayoutEntry(new BindingKey(0, binding), type, 1, Set.of(stage), false);
    }
    private static ShaderModule module(ShaderStage stage, int kind, String source, List<BindingLayoutEntry> bindings, List<ShaderInterfaceVariable> outputs) {
        return VulkanGenericLogicOperationNativeSelfTest.module(++id, stage, kind, source, List.of(), outputs, bindings);
    }
    private static BufferResource uniform() {
        return new BufferResource(nextId(), ResourceVersion.initial(), 16, Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.UNIFORM));
    }
    private static TextureResource output() {
        return new TextureResource(nextId(), ResourceVersion.initial(), TextureDimension.TEXTURE_2D, WIDTH, HEIGHT, 1, 1, 1, 1,
                TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.STORAGE_READ_WRITE, TextureUsage.COLOR_ATTACHMENT, TextureUsage.COPY_DESTINATION));
    }
    private static RenderResourceId nextId() { return new RenderResourceId(++id); }
    private static TextureView view(TextureResource texture) { return new TextureView(texture, TextureViewDimension.TEXTURE_2D, RANGE); }
    private static ResourceSlice.TextureSlice slice(TextureResource texture) { return new ResourceSlice.TextureSlice(texture, RANGE); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
