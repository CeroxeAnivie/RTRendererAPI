package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneShaderBindings;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical host/shader word layout for the renderer-owned persistent GPU scene.
 *
 * <p>Every stable identity slot maps to one fixed-size descriptor record. Variable-size texture
 * pixels and mesh streams live in separate arenas and are referenced by 64-bit byte offsets. A
 * zero-filled record is always inactive, which makes slot removal a bounded transfer rather than
 * a buffer compaction. Java object ids never enter the shader ABI; references are resolved to
 * stable GPU slots during packing.</p>
 */
final class VulkanGpuSceneAbi {
    static final int TLAS_BINDING = GpuSceneShaderBindings.TLAS;
    static final int OUTPUT_IMAGE_BINDING = GpuSceneShaderBindings.OUTPUT_IMAGE;
    static final int FRAME_UNIFORMS_BINDING = GpuSceneShaderBindings.FRAME_UNIFORMS;
    static final int TEXTURE_RECORDS_BINDING = GpuSceneShaderBindings.TEXTURE_RECORDS;
    static final int TEXTURE_PIXELS_BINDING = GpuSceneShaderBindings.TEXTURE_PIXELS;
    static final int MATERIAL_RECORDS_BINDING = GpuSceneShaderBindings.MATERIAL_RECORDS;
    static final int MESH_RECORDS_BINDING = GpuSceneShaderBindings.MESH_RECORDS;
    static final int POSITIONS_BINDING = GpuSceneShaderBindings.POSITIONS;
    static final int NORMALS_BINDING = GpuSceneShaderBindings.NORMALS;
    static final int TANGENTS_BINDING = GpuSceneShaderBindings.TANGENTS;
    static final int TEXTURE_COORDINATES_BINDING = GpuSceneShaderBindings.TEXTURE_COORDINATES;
    static final int COLORS_BINDING = GpuSceneShaderBindings.COLORS;
    static final int LIGHTMAP_COORDINATES_BINDING = GpuSceneShaderBindings.LIGHTMAP_COORDINATES;
    static final int INDICES_BINDING = GpuSceneShaderBindings.INDICES;
    static final int TRIANGLE_MATERIAL_SLOTS_BINDING = GpuSceneShaderBindings.TRIANGLE_MATERIAL_SLOTS;
    static final int INSTANCE_RECORDS_BINDING = GpuSceneShaderBindings.INSTANCE_RECORDS;
    static final int LIGHT_RECORDS_BINDING = GpuSceneShaderBindings.LIGHT_RECORDS;
    static final int HISTORY_COLOR_INPUT_BINDING = GpuSceneShaderBindings.HISTORY_COLOR_INPUT;
    static final int HISTORY_COLOR_OUTPUT_BINDING = GpuSceneShaderBindings.HISTORY_COLOR_OUTPUT;
    static final int HISTORY_GEOMETRY_INPUT_BINDING = GpuSceneShaderBindings.HISTORY_GEOMETRY_INPUT;
    static final int HISTORY_GEOMETRY_OUTPUT_BINDING = GpuSceneShaderBindings.HISTORY_GEOMETRY_OUTPUT;
    static final int MOTION_OUTPUT_BINDING = GpuSceneShaderBindings.MOTION_OUTPUT;
    static final int DESCRIPTOR_BINDING_COUNT = GpuSceneShaderBindings.COUNT;

    static final int TEXTURE_RECORD_WORDS = 14;
    static final int MATERIAL_RECORD_WORDS = 16;
    static final int MESH_RECORD_WORDS = 18;
    static final int INSTANCE_RECORD_WORDS = 16;
    static final int LIGHT_RECORD_WORDS = 24;

    static final int FRAME_FOG_COLOR_WORD = 44;
    static final int FRAME_FOG_SPHERICAL_START_WORD = 48;
    static final int FRAME_FOG_SPHERICAL_END_WORD = 49;
    static final int FRAME_FOG_CYLINDRICAL_START_WORD = 50;
    static final int FRAME_FOG_CYLINDRICAL_END_WORD = 51;
    static final int FRAME_TEXTURE_MINIFICATION_MODE_WORD = 52;
    static final int FRAME_MAX_ANISOTROPY_WORD = 53;
    static final int FRAME_SAMPLE_COUNT_WORD = 54;
    static final int FRAME_LIGHTMAP_WORD = 56;
    static final int FRAME_PREVIOUS_CAMERA_POSITION_WORD = FRAME_LIGHTMAP_WORD
            + top.ceroxe.rt.renderer.api.LightmapState.ENTRY_COUNT;
    static final int FRAME_PREVIOUS_CAMERA_FORWARD_WORD = 318;
    static final int FRAME_PREVIOUS_CAMERA_RIGHT_WORD = 321;
    static final int FRAME_PREVIOUS_CAMERA_UP_WORD = 324;
    static final int FRAME_PREVIOUS_FOV_WORD = 327;
    static final int FRAME_PREVIOUS_SEQUENCE_WORD = 329;
    static final int FRAME_TEMPORAL_FLAGS_WORD = 331;
    static final int FRAME_MAX_HISTORY_FRAMES_WORD = 332;
    static final int FRAME_CURRENT_JITTER_WORD = 333;
    static final int FRAME_PREVIOUS_JITTER_WORD = 335;
    static final int FRAME_HISTORY_GENERATION_WORD = 337;
    static final int FRAME_HISTORY_INVALIDATION_MASK_WORD = 339;
    static final int FRAME_PREVIOUS_SCENE_REVISION_WORD = 340;
    static final int FRAME_CAMERA_DELTA_WORD = 342;
    static final int FRAME_UNIFORM_WORDS = 348;

