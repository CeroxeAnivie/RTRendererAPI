package top.ceroxe.rt.renderer.api;

/**
 * Renderer-owned native frame encoding selected for the lifetime of a renderer.
 *
 * <p>This policy controls both managed frames and native-interoperability output. A
 * {@link FrameSnapshot} preserves the source encoding: inspect {@link FrameView#format()}
 * before reading pixels. HDR snapshots contain linear half-float channels and are not
 * implicitly converted to display-ready RGBA8. Use {@link #SDR_RGBA8} for ordinary SDR display.</p>
 */
public enum FrameOutputFormat {
    /**
     * Display-ready SDR RGBA8 after renderer tone mapping and linear-to-sRGB transfer.
     */
    SDR_RGBA8,

    /**
     * Linear, scene-referred HDR RGBA16F without renderer tone mapping or transfer encoding.
     */
    LINEAR_HDR_RGBA16F
}
