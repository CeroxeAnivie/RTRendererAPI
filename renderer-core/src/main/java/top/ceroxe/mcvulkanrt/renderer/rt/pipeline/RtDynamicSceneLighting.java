package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

/** Resolves scene-owned sky and directional-light payloads before SSBO encoding. */
final class RtDynamicSceneLighting {
    private RtDynamicSceneLighting() {
    }

    static SkyPalette skyPalette(DynamicRenderScene dynamicScene, boolean active) {
        if (!active) {
            float red = 92.0F / 255.0F;
            float green = 148.0F / 255.0F;
            float blue = 224.0F / 255.0F;
            return new SkyPalette(red, green, blue, red, green, blue, 0.0F);
        }
        for (DynamicRenderScene.SceneLight light : dynamicScene.lights()) {
            if (light.kind() == DynamicRenderScene.LightKind.SKY) {
                float intensity = clamp(light.intensity(), 0.0F, 4.0F);
                float scale = clamp(0.35F + intensity * 0.25F, 0.0F, 1.5F);
                float red = clamp(rgbRed(light.rgb8()) * scale, 0.0F, 1.0F);
                float green = clamp(rgbGreen(light.rgb8()) * scale, 0.0F, 1.0F);
                float blue = clamp(rgbBlue(light.rgb8()) * scale, 0.0F, 1.0F);
                return new SkyPalette(
                        red, green, blue,
                        mix(red, 0.78F, 0.45F),
                        mix(green, 0.86F, 0.45F),
                        mix(blue, 1.0F, 0.45F),
                        intensity
                );
            }
        }
        return new SkyPalette(0.20F, 0.43F, 0.95F, 0.62F, 0.78F, 1.0F, 1.0F);
    }

    static DirectionalLight directionalLight(DynamicRenderScene dynamicScene, boolean active) {
        if (!active) {
            return DirectionalLight.none();
        }
        for (DynamicRenderScene.SceneLight light : dynamicScene.lights()) {
            if (light.kind() == DynamicRenderScene.LightKind.SUN
                    || light.kind() == DynamicRenderScene.LightKind.MOON
                    || light.kind() == DynamicRenderScene.LightKind.SKY) {
                return new DirectionalLight(
                        light.directionX(), light.directionY(), light.directionZ(),
                        clamp(light.intensity(), 0.0F, 64.0F),
                        rgbRed(light.rgb8()), rgbGreen(light.rgb8()), rgbBlue(light.rgb8()),
                        light.castsShadow()
                );
            }
        }
        for (DynamicRenderScene.CelestialBody body : dynamicScene.celestialBodies()) {
            if (body.kind() == DynamicRenderScene.CelestialKind.SUN
                    || body.kind() == DynamicRenderScene.CelestialKind.MOON) {
                return new DirectionalLight(
                        body.directionX(), body.directionY(), body.directionZ(),
                        clamp(body.brightness(), 0.0F, 64.0F),
                        rgbaRed(body.rgba8()), rgbaGreen(body.rgba8()), rgbaBlue(body.rgba8()), true
                );
            }
        }
        return DirectionalLight.none();
    }

    record SkyPalette(
            float zenithRed, float zenithGreen, float zenithBlue,
            float horizonRed, float horizonGreen, float horizonBlue,
            float intensity
    ) {
    }

    record DirectionalLight(
            float directionX, float directionY, float directionZ, float intensity,
            float red, float green, float blue, boolean castsShadow
    ) {
        private static DirectionalLight none() {
            return new DirectionalLight(0.0F, -1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, false);
        }
    }

    private static float rgbRed(int rgb8) { return ((rgb8 >>> 16) & 0xff) / 255.0F; }
    private static float rgbGreen(int rgb8) { return ((rgb8 >>> 8) & 0xff) / 255.0F; }
    private static float rgbBlue(int rgb8) { return (rgb8 & 0xff) / 255.0F; }
    private static float rgbaRed(int rgba8) { return (rgba8 & 0xff) / 255.0F; }
    private static float rgbaGreen(int rgba8) { return ((rgba8 >>> 8) & 0xff) / 255.0F; }
    private static float rgbaBlue(int rgba8) { return ((rgba8 >>> 16) & 0xff) / 255.0F; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float mix(float left, float right, float alpha) { return left * (1.0F - alpha) + right * alpha; }
}