    static final int TEMPORAL_FLAG_ENABLED = 1;
    static final int TEMPORAL_FLAG_HISTORY_VALID = 1 << 1;

    static final int TEXTURE_FLAGS_WORD = 0;
    static final int TEXTURE_PIXEL_OFFSET_WORD = 1;
    static final int TEXTURE_WIDTH_WORD = 3;
    static final int TEXTURE_HEIGHT_WORD = 4;
    static final int TEXTURE_ROW_BYTES_WORD = 5;
    static final int TEXTURE_COLOR_SPACE_WORD = 6;
    static final int TEXTURE_ADDRESS_U_WORD = 7;
    static final int TEXTURE_ADDRESS_V_WORD = 8;
    static final int TEXTURE_FILTER_WORD = 9;
    static final int TEXTURE_BYTE_COUNT_WORD = 10;
    static final int TEXTURE_MIP_LEVEL_COUNT_WORD = 12;

    static final int MATERIAL_FLAGS_WORD = 0;
    static final int MATERIAL_BASE_COLOR_WORD = 1;
    static final int MATERIAL_BASE_COLOR_TEXTURE_WORD = 2;
    static final int MATERIAL_NORMAL_TEXTURE_WORD = 3;
    static final int MATERIAL_METALLIC_ROUGHNESS_TEXTURE_WORD = 4;
    static final int MATERIAL_EMISSIVE_TEXTURE_WORD = 5;
    static final int MATERIAL_EMISSIVE_COLOR_WORD = 6;
    static final int MATERIAL_EMISSIVE_STRENGTH_WORD = 7;
    static final int MATERIAL_ALPHA_CUTOFF_WORD = 8;
    static final int MATERIAL_ROUGHNESS_WORD = 9;
    static final int MATERIAL_METALLIC_WORD = 10;
    static final int MATERIAL_TRANSMISSION_WORD = 11;
    static final int MATERIAL_IOR_WORD = 12;

    static final int MESH_POSITION_OFFSET_WORD = 0;
    static final int MESH_NORMAL_OFFSET_WORD = 2;
    static final int MESH_TANGENT_OFFSET_WORD = 4;
    static final int MESH_TEXCOORD_OFFSET_WORD = 6;
    static final int MESH_COLOR_OFFSET_WORD = 8;
    static final int MESH_LIGHTMAP_COORDINATE_OFFSET_WORD = 10;
    static final int MESH_INDEX_OFFSET_WORD = 12;
    static final int MESH_TRIANGLE_MATERIAL_OFFSET_WORD = 14;
    static final int MESH_VERTEX_COUNT_WORD = 16;
    static final int MESH_TRIANGLE_COUNT_WORD = 17;

    static final int INSTANCE_MESH_SLOT_WORD = 0;
    static final int INSTANCE_FLAGS_WORD = 1;
    static final int INSTANCE_VISIBILITY_MASK_WORD = 2;
    static final int INSTANCE_TRANSFORM_WORD = 3;
    static final int INSTANCE_SURFACE_VISIBILITY_WORD = 15;

    static final int LIGHT_FLAGS_WORD = 0;
    static final int LIGHT_POSITION_X_WORD = 1;
    static final int LIGHT_POSITION_Y_WORD = 3;
    static final int LIGHT_POSITION_Z_WORD = 5;
    static final int LIGHT_DIRECTION_WORD = 7;
    static final int LIGHT_COLOR_WORD = 10;
    static final int LIGHT_INTENSITY_WORD = 13;
    static final int LIGHT_RANGE_WORD = 14;
    static final int LIGHT_INNER_CONE_WORD = 15;
    static final int LIGHT_OUTER_CONE_WORD = 16;

    static final int FRAME_EXTENT_WORD = 0;
    static final int FRAME_SEQUENCE_WORD = 2;
    static final int FRAME_CAMERA_POSITION_WORD = 4;
    static final int FRAME_CAMERA_FORWARD_WORD = 10;
    static final int FRAME_CAMERA_RIGHT_WORD = 13;
    static final int FRAME_CAMERA_UP_WORD = 16;
    static final int FRAME_FOV_WORD = 19;
    static final int FRAME_SKY_COLOR_WORD = 21;
    static final int FRAME_AMBIENT_INTENSITY_WORD = 24;
    static final int FRAME_SUN_DIRECTION_WORD = 25;
    static final int FRAME_SUN_COLOR_WORD = 28;
    static final int FRAME_SUN_INTENSITY_WORD = 31;
    static final int FRAME_MEDIUM_EXTINCTION_WORD = 32;
    static final int FRAME_MEDIUM_SCATTERING_WORD = 35;
    static final int FRAME_MEDIUM_DENSITY_WORD = 38;
    static final int FRAME_MEDIUM_IOR_WORD = 39;
    static final int FRAME_LIGHT_SLOT_UPPER_BOUND_WORD = 40;
    static final int FRAME_SCENE_REVISION_WORD = 41;

