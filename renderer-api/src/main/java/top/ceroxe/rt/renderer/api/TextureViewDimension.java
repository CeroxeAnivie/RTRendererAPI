package top.ceroxe.rt.renderer.api;

/**
 * Logical shape exposed by a texture view.
 *
 * <p>The view shape is explicit because a backend must never infer cube or array sampling
 * semantics from an unrelated texture dimension.</p>
 */
public enum TextureViewDimension {
    TEXTURE_1D,
    TEXTURE_1D_ARRAY,
    TEXTURE_2D,
    TEXTURE_2D_ARRAY,
    TEXTURE_2D_MULTISAMPLED,
    TEXTURE_2D_MULTISAMPLED_ARRAY,
    TEXTURE_3D,
    CUBE,
    CUBE_ARRAY
}
