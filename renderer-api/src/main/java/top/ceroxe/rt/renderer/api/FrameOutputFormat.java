package top.ceroxe.rt.renderer.api;

/**
 * Renderer-owned native frame encoding selected for the lifetime of a renderer.
 *
 * <p>This policy controls frames obtained through an expert native-interoperability extension.
 * {@link CpuFrame} remains a managed, display-ready RGBA8 snapshot for both choices so beginner
 * integrations never need to decode floating-point pixels or perform tone mapping.</p>
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
