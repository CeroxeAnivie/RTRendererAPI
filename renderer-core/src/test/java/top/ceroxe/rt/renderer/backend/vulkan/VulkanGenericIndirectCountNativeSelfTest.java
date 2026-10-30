package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GPU count-buffer direct/indexed draws checked against every output pixel. */
final class VulkanGenericIndirectCountNativeSelfTest {
    private static final int WIDTH = 8, HEIGHT = 4;
    private static long id = 960_000;

    public static void main(String[] args) throws Exception {
        var config = RendererConfig.builder().frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe.capture(),
                top.ceroxe.rt.renderer.RendererRtDiagnostics.noop(), true, true, config)) {
            verify(device);
        }
    }

    static void verify(VulkanDeviceRuntime device) throws Exception {
        System.out.println("counted draw device limit=" + device.maxDrawIndirectCount());
        try (var session = new VulkanGenericCommandSession(device, 2)) {
            require(session.capabilities().feature(RenderingSemanticCapabilities.Feature.COUNTED_INDIRECT_DRAW).executable()
                    == (device.dynamicRenderingEnabled() && device.drawIndirectCountEnabled()), "counted draw capability mismatch");
        }
        if (!device.drawIndirectCountEnabled()) {
            System.out.println("VulkanGenericIndirectCountNativeSelfTest: device explicitly reports GPU count-buffer draws unsupported");
            return;
        }
        for (var kind : IndirectDrawCommand.Kind.values()) verifyKind(device, kind);
        verifyCountNormalizationOwnership(device);
        System.out.println("VulkanGenericIndirectCountNativeSelfTest selected cases passed: case="
                + Integer.getInteger("rtrenderer.test.indirectCase", -1)
                + " skipLimitCase=" + Boolean.getBoolean("rtrenderer.test.indirectSkipLimitCase"));
    }

    private static void verifyKind(VulkanDeviceRuntime device, IndirectDrawCommand.Kind kind) throws Exception {
        try (var session = new VulkanGenericCommandSession(device, 2)) {
            var arguments = buffer(96, BufferUsage.INDIRECT, BufferUsage.STORAGE_READ_WRITE);
            var count = buffer(16, BufferUsage.INDIRECT, BufferUsage.STORAGE_READ_WRITE);
            var indices = buffer(32, BufferUsage.INDEX);
            var output = new TextureResource(nextId(), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                    WIDTH, HEIGHT, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT));
            require(session.submitResources(new RenderResourceTransaction(0, List.of(arguments, count, indices),
                    List.of(output), List.of())).outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "indirect fixture rejected");
            var vertex = module(ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader, """
                    #version 450
                    void main(){
                        const vec2 p[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));
                        int tile=gl_VertexIndex/6; vec2 q=p[gl_VertexIndex%6];
                        gl_Position=vec4(q.x+float(tile)-1.0,q.y*2.0-1.0,0,1);
                    }
                    """, List.of(), List.of());
            var color = new ShaderInterfaceVariable(0, new ShaderInterfaceType(
                    ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4), ShaderInterfaceVariable.Interpolation.SMOOTH);
            var fragment = module(ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader,
                    "#version 450\nlayout(location=0) out vec4 color;void main(){color=vec4(1,0,0,1);}", List.of(), List.of(color));
            var program = new ShaderProgram(nextId(), ResourceVersion.initial(), ShaderProgram.Kind.GRAPHICS,
                    List.of(vertex, fragment), new BindingLayout(List.of()), 0);
            var pipeline = GraphicsPipelineState.builder(program).rasterState(new RasterState(false, false,
                    RasterState.PolygonMode.FILL, RasterState.CullMode.NONE, RasterState.FrontFace.COUNTER_CLOCKWISE,
                    false, 0, 0, 0, 1)).colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)).build();
            int stride = kind.recordByteSize() + 4;
            var data = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
            for (int draw = 0; draw < 2; draw++) {
                data.position(4 + draw * stride);
                data.putInt(6).putInt(1);
                if (kind.indexed()) data.putInt(1).putInt(draw * 6).putInt(0);
                else data.putInt(draw * 6).putInt(0);
            }
            data.position(0).limit(96);
            var indexData = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).putInt(-99).putInt(-98);
            for (int index = 0; index < 6; index++) indexData.putInt(index);
            indexData.flip();
            require(session.submit(new RenderCommandTransaction(0, List.of(write(arguments, 0, data), write(indices, 0, indexData))))
                    .outcome().recorded(), "indirect argument upload rejected");
            VulkanGenericCommandNativeSelfTest.await(session, 0, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            var view = new TextureView(output, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            int[] counts = {0, 1, 2, (int) Math.min(7L, device.maxDrawIndirectCount()),
                    (int) device.maxDrawIndirectCount(), 2, 2, 1, 2, (int) device.maxDrawIndirectCount(), (int) device.maxDrawIndirectCount()};
            int[] maxima = {2, 2, 2, 2, 2, 0, 1, 2, 2, 2, 1};
            long sequence = 1;
            for (int test = 0; test < counts.length; test++) {
                int selectedCase = Integer.getInteger("rtrenderer.test.indirectCase", -1);
                if (selectedCase >= 0 && selectedCase != test) continue;
                if (test == 4 && Boolean.getBoolean("rtrenderer.test.indirectSkipLimitCase")) continue;
                int maximum = device.multiDrawIndirectEnabled() ? maxima[test] : Math.min(maxima[test], 1);
                boolean computeProducer = test == 7 || test == 9;
                boolean dualMaximum = test == 10 && device.multiDrawIndirectEnabled();
                var countBuffer = test == 8 ? arguments : count;
                int countOffset = test == 8 ? 64 : 4;
                var commands = new ArrayList<RenderCommand>();
                if (computeProducer) {
                    commands.add(write(count, 0, ByteBuffer.allocate(16)));
                    var entry = new BindingLayoutEntry(new BindingKey(0, 0), BindingType.READ_WRITE_STORAGE_BUFFER,
                            1, Set.of(ShaderStage.COMPUTE), false);
                    var shader = module(ShaderStage.COMPUTE, Shaderc.shaderc_compute_shader,
                            "#version 450\nlayout(local_size_x=1) in;layout(set=0,binding=0) buffer Counts {uint values[];} target;void main(){target.values[1]="
                                    + Integer.toUnsignedLong(counts[test]) + "u;}",
                            List.of(entry), List.of());
                    var computeProgram = new ShaderProgram(nextId(), ResourceVersion.initial(), ShaderProgram.Kind.COMPUTE,
                            List.of(shader), new BindingLayout(List.of(entry)), 0);
                    commands.add(new BindComputePipelineCommand(new ComputePipelineState(computeProgram)));
                    commands.add(BindBindingSetCommand.fixed(new BindingSet(computeProgram.bindingLayout(), Map.of(entry.key(),
                            List.of(new BindingSet.BufferValue(count, new ByteRange(0, 16), BindingType.READ_WRITE_STORAGE_BUFFER))))));
                    commands.add(new DispatchCommand(1, 1, 1));
                    commands.add(new ResourceBarrierCommand(List.of(new BufferBarrier(
                            new ResourceSlice.BufferSlice(count, new ByteRange(0, 16)),
                            Set.of(RenderPipelineStage.COMPUTE_SHADER), Set.of(RenderResourceAccess.SHADER_WRITE),
                            Set.of(RenderPipelineStage.INDIRECT), Set.of(RenderResourceAccess.INDIRECT_READ))), List.of()));
                    var produced = session.submit(new RenderCommandTransaction(sequence, commands));
                    require(produced.outcome().recorded(), "GPU count producer rejected: " + produced.detail());
                    VulkanGenericCommandNativeSelfTest.await(session, sequence, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
                    sequence++;
                    commands = new ArrayList<>();
                } else {
                    commands.add(write(countBuffer, countOffset, ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(counts[test]).flip()));
                }
                commands.add(new BeginRenderPassCommand(RenderPassDescriptor.color(WIDTH, HEIGHT, List.of(
                        RenderAttachment.cleared(view, StoreOp.STORE, new ClearValue.Color(0, 0, 0, 1))))));
                commands.add(new BindGraphicsPipelineCommand(pipeline));
                commands.add(new SetViewportCommand(new Viewport(0, 0, dualMaximum ? WIDTH / 2 : WIDTH, HEIGHT, 0, 1)));
                commands.add(new SetScissorCommand(new ScissorRectangle(0, 0, WIDTH, HEIGHT)));
                if (kind.indexed()) commands.add(new BindIndexBufferCommand(
                        new ResourceSlice.BufferSlice(indices, new ByteRange(4, 28)), IndexFormat.UINT32));
                commands.add(IndirectDrawCommand.counted(kind, new ResourceSlice.BufferSlice(arguments,
                        new ByteRange(4, stride + kind.recordByteSize())), maximum, stride,
                        new ResourceSlice.BufferSlice(countBuffer, new ByteRange(countOffset, 4))));
                if (dualMaximum) {
                    commands.add(new SetViewportCommand(new Viewport(WIDTH / 2, 0, WIDTH / 2, HEIGHT, 0, 1)));
                    commands.add(IndirectDrawCommand.counted(kind, new ResourceSlice.BufferSlice(arguments,
                            new ByteRange(4, stride + kind.recordByteSize())), 2, stride,
                            new ResourceSlice.BufferSlice(countBuffer, new ByteRange(countOffset, 4))));
                }
                commands.add(new EndRenderPassCommand());
                var evidence = session.submit(new RenderCommandTransaction(sequence, commands));
                require(evidence.outcome().recorded(), "counted " + kind + " test=" + test + " rejected: " + evidence.detail());
                VulkanGenericCommandNativeSelfTest.await(session, sequence, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                var frame = session.captureLatestCpuFrame(sequence - 1);
                require(frame != null && frame.frameSequence() == sequence, "counted draw output missing");
                int expectedDraws = (int) Math.min(Integer.toUnsignedLong(counts[test]), maximum);
                var pixels = frame.pixels();
                for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                    int expected = dualMaximum ? (x < WIDTH / 4 || x >= WIDTH / 2 ? 255 : 0)
                            : x < WIDTH / 2 ? (expectedDraws >= 1 ? 255 : 0) : (expectedDraws >= 2 ? 255 : 0);
                    require(Byte.toUnsignedInt(pixels.get()) == expected && pixels.get() == 0 && pixels.get() == 0
                            && Byte.toUnsignedInt(pixels.get()) == 255,
                            "counted " + kind + " test=" + test + " pixel=" + x + "," + y + " expectedDraws=" + expectedDraws);
                }
                System.out.println("counted " + kind + " test=" + test + " count="
                        + Integer.toUnsignedLong(counts[test]) + " maximum=" + maximum + " passed");
                sequence++;
            }
        }
    }

    private static WriteBufferCommand write(BufferResource buffer, long offset, ByteBuffer data) {
        return new WriteBufferCommand(new ResourceSlice.BufferSlice(buffer, new ByteRange(offset, data.remaining())), new ResourceData(data));
    }

    private static void verifyCountNormalizationOwnership(VulkanDeviceRuntime device) throws Exception {
        try (var resources = new VulkanGenericResourceRegistry(device);
             var owner = new VulkanGenericIndirectCounts(device);
             var compute = new VulkanGenericComputePipelines(device, resources);
             var graphics = new VulkanGenericGraphicsPipelines(device.device(), resources, device.maxBoundDescriptorSets(),
                     device.logicOpEnabled(), device.geometryShaderEnabled(), device.vertexPipelineStoresAndAtomicsEnabled(),
                     device.drawIndirectCountEnabled(), device.multiDrawIndirectEnabled(), device.maxDrawIndirectCount());
             var rays = new VulkanGenericRayTracingPipelines(device, resources);
             var structures = new VulkanGenericAccelerationStructures(device)) {
            var source = buffer(64, BufferUsage.INDIRECT);
            require(resources.apply(new RenderResourceTransaction(0, List.of(source), List.of(), List.of()), -1).outcome()
                    == ResourceTransactionEvidence.Outcome.ACCEPTED, "count scratch fixture rejected");
            var sourceRecord = resources.requireBuffer(source);
            var bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
            bytes.putInt(52, -1);
            sourceRecord.buffer().writeBytes(bytes);
            var color = new TextureResource(nextId(), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                    WIDTH, HEIGHT, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM, Set.of(TextureUsage.COLOR_ATTACHMENT));
            require(resources.apply(new RenderResourceTransaction(1, List.of(), List.of(color), List.of()), -1).outcome()
                    == ResourceTransactionEvidence.Outcome.ACCEPTED, "count scratch output rejected");
            var vertex = module(ShaderStage.VERTEX, Shaderc.shaderc_vertex_shader,
                    "#version 450\nvoid main(){gl_Position=vec4(0,0,0,1);}", List.of(), List.of());
            var outputVariable = new ShaderInterfaceVariable(0, new ShaderInterfaceType(
                    ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 4), ShaderInterfaceVariable.Interpolation.SMOOTH);
            var fragment = module(ShaderStage.FRAGMENT, Shaderc.shaderc_fragment_shader,
                    "#version 450\nlayout(location=0) out vec4 color;void main(){color=vec4(1);}", List.of(), List.of(outputVariable));
            var program = new ShaderProgram(nextId(), ResourceVersion.initial(), ShaderProgram.Kind.GRAPHICS,
                    List.of(vertex, fragment), new BindingLayout(List.of()), 0);
            var pipeline = GraphicsPipelineState.builder(program).colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1)).build();
            var view = new TextureView(color, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            var commands = new ArrayList<RenderCommand>();
            commands.add(write(source, 0, bytes));
            commands.add(new BeginRenderPassCommand(RenderPassDescriptor.color(WIDTH, HEIGHT, List.of(
                    RenderAttachment.cleared(view, StoreOp.STORE, new ClearValue.Color(0, 0, 0, 1))))));
            commands.add(new BindGraphicsPipelineCommand(pipeline));
            commands.add(new SetViewportCommand(new Viewport(0, 0, WIDTH, HEIGHT, 0, 1)));
            commands.add(new SetScissorCommand(new ScissorRectangle(0, 0, WIDTH, HEIGHT)));
            for (int maximum : new int[]{1, 2}) commands.add(IndirectDrawCommand.counted(IndirectDrawCommand.Kind.DIRECT,
                    new ResourceSlice.BufferSlice(source, new ByteRange(0, 32)), maximum, 16,
                    new ResourceSlice.BufferSlice(source, new ByteRange(52, 4))));
            commands.add(new EndRenderPassCommand());
            var plan = VulkanGenericCommandPlan.compile(resources, compute, graphics, rays, structures,
                    new RenderCommandTransaction(0, commands));
            try (var first = owner.prepare(plan); var second = owner.prepare(plan)) {
                require(first.buffer() != second.buffer(), "in-flight submissions share count scratch");
                var draws = plan.actions().stream().filter(VulkanGenericCommandPlan.Indirect.class::isInstance)
                        .map(VulkanGenericCommandPlan.Indirect.class::cast).toList();
                require(first.offset(draws.get(0)) != first.offset(draws.get(1)), "different command maxima share one scratch word");
                try (var submission = device.frameCommands().submitTimedOneTimeAsync("generic.countNormalizationOracle",
                        (nativeCommands, stack) -> first.record(nativeCommands, stack, draws))) {
                    submission.close(); // Test-only observation; production releases this lease from its existing fence.
                }
                var actual = sourceRecord.buffer().readBytes();
                require(java.util.Arrays.equals(actual, bytes.array()), "normalization modified the original count buffer");
            }
            plan.accelerationStructures().close();
        }
    }
    private static BufferResource buffer(int size, BufferUsage... usage) {
        var flags = java.util.EnumSet.of(BufferUsage.COPY_DESTINATION);
        java.util.Collections.addAll(flags, usage);
        return new BufferResource(nextId(), ResourceVersion.initial(), size, flags);
    }
    private static ShaderModule module(ShaderStage stage, int kind, String source, List<BindingLayoutEntry> bindings, List<ShaderInterfaceVariable> outputs) {
        return VulkanGenericLogicOperationNativeSelfTest.module(++id, stage, kind, source, List.of(), outputs, bindings);
    }
    private static RenderResourceId nextId() { return new RenderResourceId(++id); }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
