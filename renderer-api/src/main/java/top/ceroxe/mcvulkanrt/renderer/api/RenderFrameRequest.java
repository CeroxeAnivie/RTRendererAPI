package top.ceroxe.mcvulkanrt.renderer.api;

/** One render request against a minimum accepted persistent-scene revision. */
public record RenderFrameRequest(
        long sequence,
        long minimumSceneRevision,
        int width,
        int height,
        CameraState camera,
        EnvironmentState environment,
        LightmapState lightmap,
        DistanceFogState fog,
        TextureSamplingState textureSampling
) {
    public RenderFrameRequest(
            long sequence,
            long minimumSceneRevision,
            int width,
            int height,
            CameraState camera,
            EnvironmentState environment
    ) {
        this(
                sequence, minimumSceneRevision, width, height, camera, environment,
                LightmapState.fullIntensity(), DistanceFogState.disabled(),
                TextureSamplingState.pixelStable()
        );
    }

    public RenderFrameRequest(
            long sequence,
            long minimumSceneRevision,
            int width,
            int height,
            CameraState camera,
            EnvironmentState environment,
            LightmapState lightmap
    ) {
        this(
                sequence, minimumSceneRevision, width, height, camera, environment,
                lightmap, DistanceFogState.disabled(), TextureSamplingState.pixelStable()
        );
    }

    public RenderFrameRequest(
            long sequence,
            long minimumSceneRevision,
            int width,
            int height,
            CameraState camera,
            EnvironmentState environment,
            LightmapState lightmap,
            DistanceFogState fog
    ) {
        this(
                sequence, minimumSceneRevision, width, height, camera, environment,
                lightmap, fog, TextureSamplingState.pixelStable()
        );
    }

    public RenderFrameRequest {
        if (sequence < 0L || minimumSceneRevision < 0L) {
            throw new IllegalArgumentException("frame and scene revisions must not be negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        long pixels = (long) width * height;
        if (pixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame extent exceeds supported pixel address space");
        }
        camera = java.util.Objects.requireNonNull(camera, "camera");
        environment = java.util.Objects.requireNonNull(environment, "environment");
        lightmap = java.util.Objects.requireNonNull(lightmap, "lightmap");
        fog = java.util.Objects.requireNonNull(fog, "fog");
        textureSampling = java.util.Objects.requireNonNull(textureSampling, "textureSampling");
    }
}
