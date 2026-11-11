package top.ceroxe.rt.renderer.rtxpt;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Independent public-API native acceptance for environment replacement and light-only updates. */
public final class RtxptEnvironmentMapNativeSelfTest {
    private static final int EXTENT = 64;
    private static final CameraState CAMERA = CameraState.lookAt(0, 0, 4, 0, 0, 0).build();

    private record Result(String name, long revision, long firstFrame, long lastFrame, int channel,
            double red, double green, double blue, int minimumDominant, int maximumOther) { }

    private static EnvironmentMap constantMap(int channel) {
        float[] rgb = new float[8 * 4 * 3];
        for (int pixel = 0; pixel < 8 * 4; pixel++) rgb[pixel * 3 + channel] = .5f;
        return new EnvironmentMap(8, 4, rgb);
    }

    private static RendererConfig config() {
        var quality = PathTracingQualityOptions.defaults().withPostProcessing(
                new PathTracingQualityOptions.PostProcessing(false, 8, 0, false, 0));
        var path = new PathTracingOptions(1, 1, 0, RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.DISABLED, RendererFeaturePreference.DISABLED, quality);
        return RendererConfig.builder().frameGeneration(FrameGenerationOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled()).denoising(DenoisingOptions.disabled())
                .lowLatency(LowLatencyOptions.disabled()).rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
                .pathTracing(path).build();
    }

    private static Result step(Renderer renderer, RtxptAccess access, Path output, String name,
            SceneTransaction transaction, EnvironmentState environment, int channel, long[] sequence) throws Exception {
        long revision = transaction.revision();
        if (renderer.apply(transaction).acceptedSceneRevision() != revision)
            throw new AssertionError(name + ": wrong accepted scene revision");
        long first = sequence[0];
        for (int frame = 0; frame < 2; frame++) {
            long current = sequence[0]++;
            var submitted = renderer.submit(RenderFrameRequest.builder(current, EXTENT, EXTENT, CAMERA)
                    .minimumSceneRevision(revision).environment(environment).build());
            if (submitted.frameSequence() != current || submitted.scheduledSceneRevision() != revision)
                throw new AssertionError(name + ": wrong public frame admission identity");
        }
        access.awaitIdle();
        Path capture = output.resolve(name + ".png"); access.capture(capture);
        BufferedImage image = ImageIO.read(capture.toFile());
        if (image == null || image.getWidth() != EXTENT || image.getHeight() != EXTENT)
            throw new AssertionError(name + ": expected a 64x64 capture");
        long[] sum = new long[3]; int minDominant = 255, maxOther = 0;
        for (int y = EXTENT / 2 - 4; y <= EXTENT / 2 + 4; y++) {
            for (int x = EXTENT / 2 - 4; x <= EXTENT / 2 + 4; x++) {
                int pixel = image.getRGB(x, y);
                int[] rgb = {(pixel >>> 16) & 255, (pixel >>> 8) & 255, pixel & 255};
                int dominant = rgb[channel]; minDominant = Math.min(minDominant, dominant);
                for (int color = 0; color < 3; color++) {
                    sum[color] += rgb[color];
                    if (color != channel) {
                        maxOther = Math.max(maxOther, rgb[color]);
                        if (dominant < 16 || rgb[color] > Math.max(8, dominant * .1))
                            throw new AssertionError(name + ": wrong center color at " + x + "," + y
                                    + " RGB=" + rgb[0] + "," + rgb[1] + "," + rgb[2]);
                    }
                }
            }
        }
        return new Result(name, revision, first, sequence[0] - 1, channel,
                sum[0] / 81.0, sum[1] / 81.0, sum[2] / 81.0, minDominant, maxOther);
    }

