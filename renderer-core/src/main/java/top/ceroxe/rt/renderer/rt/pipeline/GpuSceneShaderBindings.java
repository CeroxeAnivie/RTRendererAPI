package top.ceroxe.rt.renderer.rt.pipeline;

/**
 * Canonical descriptor-set ABI shared by the generic GPUScene shaders and Vulkan pipeline.
 *
 * <p>This type belongs to renderer-core internals; renderer clients consume only renderer-api.
 * Keeping the binding numbers here prevents the scene packer and pipeline factory from evolving
 * independent copies of the same native contract.</p>
 */
public final class GpuSceneShaderBindings {
    /**
     * 顶层加速结构绑定。
     */
    public static final int TLAS = 0;
    /**
     * 存储输出图像绑定。
     */
    public static final int OUTPUT_IMAGE = 1;
    /**
     * 帧常量缓冲区绑定。
     */
    public static final int FRAME_UNIFORMS = 2;
    /**
     * 纹理元数据记录绑定。
     */
    public static final int TEXTURE_RECORDS = 3;
    /**
     * 纹理像素负载绑定。
     */
    public static final int TEXTURE_PIXELS = 4;
    /**
     * 材料记录绑定。
     */
    public static final int MATERIAL_RECORDS = 5;
    /**
     * 网格记录绑定。
     */
    public static final int MESH_RECORDS = 6;
    /**
     * 顶点位置绑定。
     */
    public static final int POSITIONS = 7;
    /**
     * 顶点法线绑定。
     */
    public static final int NORMALS = 8;
    /**
     * 顶点切线绑定。
     */
    public static final int TANGENTS = 9;
    /**
     * 顶点纹理坐标绑定。
     */
    public static final int TEXTURE_COORDINATES = 10;
    /**
     * 顶点颜色绑定。
     */
    public static final int COLORS = 11;
    /**
     * 光照贴图坐标绑定。
     */
    public static final int LIGHTMAP_COORDINATES = 12;
    /**
     * 三角形索引绑定。
     */
    public static final int INDICES = 13;
    /**
     * 三角形材料槽绑定。
     */
    public static final int TRIANGLE_MATERIAL_SLOTS = 14;
    /**
     * 实例记录绑定。
     */
    public static final int INSTANCE_RECORDS = 15;
    /**
     * 光源记录绑定。
     */
    public static final int LIGHT_RECORDS = 16;
    /**
     * Previous linear-radiance history input.
     */
    public static final int HISTORY_COLOR_INPUT = 17;
    /**
     * Current linear-radiance history output.
     */
    public static final int HISTORY_COLOR_OUTPUT = 18;
    /**
     * Previous normal/depth rejection history input.
     */
    public static final int HISTORY_GEOMETRY_INPUT = 19;
    /**
     * Current normal/depth rejection history output.
     */
    public static final int HISTORY_GEOMETRY_OUTPUT = 20;
    /**
     * Current-frame screen-space motion output.
     */
    public static final int MOTION_OUTPUT = 21;
    /**
     * 描述符集合总绑定数。
     */
    public static final int COUNT = 22;
    /**
     * 存储缓冲区类型绑定数。
     */
    public static final int STORAGE_BUFFER_COUNT = 15;
    /**
     * Storage-image bindings per descriptor set, including the public output.
     */
    public static final int STORAGE_IMAGE_COUNT = 6;

    private GpuSceneShaderBindings() {
    }
}
