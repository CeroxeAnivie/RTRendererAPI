package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericRayTracingNativeSelfTest.await;
import static top.ceroxe.rt.renderer.backend.vulkan.VulkanGenericRayTracingNativeSelfTest.module;

/** AABB build/update, intersection, any-hit, SBT selection, exact pixels, and retirement. */
public final class VulkanGenericProceduralRayTracingNativeSelfTest {
    private static final String PREFIX = "#version 460\n#extension GL_EXT_ray_tracing : require\n";

    public static void main(String[] args) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), "procedural gate requires hardware RT");
        var config = RendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled()).denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled()).build();
        try (var device = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            var input = new BufferResource(new RenderResourceId(81001), ResourceVersion.initial(), 64,
                    Set.of(BufferUsage.COPY_DESTINATION, BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT));
            var output = new TextureResource(new RenderResourceId(81002), ResourceVersion.initial(),
                    TextureDimension.TEXTURE_2D, 4, 1, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    Set.of(TextureUsage.STORAGE_READ_WRITE, TextureUsage.COPY_SOURCE, TextureUsage.COPY_DESTINATION));
            require(session.submitResources(new RenderResourceTransaction(0, List.of(input), List.of(output), List.of()))
                    .outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "resource admission failed");
            var slice = new ResourceSlice.BufferSlice(input, new ByteRange(8, 56));
            var geometry = new AccelerationStructureAabbGeometry(slice, 32, 2, false);
            var blas = new AccelerationStructureResource(new RenderResourceId(81003), ResourceVersion.initial(),
                    AccelerationStructureKind.BOTTOM_LEVEL, true);
            var tlas = new AccelerationStructureResource(new RenderResourceId(81004), ResourceVersion.initial(),
                    AccelerationStructureKind.TOP_LEVEL, true);
            var instances = List.of(new AccelerationStructureInstance(blas, AffineTransform3x4.identity(),
                    0, 255, 0, false, false));
            submit(session, 0, List.of(new WriteBufferCommand(slice, bounds(false)),
                    new ClearColorCommand(new ResourceSlice.TextureSlice(output,
                            new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1)),
                            new ClearColorValue(0, 0, 0, 1)),
                    new BuildProceduralBottomLevelAccelerationStructureCommand(blas, AccelerationStructureBuildMode.BUILD,
                            List.of(geometry, geometry)),
                    new BuildTopLevelAccelerationStructureCommand(tlas, AccelerationStructureBuildMode.BUILD, instances)),
                    CommandExecutionEvidence.Outcome.GPU_COMPLETED);

            var view = new TextureView(output, TextureViewDimension.TEXTURE_2D,
                    new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
            var pipeline = pipeline();
            var bindings = new BindingSet(pipeline.program().bindingLayout(), Map.of(
                    new BindingKey(0, 0), List.of(new BindingSet.TextureValue(view, BindingType.READ_WRITE_STORAGE_TEXTURE)),
                    new BindingKey(0, 1), List.of(new BindingSet.AccelerationStructureValue(tlas))));
            var trace = List.<RenderCommand>of(new BindRayTracingPipelineCommand(pipeline),
                    BindBindingSetCommand.fixed(bindings), new TraceRaysCommand(view, 4, 1, 1));
            submit(session, 1, trace, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            assertPixels(session.captureLatestCpuFrame(0), false);

            // Geometry flags and geometry kinds cannot change under UPDATE, even at identical capacity.
            var invalid = new AccelerationStructureAabbGeometry(slice, 32, 2, true);
            require(session.submit(new RenderCommandTransaction(2, List.of(
                    new BuildProceduralBottomLevelAccelerationStructureCommand(blas, AccelerationStructureBuildMode.UPDATE,
                            List.of(invalid, invalid)))))
                    .outcome() == CommandExecutionEvidence.Outcome.REJECTED, "UPDATE changed opaque flags");
            var triangles = new AccelerationStructureTriangleGeometry(
                    new ResourceSlice.BufferSlice(input, new ByteRange(0, 36)), 12, 3, null, null, 0);
            require(session.submit(new RenderCommandTransaction(3, List.of(
                    new BuildBottomLevelAccelerationStructureCommand(blas, AccelerationStructureBuildMode.UPDATE,
                            List.of(triangles, triangles)))))
                    .outcome() == CommandExecutionEvidence.Outcome.REJECTED, "UPDATE changed geometry kind");

            submit(session, 4, List.of(new WriteBufferCommand(slice, bounds(true)),
                    new BuildProceduralBottomLevelAccelerationStructureCommand(blas, AccelerationStructureBuildMode.UPDATE,
                            List.of(geometry, geometry)),
                    new BuildTopLevelAccelerationStructureCommand(tlas, AccelerationStructureBuildMode.UPDATE, instances)),
                    CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            submit(session, 5, trace, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
            assertPixels(session.captureLatestCpuFrame(1), true);
            require(session.submit(new RenderCommandTransaction(6, List.of(new DestroyAccelerationStructureCommand(blas))))
                    .outcome() == CommandExecutionEvidence.Outcome.REJECTED, "resident TLAS lost BLAS ownership");
            submit(session, 7, List.of(new DestroyAccelerationStructureCommand(tlas)), CommandExecutionEvidence.Outcome.GPU_COMPLETED);
            submit(session, 8, List.of(new DestroyAccelerationStructureCommand(blas)), CommandExecutionEvidence.Outcome.GPU_COMPLETED);
        }
        System.out.println("VulkanGenericProceduralRayTracingNativeSelfTest passed: exact build/update pixels and retirement");
    }

    private static ResourceData bounds(boolean moved) {
        ByteBuffer bytes = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        float offset = moved ? 10 : 0;
        bytes.putFloat(-1 + offset).putFloat(-1).putFloat(-0.1f).putFloat(0 + offset).putFloat(1).putFloat(0.1f);
        bytes.position(32);
        bytes.putFloat(0 + offset).putFloat(-1).putFloat(-0.1f).putFloat(1 + offset).putFloat(1).putFloat(0.1f).flip();
        return new ResourceData(bytes);
    }

    private static RayTracingPipelineState pipeline() {
        var image = new BindingLayoutEntry(new BindingKey(0, 0), BindingType.READ_WRITE_STORAGE_TEXTURE,
                1, Set.of(ShaderStage.RAY_GENERATION), false);
        var scene = new BindingLayoutEntry(new BindingKey(0, 1), BindingType.ACCELERATION_STRUCTURE,
                1, Set.of(ShaderStage.RAY_GENERATION), false);
        var raygen = module(81100, ShaderStage.RAY_GENERATION, Shaderc.shaderc_raygen_shader, PREFIX + """
                layout(set=0,binding=0,rgba8) uniform image2D outputImage;
                layout(set=0,binding=1) uniform accelerationStructureEXT scene;
                layout(location=0) rayPayloadEXT vec3 payload;
                void main() {
                    float x = float(gl_LaunchIDEXT.x)*0.5-0.75;
                    payload=vec3(0);
                    traceRayEXT(scene,0,255,0,1,0,vec3(x,0,1),0.001,vec3(0,0,-1),100,0);
                    imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(payload,1));
                }
                """, List.of(image, scene));
        var miss = module(81101, ShaderStage.RAY_MISS, Shaderc.shaderc_miss_shader, PREFIX + """
                layout(location=0) rayPayloadInEXT vec3 payload;
                void main() { payload=vec3(0,0,1); }
                """, List.of());
        var closest = module(81102, ShaderStage.RAY_CLOSEST_HIT, Shaderc.shaderc_closesthit_shader, PREFIX + """
                layout(location=0) rayPayloadInEXT vec3 payload;
                hitAttributeEXT vec2 attributeValue;
                void main() { payload=vec3(attributeValue.x,1-attributeValue.x,0); }
                """, List.of());
        var intersection = module(81103, ShaderStage.RAY_INTERSECTION, Shaderc.shaderc_intersection_shader, PREFIX + """
                hitAttributeEXT vec2 attributeValue;
                void main() {
                    if (gl_PrimitiveID == (gl_WorldRayOriginEXT.x < 0 ? 0 : 1)) {
                        attributeValue=vec2(1,0);
                        reportIntersectionEXT(1.0,0);
                    }
                }
                """, List.of());
        var anyHit = module(81104, ShaderStage.RAY_ANY_HIT, Shaderc.shaderc_anyhit_shader, PREFIX + """
                hitAttributeEXT vec2 attributeValue;
                void main() { if (gl_WorldRayOriginEXT.x > 0) ignoreIntersectionEXT; }
                """, List.of());
        var program = new ShaderProgram(new RenderResourceId(81105), ResourceVersion.initial(), ShaderProgram.Kind.RAY_TRACING,
                List.of(raygen, miss, closest, intersection, anyHit), new BindingLayout(List.of(image, scene)), 0);
        var hit = RayTracingShaderGroup.procedural(closest, anyHit, intersection);
        return new RayTracingPipelineState(program, List.of(RayTracingShaderGroup.general(raygen),
                RayTracingShaderGroup.general(miss), hit, hit), 1);
    }

    private static void assertPixels(CpuFrame frame, boolean moved) {
        require(frame != null && frame.width() == 4 && frame.height() == 1, "missing procedural output");
        var bytes = frame.pixelsRgba8();
        for (int x = 0; x < 4; x++) {
            boolean hit = !moved && x < 2;
            require(Byte.toUnsignedInt(bytes.get()) == (hit ? 255 : 0)
                    && bytes.get() == 0 && Byte.toUnsignedInt(bytes.get()) == (hit ? 0 : 255)
                    && Byte.toUnsignedInt(bytes.get()) == 255, "unexpected procedural pixel at " + x);
        }
    }

    private static void submit(VulkanGenericCommandSession session, long sequence, List<RenderCommand> commands,
                               CommandExecutionEvidence.Outcome outcome) throws InterruptedException {
        var evidence = session.submit(new RenderCommandTransaction(sequence, commands));
        require(evidence.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                "admission failed: sequence=" + sequence + ", outcome=" + evidence.outcome()
                        + ", reason=" + evidence.reason() + ", detail=" + evidence.detail());
        await(session, sequence, outcome);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