    static final int FLAG_ACTIVE = 1;
    static final int FLAG_DOUBLE_SIDED = 1 << 4;
    static final int FLAG_CASTS_SHADOW = 1 << 5;
    static final int FLAG_DYNAMIC = 1 << 6;
    static final int SHADING_MODEL_SHIFT = 7;
    static final int SHADING_MODEL_MASK = 0x3;

    static final int BLEND_OPAQUE = MaterialAsset.BlendMode.OPAQUE.ordinal();
    static final int BLEND_MASKED = MaterialAsset.BlendMode.MASKED.ordinal();
    static final int BLEND_TRANSLUCENT = MaterialAsset.BlendMode.TRANSLUCENT.ordinal();
    static final int SHADING_PHYSICALLY_BASED = MaterialAsset.ShadingModel.PHYSICALLY_BASED.ordinal();
    static final int SHADING_LIGHTMAP_MODULATED = MaterialAsset.ShadingModel.LIGHTMAP_MODULATED.ordinal();
    static final int COLOR_SPACE_LINEAR = TextureAsset.ColorSpace.LINEAR.ordinal();
    static final int COLOR_SPACE_SRGB = TextureAsset.ColorSpace.SRGB.ordinal();
    static final int ADDRESS_REPEAT = TextureAsset.AddressMode.REPEAT.ordinal();
    static final int ADDRESS_CLAMP_TO_EDGE = TextureAsset.AddressMode.CLAMP_TO_EDGE.ordinal();
    static final int FILTER_NEAREST = TextureAsset.Filter.NEAREST.ordinal();
    static final int FILTER_LINEAR = TextureAsset.Filter.LINEAR.ordinal();
    static final int LIGHT_DIRECTIONAL = SceneLight.Type.DIRECTIONAL.ordinal();
    static final int LIGHT_POINT = SceneLight.Type.POINT.ordinal();
    static final int LIGHT_SPOT = SceneLight.Type.SPOT.ordinal();

    private VulkanGpuSceneAbi() {
    }

    /**
     * Returns the descriptor binding for a GPUScene storage target.
     *
     * <p>The mapping is deliberately exhaustive. Adding a host-side target without assigning a
     * shader-visible binding therefore fails compilation instead of silently aliasing a buffer.</p>
     */
    static int descriptorBinding(VulkanGpuSceneUploadPlanner.Target target) {
        return switch (Objects.requireNonNull(target, "target")) {
            case TEXTURE_RECORDS -> TEXTURE_RECORDS_BINDING;
            case TEXTURE_PIXELS -> TEXTURE_PIXELS_BINDING;
            case MATERIAL_RECORDS -> MATERIAL_RECORDS_BINDING;
            case MESH_RECORDS -> MESH_RECORDS_BINDING;
            case POSITIONS -> POSITIONS_BINDING;
            case NORMALS -> NORMALS_BINDING;
            case TANGENTS -> TANGENTS_BINDING;
            case TEXTURE_COORDINATES -> TEXTURE_COORDINATES_BINDING;
            case COLORS -> COLORS_BINDING;
            case LIGHTMAP_COORDINATES -> LIGHTMAP_COORDINATES_BINDING;
            case INDICES -> INDICES_BINDING;
            case TRIANGLE_MATERIAL_SLOTS -> TRIANGLE_MATERIAL_SLOTS_BINDING;
            case INSTANCE_RECORDS -> INSTANCE_RECORDS_BINDING;
            case LIGHT_RECORDS -> LIGHT_RECORDS_BINDING;
        };
    }

