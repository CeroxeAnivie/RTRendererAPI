package demo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Minimal public-API example: application bytes drive a custom retained-scene HDR shader. */
public final class ProgrammableSceneDemo {
    private ProgrammableSceneDemo() { }

    public static void main(String[] arguments) throws Exception {
        if (!java.util.Arrays.asList(arguments).contains("--verify")) {
            ProgrammableSceneWorkbench.open();
            return;
        }
        String raygen = """
                #version 460
                #extension GL_EXT_ray_tracing : require
                layout(set=0,binding=1,rgba16f) uniform writeonly image2D outputImage;
                layout(set=0,binding=33,std430) readonly buffer Parameters { vec4 radiance; } parameters;
                void main() { imageStore(outputImage,ivec2(gl_LaunchIDEXT.xy),parameters.radiance); }
                """;
        var shaders = SceneShaderProgram.builder().shader(ShaderStage.RAY_GENERATION, raygen)
                .dataBindingCount(1).build();
        var config = RendererConfig.builder().sceneShaders(shaders)
                .temporalRendering(TemporalRenderingOptions.disabled())
                .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).build();
        var bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putFloat(4).putFloat(2).putFloat(0.5f).putFloat(1).flip();
        var parameters = new ResourceData(bytes);
        try (Renderer renderer = Renderer.open(config)) {
            long revision = renderer.apply(SceneTransaction.empty(0)).acceptedSceneRevision();
            var camera = CameraState.lookAt(0, 0, 5, 0, 0, 0).aspectRatio(1).build();
            renderer.submit(RenderFrameRequest.builder(0, 64, 64, camera).minimumSceneRevision(revision)
                    .shaderData(List.of(parameters)).build());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!renderer.readFrame(frame -> {
                if (frame.format() != TextureFormat.RGBA16_FLOAT || frame.pixels().getShort(0) != (short) 0x4400) {
                    throw new IllegalStateException("native HDR radiance did not survive readback");
                }
                System.out.println("Programmable scene: " + frame.width() + "x" + frame.height()
                        + " " + frame.format() + " first radiance=4.0; explicit snapshot bytes="
                        + FrameSnapshot.copyOf(frame).byteCount());
            })) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("frame timed out");
                Thread.sleep(1);
            }
        }
    }
}
