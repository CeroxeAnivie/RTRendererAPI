package top.ceroxe.rt.renderer.rt.device;

/**
 * Stable renderer-owned labels joining GPU submission, aggregation, tests, and diagnostics.
 */
public final class RtGpuWorkLabels {
    /**
     * Near-field section BLAS work.
     */
    public static final String SECTION_BLAS = "sectionBlas";
    /**
     * Far-field BLAS work.
     */
    public static final String FAR_FIELD_BLAS = "farFieldBlas";
    /**
     * Dynamic-scene BLAS work.
     */
    public static final String DYNAMIC_BLAS = "dynamicBlas";
    /**
     * Dynamic-scene TLAS work.
     */
    public static final String DYNAMIC_TLAS = "dynamicTlas";
    /**
     * World TLAS work.
     */
    public static final String WORLD_TLAS = "worldTlas";
    /**
     * Material table upload work.
     */
    public static final String MATERIAL_UPLOAD = "materialUpload";

    private RtGpuWorkLabels() {
    }
}
