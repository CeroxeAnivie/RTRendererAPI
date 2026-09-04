package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.BeginRenderPassCommand;
import top.ceroxe.rt.renderer.api.BindBindingSetCommand;
import top.ceroxe.rt.renderer.api.BindGraphicsPipelineCommand;
import top.ceroxe.rt.renderer.api.BindVertexBufferCommand;
import top.ceroxe.rt.renderer.api.BindingKey;
import top.ceroxe.rt.renderer.api.BindingLayout;
import top.ceroxe.rt.renderer.api.BindingLayoutEntry;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.BindingType;
import top.ceroxe.rt.renderer.api.BlendState;
import top.ceroxe.rt.renderer.api.BufferResource;
import top.ceroxe.rt.renderer.api.BufferUsage;
import top.ceroxe.rt.renderer.api.ByteRange;
import top.ceroxe.rt.renderer.api.ClearValue;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.DrawCommand;
import top.ceroxe.rt.renderer.api.EndRenderPassCommand;
import top.ceroxe.rt.renderer.api.GraphicsPipelineState;
import top.ceroxe.rt.renderer.api.PrimitiveTopology;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RenderCommandTransaction;
import top.ceroxe.rt.renderer.api.RenderAttachment;
import top.ceroxe.rt.renderer.api.RenderPassDescriptor;
import top.ceroxe.rt.renderer.api.RenderPipelineStage;
import top.ceroxe.rt.renderer.api.RenderResourceAccess;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.RenderResourceTransaction;
import top.ceroxe.rt.renderer.api.ResourceBarrierCommand;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.api.ResourceSlice;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.ScissorRectangle;
import top.ceroxe.rt.renderer.api.SetScissorCommand;
import top.ceroxe.rt.renderer.api.SetViewportCommand;
import top.ceroxe.rt.renderer.api.SamplerState;
import top.ceroxe.rt.renderer.api.ShaderInterfaceType;
import top.ceroxe.rt.renderer.api.ShaderInterfaceVariable;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderProgram;
import top.ceroxe.rt.renderer.api.ShaderReflection;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.api.StoreOp;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureBarrier;
import top.ceroxe.rt.renderer.api.TextureDataLayout;
import top.ceroxe.rt.renderer.api.TextureDimension;
import top.ceroxe.rt.renderer.api.TextureExtent;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.TextureOrigin;
import top.ceroxe.rt.renderer.api.TextureResource;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.api.TextureView;
import top.ceroxe.rt.renderer.api.TextureViewDimension;
import top.ceroxe.rt.renderer.api.VertexAttribute;
import top.ceroxe.rt.renderer.api.VertexBufferLayout;
import top.ceroxe.rt.renderer.api.VertexFormat;
import top.ceroxe.rt.renderer.api.VertexLayout;
import top.ceroxe.rt.renderer.api.Viewport;
import top.ceroxe.rt.renderer.api.WriteBufferCommand;
import top.ceroxe.rt.renderer.api.WriteTextureCommand;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GPU acceptance for the generic resource publication, upload, fence, and evidence path. */
public final class VulkanGenericCommandNativeSelfTest {
    private VulkanGenericCommandNativeSelfTest() { }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        if (!capability.hardwareRayTracingReady()) {
            throw new IllegalStateException("generic command acceptance requires hardware RT: " + capability.summary());
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
            BufferResource buffer = new BufferResource(
                    new RenderResourceId(1L), ResourceVersion.initial(), 24L,
                    EnumSet.of(BufferUsage.COPY_DESTINATION, BufferUsage.VERTEX)
            );
            TextureResource color = new TextureResource(
                    new RenderResourceId(2L), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                    64, 64, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    EnumSet.of(TextureUsage.COLOR_ATTACHMENT)
            );
            TextureResource sampledTexture = new TextureResource(
                    new RenderResourceId(3L), ResourceVersion.initial(), TextureDimension.TEXTURE_2D,
                    1, 1, 1, 1, 1, 1, TextureFormat.RGBA8_UNORM,
                    EnumSet.of(TextureUsage.COPY_DESTINATION, TextureUsage.SAMPLED)
            );
            TextureSubresourceRange sampledRange = new TextureSubresourceRange(
                    TextureAspect.COLOR, 0, 1, 0, 1
            );
            TextureView sampledView = new TextureView(
                    sampledTexture, TextureViewDimension.TEXTURE_2D, sampledRange
            );
            ResourceTransactionEvidenceCheck.requireAccepted(session.submitResources(
                    new RenderResourceTransaction(0L, List.of(buffer), List.of(color, sampledTexture), List.of())
            ));
            ByteBuffer payload = MemoryUtil.memAlloc(24);
            ByteBuffer texturePayload = MemoryUtil.memAlloc(4);
            try {
                payload.putFloat(-0.75f).putFloat(-0.75f)
                        .putFloat(0.75f).putFloat(-0.75f)
                        .putFloat(0.0f).putFloat(0.75f).flip();
                texturePayload.putInt(0xffff_ffff).flip();
                WriteBufferCommand write = new WriteBufferCommand(
                        new ResourceSlice.BufferSlice(buffer, new ByteRange(0L, 24L)),
                        new ResourceData(payload)
                );
                WriteTextureCommand writeTexture = new WriteTextureCommand(
                        new ResourceSlice.TextureSlice(sampledTexture, sampledRange),
                        new TextureOrigin(0, 0, 0), new TextureExtent(1, 1, 1),
                        new TextureDataLayout(0L, 4L, 1L), new ResourceData(texturePayload)
                );
                ResourceBarrierCommand sampleReady = new ResourceBarrierCommand(List.of(), List.of(
                        new TextureBarrier(
                                new ResourceSlice.TextureSlice(sampledTexture, sampledRange),
                                Set.of(RenderPipelineStage.COPY), Set.of(RenderResourceAccess.COPY_WRITE),
                                Set.of(RenderPipelineStage.FRAGMENT_SHADER), Set.of(RenderResourceAccess.SHADER_READ)
                        )
                ));
                CommandExecutionEvidence recorded = session.submit(new RenderCommandTransaction(
                        0L, List.of(write, writeTexture, sampleReady)
                ));
                if (recorded.outcome() != CommandExecutionEvidence.Outcome.RECORDED) {
                    throw new IllegalStateException("generic upload was not recorded: outcome="
                            + recorded.outcome() + ", reason=" + recorded.reason()
                            + ", detail=" + recorded.detail());
                }
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                CommandExecutionEvidence completed;
                do {
                    session.pump();
                    completed = session.commandEvidence(0L).orElseThrow();
                    if (completed.outcome() == CommandExecutionEvidence.Outcome.GPU_COMPLETED) break;
                    Thread.sleep(1L);
                } while (System.nanoTime() < deadline);
                if (completed.outcome() != CommandExecutionEvidence.Outcome.GPU_COMPLETED) {
                    throw new IllegalStateException("generic upload did not reach GPU_COMPLETED: outcome="
                            + completed.outcome() + ", reason=" + completed.reason()
                            + ", detail=" + completed.detail());
                }
                if (!device.dynamicRenderingEnabled()) {
                    System.out.println("VulkanGenericCommandNativeSelfTest passed: transfer=" + completed
                            + "; graphics skipped because dynamic rendering is unsupported");
                    return;
                }
                TextureView colorView = new TextureView(color, TextureViewDimension.TEXTURE_2D,
                        new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
                GraphicsPipelineState pipeline = graphicsPipeline();
                BindingSet bindings = new BindingSet(pipeline.program().bindingLayout(), Map.of(
                        new BindingKey(0, 0), List.of(new BindingSet.CombinedImageSamplerValue(
                                sampledView, SamplerState.builder().build()
                        ))
                ));
                RenderPassDescriptor pass = RenderPassDescriptor.color(64, 64, List.of(
                        RenderAttachment.cleared(colorView, StoreOp.STORE,
                                new ClearValue.Color(0.0f, 0.0f, 0.0f, 1.0f))
                ));
                CommandExecutionEvidence graphics = session.submit(new RenderCommandTransaction(1L, List.of(
                        new BeginRenderPassCommand(pass),
                        new BindGraphicsPipelineCommand(pipeline),
                        BindBindingSetCommand.fixed(bindings),
                        new BindVertexBufferCommand(0, new ResourceSlice.BufferSlice(buffer, new ByteRange(0L, 24L))),
                        new SetViewportCommand(new Viewport(0.0f, 0.0f, 64.0f, 64.0f, 0.0f, 1.0f)),
                        new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)),
                        new DrawCommand(3, 1, 0, 0),
                        new EndRenderPassCommand()
                )));
                if (graphics.outcome() != CommandExecutionEvidence.Outcome.RECORDED) {
                    throw new IllegalStateException("generic graphics transaction was not recorded: " + graphics);
                }
                CommandExecutionEvidence rendered = await(session, 1L, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                CpuFrame firstFrame = session.captureLatestCpuFrame(0L);
                if (firstFrame == null || firstFrame.outputResource().orElseThrow().value() != color.id().value()
                        || firstFrame.width() != 64 || firstFrame.height() != 64 || allZero(firstFrame)) {
                    throw new IllegalStateException("generic graphics CPU readback was empty, black, or misidentified: " + firstFrame);
                }

                GraphicsPipelineState procedural = proceduralGraphicsPipeline();
                CommandExecutionEvidence proceduralRecorded = session.submit(new RenderCommandTransaction(2L, List.of(
                        new BeginRenderPassCommand(pass),
                        new BindGraphicsPipelineCommand(procedural),
                        new SetViewportCommand(new Viewport(0.0f, 0.0f, 64.0f, 64.0f, 0.0f, 1.0f)),
                        new SetScissorCommand(new ScissorRectangle(0, 0, 64, 64)),
                        new DrawCommand(3, 1, 0, 0),
                        new EndRenderPassCommand()
                )));
                if (proceduralRecorded.outcome() != CommandExecutionEvidence.Outcome.RECORDED) {
                    throw new IllegalStateException("procedural graphics transaction was not recorded: " + proceduralRecorded);
                }
                CommandExecutionEvidence proceduralRendered = await(
                        session, 2L, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED
                );
                CpuFrame proceduralFrame = session.captureLatestCpuFrame(1L);
                if (proceduralFrame == null || proceduralFrame.outputResource().orElseThrow().value() != color.id().value()
                        || proceduralFrame.width() != 64 || proceduralFrame.height() != 64 || allZero(proceduralFrame)) {
                    throw new IllegalStateException("procedural graphics CPU readback was empty, black, or misidentified: " + proceduralFrame);
                }
                if (session.captureLatestCpuFrame(2L) != null) {
                    throw new IllegalStateException("generic CPU output was returned more than once for a sequence");
                }
                System.out.println("VulkanGenericCommandNativeSelfTest passed: transfer=" + completed
                        + "; graphics=" + rendered + "; procedural=" + proceduralRendered
                        + "; cpuFrames=" + firstFrame.frameSequence() + "," + proceduralFrame.frameSequence());
            } finally {
                MemoryUtil.memFree(texturePayload);
                MemoryUtil.memFree(payload);
            }
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
                throw new IllegalStateException("generic command execution failed: " + evidence);
            }
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("generic command execution did not reach " + expected + ": " + evidence);
    }

    private static GraphicsPipelineState graphicsPipeline() {
        ShaderInterfaceType vec2 = new ShaderInterfaceType(
                ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 2
        );
        ShaderInterfaceType vec3 = new ShaderInterfaceType(
                ShaderInterfaceType.NumericType.FLOATING_POINT, 32, 3
        );
        ShaderModule vertex = module(100L, ShaderStage.VERTEX,
                "#version 450\n"
                        + "layout(location=0) in vec2 position;\n"
                        + "layout(location=0) out vec3 color;\n"
                        + "void main(){ gl_Position=vec4(position,0.0,1.0); color=vec3(1.0,0.0,0.0); }\n",
                List.of(new ShaderInterfaceVariable(0, vec2, ShaderInterfaceVariable.Interpolation.SMOOTH)),
                List.of(new ShaderInterfaceVariable(0, vec3, ShaderInterfaceVariable.Interpolation.SMOOTH)),
                List.of(), Shaderc.shaderc_vertex_shader);
        BindingLayoutEntry combinedSampler = new BindingLayoutEntry(
                new BindingKey(0, 0), BindingType.COMBINED_IMAGE_SAMPLER, 1,
                Set.of(ShaderStage.FRAGMENT), false
        );
        ShaderModule fragment = module(101L, ShaderStage.FRAGMENT,
                "#version 450\n"
                        + "layout(location=0) in vec3 color;\n"
                        + "layout(set=0,binding=0) uniform sampler2D sourceTexture;\n"
                        + "layout(location=0) out vec4 outputColor;\n"
                        + "void main(){ outputColor=texture(sourceTexture,vec2(0.5)); }\n",
                List.of(new ShaderInterfaceVariable(0, vec3, ShaderInterfaceVariable.Interpolation.SMOOTH)),
                List.of(), List.of(combinedSampler), Shaderc.shaderc_fragment_shader);
        ShaderProgram program = new ShaderProgram(new RenderResourceId(102L), ResourceVersion.initial(),
                ShaderProgram.Kind.GRAPHICS, List.of(vertex, fragment),
                new BindingLayout(List.of(combinedSampler)), 0);
        return GraphicsPipelineState.builder(program)
                .vertexLayout(new VertexLayout(
                        List.of(VertexBufferLayout.perVertex(0, 8)),
                        List.of(new VertexAttribute(0, 0, 0, VertexFormat.FLOAT32X2))))
                .primitiveAssembly(PrimitiveTopology.TRIANGLE_LIST, false)
                .colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1))
                .build();
    }

    private static GraphicsPipelineState proceduralGraphicsPipeline() {
        ShaderModule vertex = module(110L, ShaderStage.VERTEX,
                "#version 450\n"
                        + "void main(){ vec2 p[3] = vec2[3](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0,3.0));\n"
                        + "gl_Position=vec4(p[gl_VertexIndex],0.0,1.0); }\n",
                List.of(), List.of(), List.of(), Shaderc.shaderc_vertex_shader);
        ShaderModule fragment = module(111L, ShaderStage.FRAGMENT,
                "#version 450\n"
                        + "layout(location=0) out vec4 outputColor;\n"
                        + "void main(){ outputColor=vec4(0.0,1.0,0.0,1.0); }\n",
                List.of(), List.of(), List.of(), Shaderc.shaderc_fragment_shader);
        ShaderProgram program = new ShaderProgram(new RenderResourceId(112L), ResourceVersion.initial(),
                ShaderProgram.Kind.GRAPHICS, List.of(vertex, fragment), new BindingLayout(List.of()), 0);
        return GraphicsPipelineState.builder(program)
                .vertexLayout(VertexLayout.empty())
                .primitiveAssembly(PrimitiveTopology.TRIANGLE_LIST, false)
                .colorTargets(List.of(TextureFormat.RGBA8_UNORM), BlendState.replace(1))
                .build();
    }

    private static boolean allZero(CpuFrame frame) {
        ByteBuffer pixels = frame.pixelsRgba8();
        while (pixels.hasRemaining()) if (pixels.get() != 0) return false;
        return true;
    }

    private static ShaderModule module(long id, ShaderStage stage, String source,
                                       List<ShaderInterfaceVariable> inputs,
                                       List<ShaderInterfaceVariable> outputs,
                                       List<BindingLayoutEntry> bindings, int kind) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) throw new IllegalStateException("shaderc compiler initialization failed");
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc options initialization failed");
        }
        long result = 0L;
        ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
        ByteBuffer fileBytes = MemoryUtil.memUTF8("generic-acceptance.glsl", true);
        ByteBuffer entryBytes = MemoryUtil.memUTF8("main", true);
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, kind, fileBytes, entryBytes, options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                String error = result == 0L ? "null result" : Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException("generic acceptance shader compilation failed: " + error);
            }
            long length = Shaderc.shaderc_result_get_length(result);
            ByteBuffer code = Shaderc.shaderc_result_get_bytes(result, length)
                    .duplicate().order(ByteOrder.nativeOrder());
            return new ShaderModule(new RenderResourceId(id), ResourceVersion.initial(), stage, "main", code,
                    new ShaderReflection(bindings, 0, inputs, outputs, List.of()));
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            MemoryUtil.memFree(entryBytes);
            MemoryUtil.memFree(fileBytes);
            MemoryUtil.memFree(sourceBytes);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static final class ResourceTransactionEvidenceCheck {
        private ResourceTransactionEvidenceCheck() { }

        static void requireAccepted(top.ceroxe.rt.renderer.api.ResourceTransactionEvidence evidence) {
            if (evidence.outcome() != top.ceroxe.rt.renderer.api.ResourceTransactionEvidence.Outcome.ACCEPTED) {
                throw new IllegalStateException("generic resource publication was not accepted: " + evidence);
            }
        }
    }
}