    private static String quoted(String value) {
        var result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static void summary(Path output, List<Result> results, boolean pass, String failure) throws Exception {
        var json = new StringBuilder("{\n  \"pass\": " + pass
                + ",\n  \"width\": 64,\n  \"height\": 64,\n  \"centerRegion\": \"9x9\",\n"
                + "  \"framesPerState\": 2,\n  \"geometry\": true,\n  \"geometryOutsideView\": true,\n  \"diagnostics\": \"OFF\",\n"
                + "  \"frameGeneration\": false,\n  \"superResolution\": false,\n  \"denoising\": false,\n"
                + "  \"bloom\": false,\n  \"toneMapping\": false,\n  \"states\": [");
        for (int index = 0; index < results.size(); index++) {
            if (index != 0) json.append(','); Result result = results.get(index);
            json.append("\n    {\"name\":").append(quoted(result.name())).append(",\"sceneRevision\":").append(result.revision())
                    .append(",\"firstFrame\":").append(result.firstFrame()).append(",\"lastFrame\":").append(result.lastFrame())
                    .append(",\"expectedChannel\":").append(quoted(new String[]{"red", "green", "blue"}[result.channel()]))
                    .append(",\"meanRgb\":[").append(result.red()).append(',').append(result.green()).append(',').append(result.blue())
                    .append("],\"minimumDominant\":").append(result.minimumDominant()).append(",\"maximumOther\":").append(result.maximumOther())
                    .append(",\"png\":").append(quoted(result.name() + ".png")).append(",\"pass\":true}");
        }
        json.append("\n  ],\n  \"failure\":").append(failure == null ? "null" : quoted(failure)).append("\n}\n");
        Files.writeString(output.resolve("summary.json"), json, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[1].equals("--procedural-sky")) {
            proceduralSky(Path.of(args[0]).toAbsolutePath());
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("Usage: RtxptEnvironmentMapNativeSelfTest <output-directory>");
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output);
        var results = new ArrayList<Result>();
        var redMap = constantMap(0); var blueMap = constantMap(2);
        var green = EnvironmentState.builder().skyRadiance(0, .5f, 0).ambientIntensity(1).build();
        var red = green.toBuilder().environmentMap(redMap).build();
        var blue = green.toBuilder().environmentMap(blueMap).build();
        var cleared = blue.toBuilder().environmentMap(null).build();
        var restored = cleared.toBuilder().environmentMap(redMap).build();
        if (!restored.equals(red) || restored.environmentMap() != redMap || !cleared.equals(green))
            throw new AssertionError("Environment copy/clear/restore did not preserve public values");
        long[] sequence = {0};
        try (Renderer renderer = RtxptRenderers.openHiddenWindow(config())) {
            RtxptAccess access = renderer.extension(RtxptAccess.class).orElseThrow(
                    () -> new AssertionError("RTXPT public capture extension is missing"));
            access.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            var initial = SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(91).build())
                    .upsert(MeshAsset.builder(92, new float[]{-1,-1,5, 1,-1,5, 0,1,5},
                            new int[]{0,1,2}, new long[]{91}).build())
                    .upsert(SceneInstance.builder(93,92).build()).build();
            results.add(step(renderer, access, output, "red-map", initial, red, 0, sequence));
            results.add(step(renderer, access, output, "blue-map", SceneTransaction.builder(2).build(), blue, 2, sequence));
            results.add(step(renderer, access, output, "blue-light-only", SceneTransaction.builder(3)
                    .upsert(SceneLight.point(17, 1, 2, 3).intensity(1).range(10).build()).build(), blue, 2, sequence));
            results.add(step(renderer, access, output, "green-constant", SceneTransaction.builder(4).build(), cleared, 1, sequence));
            results.add(step(renderer, access, output, "red-map-restored", SceneTransaction.builder(5).build(), restored, 0, sequence));
        } catch (Exception | Error failure) {
            try { summary(output, results, false, failure.toString()); }
            catch (Exception summaryFailure) { failure.addSuppressed(summaryFailure); }
            throw failure;
        }
        summary(output, results, true, null);
        System.out.println("ENVIRONMENT_MAP_NATIVE PASS states=5 frames=10 publicRenderer=true lightOnlyMapRetained=true output=" + output);
    }