    /**
     * Canonical values mirrored by {@code gpuscene_abi.glsl}; used by the drift gate.
     */
    static Map<String, Integer> shaderDefines() {
        LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        values.put("GPU_SCENE_BINDING_TLAS", TLAS_BINDING);
        values.put("GPU_SCENE_BINDING_OUTPUT_IMAGE", OUTPUT_IMAGE_BINDING);
        values.put("GPU_SCENE_BINDING_FRAME_UNIFORMS", FRAME_UNIFORMS_BINDING);
        values.put("GPU_SCENE_BINDING_TEXTURE_RECORDS", TEXTURE_RECORDS_BINDING);
        values.put("GPU_SCENE_BINDING_TEXTURE_PIXELS", TEXTURE_PIXELS_BINDING);
        values.put("GPU_SCENE_BINDING_MATERIAL_RECORDS", MATERIAL_RECORDS_BINDING);
        values.put("GPU_SCENE_BINDING_MESH_RECORDS", MESH_RECORDS_BINDING);
        values.put("GPU_SCENE_BINDING_POSITIONS", POSITIONS_BINDING);
        values.put("GPU_SCENE_BINDING_NORMALS", NORMALS_BINDING);
        values.put("GPU_SCENE_BINDING_TANGENTS", TANGENTS_BINDING);
        values.put("GPU_SCENE_BINDING_TEXCOORDS", TEXTURE_COORDINATES_BINDING);
        values.put("GPU_SCENE_BINDING_COLORS", COLORS_BINDING);
        values.put("GPU_SCENE_BINDING_LIGHTMAP_COORDINATES", LIGHTMAP_COORDINATES_BINDING);
        values.put("GPU_SCENE_BINDING_INDICES", INDICES_BINDING);
        values.put("GPU_SCENE_BINDING_TRIANGLE_MATERIAL_SLOTS", TRIANGLE_MATERIAL_SLOTS_BINDING);
        values.put("GPU_SCENE_BINDING_INSTANCE_RECORDS", INSTANCE_RECORDS_BINDING);
        values.put("GPU_SCENE_BINDING_LIGHT_RECORDS", LIGHT_RECORDS_BINDING);
        values.put("GPU_SCENE_BINDING_HISTORY_COLOR_INPUT", HISTORY_COLOR_INPUT_BINDING);
        values.put("GPU_SCENE_BINDING_HISTORY_COLOR_OUTPUT", HISTORY_COLOR_OUTPUT_BINDING);
        values.put("GPU_SCENE_BINDING_HISTORY_GEOMETRY_INPUT", HISTORY_GEOMETRY_INPUT_BINDING);
        values.put("GPU_SCENE_BINDING_HISTORY_GEOMETRY_OUTPUT", HISTORY_GEOMETRY_OUTPUT_BINDING);
        values.put("GPU_SCENE_BINDING_MOTION_OUTPUT", MOTION_OUTPUT_BINDING);
        values.put("GPU_SCENE_TEXTURE_RECORD_WORDS", TEXTURE_RECORD_WORDS);
        values.put("GPU_SCENE_MATERIAL_RECORD_WORDS", MATERIAL_RECORD_WORDS);
        values.put("GPU_SCENE_MESH_RECORD_WORDS", MESH_RECORD_WORDS);
        values.put("GPU_SCENE_INSTANCE_RECORD_WORDS", INSTANCE_RECORD_WORDS);
        values.put("GPU_SCENE_LIGHT_RECORD_WORDS", LIGHT_RECORD_WORDS);
        values.put("GPU_SCENE_FRAME_UNIFORM_WORDS", FRAME_UNIFORM_WORDS);
        values.put("GPU_SCENE_TEXTURE_FLAGS_WORD", TEXTURE_FLAGS_WORD);
        values.put("GPU_SCENE_TEXTURE_PIXEL_OFFSET_WORD", TEXTURE_PIXEL_OFFSET_WORD);
        values.put("GPU_SCENE_TEXTURE_WIDTH_WORD", TEXTURE_WIDTH_WORD);
        values.put("GPU_SCENE_TEXTURE_HEIGHT_WORD", TEXTURE_HEIGHT_WORD);
        values.put("GPU_SCENE_TEXTURE_ROW_BYTES_WORD", TEXTURE_ROW_BYTES_WORD);
        values.put("GPU_SCENE_TEXTURE_COLOR_SPACE_WORD", TEXTURE_COLOR_SPACE_WORD);
        values.put("GPU_SCENE_TEXTURE_ADDRESS_U_WORD", TEXTURE_ADDRESS_U_WORD);
        values.put("GPU_SCENE_TEXTURE_ADDRESS_V_WORD", TEXTURE_ADDRESS_V_WORD);
        values.put("GPU_SCENE_TEXTURE_FILTER_WORD", TEXTURE_FILTER_WORD);
        values.put("GPU_SCENE_TEXTURE_BYTE_COUNT_WORD", TEXTURE_BYTE_COUNT_WORD);
        values.put("GPU_SCENE_TEXTURE_MIP_LEVEL_COUNT_WORD", TEXTURE_MIP_LEVEL_COUNT_WORD);
        values.put("GPU_SCENE_MATERIAL_FLAGS_WORD", MATERIAL_FLAGS_WORD);
        values.put("GPU_SCENE_MATERIAL_BASE_COLOR_WORD", MATERIAL_BASE_COLOR_WORD);
        values.put("GPU_SCENE_MATERIAL_BASE_COLOR_TEXTURE_WORD", MATERIAL_BASE_COLOR_TEXTURE_WORD);
        values.put("GPU_SCENE_MATERIAL_NORMAL_TEXTURE_WORD", MATERIAL_NORMAL_TEXTURE_WORD);
        values.put("GPU_SCENE_MATERIAL_METALLIC_ROUGHNESS_TEXTURE_WORD", MATERIAL_METALLIC_ROUGHNESS_TEXTURE_WORD);
        values.put("GPU_SCENE_MATERIAL_EMISSIVE_TEXTURE_WORD", MATERIAL_EMISSIVE_TEXTURE_WORD);
        values.put("GPU_SCENE_MATERIAL_EMISSIVE_COLOR_WORD", MATERIAL_EMISSIVE_COLOR_WORD);
        values.put("GPU_SCENE_MATERIAL_EMISSIVE_STRENGTH_WORD", MATERIAL_EMISSIVE_STRENGTH_WORD);
        values.put("GPU_SCENE_MATERIAL_ALPHA_CUTOFF_WORD", MATERIAL_ALPHA_CUTOFF_WORD);
        values.put("GPU_SCENE_MATERIAL_ROUGHNESS_WORD", MATERIAL_ROUGHNESS_WORD);
        values.put("GPU_SCENE_MATERIAL_METALLIC_WORD", MATERIAL_METALLIC_WORD);
        values.put("GPU_SCENE_MATERIAL_TRANSMISSION_WORD", MATERIAL_TRANSMISSION_WORD);
        values.put("GPU_SCENE_MATERIAL_IOR_WORD", MATERIAL_IOR_WORD);
        values.put("GPU_SCENE_MESH_POSITION_OFFSET_WORD", MESH_POSITION_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_NORMAL_OFFSET_WORD", MESH_NORMAL_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_TANGENT_OFFSET_WORD", MESH_TANGENT_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_TEXCOORD_OFFSET_WORD", MESH_TEXCOORD_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_COLOR_OFFSET_WORD", MESH_COLOR_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_LIGHTMAP_COORDINATE_OFFSET_WORD", MESH_LIGHTMAP_COORDINATE_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_INDEX_OFFSET_WORD", MESH_INDEX_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_TRIANGLE_MATERIAL_OFFSET_WORD", MESH_TRIANGLE_MATERIAL_OFFSET_WORD);
        values.put("GPU_SCENE_MESH_VERTEX_COUNT_WORD", MESH_VERTEX_COUNT_WORD);
        values.put("GPU_SCENE_MESH_TRIANGLE_COUNT_WORD", MESH_TRIANGLE_COUNT_WORD);
        values.put("GPU_SCENE_INSTANCE_MESH_SLOT_WORD", INSTANCE_MESH_SLOT_WORD);
        values.put("GPU_SCENE_INSTANCE_FLAGS_WORD", INSTANCE_FLAGS_WORD);
        values.put("GPU_SCENE_INSTANCE_VISIBILITY_MASK_WORD", INSTANCE_VISIBILITY_MASK_WORD);
        values.put("GPU_SCENE_INSTANCE_TRANSFORM_WORD", INSTANCE_TRANSFORM_WORD);
        values.put("GPU_SCENE_INSTANCE_SURFACE_VISIBILITY_WORD", INSTANCE_SURFACE_VISIBILITY_WORD);
        values.put("GPU_SCENE_LIGHT_FLAGS_WORD", LIGHT_FLAGS_WORD);
        values.put("GPU_SCENE_LIGHT_POSITION_X_WORD", LIGHT_POSITION_X_WORD);
        values.put("GPU_SCENE_LIGHT_POSITION_Y_WORD", LIGHT_POSITION_Y_WORD);
        values.put("GPU_SCENE_LIGHT_POSITION_Z_WORD", LIGHT_POSITION_Z_WORD);
        values.put("GPU_SCENE_LIGHT_DIRECTION_WORD", LIGHT_DIRECTION_WORD);
        values.put("GPU_SCENE_LIGHT_COLOR_WORD", LIGHT_COLOR_WORD);
        values.put("GPU_SCENE_LIGHT_INTENSITY_WORD", LIGHT_INTENSITY_WORD);
        values.put("GPU_SCENE_LIGHT_RANGE_WORD", LIGHT_RANGE_WORD);
        values.put("GPU_SCENE_LIGHT_INNER_CONE_WORD", LIGHT_INNER_CONE_WORD);
        values.put("GPU_SCENE_LIGHT_OUTER_CONE_WORD", LIGHT_OUTER_CONE_WORD);
        values.put("GPU_SCENE_FRAME_EXTENT_WORD", FRAME_EXTENT_WORD);
        values.put("GPU_SCENE_FRAME_SEQUENCE_WORD", FRAME_SEQUENCE_WORD);
        values.put("GPU_SCENE_FRAME_CAMERA_POSITION_WORD", FRAME_CAMERA_POSITION_WORD);
        values.put("GPU_SCENE_FRAME_CAMERA_FORWARD_WORD", FRAME_CAMERA_FORWARD_WORD);
        values.put("GPU_SCENE_FRAME_CAMERA_RIGHT_WORD", FRAME_CAMERA_RIGHT_WORD);
        values.put("GPU_SCENE_FRAME_CAMERA_UP_WORD", FRAME_CAMERA_UP_WORD);
        values.put("GPU_SCENE_FRAME_FOV_WORD", FRAME_FOV_WORD);
        values.put("GPU_SCENE_FRAME_SKY_COLOR_WORD", FRAME_SKY_COLOR_WORD);
        values.put("GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD", FRAME_AMBIENT_INTENSITY_WORD);
        values.put("GPU_SCENE_FRAME_SUN_DIRECTION_WORD", FRAME_SUN_DIRECTION_WORD);
        values.put("GPU_SCENE_FRAME_SUN_COLOR_WORD", FRAME_SUN_COLOR_WORD);
        values.put("GPU_SCENE_FRAME_SUN_INTENSITY_WORD", FRAME_SUN_INTENSITY_WORD);
        values.put("GPU_SCENE_FRAME_MEDIUM_EXTINCTION_WORD", FRAME_MEDIUM_EXTINCTION_WORD);
        values.put("GPU_SCENE_FRAME_MEDIUM_SCATTERING_WORD", FRAME_MEDIUM_SCATTERING_WORD);
        values.put("GPU_SCENE_FRAME_MEDIUM_DENSITY_WORD", FRAME_MEDIUM_DENSITY_WORD);
        values.put("GPU_SCENE_FRAME_MEDIUM_IOR_WORD", FRAME_MEDIUM_IOR_WORD);
        values.put("GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD", FRAME_LIGHT_SLOT_UPPER_BOUND_WORD);
        values.put("GPU_SCENE_FRAME_SCENE_REVISION_WORD", FRAME_SCENE_REVISION_WORD);
        values.put("GPU_SCENE_FRAME_FOG_COLOR_WORD", FRAME_FOG_COLOR_WORD);
        values.put("GPU_SCENE_FRAME_FOG_SPHERICAL_START_WORD", FRAME_FOG_SPHERICAL_START_WORD);
        values.put("GPU_SCENE_FRAME_FOG_SPHERICAL_END_WORD", FRAME_FOG_SPHERICAL_END_WORD);
        values.put("GPU_SCENE_FRAME_FOG_CYLINDRICAL_START_WORD", FRAME_FOG_CYLINDRICAL_START_WORD);
        values.put("GPU_SCENE_FRAME_FOG_CYLINDRICAL_END_WORD", FRAME_FOG_CYLINDRICAL_END_WORD);
        values.put("GPU_SCENE_FRAME_TEXTURE_MINIFICATION_MODE_WORD", FRAME_TEXTURE_MINIFICATION_MODE_WORD);
        values.put("GPU_SCENE_FRAME_MAX_ANISOTROPY_WORD", FRAME_MAX_ANISOTROPY_WORD);
        values.put("GPU_SCENE_FRAME_SAMPLE_COUNT_WORD", FRAME_SAMPLE_COUNT_WORD);
        values.put("GPU_SCENE_FRAME_LIGHTMAP_WORD", FRAME_LIGHTMAP_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_CAMERA_POSITION_WORD", FRAME_PREVIOUS_CAMERA_POSITION_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_CAMERA_FORWARD_WORD", FRAME_PREVIOUS_CAMERA_FORWARD_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_CAMERA_RIGHT_WORD", FRAME_PREVIOUS_CAMERA_RIGHT_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_CAMERA_UP_WORD", FRAME_PREVIOUS_CAMERA_UP_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_FOV_WORD", FRAME_PREVIOUS_FOV_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_SEQUENCE_WORD", FRAME_PREVIOUS_SEQUENCE_WORD);
        values.put("GPU_SCENE_FRAME_TEMPORAL_FLAGS_WORD", FRAME_TEMPORAL_FLAGS_WORD);
        values.put("GPU_SCENE_FRAME_MAX_HISTORY_FRAMES_WORD", FRAME_MAX_HISTORY_FRAMES_WORD);
        values.put("GPU_SCENE_FRAME_CURRENT_JITTER_WORD", FRAME_CURRENT_JITTER_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_JITTER_WORD", FRAME_PREVIOUS_JITTER_WORD);
        values.put("GPU_SCENE_FRAME_HISTORY_GENERATION_WORD", FRAME_HISTORY_GENERATION_WORD);
        values.put("GPU_SCENE_FRAME_HISTORY_INVALIDATION_MASK_WORD", FRAME_HISTORY_INVALIDATION_MASK_WORD);
        values.put("GPU_SCENE_FRAME_PREVIOUS_SCENE_REVISION_WORD", FRAME_PREVIOUS_SCENE_REVISION_WORD);
        values.put("GPU_SCENE_FRAME_CAMERA_DELTA_WORD", FRAME_CAMERA_DELTA_WORD);
        values.put("GPU_SCENE_TEMPORAL_FLAG_ENABLED", TEMPORAL_FLAG_ENABLED);
        values.put("GPU_SCENE_TEMPORAL_FLAG_HISTORY_VALID", TEMPORAL_FLAG_HISTORY_VALID);
        values.put("GPU_SCENE_FLAG_ACTIVE", FLAG_ACTIVE);
        values.put("GPU_SCENE_FLAG_DOUBLE_SIDED", FLAG_DOUBLE_SIDED);
        values.put("GPU_SCENE_FLAG_CASTS_SHADOW", FLAG_CASTS_SHADOW);
        values.put("GPU_SCENE_FLAG_DYNAMIC", FLAG_DYNAMIC);
        values.put("GPU_SCENE_SHADING_MODEL_SHIFT", SHADING_MODEL_SHIFT);
        values.put("GPU_SCENE_SHADING_MODEL_MASK", SHADING_MODEL_MASK);
        values.put("GPU_SCENE_BLEND_OPAQUE", BLEND_OPAQUE);
        values.put("GPU_SCENE_BLEND_MASKED", BLEND_MASKED);
        values.put("GPU_SCENE_BLEND_TRANSLUCENT", BLEND_TRANSLUCENT);
        values.put("GPU_SCENE_SHADING_PHYSICALLY_BASED", SHADING_PHYSICALLY_BASED);
        values.put("GPU_SCENE_SHADING_LIGHTMAP_MODULATED", SHADING_LIGHTMAP_MODULATED);
        values.put("GPU_SCENE_COLOR_SPACE_LINEAR", COLOR_SPACE_LINEAR);
        values.put("GPU_SCENE_COLOR_SPACE_SRGB", COLOR_SPACE_SRGB);
        values.put("GPU_SCENE_ADDRESS_REPEAT", ADDRESS_REPEAT);
        values.put("GPU_SCENE_ADDRESS_CLAMP_TO_EDGE", ADDRESS_CLAMP_TO_EDGE);
        values.put("GPU_SCENE_FILTER_NEAREST", FILTER_NEAREST);
        values.put("GPU_SCENE_FILTER_LINEAR", FILTER_LINEAR);
        values.put("GPU_SCENE_LIGHT_DIRECTIONAL", LIGHT_DIRECTIONAL);
        values.put("GPU_SCENE_LIGHT_POINT", LIGHT_POINT);
        values.put("GPU_SCENE_LIGHT_SPOT", LIGHT_SPOT);
        return Map.copyOf(values);
    }

    static int[] packTexture(TextureAsset texture, TexturePlacement placement) {
        TextureAsset asset = Objects.requireNonNull(texture, "texture");
        TexturePlacement pixels = Objects.requireNonNull(placement, "placement");
        long requiredBytes = asset.rgba8().remaining();
        if (pixels.byteCount() != requiredBytes) {
            throw new IllegalArgumentException("texture placement byte count does not match RGBA8 extent");
        }
        int[] words = new int[TEXTURE_RECORD_WORDS];
        words[0] = FLAG_ACTIVE;
        putLong(words, 1, pixels.byteOffset());
        words[3] = asset.width();
        words[4] = asset.height();
        words[5] = Math.multiplyExact(asset.width(), 4);
        words[6] = asset.colorSpace().ordinal();
        words[7] = asset.addressU().ordinal();
        words[8] = asset.addressV().ordinal();
        words[9] = asset.filter().ordinal();
        putLong(words, 10, pixels.byteCount());
        words[TEXTURE_MIP_LEVEL_COUNT_WORD] = asset.mipLevelCount();
        return words;
    }

    static int[] packMaterial(MaterialAsset material, SlotResolver textureSlots) {
        MaterialAsset asset = Objects.requireNonNull(material, "material");
        SlotResolver slots = Objects.requireNonNull(textureSlots, "textureSlots");
        int[] words = new int[MATERIAL_RECORD_WORDS];
        words[0] = FLAG_ACTIVE | asset.blendMode().ordinal() << 1
                | (asset.doubleSided() ? FLAG_DOUBLE_SIDED : 0)
                | asset.shadingModel().ordinal() << SHADING_MODEL_SHIFT;
        words[1] = asset.baseColorRgba8();
        words[2] = optionalSlot(asset.baseColorTextureId(), slots, "baseColorTexture");
        words[3] = optionalSlot(asset.normalTextureId(), slots, "normalTexture");
        words[4] = optionalSlot(asset.metallicRoughnessTextureId(), slots, "metallicRoughnessTexture");
        words[5] = optionalSlot(asset.emissiveTextureId(), slots, "emissiveTexture");
        words[6] = asset.emissiveColorRgba8();
        words[7] = Float.floatToRawIntBits(asset.emissiveStrength());
        words[8] = Float.floatToRawIntBits(asset.alphaCutoff());
        words[9] = Float.floatToRawIntBits(asset.roughness());
        words[10] = Float.floatToRawIntBits(asset.metallic());
        words[11] = Float.floatToRawIntBits(asset.transmission());
        words[12] = Float.floatToRawIntBits(asset.indexOfRefraction());
        return words;
    }

    static int[] packMesh(MeshAsset mesh, GeometryPlacement placement) {
        MeshAsset asset = Objects.requireNonNull(mesh, "mesh");
        GeometryPlacement streams = Objects.requireNonNull(placement, "placement");
        requireStreamPresence(asset.normals().hasRemaining(), streams.normalBytes(), "normal");
        requireStreamPresence(asset.tangents().hasRemaining(), streams.tangentBytes(), "tangent");
        requireStreamPresence(asset.textureCoordinates().hasRemaining(), streams.textureCoordinateBytes(), "UV");
        requireStreamPresence(asset.vertexColorsRgba8().hasRemaining(), streams.colorBytes(), "vertex color");
        requireStreamPresence(
                asset.lightmapCoordinates().hasRemaining(),
                streams.lightmapCoordinateBytes(),
                "lightmap coordinate"
        );
        int[] words = new int[MESH_RECORD_WORDS];
        putLong(words, 0, streams.positionBytes());
        putLong(words, 2, streams.normalBytes());
        putLong(words, 4, streams.tangentBytes());
        putLong(words, 6, streams.textureCoordinateBytes());
        putLong(words, 8, streams.colorBytes());
        putLong(words, 10, streams.lightmapCoordinateBytes());
        putLong(words, 12, streams.indexBytes());
        putLong(words, 14, streams.triangleMaterialSlotBytes());
        words[16] = asset.vertexCount();
        words[17] = asset.triangleCount();
        return words;
    }

    static int[] packInstance(SceneInstance instance, SlotResolver meshSlots) {
        SceneInstance value = Objects.requireNonNull(instance, "instance");
        int[] words = new int[INSTANCE_RECORD_WORDS];
        words[0] = requiredSlot(value.meshAssetId(), Objects.requireNonNull(meshSlots, "meshSlots"), "mesh");
        words[1] = FLAG_ACTIVE | (value.castsShadow() ? FLAG_CASTS_SHADOW : 0)
                | (value.mobility() == SceneInstance.Mobility.DYNAMIC ? FLAG_DYNAMIC : 0);
        words[2] = value.visibilityMask();
        FloatBuffer transform = value.transform().elements();
        for (int index = 0; index < 12; index++) {
            words[index + 3] = Float.floatToRawIntBits(transform.get(index));
        }
        words[INSTANCE_SURFACE_VISIBILITY_WORD] = Float.floatToRawIntBits(value.surfaceVisibility());
        return words;
    }

    static int[] packLight(SceneLight light) {
        SceneLight value = Objects.requireNonNull(light, "light");
        int[] words = new int[LIGHT_RECORD_WORDS];
        words[0] = FLAG_ACTIVE | value.type().ordinal() << 1
                | (value.castsShadow() ? FLAG_CASTS_SHADOW : 0);
        putLong(words, 1, Double.doubleToRawLongBits(value.x()));
        putLong(words, 3, Double.doubleToRawLongBits(value.y()));
        putLong(words, 5, Double.doubleToRawLongBits(value.z()));
        words[7] = Float.floatToRawIntBits(value.directionX());
        words[8] = Float.floatToRawIntBits(value.directionY());
        words[9] = Float.floatToRawIntBits(value.directionZ());
        words[10] = Float.floatToRawIntBits(value.red());
        words[11] = Float.floatToRawIntBits(value.green());
        words[12] = Float.floatToRawIntBits(value.blue());
        words[13] = Float.floatToRawIntBits(value.intensity());
        words[14] = Float.floatToRawIntBits(value.range());
        words[15] = Float.floatToRawIntBits(value.innerConeCosine());
        words[16] = Float.floatToRawIntBits(value.outerConeCosine());
        return words;
    }

    static int[] clearedRecord(int wordsPerRecord) {
        if (wordsPerRecord <= 0) {
            throw new IllegalArgumentException("wordsPerRecord must be positive");
        }
        return new int[wordsPerRecord];
    }

    static long recordByteOffset(int slot, int wordsPerRecord) {
        if (slot < 0 || wordsPerRecord <= 0) {
            throw new IllegalArgumentException("record slot and stride must be positive");
        }
        return Math.multiplyExact(Math.multiplyExact((long) slot, wordsPerRecord), Integer.BYTES);
    }

    private static int optionalSlot(long id, SlotResolver slots, String label) {
        return id < 0L ? -1 : requiredSlot(id, slots, label);
    }

    private static int requiredSlot(long id, SlotResolver slots, String label) {
        int slot = slots.resolve(id);
        if (slot < 0) {
            throw new IllegalArgumentException(label + " identity " + id + " has no resident GPU slot");
        }
        return slot;
    }

    private static void requireStreamPresence(boolean present, long byteOffset, String label) {
        if (present != (byteOffset >= 0L)) {
            throw new IllegalArgumentException(label + " stream presence does not match geometry placement");
        }
    }

    private static void putLong(int[] words, int index, long value) {
        words[index] = (int) value;
        words[index + 1] = (int) (value >>> 32);
    }

    @FunctionalInterface
    interface SlotResolver {
        int resolve(long identity);
    }

    record TexturePlacement(long byteOffset, long byteCount) {
        TexturePlacement {
            if (byteOffset < 0L || byteCount <= 0L || (byteOffset & 3L) != 0L) {
                throw new IllegalArgumentException("texture placement must be positive and four-byte aligned");
            }
        }
    }

    record GeometryPlacement(
            long positionBytes,
            long normalBytes,
            long tangentBytes,
            long textureCoordinateBytes,
            long colorBytes,
            long lightmapCoordinateBytes,
            long indexBytes,
            long triangleMaterialSlotBytes
    ) {
        GeometryPlacement {
            long[] required = {positionBytes, indexBytes, triangleMaterialSlotBytes};
            for (long offset : required) {
                if (offset < 0L || (offset & 3L) != 0L) {
                    throw new IllegalArgumentException("required geometry stream offset must be four-byte aligned");
                }
            }
            long[] optional = {
                    normalBytes, tangentBytes, textureCoordinateBytes, colorBytes, lightmapCoordinateBytes
            };
            if (Arrays.stream(optional).anyMatch(offset -> offset < -1L || offset >= 0L && (offset & 3L) != 0L)) {
                throw new IllegalArgumentException("optional geometry stream offset must be -1 or four-byte aligned");
            }
        }
    }
}
