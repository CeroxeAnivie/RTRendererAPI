package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.AccelerationStructureBuildMode;
import top.ceroxe.rt.renderer.api.AccelerationStructureInstance;
import top.ceroxe.rt.renderer.api.AccelerationStructureKind;
import top.ceroxe.rt.renderer.api.AccelerationStructureResource;
import top.ceroxe.rt.renderer.api.AccelerationStructureTriangleGeometry;
import top.ceroxe.rt.renderer.api.AffineTransform3x4;
import top.ceroxe.rt.renderer.api.BindBindingSetCommand;
import top.ceroxe.rt.renderer.api.BindRayTracingPipelineCommand;
import top.ceroxe.rt.renderer.api.BindingKey;
import top.ceroxe.rt.renderer.api.BindingLayout;
import top.ceroxe.rt.renderer.api.BindingLayoutEntry;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.BindingType;
import top.ceroxe.rt.renderer.api.BufferResource;
import top.ceroxe.rt.renderer.api.BufferUsage;
import top.ceroxe.rt.renderer.api.ByteRange;
import top.ceroxe.rt.renderer.api.BuildBottomLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.BuildTopLevelAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.ClearColorCommand;
import top.ceroxe.rt.renderer.api.ClearColorValue;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.DestroyAccelerationStructureCommand;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RayTracingPipelineState;
import top.ceroxe.rt.renderer.api.RayTracingShaderGroup;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.ResourceSlice;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderProgram;
import top.ceroxe.rt.renderer.api.ShaderReflection;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.api.TextureView;
import top.ceroxe.rt.renderer.api.TextureViewDimension;
import top.ceroxe.rt.renderer.api.TraceRaysCommand;
import top.ceroxe.rt.renderer.api.WriteBufferCommand;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/**
 * Real-device acceptance for the generic hardware RT path introduced by the public command API.
 *
 * <p>This test intentionally does not use retained-scene types. It proves that an arbitrary host
 * can submit versioned geometry, construct BLAS/TLAS, bind a typed AS descriptor and storage-image
 * descriptor, compile explicit raygen/miss/hit SPIR-V groups, and obtain fence-backed output.
 * A completed command is insufficient here: the final assertion requires the trace output's
 * {@link CommandExecutionEvidence.Outcome#OUTPUT_PRODUCED} evidence.</p>
 */
public final class VulkanGenericRayTracingNativeSelfTest {
    private static final int OUTPUT_WIDTH = 16;
    private static final int OUTPUT_HEIGHT = 16;

    private VulkanGenericRayTracingNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        if (!capability.hardwareRayTracingReady()) {
            throw new IllegalStateException("generic RT acceptance requires hardware RT: " + capability.summary());
        }
        RendererConfig configuration = RendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .build();
        try (VulkanDeviceRuntime device = VulkanDeviceRuntime.open(
                capability, RendererRtDiagnostics.noop(), true, true, configuration
        ); VulkanGenericCommandSession session = new VulkanGenericCommandSession(device, 2)) {
            require(session.capabilities().feature(
                    top.ceroxe.rt.renderer.api.RenderingSemanticCapabilities.Feature.RAY_TRACING_DISPATCH
            ).executable(), "generic RT dispatch was not advertised as executable");

            BufferResource positions = new BufferResource(
                    new RenderResourceId(70_001L), ResourceVersion.initial(), 36L,
                    EnumSet.of(BufferUsage.COPY_DESTINATION, BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT)
            );
            BufferResource frameData = new BufferResource(
                    new RenderResourceId(70_005L), ResourceVersion.initial(), Float.BYTES,
                    EnumSet.of(BufferUsage.COPY_DESTINATION, BufferUsage.STORAGE_READ)
            );
            TextureResource output = new TextureResource(
                    new RenderResourceId(70_002L), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                    OUTPUT_WIDTH, OUTPUT_HEIGHT, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    EnumSet.of(TextureUsage.COPY_DESTINATION, TextureUsage.STORAGE_READ_WRITE)
            );
            requireAccepted(session.submitResources(new RenderResourceTransaction(
                    0L, List.of(positions, frameData), List.of(output), List.of()
            )));

            AccelerationStructureResource bottomLevel = new AccelerationStructureResource(
                    new RenderResourceId(70_003L), ResourceVersion.initial(),
                    AccelerationStructureKind.BOTTOM_LEVEL, false
            );
            AccelerationStructureResource topLevel = new AccelerationStructureResource(
                    new RenderResourceId(70_004L), ResourceVersion.initial(),
                    AccelerationStructureKind.TOP_LEVEL, false
            );
            ByteBuffer triangle = MemoryUtil.memAlloc(36).order(ByteOrder.nativeOrder());
            ByteBuffer frameValue = MemoryUtil.memAlloc(Float.BYTES).order(ByteOrder.nativeOrder());
            try {
                triangle.putFloat(-0.75F).putFloat(-0.75F).putFloat(0.0F);
                triangle.putFloat(0.75F).putFloat(-0.75F).putFloat(0.0F);
                triangle.putFloat(0.0F).putFloat(0.75F).putFloat(0.0F).flip();
                ResourceSlice.BufferSlice positionSlice = new ResourceSlice.BufferSlice(
                        positions, new ByteRange(0L, positions.byteSize())
                );
                ResourceSlice.BufferSlice frameSlice = new ResourceSlice.BufferSlice(
                        frameData, new ByteRange(0L, frameData.byteSize())
                );
                frameValue.putFloat(1.0F).flip();
                TextureSubresourceRange outputRange = new TextureSubresourceRange(
                        TextureAspect.COLOR, 0, 1, 0, 1
                );
                TextureView outputView = new TextureView(
                        output, TextureViewDimension.TEXTURE_2D, outputRange
                );
                AccelerationStructureTriangleGeometry geometry = new AccelerationStructureTriangleGeometry(
                        positionSlice, 12, 3, null, null, 0
                );
                CommandExecutionEvidence build = session.submit(new RenderCommandTransaction(0L, List.of(
                        new WriteBufferCommand(positionSlice, new ResourceData(triangle)),
                        new ClearColorCommand(
                                new ResourceSlice.TextureSlice(output, outputRange),
                                new ClearColorValue(0.0F, 0.0F, 0.0F, 1.0F)
                        ),
                        new BuildBottomLevelAccelerationStructureCommand(
                                bottomLevel, AccelerationStructureBuildMode.BUILD, List.of(geometry)
                        ),
                        new BuildTopLevelAccelerationStructureCommand(
                                topLevel, AccelerationStructureBuildMode.BUILD,
                                List.of(new AccelerationStructureInstance(
                                        bottomLevel, AffineTransform3x4.identity(), 0, 0xff, 0, true, false
                                ))
                        )
                )));
                require(build.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                        "generic AS transaction was not recorded: " + build);
                await(session, 0L, CommandExecutionEvidence.Outcome.GPU_COMPLETED);

                RayTracingPipelineState pipeline = pipeline();
                BindingSet bindings = new BindingSet(pipeline.program().bindingLayout(), Map.of(
                        new BindingKey(0, 0), List.of(new BindingSet.TextureValue(
                                outputView, BindingType.READ_WRITE_STORAGE_TEXTURE
                        )),
                        new BindingKey(0, 1), List.of(new BindingSet.AccelerationStructureValue(topLevel)),
                        new BindingKey(0, 2), List.of(new BindingSet.BufferValue(
                                frameData, frameSlice.range(), BindingType.READ_ONLY_STORAGE_BUFFER
                        ))
                ));
                CommandExecutionEvidence trace = session.submit(new RenderCommandTransaction(1L, List.of(
                        // This generation is still ACCEPTED here. The command planner must prove
                        // the local write/visibility edge without the descriptor allocator
                        // incorrectly demanding a completed earlier submission.
                        new WriteBufferCommand(frameSlice, new ResourceData(frameValue)),
                        new BindRayTracingPipelineCommand(pipeline),
                        BindBindingSetCommand.fixed(bindings),
                        new TraceRaysCommand(outputView, OUTPUT_WIDTH, OUTPUT_HEIGHT, 1)
                )));
                require(trace.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                        "generic trace transaction was not recorded: " + describe(trace));
                CommandExecutionEvidence completed = await(
                        session, 1L, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED
                );
                require(completed.outputResource().orElseThrow().equals(output.id()),
                        "trace output evidence names the wrong resource: " + completed);

                CommandExecutionEvidence blockedDestroy = session.submit(new RenderCommandTransaction(2L, List.of(
                        new DestroyAccelerationStructureCommand(bottomLevel)
                )));
                require(blockedDestroy.outcome() == CommandExecutionEvidence.Outcome.REJECTED,
                        "BLAS destroy was recorded while a resident TLAS still referenced its device address: "
                                + blockedDestroy);

                CommandExecutionEvidence destroyTlas = session.submit(new RenderCommandTransaction(3L, List.of(
                        new DestroyAccelerationStructureCommand(topLevel)
                )));
                require(destroyTlas.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                        "resident TLAS retirement was not recorded: " + destroyTlas);
                await(session, 3L, CommandExecutionEvidence.Outcome.GPU_COMPLETED);

                CommandExecutionEvidence destroyBlas = session.submit(new RenderCommandTransaction(4L, List.of(
                        new DestroyAccelerationStructureCommand(bottomLevel)
                )));
                require(destroyBlas.outcome() == CommandExecutionEvidence.Outcome.RECORDED,
                        "BLAS destroy was not admitted after TLAS retirement: " + destroyBlas);
                await(session, 4L, CommandExecutionEvidence.Outcome.GPU_COMPLETED);
                System.out.println("VulkanGenericRayTracingNativeSelfTest passed: build=" + build
                        + "; trace=" + completed + "; residentDependencyGate=passed");
            } finally {
                MemoryUtil.memFree(frameValue);
                MemoryUtil.memFree(triangle);
            }
        }
    }

    private static RayTracingPipelineState pipeline() {
        BindingLayoutEntry output = new BindingLayoutEntry(
                new BindingKey(0, 0), BindingType.READ_WRITE_STORAGE_TEXTURE, 1,
                Set.of(ShaderStage.RAY_GENERATION), false
        );
        BindingLayoutEntry scene = new BindingLayoutEntry(
                new BindingKey(0, 1), BindingType.ACCELERATION_STRUCTURE, 1,
                Set.of(ShaderStage.RAY_GENERATION), false
        );
        BindingLayoutEntry frameData = new BindingLayoutEntry(
                new BindingKey(0, 2), BindingType.READ_ONLY_STORAGE_BUFFER, 1,
                Set.of(ShaderStage.RAY_GENERATION), false
        );
        ShaderModule raygen = module(70_010L, ShaderStage.RAY_GENERATION, Shaderc.shaderc_raygen_shader,
                "#version 460\n"
                        + "#extension GL_EXT_ray_tracing : require\n"
                        + "layout(set=0,binding=0,rgba8) uniform image2D outputImage;\n"
                        + "layout(set=0,binding=1) uniform accelerationStructureEXT scene;\n"
                        + "layout(set=0,binding=2,std430) readonly buffer FrameData { float value; } frameData;\n"
                        + "layout(location=0) rayPayloadEXT vec3 payload;\n"
                        + "void main(){\n"
                        + "  vec2 uv=(vec2(gl_LaunchIDEXT.xy)+vec2(0.5))/vec2(gl_LaunchSizeEXT.xy);\n"
                        + "  vec3 direction=normalize(vec3(uv*2.0-1.0,-1.0));\n"
                        + "  payload=vec3(0.01*frameData.value);\n"
                        + "  traceRayEXT(scene,gl_RayFlagsOpaqueEXT,0xff,0,0,0,vec3(0.0,0.0,1.0),0.001,direction,100.0,0);\n"
                        + "  imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(payload,1.0));\n"
                        + "}\n",
                List.of(output, scene, frameData));
        ShaderModule miss = module(70_011L, ShaderStage.RAY_MISS, Shaderc.shaderc_miss_shader,
                "#version 460\n"
                        + "#extension GL_EXT_ray_tracing : require\n"
                        + "layout(location=0) rayPayloadInEXT vec3 payload;\n"
                        + "void main(){ payload=vec3(0.0,0.0,1.0); }\n",
                List.of());
        ShaderModule closestHit = module(70_012L, ShaderStage.RAY_CLOSEST_HIT, Shaderc.shaderc_closesthit_shader,
                "#version 460\n"
                        + "#extension GL_EXT_ray_tracing : require\n"
                        + "layout(location=0) rayPayloadInEXT vec3 payload;\n"
                        + "hitAttributeEXT vec2 barycentrics;\n"
                        + "void main(){ payload=vec3(1.0,0.0,0.0); }\n",
                List.of());
        ShaderProgram program = new ShaderProgram(
                new RenderResourceId(70_013L), ResourceVersion.initial(), ShaderProgram.Kind.RAY_TRACING,
                List.of(raygen, miss, closestHit), new BindingLayout(List.of(output, scene, frameData)), 0
        );
        return new RayTracingPipelineState(program, List.of(
                RayTracingShaderGroup.general(raygen),
                RayTracingShaderGroup.general(miss),
                RayTracingShaderGroup.triangles(closestHit, null)
        ), 1);
    }

    private static ShaderModule module(
            long id, ShaderStage stage, int shadercKind, String source, List<BindingLayoutEntry> bindings
    ) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            if (options != 0L) Shaderc.shaderc_compile_options_release(options);
            if (compiler != 0L) Shaderc.shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc initialization failed for generic RT acceptance");
        }
        long result = 0L;
        ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
        ByteBuffer fileBytes = MemoryUtil.memUTF8("generic-rt-acceptance.glsl", true);
        ByteBuffer entryBytes = MemoryUtil.memUTF8("main", true);
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2
            );
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, shadercKind, fileBytes, entryBytes, options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                String message = result == 0L ? "null result" : Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException("generic RT acceptance shader compilation failed: " + message);
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result, Shaderc.shaderc_result_get_length(result))
                    .duplicate().order(ByteOrder.nativeOrder());
            return new ShaderModule(new RenderResourceId(id), ResourceVersion.initial(), stage, "main", spirv,
                    new ShaderReflection(bindings, 0));
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            MemoryUtil.memFree(entryBytes);
            MemoryUtil.memFree(fileBytes);
            MemoryUtil.memFree(sourceBytes);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static CommandExecutionEvidence await(
            VulkanGenericCommandSession session, long sequence, CommandExecutionEvidence.Outcome expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        CommandExecutionEvidence evidence;
        do {
            session.pump();
            evidence = session.commandEvidence(sequence).orElseThrow();
            if (evidence.outcome() == expected) return evidence;
            if (evidence.outcome() != CommandExecutionEvidence.Outcome.RECORDED) {
                throw new IllegalStateException("generic RT command failed before completion: " + evidence);
            }
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("generic RT command did not reach " + expected + ": " + evidence);
    }

    private static void requireAccepted(top.ceroxe.rt.renderer.api.ResourceTransactionEvidence evidence) {
        require(evidence.outcome() == top.ceroxe.rt.renderer.api.ResourceTransactionEvidence.Outcome.ACCEPTED,
                "generic RT resource publication was not accepted: " + evidence);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String describe(CommandExecutionEvidence evidence) {
        return "outcome=" + evidence.outcome()
                + ", reason=" + evidence.reason()
                + ", detail=" + evidence.detail();
    }
}