    /** Select the original procedural baker through native scenes, without generated sky DDS files. */
    private static void proceduralSky(Path output) throws Exception {
        Files.createDirectories(output);
        final int extent = 1024;
        Path assets = RtxptNative.runtime().resolve("../Assets/StandaloneTextures").normalize();
        for (String name : new String[]{"RGBANoiseMedium.png", "q2rtx_env/clouds.dds", "q2rtx_env/inscatter_earth.dds",
                "q2rtx_env/irradiance_earth.dds", "q2rtx_env/transmittance_earth.dds"})
            if (!Files.isRegularFile(assets.resolve(name))) throw new AssertionError("Missing procedural sky resource: " + name);
        // A real triangle behind the camera exercises the ordinary native-file
        // AS lifecycle without any demonstration models or empty-AS shortcuts.
        var buffer = java.nio.ByteBuffer.allocate(84).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{-1,-1,5, 1,-1,5, 0,1,5, 0,0,-1, 0,0,-1, 0,0,-1}) buffer.putFloat(value);
        buffer.putInt(0).putInt(1).putInt(2);
        Files.writeString(output.resolve("sky-guard.gltf"), """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"mesh":0}],"materials":[{"name":"sky-contract-guard"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"material":0}]}],
                 "buffers":[{"uri":"data:application/octet-stream;base64,%s","byteLength":84}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
                   {"buffer":0,"byteOffset":36,"byteLength":36,"target":34962},
                   {"buffer":0,"byteOffset":72,"byteLength":12,"target":34963}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[-1,-1,5],"max":[1,1,5]},
                   {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":2,"componentType":5125,"count":3,"type":"SCALAR"}]}
                """.formatted(java.util.Base64.getEncoder().encodeToString(buffer.array())), StandardCharsets.UTF_8);
        var results = new ArrayList<String>(); results.add("preset,firstSequence,lastSequence,meanChannel,maximumChannel");
        try (Renderer renderer = RtxptRenderers.openHiddenWindow(config())) {
            RtxptAccess access = renderer.extension(RtxptAccess.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            long sequence = 1;
            for (String preset : new String[]{"MIDDAY", "PITCHBLACK"}) {
                Path scene = output.resolve("sky-" + preset + ".scene.json");
                Files.writeString(scene, """
                        {"models":["sky-guard.gltf"],"graph":[{"model":0},
                         {"name":"Sky","type":"EnvironmentLight","intensity":1,"path":"==PROCEDURAL_SKY_%s=="}]}
                        """.formatted(preset), StandardCharsets.UTF_8);
                access.loadNativeScene(scene);
                long first = sequence;
                for (int frame = 0; frame < 2; frame++)
                    renderer.submit(RenderFrameRequest.builder(sequence++, extent, extent, CAMERA).build());
                access.awaitIdle();
                Path capture = output.resolve("procedural-" + preset + ".png"); access.capture(capture);
                BufferedImage image = ImageIO.read(capture.toFile());
                if (image == null || image.getWidth() != extent || image.getHeight() != extent)
                    throw new AssertionError("Missing procedural sky capture: " + preset);
                long sum = 0; int maximum = 0;
                // Native-file scenes retain the official settings panel on the left.
                // Sample a fixed 64x64 sky region clear of that panel.
                for (int y = extent / 2 - 32; y < extent / 2 + 32; y++) for (int x = extent - 80; x < extent - 16; x++) {
                    int pixel = image.getRGB(x, y);
                    for (int shift = 0; shift <= 16; shift += 8) {
                        int value = (pixel >>> shift) & 255; sum += value; maximum = Math.max(maximum, value);
                    }
                }
                double mean = sum / (64 * 64 * 3.0);
                if (preset.equals("MIDDAY") && mean < 4) throw new AssertionError("Procedural midday sky is black: " + mean);
                if (preset.equals("PITCHBLACK") && maximum > 1) throw new AssertionError("Procedural pitch-black sky retained radiance: " + maximum);
                results.add(preset + "," + first + "," + (sequence - 1) + "," + mean + "," + maximum);
            }
        }
        Files.write(output.resolve("procedural-sky-cases.csv"), results, StandardCharsets.UTF_8);
        System.out.println("PROCEDURAL_SKY_NATIVE PASS states=2 frames=4 assets=5 nativeScene=true generatedEnvironmentMap=false output=" + output);
    }
}
