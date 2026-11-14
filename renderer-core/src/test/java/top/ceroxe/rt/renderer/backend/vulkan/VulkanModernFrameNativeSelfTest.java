package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Real-device acceptance for custom retained stages, data generations and scoped native frames. */
public final class VulkanModernFrameNativeSelfTest {
    public static void main(String[] arguments) throws Exception {
        var capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(), capability.summary());
        verifyProgrammableScene(capability);
        verifyMappedHdr(capability);
        verifyNativeColorFormats(capability);
        verifyShaderInterfaceRejections();
        System.out.println("VulkanModernFrameNativeSelfTest passed: device=" + capability.preferredDevice().name());
    }

    private static RendererConfig.Builder configuration() {
        return RendererConfig.builder().validationEnabled(true).maxFramesInFlight(2)
                .temporalRendering(TemporalRenderingOptions.disabled());
    }

    private static void verifyProgrammableScene(VulkanRtCapabilityProbe.Result capability) throws Exception {
        String miss = source("rmiss").replace("void main()", "void builtinMain()") + """

                layout(set=0, binding=33, std430) readonly buffer Tint { vec4 color; } tint;
                void main() { builtinMain(); payload.baseColorAndOpacity.rgb *= tint.color.rgb; }
                """;
        var shaders = SceneShaderProgram.builder().dataBindingCount(1)
                .shader(ShaderStage.RAY_GENERATION, source("rgen"))
                .shader(ShaderStage.RAY_MISS, miss)
                .shader(ShaderStage.RAY_CLOSEST_HIT, source("rchit"))
                .shader(ShaderStage.RAY_ANY_HIT, source("rahit")).build();
        var config = configuration().sceneShaders(shaders).build();
        var session = VulkanGpuSceneRenderingSession.open(capability, config, RendererRtDiagnostics.noop());
        try (var renderer = new VulkanRendererHost(config, session)) {
            renderer.apply(VulkanGpuSceneRenderingSessionNativeSelfTest.complexScene());
            ResourceData red = floats(1, 0.1f, 0.1f, 1);
            ResourceData blue = floats(0.1f, 0.1f, 1, 1);
            RenderFrameRequest invalid = request(0, 128, 72, List.of());
            expect(FrameValidationException.class, () -> renderer.submit(invalid));
            submit(renderer, request(0, 128, 72, List.of(red)));
            FrameSnapshot first = renderer.awaitFrame(Duration.ofSeconds(15)).orElseThrow();
            submit(renderer, request(1, 128, 72, List.of(blue)));
            FrameSnapshot second = renderer.awaitFrame(Duration.ofSeconds(15)).orElseThrow();
            require(checksum(first) != checksum(second), "application data did not alter real traced scene output");
            require(renderer.status() == Renderer.Status.READY, "deterministic validation poisoned renderer");
        }
    }

    private static void verifyMappedHdr(VulkanRtCapabilityProbe.Result capability) throws Exception {
        String raygen = """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(set=0, binding=1, rgba16f) uniform writeonly image2D outputImage;
                layout(set=0, binding=33, std430) readonly buffer Data { vec4 colors[]; } data;
                void main() { imageStore(outputImage, ivec2(gl_LaunchIDEXT.xy), data.colors[0]); }
                """;
        var config = configuration().frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .sceneShaders(SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION, raygen)
                        .dataBindingCount(1).build()).build();
        var session = VulkanGpuSceneRenderingSession.open(capability, config, RendererRtDiagnostics.noop());
        try (var renderer = new VulkanRendererHost(config, session)) {
            renderer.apply(VulkanGpuSceneRenderingSessionNativeSelfTest.complexScene());
            ResourceData data = floats(4, 2, 0.5f, 1);
            ByteBuffer[] stale = new ByteBuffer[3];
            IntBuffer[] derived = new IntBuffer[1];
            FrameSnapshot[] retained = new FrameSnapshot[1];
            submit(renderer, request(0, 16, 16, List.of(data)));
            read(renderer, view -> {
                require(view.format() == TextureFormat.RGBA16_FLOAT && view.byteCount() == 16 * 16 * 8,
                        "HDR output was converted or truncated");
                require(view.pixels().getShort(0) == (short) 0x4400, "radiance above one was lost");
                require(view.pixels().isReadOnly(), "mapped pixels are writable");
                stale[0] = view.pixels(); stale[1] = stale[0].duplicate(); stale[2] = stale[0].slice();
                derived[0] = stale[0].asIntBuffer();
                retained[0] = FrameSnapshot.copyOf(view);
                try {
                    CompletableFuture.runAsync(() -> stale[0].get(0)).join();
                    throw new AssertionError("mapped frame allowed cross-thread access");
                } catch (CompletionException expected) {
                    require(expected.getCause() instanceof WrongThreadException, "unexpected cross-thread exception: " + expected);
                }
                expect(IllegalStateException.class, renderer::close);
                expect(IllegalStateException.class, () -> renderer.readFrame(ignored -> {}));
            });
            for (ByteBuffer view : stale) expect(IllegalStateException.class, () -> view.get(0));
            expect(IllegalStateException.class, () -> derived[0].get(0));
            require(retained[0].pixels().getShort(0) == (short) 0x4400, "explicit snapshot lost independent storage");
            require(!renderer.readFrame(ignored -> { throw new AssertionError("duplicate frame"); }), "frame delivered twice");
            submit(renderer, request(1, 24, 12, List.of(data)));
            RuntimeException consumerFailure = new RuntimeException("intentional consumer failure");
            try { read(renderer, ignored -> { throw consumerFailure; }); throw new AssertionError("consumer failure was swallowed"); }
            catch (RuntimeException expected) { require(expected == consumerFailure, "consumer failure was rewritten"); }
            require(renderer.status() == Renderer.Status.READY, "consumer exception poisoned backend");
            for (int sequence = 2; sequence < 8; sequence++) {
                // Exercise reuse, growth and shorter subsequent descriptor ranges.
                ResourceData next = sequence == 3 ? floats(2, 1, 0.25f, 1, 8, 8, 8, 8) : data;
                submit(renderer, request(sequence, 24, 12, List.of(next)));
                read(renderer, view -> require(view.width() == 24 && view.height() == 12, "resize lost extent"));
            }
            require(retained[0].pixels().getShort(0) == (short) 0x4400, "native slot reuse corrupted snapshot");
        }
    }

    private static RenderFrameRequest request(long sequence, int width, int height, List<ResourceData> data) {
        return RenderFrameRequest.builder(sequence, width, height,
                        VulkanGpuSceneRenderingSessionNativeSelfTest.camera())
                .minimumSceneRevision(0).environment(VulkanGpuSceneRenderingSessionNativeSelfTest.environment())
                .shaderData(data).build();
    }

    private static ResourceData floats(float... values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        return new ResourceData(bytes.flip());
    }

    private static void verifyShaderInterfaceRejections() {
        String source = "#version 460\n#extension GL_EXT_ray_tracing : require\n"
                + "layout(set=0,binding=1,rgba16f) uniform writeonly image2D outputImage;\n"
                + "void main(){imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),vec4(1));}";
        var module = VulkanGenericLogicOperationNativeSelfTest.module(99_999, ShaderStage.RAY_GENERATION,
                org.lwjgl.util.shaderc.Shaderc.shaderc_raygen_shader, source, List.of(), List.of(), List.of());
        byte[] spirv = new byte[module.spirv().remaining()];
        module.spirv().get(spirv);
        expect(VulkanGenericPipelineCompilationException.class, () ->
                VulkanSpirvBindingValidator.requireSceneInterface(spirv, ShaderStage.RAY_GENERATION, 0, false));
        VulkanSpirvBindingValidator.requireSceneInterface(spirv, ShaderStage.RAY_GENERATION, 0, true);
        expect(VulkanGenericPipelineCompilationException.class, () ->
                VulkanSpirvBindingValidator.requireSceneInterface(spirv, ShaderStage.RAY_MISS, 0, true));
    }

    private static void verifyNativeColorFormats(VulkanRtCapabilityProbe.Result capability) throws Exception {
        var config = configuration().build();
        try (var device = top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime.open(
                     capability, RendererRtDiagnostics.noop(), true, true, config);
             var session = new VulkanGenericCommandSession(device, 2)) {
            long sequence = 0;
            for (TextureFormat format : TextureFormat.values()) {
                if (format.numericClass() != TextureFormat.NumericClass.FLOATING_POINT) continue;
                var texture = new TextureResource(new RenderResourceId(80_000 + sequence), ResourceVersion.initial(),
                        TextureDimension.TEXTURE_2D, 2, 2, 1, 1, 1, 1, format,
                        java.util.Set.of(TextureUsage.COLOR_ATTACHMENT));
                var resources = session.submitResources(new RenderResourceTransaction(sequence, List.of(), List.of(texture), List.of()));
                require(resources.outcome() == ResourceTransactionEvidence.Outcome.ACCEPTED, "color publication failed " + format);
                var view = new TextureView(texture, TextureViewDimension.TEXTURE_2D,
                        new TextureSubresourceRange(TextureAspect.COLOR, 0, 1, 0, 1));
                var commands = new RenderCommandTransaction(sequence, List.of(new BeginRenderPassCommand(
                        RenderPassDescriptor.color(2, 2, List.of(RenderAttachment.cleared(view, StoreOp.STORE,
                                new ClearValue.Color(1, 0, 1, 1))))), new EndRenderPassCommand()));
                require(session.submit(commands).outcome().recorded(), "color clear rejected " + format);
                VulkanGenericCommandNativeSelfTest.await(session, sequence, CommandExecutionEvidence.Outcome.OUTPUT_PRODUCED);
                var frame = session.captureLatestCpuFrame(sequence - 1);
                require(frame != null && frame.format() == format && frame.byteCount() == 4 * format.bytesPerTexel(),
                        "native color metadata mismatch " + format);
                ByteBuffer expected = ByteBuffer.allocate(frame.byteCount()).order(ByteOrder.LITTLE_ENDIAN);
                int bits = format.name().contains("32") ? 32 : format.name().contains("16") ? 16 : 8;
                int components = format.bytesPerTexel() / (bits / 8);
                for (int pixel = 0; pixel < 4; pixel++) for (int component = 0; component < components; component++) {
                    float value = component == 1 ? 0 : 1;
                    switch (bits) {
                        case 8 -> expected.put((byte) (value == 0 ? 0 : 255));
                        case 16 -> expected.putShort((short) (value == 0 ? 0 : 0x3c00));
                        case 32 -> expected.putFloat(value);
                        default -> throw new AssertionError("unexpected color width");
                    }
                }
                require(frame.pixels().equals(expected.flip()), "native color payload mismatch " + format);
                sequence++;
            }
            require(sequence == 10, "native normalized/float format coverage changed");
        }
    }

    private static void submit(Renderer renderer, RenderFrameRequest request) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred) {
            if (System.nanoTime() > deadline) throw new AssertionError("frame admission timeout");
            Thread.sleep(1);
        }
    }

    private static void read(Renderer renderer, FrameConsumer consumer) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!renderer.readFrame(consumer)) {
            if (System.nanoTime() > deadline) throw new AssertionError("frame read timeout");
            Thread.sleep(1);
        }
    }

    private static long checksum(FrameView view) {
        long checksum = 1; var bytes = view.pixels();
        while (bytes.hasRemaining()) checksum = checksum * 31 + Byte.toUnsignedInt(bytes.get());
        return checksum;
    }

    private static String source(String stage) throws IOException {
        try (var stream = VulkanModernFrameNativeSelfTest.class.getClassLoader()
                .getResourceAsStream("assets/rtrenderer/shaders/gpuscene/gpuscene." + stage)) {
            if (stream == null) throw new IOException("missing scene stage source");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError("unexpected failure", failure); }
        throw new AssertionError("expected " + type.getName());
    }

    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
