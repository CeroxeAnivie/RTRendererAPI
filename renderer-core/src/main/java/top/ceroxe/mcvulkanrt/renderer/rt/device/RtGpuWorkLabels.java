package top.ceroxe.mcvulkanrt.renderer.rt.device;

/** Stable renderer-owned labels joining GPU submission, aggregation, tests, and diagnostics. */
public final class RtGpuWorkLabels {
    public static final String SECTION_BLAS = "sectionBlas";
    public static final String FAR_FIELD_BLAS = "farFieldBlas";
    public static final String DYNAMIC_BLAS = "dynamicBlas";
    public static final String DYNAMIC_TLAS = "dynamicTlas";
    public static final String WORLD_TLAS = "worldTlas";
    public static final String MATERIAL_UPLOAD = "materialUpload";

    private RtGpuWorkLabels() {
    }
}
