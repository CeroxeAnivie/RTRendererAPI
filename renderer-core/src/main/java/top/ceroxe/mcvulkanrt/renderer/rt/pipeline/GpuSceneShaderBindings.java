package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

/**
 * Canonical descriptor-set ABI shared by the generic GPUScene shaders and Vulkan pipeline.
 *
 * <p>This type belongs to renderer-core internals; renderer clients consume only renderer-api.
 * Keeping the binding numbers here prevents the scene packer and pipeline factory from evolving
 * independent copies of the same native contract.</p>
 */
public final class GpuSceneShaderBindings {
    public static final int TLAS = 0;
    public static final int OUTPUT_IMAGE = 1;
    public static final int FRAME_UNIFORMS = 2;
    public static final int TEXTURE_RECORDS = 3;
    public static final int TEXTURE_PIXELS = 4;
    public static final int MATERIAL_RECORDS = 5;
    public static final int MESH_RECORDS = 6;
    public static final int POSITIONS = 7;
    public static final int NORMALS = 8;
    public static final int TANGENTS = 9;
    public static final int TEXTURE_COORDINATES = 10;
    public static final int COLORS = 11;
    public static final int LIGHTMAP_COORDINATES = 12;
    public static final int INDICES = 13;
    public static final int TRIANGLE_MATERIAL_SLOTS = 14;
    public static final int INSTANCE_RECORDS = 15;
    public static final int LIGHT_RECORDS = 16;
    public static final int COUNT = 17;
    public static final int STORAGE_BUFFER_COUNT = 15;

    private GpuSceneShaderBindings() {
    }
}
