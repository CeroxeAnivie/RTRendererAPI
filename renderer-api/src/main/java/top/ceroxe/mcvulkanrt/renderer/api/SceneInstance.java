package top.ceroxe.mcvulkanrt.renderer.api;

/** Persistent scene instance referencing an immutable mesh asset generation. */
public record SceneInstance(
        long id,
        long meshAssetId,
        AffineTransform transform,
        Mobility mobility,
        int visibilityMask,
        boolean castsShadow,
        float surfaceVisibility
) {
    /**
     * Backward-compatible fully visible instance. The scalar is deliberately separate from the
     * Vulkan visibility mask: the mask controls ray participation, while surface visibility is a
     * shader-visible appearance transition and must never require a TLAS rebuild.
     */
    public SceneInstance(
            long id,
            long meshAssetId,
            AffineTransform transform,
            Mobility mobility,
            int visibilityMask,
            boolean castsShadow
    ) {
        this(id, meshAssetId, transform, mobility, visibilityMask, castsShadow, 1.0F);
    }

    public SceneInstance {
        MaterialAsset.requireId(id, "id");
        MaterialAsset.requireId(meshAssetId, "meshAssetId");
        transform = java.util.Objects.requireNonNull(transform, "transform");
        mobility = java.util.Objects.requireNonNull(mobility, "mobility");
        if ((visibilityMask & ~0xff) != 0 || visibilityMask == 0) {
            throw new IllegalArgumentException("visibilityMask must contain a non-zero Vulkan 8-bit mask");
        }
        if (!Float.isFinite(surfaceVisibility)
                || surfaceVisibility < 0.0F || surfaceVisibility > 1.0F) {
            throw new IllegalArgumentException("surfaceVisibility must be finite and within [0, 1]");
        }
    }

    public enum Mobility {
        STATIC,
        DYNAMIC
    }
}
