package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.module;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericLogicOperationNativeSelfTest.require;

/** Real dispatch/retirement stress with bounded live allocations, stale handles, and release failure. */
public final class VulkanGenericPipelineRetirementNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        var config = RendererConfig.expertBuilder().frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            var output = new TextureResource(new RenderResourceId(83_001), ResourceVersion.initial(),
                    TextureDimension.TEXTURE_2D, 4, 4, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.COPY_DESTINATION, TextureUsage.STORAGE_READ_WRITE));
            var range = new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1);
            var view = new TextureView(output, TextureViewDimension.TEXTURE_2D, range);
            require(session.submitResources(new RenderResourceTransaction(0, List.of(), List.of(output), List.of()))
                    .outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "output publication failed");
            require(session.submit(new RenderCommandTransaction(0, List.of(new ClearColorCommand(
                    new ResourceSlice.TextureSlice(output, range), new ClearColorValue(0, 0, 0, 0)))))
                    .outcome().recorded(), "output initialization failed");
            await(session, 0, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            List<RayTracingPipelineState> variants = variants();
            long started = System.nanoTime(), sequence = 1, retainedBaseline = 0;
            RayTracingPipelineHandle stale = null;
            for (int iteration = 0; iteration < 2_000; iteration++) {
                var pipeline = variants.get(iteration % variants.size());
                var bindings = new BindingSet(pipeline.program().bindingLayout(), Map.of(new BindingKey(0, 0),
                        List.of(new BindingSet.TextureValue(view, BindingType.READ_WRITE_STORAGE_TEXTURE))));
                long traceSequence = sequence++;
                var recorded = session.submit(new RenderCommandTransaction(traceSequence, List.of(
                        new BindRayTracingPipelineCommand(pipeline), BindBindingSetCommand.fixed(bindings), new TraceRaysCommand(view, 4, 4, 1))));
                require(recorded.outcome().recorded(), "dispatch rejected: " + recorded.reason() + " " + recorded.detail());
                var handle = recorded.rayTracingPipelines().getFirst();
                require(stale == null || handle.generation() > stale.generation(), "generation reused after retirement");
                var complete = await(session, traceSequence, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                require(complete.rayTracingPipelines().equals(List.of(handle)), "completion lost exact handle");
                var cpu = session.captureLatestCpuFrame(traceSequence - 1);
                require(cpu != null && cpu.frameSequence() == traceSequence, "trace output missing");
                ByteBuffer pixels = cpu.pixelsRgba8();
                while (pixels.hasRemaining()) {
                    require(Byte.toUnsignedInt(pixels.get()) == 51 && Byte.toUnsignedInt(pixels.get()) == 102
                            && Byte.toUnsignedInt(pixels.get()) == 153 && Byte.toUnsignedInt(pixels.get()) == 255,
                            "trace pixel oracle mismatch");
                }
                if (iteration < 8 && stale != null) {
                    var rejected = session.submit(new RenderCommandTransaction(sequence++, List.of(new RetireRayTracingPipelineCommand(stale))));
                    require(rejected.reason() == CommandExecutionEvidence.Reason.PIPELINE_GENERATION_MISMATCH,
                            "stale retirement accepted or untyped");
                }
                long retirementSequence = sequence++;
                var retirement = session.submit(new RenderCommandTransaction(retirementSequence, List.of(new RetireRayTracingPipelineCommand(handle))));
                require(retirement.outcome().recorded() && retirement.rayTracingRetirements().getFirst().outcome()
                        == RayTracingPipelineRetirementEvidence.Outcome.PENDING, "retirement did not preserve pending ownership");
                var retired = await(session, retirementSequence, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
                require(retired.rayTracingRetirements().getFirst().handle().equals(handle)
                        && retired.rayTracingRetirements().getFirst().outcome() == RayTracingPipelineRetirementEvidence.Outcome.RETIRED,
                        "missing typed retirement completion");
                var statistics = retired.rayTracingPipelineStatistics().orElseThrow();
                require(statistics.livePipelines() == 0 && statistics.pendingRetirements() == 0
                        && statistics.liveSbtBytes() == 0 && statistics.inFlightPipelines() == 0
                        && statistics.failedReleases() == 0 && statistics.createdPipelines() == iteration + 1
                        && statistics.retiredPipelines() == iteration + 1, "native allocation count grew after retirement: " + statistics);
                stale = handle;
                if (iteration == 249) retainedBaseline = retainedHeap();
                if (iteration % 250 == 249) {
                    long heap = retainedHeap();
                    require(heap <= retainedBaseline + 16L * 1024 * 1024, "post-GC retained heap grew beyond bounded evidence allowance");
                }
                require(System.nanoTime() - started < Duration.ofSeconds(180).toNanos(), "2,000-cycle retirement exceeded 180-second gate");
            }
            releaseFailureAndInFlight(device, variants.getFirst());
            System.out.println("VulkanGenericPipelineRetirementNativeSelfTest passed: cycles=2000 live=0 sbtBytes=0 elapsedMs="
                    + Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private static void releaseFailureAndInFlight(VulkanDeviceRuntime device, RayTracingPipelineState state) {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var resources = new VulkanGenericResourceRegistry(device, EvidenceRetentionPolicy.bounded());
             var owner = new VulkanGenericRayTracingPipelines(device, resources, compiled -> {
                 if (fail.getAndSet(false)) throw new IllegalStateException("injected provider release failure");
                 compiled.close();
             })) {
            var compiled = owner.require(state);
            var handle = owner.handle(compiled);
            owner.noteSubmitted(List.of(handle), 12);
            expectReason(() -> owner.validateRetirement(handle), CommandExecutionEvidence.Reason.PIPELINE_IN_USE);
            owner.retireCompletedThrough(11);
            expectReason(() -> owner.validateRetirement(handle), CommandExecutionEvidence.Reason.PIPELINE_IN_USE);
            owner.retireCompletedThrough(12);
            owner.validateRetirement(handle);
            owner.scheduleRetirement(List.of(handle), 13);
            expectReason(() -> owner.require(state), CommandExecutionEvidence.Reason.PIPELINE_IN_USE);
            try { owner.retireCompletedThrough(13); throw new AssertionError("provider failure swallowed"); }
            catch (IllegalStateException expected) { require(expected.getMessage().contains("injected"), "wrong failure"); }
            require(owner.statistics().livePipelines() == 1 && owner.statistics().pendingRetirements() == 1
                    && owner.statistics().failedReleases() == 1 && compiled.pipeline() != 0, "failed release lost native ownership");
            owner.retireCompletedThrough(13);
            require(owner.statistics().livePipelines() == 0, "retry did not release preserved allocation");
            expectReason(() -> owner.validateRetirement(handle), CommandExecutionEvidence.Reason.PIPELINE_GENERATION_MISMATCH);
            var remaining = owner.require(state);
            owner.close();
            require(remaining.pipeline() == 0 && remaining.layout() == 0, "owner close leaked remaining native handles");
        }
    }

    private static List<RayTracingPipelineState> variants() {
        var output = new BindingLayoutEntry(new BindingKey(0, 0), BindingType.READ_WRITE_STORAGE_TEXTURE,
                1, Set.of(ShaderStage.RAY_GENERATION), false);
        var raygen = module(83_010, ShaderStage.RAY_GENERATION, Shaderc.shaderc_raygen_shader, """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(set=0,binding=0,rgba8) uniform image2D result;
                void main(){imageStore(result,ivec2(gl_LaunchIDEXT.xy),vec4(.2,.4,.6,1));}
                """, List.of(), List.of(), List.of(output));
        var missA = module(83_011, ShaderStage.RAY_MISS, Shaderc.shaderc_miss_shader, """
                #version 460
                #extension GL_EXT_ray_tracing : require
                void main(){}
                """, List.of(), List.of(), List.of());
        var missB = module(83_012, ShaderStage.RAY_MISS, Shaderc.shaderc_miss_shader, """
                #version 460
                #extension GL_EXT_ray_tracing : require
                void main(){}
                """, List.of(), List.of(), List.of());
        var program = new ShaderProgram(new RenderResourceId(83_013), ResourceVersion.initial(), ShaderProgram.Kind.RAY_TRACING,
                List.of(raygen, missA, missB), new BindingLayout(List.of(output)), 0);
        var groups = List.of(RayTracingShaderGroup.general(raygen), RayTracingShaderGroup.general(missA), RayTracingShaderGroup.general(missB));
        var reordered = List.of(groups.get(0), groups.get(2), groups.get(1));
        return List.of(new RayTracingPipelineState(program, groups, 1), new RayTracingPipelineState(program, reordered, 1),
                new RayTracingPipelineState(program, groups, 2));
    }

    private static long retainedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    private static CommandExecutionEvidence await(VulkanGenericCommandSession session, long sequence, CommandExecutionEvidence.Outcome expected) throws Exception {
        return VulkanGenericCommandNativeSelfTest.await(session, sequence, expected);
    }
    private static void expectReason(Runnable action, CommandExecutionEvidence.Reason reason) {
        try { action.run(); } catch (VulkanGenericPipelineLifecycleException expected) {
            require(expected.reason() == reason, "wrong typed lifecycle reason"); return;
        }
        throw new AssertionError("unsafe lifecycle operation accepted");
    }
}
