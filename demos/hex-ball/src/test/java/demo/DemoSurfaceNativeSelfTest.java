package demo;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Compares the real demo and runtime transport transitions with the unchanged full shader. */
public final class DemoSurfaceNativeSelfTest {
    private static final int WIDTH = 160, HEIGHT = 90;

    private DemoSurfaceNativeSelfTest() { }

    public static void main(String[] args) throws Exception {
        for (var format : FrameOutputFormat.values()) {
            var reference = capture(format, true);
            var optimized = capture(format, false);
            for (int frame = 0; frame < reference.size(); frame++) compare(format, frame, reference.get(frame), optimized.get(frame));
            System.out.println("DemoSurfaceNativeSelfTest passed: " + format + ", frames=" + reference.size());
        }
    }

    private static List<byte[]> capture(FrameOutputFormat format, boolean full) throws Exception {
        var config = RendererConfig.builder().maxFramesInFlight(3).validationEnabled(true)
                .frameOutputFormat(format).temporalRendering(TemporalRenderingOptions.disabled());
        if (full) {
            String variant = format == FrameOutputFormat.SDR_RGBA8 ? "base" : "base-hdr";
            String resource = "/assets/rtrenderer/shaders/spv/" + variant + "/gpuscene_gpuscene.rgen.spv";
            try (var input = DemoSurfaceNativeSelfTest.class.getResourceAsStream(resource)) {
                if (input == null) throw new AssertionError("missing reference SPIR-V: " + resource);
                config.sceneShaders(SceneShaderProgram.builder()
                        .spirv(ShaderStage.RAY_GENERATION, format, input.readAllBytes()).build());
            }
        }
        var frames = new ArrayList<byte[]>();
        try (var renderer = Renderer.open(config.build())) {
            var scene = new DemoScene();
            var initial = scene.initialTransaction();
            renderer.apply(initial);
            var batch = scene.primitiveBatch(new HexPhysics(), FramePrimitiveBatch.empty());
            var environment = DemoRendererProfile.environment();
            long revision = 0;
            for (int spp : new int[]{1,2,4,8}) frames.add(render(renderer, frames.size(), revision, batch, environment, spp));
            frames.add(render(renderer, frames.size(), revision, batch, environment.toBuilder().cameraMedium(
                    new Medium(new Rgb(.01f,.02f,.03f), new Rgb(.002f,.002f,.002f), new Rgb(.001f,0,0), 1, 1, 0)).build(), 1));
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            var ball = initial.upserts().materials().stream().filter(value -> value.id() == 110).findFirst().orElseThrow();
            renderer.apply(SceneTransaction.builder(++revision).upsert(ball.toBuilder()
                    .blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(.8f).build()).build());
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            renderer.apply(SceneTransaction.builder(++revision).upsert(ball).build());
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            var medium = MediumBoundary.volume(new Medium(new Rgb(.2f,.1f,.05f), Rgb.ZERO, Rgb.ZERO, 1, 1, 0));
            var cube = MeshAsset.triangles(900, new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},
                    new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5}, 100);
            renderer.apply(SceneTransaction.builder(++revision).upsert(cube)
                    .upsert(SceneInstance.builder(900,900).mediumBoundary(medium).build()).build());
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            renderer.apply(SceneTransaction.builder(++revision).removeInstance(900).build());
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            var local = FramePrimitiveBatch.builder();
            for (var primitive : batch.primitives()) local.add(primitive);
            local.add(PrimitiveInstance.builder(900).mediumBoundary(medium).build());
            frames.add(render(renderer, frames.size(), revision, local.build(), environment, 1));
            frames.add(render(renderer, frames.size(), revision, batch, environment, 1));
            if (renderer.health().activeFailure().isPresent()
                    || !renderer.health().obligations().equals(RendererHealth.ResourceObligations.none()))
                throw new AssertionError("unhealthy renderer after transitions: " + renderer.health());
        }
        return frames;
    }

    private static byte[] render(Renderer renderer, long sequence, long revision, FramePrimitiveBatch batch,
            EnvironmentState environment, int spp) throws Exception {
        var request = DemoRendererProfile.frame(sequence, WIDTH, HEIGHT, DemoRendererProfile.camera(WIDTH, HEIGHT, 15.4))
                .minimumSceneRevision(revision).primitiveBatch(batch).environment(environment)
                .antiAliasing(DemoRendererProfile.antiAliasing(spp)).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            if (System.nanoTime() >= deadline) throw new AssertionError("admission timeout");
            Thread.sleep(1);
        }
        while (System.nanoTime() < deadline) {
            var completed = renderer.pollFrame();
            if (completed.isPresent()) {
                var pixels = completed.orElseThrow().pixels();
                byte[] result = new byte[pixels.remaining()];
                pixels.get(result);
                return result;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("frame completion timeout");
    }

    private static void compare(FrameOutputFormat format, int frame, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) throw new AssertionError("output extent/format changed");
        int differences = 0;
        if (format == FrameOutputFormat.SDR_RGBA8) {
            for (int i = 0; i < expected.length; i++) {
                int delta = Math.abs(Byte.toUnsignedInt(expected[i]) - Byte.toUnsignedInt(actual[i]));
                if (delta > 1) throw new AssertionError("SDR frame=" + frame + " byte=" + i + " delta=" + delta);
                if (delta != 0) differences++;
            }
        } else {
            var a = java.nio.ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
            var b = java.nio.ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
            while (a.hasRemaining()) {
                float value = Float.float16ToFloat(a.getShort()), other = Float.float16ToFloat(b.getShort());
                if (!Float.isFinite(value) || !Float.isFinite(other)
                        || Math.abs(value - other) > .002f + .002f * Math.abs(value))
                    throw new AssertionError("HDR frame=" + frame + " value=" + value + " actual=" + other);
                if (value != other) differences++;
            }
        }
        System.out.println("Surface equivalence " + format + " frame=" + frame + " differingComponents=" + differences);
    }
}
