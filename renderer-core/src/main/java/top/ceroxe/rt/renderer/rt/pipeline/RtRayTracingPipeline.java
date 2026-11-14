package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.rt.renderer.rt.acceleration.RtDynamicBlasCache;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalSemaphoreProbe;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal ray tracing pipeline bootstrap.
 *
 * <p>The bootstrap owns a tiny descriptor set and storage image on purpose: a
 * pipeline that only gets created can still hide broken SBT regions, descriptor
 * layout drift, or shader write hazards. This path submits one real
 * {@code vkCmdTraceRaysKHR}, copies the shader output back through a staging
 * buffer, and validates deterministic pixels before the backend advertises RT
 * pipeline readiness.</p>
 */
public final class RtRayTracingPipeline implements AutoCloseable {
    /*
     * The shared presentation target and visible primary rays must have the
     * same extent. Upscaling a quarter-resolution trace makes the RT takeover
     * visibly blurry even when Vulkan/external graphics API interop is otherwise correct.
     */
    static final int DEFAULT_FRAME_DISPATCH_INTERVAL = RtFrameDispatchPolicy.DEFAULT_FRAME_DISPATCH_INTERVAL;
    static final int DEFAULT_VISUAL_EXPERIMENT_FRAME_DISPATCH_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_VISUAL_EXPERIMENT_FRAME_DISPATCH_INTERVAL;
    static final int DEFAULT_GPU_SHARED_PRESENTATION_FRAME_DISPATCH_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_GPU_SHARED_PRESENTATION_FRAME_DISPATCH_INTERVAL;
    static final int DEFAULT_GPU_SHARED_PRESENTATION_MAX_PENDING_FRAMES =
            RtFrameDispatchPolicy.DEFAULT_GPU_SHARED_PRESENTATION_MAX_PENDING_FRAMES;
    static final int DEFAULT_EXPLICIT_FRAME_READBACK_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_EXPLICIT_FRAME_READBACK_INTERVAL;
    static final int DEFAULT_PRESENTATION_DIAGNOSTIC_READBACK_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_PRESENTATION_DIAGNOSTIC_READBACK_INTERVAL;
    static final int DEFAULT_VISUAL_EXPERIMENT_READBACK_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_VISUAL_EXPERIMENT_READBACK_INTERVAL;
    static final int DEFAULT_GPU_SHARED_VISUAL_DIAGNOSTIC_READBACK_INTERVAL =
            RtFrameDispatchPolicy.DEFAULT_GPU_SHARED_VISUAL_DIAGNOSTIC_READBACK_INTERVAL;
    static final String FRAME_OUTPUT_WIDTH_PROPERTY = "top.ceroxe.rt.rt.output.width";
    static final String FRAME_OUTPUT_HEIGHT_PROPERTY = "top.ceroxe.rt.rt.output.height";
    static final String FRAME_OUTPUT_MAX_PIXELS_PROPERTY = "top.ceroxe.rt.rt.output.maxPixels";
    static final String PRIMARY_RAY_UPSCALE_FACTOR_PROPERTY = "top.ceroxe.rt.rt.primaryRays.upscaleFactor";
    static final String FRAME_DISPATCH_INTERVAL_PROPERTY = "top.ceroxe.rt.rt.output.dispatchInterval";
    static final String FRAME_READBACK_INTERVAL_PROPERTY = "top.ceroxe.rt.rt.output.readback.interval";
    static final String FRAME_RESOURCE_RING_SIZE_PROPERTY = "top.ceroxe.rt.rt.output.frameResourceRingSize";
    static final String FRAME_MAX_PENDING_SUBMISSIONS_PROPERTY = "top.ceroxe.rt.rt.output.maxPendingFrames";
    static final String FRAME_EXTERNAL_SEMAPHORE_ENABLED_PROPERTY =
            "top.ceroxe.rt.rt.output.externalSemaphore.enabled";
    /*
     * The current material system is descriptor-backed, not local-root-backed:
     * hit shaders recover section/material data through gl_InstanceCustomIndexEXT,
     * gl_GeometryIndexEXT, and SSBO tables. That matches UE4's "default hit group"
     * mode more closely than allocating duplicate SBT records per segment. Raygen
     * therefore traces with hitSbtRecordStride = 0 so every geometry segment uses
     * this single hit record while gl_GeometryIndexEXT still identifies the segment.
     */
    private static final int MIN_FRAME_RESOURCE_RING_SIZE =
            RtFrameDispatchPolicy.MIN_FRAME_RESOURCE_RING_SIZE;
    private static final int DESCRIPTOR_SETS_PER_FRAME_SLOT =
            RtFrameDispatchPolicy.DESCRIPTOR_SETS_PER_FRAME_SLOT;
    private static final int MAX_UPDATE_BUFFER_BYTES = 65_536;
    private static final int BOOTSTRAP_OUTPUT_WIDTH = 64;
    private static final int BOOTSTRAP_OUTPUT_HEIGHT = 64;
    private static final int DEFAULT_FRAME_OUTPUT_WIDTH = RtFrameDispatchPolicy.DEFAULT_FRAME_OUTPUT_WIDTH;
    private static final int DEFAULT_FRAME_OUTPUT_HEIGHT = RtFrameDispatchPolicy.DEFAULT_FRAME_OUTPUT_HEIGHT;
    private static final int DEFAULT_VISIBLE_FRAME_OUTPUT_WIDTH =
            RtFrameDispatchPolicy.DEFAULT_VISIBLE_FRAME_OUTPUT_WIDTH;
    private static final int DEFAULT_VISIBLE_FRAME_OUTPUT_HEIGHT =
            RtFrameDispatchPolicy.DEFAULT_VISIBLE_FRAME_OUTPUT_HEIGHT;
    private static final int DEFAULT_FRAME_MAX_PIXELS = RtFrameDispatchPolicy.DEFAULT_FRAME_MAX_PIXELS;
    private static final int DEFAULT_BACKGROUND_PRIMARY_RAY_UPSCALE_FACTOR =
            RtFrameDispatchPolicy.DEFAULT_BACKGROUND_PRIMARY_RAY_UPSCALE_FACTOR;
    private static final int DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR =
            RtFrameDispatchPolicy.DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR;
    private static final int MAX_PRIMARY_RAY_UPSCALE_FACTOR =
            RtFrameDispatchPolicy.MAX_PRIMARY_RAY_UPSCALE_FACTOR;
    private static final int PRESENTATION_GATE_PROBE_INTERVAL = 60;
    private static final boolean TAKEOVER_FLIGHT_RECORDER_ENABLED =
            Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    /**
     * Creates a separate descriptor ABI and per-slot buffers.  It is deliberately
     * fixed at pipeline creation: changing a descriptor layout under in-flight
     * RT work would invalidate both descriptor generations.
     */
    private static final String DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY = "top.ceroxe.rt.oracleGBuffer.enabled";
    private static final int BOOTSTRAP_OUTPUT_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;
    private static final String FRAME_READBACK_ENABLED_PROPERTY = "top.ceroxe.rt.rt.output.readback.enabled";
    private static final String VISUAL_OUTPUT_EXPERIMENTAL_ENABLED_PROPERTY =
            "top.ceroxe.rt.visualOutput.experimental.enabled";
    private static final int FRAME_UNIFORM_BYTES = RtFrameUniformEncoder.BYTES;
    private static final int WORLD_MODE = RtFrameUniformEncoder.WORLD_MODE;
    private static final int BOOTSTRAP_MODE = RtFrameUniformEncoder.BOOTSTRAP_MODE;
    private static final int DIAGNOSTIC_GBUFFER_UINTS_PER_PIXEL = 8;
    private static final int DIAGNOSTIC_GBUFFER_BYTES_PER_PIXEL =
            DIAGNOSTIC_GBUFFER_UINTS_PER_PIXEL * Integer.BYTES;
    private static final int MAX_DYNAMIC_CELESTIAL_BODIES = DynamicRenderScene.MAX_GPU_CELESTIAL_BODIES;
    private static final int MAX_DYNAMIC_PRIMITIVES = DynamicRenderScene.MAX_GPU_PRIMITIVES;
    private static final int MAX_DYNAMIC_PARTICLES = DynamicRenderScene.MAX_GPU_PARTICLES;
    private static final int MAX_DYNAMIC_BEAMS = DynamicRenderScene.MAX_GPU_BEAMS;
    private static final int MAX_DYNAMIC_LIGHTS = DynamicRenderScene.MAX_GPU_LIGHTS;
    private static final int MAX_DYNAMIC_WEATHER_COLUMNS = DynamicRenderScene.MAX_GPU_WEATHER_COLUMNS;
    private static final int MAX_DYNAMIC_BLOCK_DECALS = DynamicRenderScene.MAX_GPU_BLOCK_DECALS;
    private static final int BLOCK_DECAL_TABLE_SLOTS = 128;
    private static final int PARTICLE_TILE_COLUMNS = RtParticleTilePlanner.COLUMNS;
    private static final int PARTICLE_TILE_ROWS = RtParticleTilePlanner.ROWS;
    private static final int PARTICLE_TILE_COUNT = RtParticleTilePlanner.TILE_COUNT;
    private static final int MAX_PARTICLE_TILE_REFERENCES = RtParticleTilePlanner.MAX_REFERENCES;
    private static final int DYNAMIC_ENVIRONMENT_FLAG_FOG_KNOWN = 1;
    private static final int DYNAMIC_ENVIRONMENT_FLAG_CLOUD_KNOWN = 1 << 1;
    private static final int DYNAMIC_ENVIRONMENT_FLAG_SKY_VISIBLE = 1 << 2;
    private static final int DYNAMIC_SCENE_LIGHTMAP_RGBA_RECORDS = LightmapPayload.PACKED_UVEC4_RECORDS;
    private static final int DYNAMIC_SCENE_HEADER_VEC4_RECORDS = 6 + DYNAMIC_SCENE_LIGHTMAP_RGBA_RECORDS;
    private static final int CELESTIAL_DIRECTION_RECORD = DYNAMIC_SCENE_HEADER_VEC4_RECORDS;
    private static final int CELESTIAL_COLOR_RECORD = CELESTIAL_DIRECTION_RECORD + MAX_DYNAMIC_CELESTIAL_BODIES;
    private static final int PRIMITIVE_POSITION_RECORD = CELESTIAL_COLOR_RECORD + MAX_DYNAMIC_CELESTIAL_BODIES;
    private static final int PRIMITIVE_COLOR_RECORD = PRIMITIVE_POSITION_RECORD + MAX_DYNAMIC_PRIMITIVES;
    private static final int PARTICLE_POSITION_RECORD = PRIMITIVE_COLOR_RECORD + MAX_DYNAMIC_PRIMITIVES;
    private static final int PARTICLE_COLOR_RECORD = PARTICLE_POSITION_RECORD + MAX_DYNAMIC_PARTICLES;
    private static final int PARTICLE_ROTATION_RECORD = PARTICLE_COLOR_RECORD + MAX_DYNAMIC_PARTICLES;
    private static final int PARTICLE_UV_RECORD = PARTICLE_ROTATION_RECORD + MAX_DYNAMIC_PARTICLES;
    private static final int PARTICLE_LIFECYCLE_RECORD = PARTICLE_UV_RECORD + MAX_DYNAMIC_PARTICLES;
    private static final int BEAM_START_RECORD = PARTICLE_LIFECYCLE_RECORD + MAX_DYNAMIC_PARTICLES;
    private static final int BEAM_END_RECORD = BEAM_START_RECORD + MAX_DYNAMIC_BEAMS;
    private static final int BEAM_COLOR_RECORD = BEAM_END_RECORD + MAX_DYNAMIC_BEAMS;
    private static final int LOCAL_LIGHT_POSITION_RECORD = BEAM_COLOR_RECORD + MAX_DYNAMIC_BEAMS;
    private static final int LOCAL_LIGHT_COLOR_RECORD = LOCAL_LIGHT_POSITION_RECORD + MAX_DYNAMIC_LIGHTS;
    private static final int ENVIRONMENT_RECORD = LOCAL_LIGHT_COLOR_RECORD + MAX_DYNAMIC_LIGHTS;
    private static final int WEATHER_BOUNDS_RECORD = ENVIRONMENT_RECORD + 5;
    private static final int WEATHER_DATA_RECORD = WEATHER_BOUNDS_RECORD + MAX_DYNAMIC_WEATHER_COLUMNS;
    private static final int WEATHER_META_RECORD = WEATHER_DATA_RECORD + MAX_DYNAMIC_WEATHER_COLUMNS;
    private static final int BLOCK_DECAL_INFO_RECORD = WEATHER_META_RECORD + MAX_DYNAMIC_WEATHER_COLUMNS;
    private static final int BLOCK_DECAL_BOUNDS_MIN_RECORD = BLOCK_DECAL_INFO_RECORD + 1;
    private static final int BLOCK_DECAL_BOUNDS_MAX_RECORD = BLOCK_DECAL_BOUNDS_MIN_RECORD + 1;
    private static final int BLOCK_DECAL_RECORD = BLOCK_DECAL_BOUNDS_MAX_RECORD + 1;
    private static final int BLOCK_DECAL_OFFSET_RECORD = BLOCK_DECAL_RECORD + BLOCK_DECAL_TABLE_SLOTS;
    private static final int PARTICLE_TILE_INFO_RECORD = BLOCK_DECAL_OFFSET_RECORD + BLOCK_DECAL_TABLE_SLOTS;
    private static final int PARTICLE_TILE_RANGES_RECORD = PARTICLE_TILE_INFO_RECORD + 1;
    private static final int PARTICLE_TILE_INDICES_RECORD = PARTICLE_TILE_RANGES_RECORD + PARTICLE_TILE_COUNT;
    private static final int DYNAMIC_SCENE_ENVIRONMENT_VEC4_RECORDS = 8
            + MAX_DYNAMIC_WEATHER_COLUMNS * 3
            + BLOCK_DECAL_TABLE_SLOTS * 2;
    private static final int DYNAMIC_SCENE_VEC4_RECORDS = DYNAMIC_SCENE_HEADER_VEC4_RECORDS
            + MAX_DYNAMIC_CELESTIAL_BODIES * 2
            + MAX_DYNAMIC_PRIMITIVES * 2
            /* position, color, quaternion, atlas UV, and lifecycle/light */
            + MAX_DYNAMIC_PARTICLES * 5
            + MAX_DYNAMIC_BEAMS * 3
            + MAX_DYNAMIC_LIGHTS * 2
            + DYNAMIC_SCENE_ENVIRONMENT_VEC4_RECORDS
            + 1
            + PARTICLE_TILE_COUNT
            + MAX_PARTICLE_TILE_REFERENCES / 4;
    private static final int DYNAMIC_SCENE_BUFFER_BYTES = DYNAMIC_SCENE_VEC4_RECORDS * 4 * Float.BYTES;
    private static final RtDynamicSceneEncoder.Layout DYNAMIC_SCENE_LAYOUT = new RtDynamicSceneEncoder.Layout(
            DYNAMIC_SCENE_BUFFER_BYTES,
            MAX_DYNAMIC_CELESTIAL_BODIES,
            MAX_DYNAMIC_PRIMITIVES,
            MAX_DYNAMIC_PARTICLES,
            MAX_DYNAMIC_BEAMS,
            MAX_DYNAMIC_LIGHTS,
            MAX_DYNAMIC_WEATHER_COLUMNS,
            MAX_DYNAMIC_BLOCK_DECALS,
            BLOCK_DECAL_TABLE_SLOTS,
            PARTICLE_TILE_COLUMNS,
            PARTICLE_TILE_ROWS,
            PARTICLE_TILE_COUNT,
            MAX_PARTICLE_TILE_REFERENCES,
            DYNAMIC_ENVIRONMENT_FLAG_FOG_KNOWN,
            DYNAMIC_ENVIRONMENT_FLAG_CLOUD_KNOWN,
            DYNAMIC_ENVIRONMENT_FLAG_SKY_VISIBLE,
            CELESTIAL_DIRECTION_RECORD,
            CELESTIAL_COLOR_RECORD,
            PRIMITIVE_POSITION_RECORD,
            PRIMITIVE_COLOR_RECORD,
            PARTICLE_POSITION_RECORD,
            PARTICLE_COLOR_RECORD,
            PARTICLE_ROTATION_RECORD,
            PARTICLE_UV_RECORD,
            PARTICLE_LIFECYCLE_RECORD,
            BEAM_START_RECORD,
            BEAM_END_RECORD,
            BEAM_COLOR_RECORD,
            LOCAL_LIGHT_POSITION_RECORD,
            LOCAL_LIGHT_COLOR_RECORD,
            ENVIRONMENT_RECORD,
            WEATHER_BOUNDS_RECORD,
            WEATHER_DATA_RECORD,
            WEATHER_META_RECORD,
            BLOCK_DECAL_INFO_RECORD,
            BLOCK_DECAL_BOUNDS_MIN_RECORD,
            BLOCK_DECAL_BOUNDS_MAX_RECORD,
            BLOCK_DECAL_RECORD,
            BLOCK_DECAL_OFFSET_RECORD,
            PARTICLE_TILE_INFO_RECORD,
            PARTICLE_TILE_RANGES_RECORD,
            PARTICLE_TILE_INDICES_RECORD
    );
    private static final int DYNAMIC_HEADER_RECORD = 0;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final RtCommandContext frameCommandContext;
    private final long allocator;
    private final boolean externalOutputExportEnabled;
    private final boolean externalOutputDedicatedAllocation;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final RtSharedFrameLifecycle sharedFrames;
    private final long pipelineLayout;
    private final long pipeline;
    private final RtDescriptorGenerationState descriptorGenerationState;
    private final RtShaderBindingTable shaderBindingTable;
    private final RtGpuBuffer frameUniformBuffer;
    private final RtBootstrapReadback bootstrapReadback;
    private final RtFrameOutputConfig frameOutputConfig;
    private final int recursionDepth;
    private final int bootstrapDispatchWidth;
    private final int bootstrapDispatchHeight;
    private final int bootstrapDispatches;
    private final int frameDispatchInterval;
    private final int frameReadbackInterval;
    private final int maxPendingFrameSubmissions;
    private final boolean frameReadbackEnabled;
    private final boolean diagnosticGBufferEnabled;
    private final boolean frameDispatchRequiresPresentationEligibility;
    private final boolean committedFrontContinuityRequired;
    private final RtFrameDispatchStatistics dispatchStatistics = new RtFrameDispatchStatistics();
    private final RtCompletedFrameFront completedFrames = new RtCompletedFrameFront();
    private final RtFrameCompletionPublisher frameCompletionPublisher;
    private final RtFrameCompletionPoller frameCompletionPoller;
    private final RtDynamicSceneFront dynamicSceneFront = new RtDynamicSceneFront();
    private final RtDynamicSceneUploadEncoder dynamicSceneEncoder;
    private final RtDynamicSceneUploadTelemetry dynamicSceneUploadTelemetry;
    private final RtFrameDispatchCpuWindow dispatchCpuWindow = new RtFrameDispatchCpuWindow();
    private final RendererRtDiagnostics diagnostics;
    private RtSceneMaterialTable boundSceneMaterialTable;
    private boolean closed;
    private boolean pipelineDestroyed;
    private boolean pipelineLayoutDestroyed;
    private boolean descriptorPoolDestroyed;
    private boolean descriptorSetLayoutDestroyed;
    private boolean resourcesClosed;
    private RtCore.NativeDispatchDecision latestDispatchDecision = RtCore.NativeDispatchDecision.unavailable();

    private RtRayTracingPipeline(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            RtCommandContext frameCommandContext,
            long allocator,
            boolean externalOutputExportEnabled,
            boolean externalOutputDedicatedAllocation,
            long descriptorSetLayout,
            long descriptorPool,
            RtPipelineFrameSlot[] frameSlots,
            long pipelineLayout,
            long pipeline,
            long boundTopLevelAccelerationStructure,
            long boundDynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable boundSceneMaterialTable,
            RtShaderBindingTable shaderBindingTable,
            RtGpuBuffer frameUniformBuffer,
            RtBootstrapReadback bootstrapReadback,
            RtFrameOutputConfig frameOutputConfig,
            int recursionDepth,
            int bootstrapDispatchWidth,
            int bootstrapDispatchHeight,
            int bootstrapDispatches,
            int frameDispatchInterval,
            int frameReadbackInterval,
            int maxPendingFrameSubmissions,
            boolean frameReadbackEnabled,
            boolean diagnosticGBufferEnabled,
            boolean frameDispatchRequiresPresentationEligibility,
            boolean committedFrontContinuityRequired,
            long activeDescriptorGeneration,
            RendererRtDiagnostics diagnostics
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        this.frameCommandContext = Objects.requireNonNull(frameCommandContext, "frameCommandContext");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.externalOutputExportEnabled = externalOutputExportEnabled;
        this.externalOutputDedicatedAllocation = externalOutputDedicatedAllocation;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.sharedFrames = new RtSharedFrameLifecycle(
                device,
                frameSlots,
                MIN_FRAME_RESOURCE_RING_SIZE,
                externalFrameSemaphoreEnabled()
        );
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.descriptorGenerationState = new RtDescriptorGenerationState(
                boundTopLevelAccelerationStructure,
                boundDynamicTopLevelAccelerationStructure,
                0,
                activeDescriptorGeneration
        );
        this.boundSceneMaterialTable = Objects.requireNonNull(boundSceneMaterialTable, "boundSceneMaterialTable");
        this.shaderBindingTable = Objects.requireNonNull(shaderBindingTable, "shaderBindingTable");
        this.frameUniformBuffer = Objects.requireNonNull(frameUniformBuffer, "frameUniformBuffer");
        this.bootstrapReadback = Objects.requireNonNull(bootstrapReadback, "bootstrapReadback");
        this.frameOutputConfig = Objects.requireNonNull(frameOutputConfig, "frameOutputConfig");
        this.recursionDepth = recursionDepth;
        this.bootstrapDispatchWidth = bootstrapDispatchWidth;
        this.bootstrapDispatchHeight = bootstrapDispatchHeight;
        this.bootstrapDispatches = bootstrapDispatches;
        if (frameDispatchInterval <= 0) {
            throw new IllegalArgumentException("frameDispatchInterval must be positive");
        }
        this.frameDispatchInterval = frameDispatchInterval;
        if (frameReadbackInterval <= 0) {
            throw new IllegalArgumentException("frameReadbackInterval must be positive");
        }
        this.frameReadbackInterval = frameReadbackInterval;
        if (maxPendingFrameSubmissions <= 0) {
            throw new IllegalArgumentException("maxPendingFrameSubmissions must be positive");
        }
        if (maxPendingFrameSubmissions > this.sharedFrames.slotCount()) {
            throw new IllegalArgumentException("maxPendingFrameSubmissions must not exceed frameSlots length");
        }
        this.maxPendingFrameSubmissions = maxPendingFrameSubmissions;
        this.frameReadbackEnabled = frameReadbackEnabled;
        this.diagnosticGBufferEnabled = diagnosticGBufferEnabled;
        this.frameDispatchRequiresPresentationEligibility = frameDispatchRequiresPresentationEligibility;
        this.committedFrontContinuityRequired = committedFrontContinuityRequired;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.dynamicSceneEncoder = new RtDynamicSceneUploadEncoder(DYNAMIC_SCENE_LAYOUT, diagnostics.builds());
        this.frameCompletionPublisher = new RtFrameCompletionPublisher(
                sharedFrames, diagnostics, DIAGNOSTIC_GBUFFER_BYTES_PER_PIXEL
        );
        this.frameCompletionPoller = new RtFrameCompletionPoller(diagnostics);
        this.dynamicSceneUploadTelemetry = new RtDynamicSceneUploadTelemetry(
                diagnostics.builds(),
                List.of(
                        new RtDynamicSceneUploadTelemetry.Range("header", DYNAMIC_HEADER_RECORD, 6),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "lightmapPayload", 6, DYNAMIC_SCENE_LIGHTMAP_RGBA_RECORDS
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "celestial", CELESTIAL_DIRECTION_RECORD, MAX_DYNAMIC_CELESTIAL_BODIES * 2
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "analytic", PRIMITIVE_POSITION_RECORD, MAX_DYNAMIC_PRIMITIVES * 2
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "particles", PARTICLE_POSITION_RECORD, MAX_DYNAMIC_PARTICLES * 5
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "beams", BEAM_START_RECORD, MAX_DYNAMIC_BEAMS * 3
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "localLights", LOCAL_LIGHT_POSITION_RECORD, MAX_DYNAMIC_LIGHTS * 2
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "environment", ENVIRONMENT_RECORD, DYNAMIC_SCENE_ENVIRONMENT_VEC4_RECORDS
                        ),
                        new RtDynamicSceneUploadTelemetry.Range(
                                "particleTiles",
                                PARTICLE_TILE_INFO_RECORD,
                                1 + PARTICLE_TILE_COUNT + MAX_PARTICLE_TILE_REFERENCES / 4
                        )
                )
        );
    }

    /**
     * Creates the pipeline with no-op diagnostics; the returned owner retains no command context.
     *
     * @param device                            logical device
     * @param physicalDevice                    physical device
     * @param allocator                         VMA allocator
     * @param externalOutputExportEnabled       whether output images are exportable
     * @param externalOutputDedicatedAllocation whether external output requires dedicated allocation
     * @param commandContext                    ordered frame command lane
     * @param topLevelAccelerationStructure     initial world TLAS
     * @param sceneMaterialTable                initial material resources
     * @param properties                        RT pipeline limits
     * @return independently owned pipeline
     */
    public static RtRayTracingPipeline create(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            long allocator,
            boolean externalOutputExportEnabled,
            boolean externalOutputDedicatedAllocation,
            RtCommandContext commandContext,
            RtAccelerationStructure topLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            RtRayTracingPipelineProperties properties
    ) {
        return create(device, physicalDevice, allocator, externalOutputExportEnabled,
                externalOutputDedicatedAllocation, commandContext, topLevelAccelerationStructure,
                sceneMaterialTable, properties, RendererRtDiagnostics.noop());
    }

    /**
     * Creates pipeline, descriptors, shaders, SBT, and frame resources with rollback on failure.
     *
     * @param device                            logical device
     * @param physicalDevice                    physical device
     * @param allocator                         VMA allocator
     * @param externalOutputExportEnabled       whether output images are exportable
     * @param externalOutputDedicatedAllocation whether external output requires dedicated allocation
     * @param commandContext                    ordered frame command lane
     * @param topLevelAccelerationStructure     initial world TLAS
     * @param sceneMaterialTable                initial material resources
     * @param properties                        RT pipeline limits
     * @param diagnostics                       diagnostics sinks
     * @return independently owned pipeline
     */
    public static RtRayTracingPipeline create(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            long allocator,
            boolean externalOutputExportEnabled,
            boolean externalOutputDedicatedAllocation,
            RtCommandContext commandContext,
            RtAccelerationStructure topLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            RtRayTracingPipelineProperties properties,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(topLevelAccelerationStructure, "topLevelAccelerationStructure");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean diagnosticGBufferEnabled = Boolean.getBoolean(DIAGNOSTIC_GBUFFER_ENABLED_PROPERTY);
            long raygenModule = 0L;
            long missModule = 0L;
            long closestHitModule = 0L;
            long anyHitModule = 0L;
            long descriptorSetLayout = 0L;
            long descriptorPool = 0L;
            long[] descriptorSets = null;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            RtShaderBindingTable shaderBindingTableResource = null;
            RtPipelineFrameSlot[] frameSlots = null;
            RtGpuBuffer frameUniformBuffer = null;
            RtGpuBuffer bootstrapReadbackBuffer = null;
            try {
                raygenModule = RtShaderModuleCompiler.createModule(
                        stack,
                        device,
                        RtShaderModuleCompiler.loadProduction("assets/rtrenderer/shaders/bootstrap.rgen", Shaderc.shaderc_raygen_shader,
                                diagnostics.edges())
                );
                missModule = RtShaderModuleCompiler.createModule(
                        stack,
                        device,
                        RtShaderModuleCompiler.loadProduction("assets/rtrenderer/shaders/bootstrap.rmiss", Shaderc.shaderc_miss_shader,
                                diagnostics.edges())
                );
                closestHitModule = RtShaderModuleCompiler.createModule(
                        stack,
                        device,
                        RtShaderModuleCompiler.loadProduction("assets/rtrenderer/shaders/bootstrap.rchit", Shaderc.shaderc_closesthit_shader,
                                diagnostics.edges())
                );
                anyHitModule = RtShaderModuleCompiler.createModule(
                        stack,
                        device,
                        RtShaderModuleCompiler.loadProduction("assets/rtrenderer/shaders/bootstrap.rahit", Shaderc.shaderc_anyhit_shader,
                                diagnostics.edges())
                );
                descriptorSetLayout = RtRayTracingPipelineFactory.createDescriptorSetLayout(
                        stack, device, diagnosticGBufferEnabled
                );
                boolean explicitFrameReadbackEnabled = Boolean.getBoolean(FRAME_READBACK_ENABLED_PROPERTY);
                /*
                 * The core always produces a renderer-owned GPU image. Whether an host
                 * later copies or imports that image is outside the RT pipeline; host presentation
                 * flags must not disable dispatch or re-enable CPU readback in standalone mode.
                 */
                boolean presentationEnabled = false;
                boolean renderReplacementEnabled = true;
                boolean gpuSharedPresentationEnabled = true;
                boolean visualOutputExperimentEnabled = Boolean.getBoolean(VISUAL_OUTPUT_EXPERIMENTAL_ENABLED_PROPERTY);
                boolean frameReadbackEnabled = shouldEnableFrameReadback(
                        explicitFrameReadbackEnabled,
                        presentationEnabled,
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled
                );
                boolean frameDispatchRequiresPresentationEligibility = shouldRequirePresentationEligibleForFrameDispatch(
                        explicitFrameReadbackEnabled,
                        presentationEnabled,
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled
                );
                int frameReadbackInterval = frameReadbackIntervalByProperties(
                        explicitFrameReadbackEnabled,
                        presentationEnabled,
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled
                );
                boolean outputFollowsRenderTarget = shouldFollowRenderTargetForOutput(
                        frameReadbackEnabled,
                        presentationEnabled,
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled
                );
                RtFrameOutputConfig frameOutputConfig = frameOutputConfigByProperties(outputFollowsRenderTarget);
                RtFrameOutputConfig.Extent initialOutputExtent = frameOutputConfig.initialExtent();
                int frameResourceRingSize = frameResourceRingSizeByProperties(
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled
                );
                int maxPendingFrameSubmissions = maxPendingFrameSubmissionsByProperties(
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled,
                        frameResourceRingSize
                );
                frameUniformBuffer = createFrameUniformBuffer(device, allocator, diagnostics.stalls());
                bootstrapReadbackBuffer = createReadbackBuffer(
                        device,
                        allocator,
                        BOOTSTRAP_OUTPUT_WIDTH,
                        BOOTSTRAP_OUTPUT_HEIGHT,
                        diagnostics.stalls()
                );
                int descriptorSetCount = descriptorSetCountForFrameSlots(frameResourceRingSize);
                descriptorPool = RtRayTracingPipelineFactory.createDescriptorPool(
                        stack, device, descriptorSetCount, diagnosticGBufferEnabled
                );
                descriptorSets = RtRayTracingPipelineFactory.allocateDescriptorSets(
                        stack,
                        device,
                        descriptorPool,
                        descriptorSetLayout,
                        descriptorSetCount
                );
                frameSlots = createFrameSlots(
                        stack,
                        physicalDevice,
                        device,
                        allocator,
                        externalOutputExportEnabled,
                        externalOutputDedicatedAllocation,
                        frameReadbackEnabled,
                        diagnosticGBufferEnabled,
                        descriptorSets,
                        topLevelAccelerationStructure.handle(),
                        topLevelAccelerationStructure.handle(),
                        sceneMaterialTable,
                        initialOutputExtent,
                        frameOutputConfig.primaryRayUpscaleFactor(),
                        1L,
                        diagnostics
                );
                pipelineLayout = RtRayTracingPipelineFactory.createPipelineLayout(stack, device, descriptorSetLayout);
                try (RtPipelineCache cache = RtPipelineCache.open(device, physicalDevice, "bootstrap")) {
                    pipeline = RtRayTracingPipelineFactory.createPipeline(
                            stack,
                            device,
                            pipelineLayout,
                            raygenModule,
                            missModule,
                            closestHitModule,
                            anyHitModule,
                            cache.handle()
                    );
                }

                byte[] shaderGroupHandles = RtRayTracingPipelineFactory.queryShaderGroupHandles(
                        properties, device, pipeline
                );
                RtRayTracingPipelineProperties.ShaderBindingTableData shaderBindingTable =
                        properties.packShaderGroupHandles(
                                shaderGroupHandles,
                                RtRayTracingPipelineFactory.RAYGEN_GROUPS,
                                RtRayTracingPipelineFactory.MISS_GROUPS,
                                RtRayTracingPipelineFactory.HIT_GROUPS,
                                RtRayTracingPipelineFactory.CALLABLE_GROUPS
                        );
                shaderBindingTableResource = RtShaderBindingTable.create(
                        device,
                        allocator,
                        commandContext,
                        shaderBindingTable.bytes(),
                        shaderBindingTable.layout()
                );
                RtBootstrapReadback bootstrapReadback = dispatchBootstrapAndReadback(
                        commandContext,
                        pipelineLayout,
                        pipeline,
                        frameSlots[0].descriptorSet(0),
                        shaderBindingTableResource.buffer(),
                        shaderBindingTableResource.baseOffsetBytes(),
                        frameSlots[0].traceImage(),
                        frameSlots[0].frameUniformBuffer(),
                        bootstrapReadbackBuffer,
                        shaderBindingTableResource.layout()
                );
                frameSlots[0].traceImageLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

                RtRayTracingPipeline result = new RtRayTracingPipeline(
                        device,
                        physicalDevice,
                        commandContext,
                        allocator,
                        externalOutputExportEnabled,
                        externalOutputDedicatedAllocation,
                        descriptorSetLayout,
                        descriptorPool,
                        frameSlots,
                        pipelineLayout,
                        pipeline,
                        topLevelAccelerationStructure.handle(),
                        topLevelAccelerationStructure.handle(),
                        sceneMaterialTable,
                        shaderBindingTableResource,
                        frameUniformBuffer,
                        bootstrapReadback,
                        frameOutputConfig,
                        1,
                        BOOTSTRAP_OUTPUT_WIDTH,
                        BOOTSTRAP_OUTPUT_HEIGHT,
                        1,
                        frameDispatchIntervalByProperties(
                                frameReadbackEnabled,
                                renderReplacementEnabled,
                                visualOutputExperimentEnabled,
                                gpuSharedPresentationEnabled
                        ),
                        frameReadbackInterval,
                        maxPendingFrameSubmissions,
                        frameReadbackEnabled,
                        diagnosticGBufferEnabled,
                        frameDispatchRequiresPresentationEligibility,
                        committedFrontContinuityRequired(
                                gpuSharedPresentationEnabled,
                                renderReplacementEnabled,
                                visualOutputExperimentEnabled
                        ),
                        1L,
                        diagnostics
                );
                descriptorSetLayout = 0L;
                descriptorPool = 0L;
                pipelineLayout = 0L;
                pipeline = 0L;
                shaderBindingTableResource = null;
                frameSlots = null;
                frameUniformBuffer = null;
                return result;
            } finally {
                if (bootstrapReadbackBuffer != null) {
                    bootstrapReadbackBuffer.close();
                }
                if (frameSlots != null) {
                    RtFrameSlotRing.closeSlots(frameSlots);
                }
                if (frameUniformBuffer != null) {
                    frameUniformBuffer.close();
                }
                if (shaderBindingTableResource != null) {
                    shaderBindingTableResource.close();
                }
                if (pipeline != 0L) {
                    VK10.vkDestroyPipeline(device, pipeline, null);
                }
                if (pipelineLayout != 0L) {
                    VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
                }
                if (descriptorPool != 0L) {
                    VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
                }
                if (descriptorSetLayout != 0L) {
                    VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
                }
                RtShaderModuleCompiler.destroyModule(device, anyHitModule);
                RtShaderModuleCompiler.destroyModule(device, closestHitModule);
                RtShaderModuleCompiler.destroyModule(device, missModule);
                RtShaderModuleCompiler.destroyModule(device, raygenModule);
            }
        }
    }

    /**
     * Returns the fixed bootstrap output format.
     *
     * @return Vulkan format used by output slots
     */
    public static int bootstrapOutputFormat() {
        return BOOTSTRAP_OUTPUT_FORMAT;
    }

    /**
     * Determines whether dispatch must preserve an already committed visible front.
     *
     * @param gpuSharedPresentationEnabled  whether GPU sharing is active
     * @param renderReplacementEnabled      whether replacement output is active
     * @param visualOutputExperimentEnabled whether experimental visual output is active
     * @return whether a committed front must remain continuous
     */
    public static boolean committedFrontContinuityRequired(
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return gpuSharedPresentationEnabled && (renderReplacementEnabled || visualOutputExperimentEnabled);
    }

    /**
     * Determines bounded ring capacity from slot count, pending count, and cursor state.
     *
     * @param pendingRecordings     command recordings not yet submitted
     * @param pendingSubmissions    submitted frames awaiting completion
     * @param maxPendingSubmissions maximum permitted pending submissions
     * @param hasWritableFrameSlot  whether the ring exposes a writable slot
     * @return whether another frame may be admitted
     */
    public static boolean hasFrameSubmissionCapacity(
            int pendingRecordings,
            int pendingSubmissions,
            int maxPendingSubmissions,
            boolean hasWritableFrameSlot
    ) {
        if (pendingRecordings < 0 || pendingSubmissions < 0 || maxPendingSubmissions <= 0) {
            throw new IllegalArgumentException("frame submission counts must be valid");
        }
        return hasWritableFrameSlot
                && (long) pendingRecordings + pendingSubmissions < maxPendingSubmissions;
    }

    private static void recordDispatchStage(
            RtFrameDispatchTiming timing,
            RtFrameDispatchTiming.Stage stage,
            long startNanos
    ) {
        timing.record(stage, startNanos);
    }

    private static long frameSequenceForSection(
            RtCore.SharedFrameState state,
            SectionKey key,
            long contentRevision
    ) {
        if (!state.available()) {
            return -1L;
        }
        Long revision = state.sectionContentRevisions().get(key);
        return revision != null && revision == contentRevision ? state.frameStateSequence() : -1L;
    }

    static boolean retainsCompletedFrameForSharedPresentation(
            boolean isPresentedFrame,
            boolean isExportedForPresentation
    ) {
        return isPresentedFrame || isExportedForPresentation;
    }

    private static long pendingFrameAgeMillis(RtPendingFrameSubmission pending, long nowNanos) {
        return RtFrameCompletionPoller.ageMillis(pending, nowNanos);
    }

    private static int pendingFrameSlotIndex(RtPendingFrameSubmission pendingFrame) {
        return pendingFrame == null ? -1 : pendingFrame.frameSlot().index();
    }

    static boolean sharedFrameAvailable(
            boolean closed,
            boolean pendingWritesSharedImage,
            long latestCompletedFrameStateSequence,
            boolean sharedImageExportable
    ) {
        return RtSharedFrameLifecycle.sharedFrameAvailable(
                closed,
                pendingWritesSharedImage,
                latestCompletedFrameStateSequence,
                sharedImageExportable
        );
    }

    static PackedSectionMembership snapshotFrameSectionKeys(Set<SectionKey> sectionKeys) {
        return PackedSectionMembership.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
    }

    static int sharedFrameSyncHandleType(long syncHandle, int exportedSemaphoreHandleType) {
        return RtSharedFrameLifecycle.sharedFrameSyncHandleType(syncHandle, exportedSemaphoreHandleType);
    }

    private static RtFrameOutputConfig.Extent primaryRayTraceExtent(
            RtFrameOutputConfig.Extent outputExtent,
            int primaryRayUpscaleFactor
    ) {
        return RtFrameOutputResourceFactory.primaryRayTraceExtent(
                outputExtent,
                primaryRayUpscaleFactor,
                BOOTSTRAP_OUTPUT_WIDTH,
                BOOTSTRAP_OUTPUT_HEIGHT
        );
    }

    private static RtGpuBuffer createFrameUniformBuffer(
            VkDevice device,
            long allocator,
            RtStallTelemetrySink stalls
    ) {
        return RtFrameOutputResourceFactory.createFrameUniformBuffer(device, allocator, FRAME_UNIFORM_BYTES, stalls);
    }

    private static RtGpuBuffer createReadbackBuffer(
            VkDevice device, long allocator, int width, int height, RtStallTelemetrySink stalls
    ) {
        return RtFrameOutputResourceFactory.createReadbackBuffer(device, allocator, width, height, stalls);
    }

    private static long checkedImageByteSize(int width, int height, int bytesPerPixel) {
        return RtFrameOutputResourceFactory.imageByteSize(width, height, bytesPerPixel);
    }

    private static RtPipelineFrameSlot[] createFrameSlots(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            long allocator,
            boolean externalOutputExportEnabled,
            boolean externalOutputDedicatedAllocation,
            boolean frameReadbackResourcesEnabled,
            boolean diagnosticGBufferResourcesEnabled,
            long[] descriptorSets,
            long topLevelAccelerationStructure,
            long dynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            RtFrameOutputConfig.Extent extent,
            int primaryRayUpscaleFactor,
            long descriptorGeneration,
            RendererRtDiagnostics diagnostics
    ) {
        return RtFrameOutputResourceFactory.createSlots(new RtFrameOutputResourceFactory.Request(
                stack,
                physicalDevice,
                device,
                allocator,
                externalOutputExportEnabled,
                externalOutputDedicatedAllocation,
                frameReadbackResourcesEnabled,
                diagnosticGBufferResourcesEnabled,
                descriptorSets,
                DESCRIPTOR_SETS_PER_FRAME_SLOT,
                MIN_FRAME_RESOURCE_RING_SIZE,
                topLevelAccelerationStructure,
                dynamicTopLevelAccelerationStructure,
                sceneMaterialTable,
                extent,
                primaryRayUpscaleFactor,
                BOOTSTRAP_OUTPUT_WIDTH,
                BOOTSTRAP_OUTPUT_HEIGHT,
                BOOTSTRAP_OUTPUT_FORMAT,
                FRAME_UNIFORM_BYTES,
                DYNAMIC_SCENE_BUFFER_BYTES,
                DIAGNOSTIC_GBUFFER_BYTES_PER_PIXEL,
                descriptorGeneration,
                diagnostics
        ));
    }

    private static RtBootstrapReadback dispatchBootstrapAndReadback(
            RtCommandContext commandContext,
            long pipelineLayout,
            long pipeline,
            long descriptorSet,
            RtGpuBuffer shaderBindingTableBuffer,
            int shaderBindingTableBaseOffsetBytes,
            RtGpuImage outputImage,
            RtGpuBuffer frameUniformBuffer,
            RtGpuBuffer readbackBuffer,
            RtRayTracingPipelineProperties.ShaderBindingTableLayout layout
    ) {
        commandContext.submitOneTime((commandBuffer, commandStack) -> {
            recordFrameUniformUpload(
                    commandBuffer,
                    commandStack,
                    frameUniformBuffer,
                    bootstrapFrameState(outputImage),
                    outputImage,
                    BOOTSTRAP_MODE,
                    0
            );
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    commandStack,
                    outputImage.image(),
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    0,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
            );
            RtFrameDispatchCommands.recordTraceRays(
                    commandBuffer,
                    commandStack,
                    pipelineLayout,
                    pipeline,
                    descriptorSet,
                    shaderBindingTableBuffer,
                    shaderBindingTableBaseOffsetBytes,
                    layout,
                    BOOTSTRAP_OUTPUT_WIDTH,
                    BOOTSTRAP_OUTPUT_HEIGHT
            );
            RtFrameDispatchCommands.recordImageLayoutTransition(
                    commandBuffer,
                    commandStack,
                    outputImage.image(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_ACCESS_TRANSFER_READ_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
            );
            RtFrameDispatchCommands.recordImageToBufferCopy(
                    commandBuffer,
                    commandStack,
                    outputImage,
                    readbackBuffer.buffer(),
                    BOOTSTRAP_OUTPUT_WIDTH,
                    BOOTSTRAP_OUTPUT_HEIGHT
            );
            RtFrameDispatchCommands.recordMemoryBarrier(
                    commandBuffer,
                    commandStack,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_ACCESS_HOST_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT
            );
        });
        return RtBootstrapReadback.verify(
                readbackBuffer.readBytes(),
                BOOTSTRAP_OUTPUT_WIDTH,
                BOOTSTRAP_OUTPUT_HEIGHT
        );
    }

    private static void recordFrameUniformUpload(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuBuffer frameUniformBuffer,
            RendererFrameState frameState,
            RtGpuImage outputImage,
            int renderMode,
            int terrainMaterialCount
    ) {
        Objects.requireNonNull(frameUniformBuffer, "frameUniformBuffer");
        Objects.requireNonNull(frameState, "frameState");
        Objects.requireNonNull(outputImage, "outputImage");
        byte[] uniforms = packFrameUniforms(
                frameState,
                outputImage.width(),
                outputImage.height(),
                renderMode,
                terrainMaterialCount
        );
        RtCommandBufferUploads.recordBytes(commandBuffer, frameUniformBuffer.buffer(), 0L, uniforms);
        RtFrameDispatchCommands.recordMemoryBarrier(
                commandBuffer,
                stack,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        );
    }

    /**
     * Encodes a dynamic scene with unavailable frame state.
     *
     * @param dynamicScene scene to encode
     * @return fixed-size shader ABI bytes
     */
    public static byte[] packDynamicScene(DynamicRenderScene dynamicScene) {
        return packDynamicScene(dynamicScene, RendererFrameState.unavailable());
    }

    /**
     * Encodes the complete fixed-size dynamic-scene shader ABI.
     *
     * @param dynamicScene scene to encode
     * @param frameState   current frame state
     * @return fixed-size shader ABI bytes
     */
    public static byte[] packDynamicScene(DynamicRenderScene dynamicScene, RendererFrameState frameState) {
        Objects.requireNonNull(dynamicScene, "dynamicScene");
        Objects.requireNonNull(frameState, "frameState");
        byte[] bytes = new byte[DYNAMIC_SCENE_BUFFER_BYTES];
        RtDynamicSceneEncoder.encode(dynamicScene, frameState, bytes, DYNAMIC_SCENE_LAYOUT, null, null);
        return bytes;
    }

    static int blockDecalTableSlot(int blockX, int blockY, int blockZ, boolean[] occupiedSlots) {
        Objects.requireNonNull(occupiedSlots, "occupiedSlots");
        if (occupiedSlots.length != BLOCK_DECAL_TABLE_SLOTS) {
            throw new IllegalArgumentException("block decal table occupancy must match the shader ABI");
        }
        return RtDynamicSceneDecalWriter.tableSlot(blockX, blockY, blockZ, occupiedSlots);
    }

    /**
     * Merges upload ranges.
     *
     * @param ranges candidate ranges
     * @return sorted merged non-overlapping ranges
     */
    public static List<UploadRange> mergeUploadRanges(List<UploadRange> ranges) {
        return RtDynamicSceneUploadPlanner.merge(ranges);
    }

    /**
     * Converts the immutable shader ABI into persistent 16-byte update units.
     * A slot's first use must initialize its full buffer; later calls compare
     * every record against the bytes that were successfully submitted for that
     * same slot. This preserves stale-record safety while eliminating copies of
     * lightmap, particle and material-adjacent regions that did not change.
     *
     * @param committed   last successfully submitted bytes
     * @param current     newly encoded bytes
     * @param initialized whether committed storage has been initialized
     * @return dirty aligned ranges
     */
    public static List<UploadRange> dynamicSceneDirtyUploadRanges(
            byte[] committed,
            byte[] current,
            boolean initialized
    ) {
        return RtDynamicSceneUploadPlanner.dirtyRanges(
                committed,
                current,
                initialized,
                List.of(new UploadRange(0, DYNAMIC_SCENE_BUFFER_BYTES)),
                DYNAMIC_SCENE_BUFFER_BYTES
        );
    }

    /**
     * Computes dirty records restricted to precomputed candidate ranges.
     *
     * @param committed       last submitted bytes
     * @param current         newly encoded bytes
     * @param initialized     whether committed storage is initialized
     * @param candidateRanges ranges that may contain changes
     * @return dirty aligned ranges
     */
    public static List<UploadRange> dynamicSceneDirtyUploadRangesWithinCandidates(
            byte[] committed,
            byte[] current,
            boolean initialized,
            List<UploadRange> candidateRanges
    ) {
        return RtDynamicSceneUploadPlanner.dirtyRanges(
                committed,
                current,
                initialized,
                candidateRanges,
                DYNAMIC_SCENE_BUFFER_BYTES
        );
    }

    /**
     * Builds the screen-tile particle reference index used by the ray-generation shader.
     *
     * @param scene         dynamic scene
     * @param frameState    projection state
     * @param particleCount active count
     * @return immutable tile index or full-scan fallback evidence
     */
    public static ParticleTileIndex buildParticleTileIndex(
            DynamicRenderScene scene,
            RendererFrameState frameState,
            int particleCount
    ) {
        return RtParticleTilePlanner.build(scene, frameState, particleCount);
    }

    /**
     * Encodes frame uniforms with zero terrain materials.
     *
     * @param frameState   frame state
     * @param outputWidth  output width
     * @param outputHeight output height
     * @param renderMode   shader render mode
     * @return fixed frame-uniform ABI bytes
     */
    public static byte[] packFrameUniforms(RendererFrameState frameState, int outputWidth, int outputHeight, int renderMode) {
        return RtFrameUniformEncoder.encode(
                frameState, frameState.frameEnvironment(), outputWidth, outputHeight, renderMode, 0
        );
    }

    /**
     * Encodes frame uniforms with an explicit terrain material count.
     *
     * @param frameState           frame state
     * @param outputWidth          output width
     * @param outputHeight         output height
     * @param renderMode           shader render mode
     * @param terrainMaterialCount terrain material prefix size
     * @return fixed frame-uniform ABI bytes
     */
    public static byte[] packFrameUniforms(
            RendererFrameState frameState,
            int outputWidth,
            int outputHeight,
            int renderMode,
            int terrainMaterialCount
    ) {
        return RtFrameUniformEncoder.encode(
                frameState,
                frameState.frameEnvironment(),
                outputWidth,
                outputHeight,
                renderMode,
                terrainMaterialCount
        );
    }

    /**
     * Encodes compatibility frame uniforms from a dynamic environment value.
     *
     * @param frameState       frame state
     * @param environmentState compatibility environment
     * @param outputWidth      output width
     * @param outputHeight     output height
     * @param renderMode       shader mode
     * @return fixed frame-uniform ABI bytes
     */
    public static byte[] packFrameUniforms(
            RendererFrameState frameState,
            DynamicRenderScene.EnvironmentState environmentState,
            int outputWidth,
            int outputHeight,
            int renderMode
    ) {
        return RtFrameUniformEncoder.encode(
                frameState,
                RendererFrameEnvironment.from(environmentState),
                outputWidth,
                outputHeight,
                renderMode,
                0
        );
    }

    private static void validateTerrainMaterialCount(
            int terrainMaterialCount,
            RtSceneMaterialTable sceneMaterialTable
    ) {
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        if (terrainMaterialCount < 0
                || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT) {
            throw new IllegalArgumentException("terrainMaterialCount overlaps the stable dynamic material namespace");
        }
    }

    private static RendererFrameState bootstrapFrameState(RtGpuImage outputImage) {
        return new RendererFrameState(
                0L,
                true,
                outputImage.width(),
                outputImage.height(),
                0.0D,
                0.0D,
                1.0D,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                -1.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                1.0F,
                1.0F,
                1.0F,
                0.0F,
                -1.0F,
                0.0F,
                false,
                false
        );
    }

    static int packDynamicPrimitiveKinds(
            DynamicRenderScene.PrimitiveGeometryKind geometryKind,
            DynamicRenderScene.PrimitiveKind primitiveKind
    ) {
        return RtDynamicSceneAnalyticPrimitiveWriter.packKinds(geometryKind, primitiveKind);
    }

    /**
     * Decodes the low 16-bit analytic geometry kind.
     *
     * @param packedKinds packed shader ABI field
     * @return geometry kind
     */
    public static DynamicRenderScene.PrimitiveGeometryKind unpackDynamicPrimitiveGeometryKind(int packedKinds) {
        int ordinal = packedKinds & 0xFFFF;
        DynamicRenderScene.PrimitiveGeometryKind[] values = DynamicRenderScene.PrimitiveGeometryKind.values();
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("invalid dynamic primitive geometry kind ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    /**
     * Decodes the high 16-bit analytic primitive kind.
     *
     * @param packedKinds packed shader ABI field
     * @return primitive kind
     */
    public static DynamicRenderScene.PrimitiveKind unpackDynamicPrimitiveKind(int packedKinds) {
        int ordinal = (packedKinds >>> 16) & 0xFFFF;
        DynamicRenderScene.PrimitiveKind[] values = DynamicRenderScene.PrimitiveKind.values();
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("invalid dynamic primitive kind ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    private static void requireAligned(long value, int alignmentBytes, String label) {
        if (alignmentBytes <= 0) {
            throw new IllegalArgumentException(label + " alignment must be positive");
        }
        if (Long.remainderUnsigned(value, alignmentBytes) != 0L) {
            throw new IllegalStateException(label + " must be aligned to " + alignmentBytes + " bytes");
        }
    }

    private static long addDeviceAddressOffset(long deviceAddress, long offsetBytes) {
        if (offsetBytes < 0L) {
            throw new IllegalArgumentException("device address offset must not be negative");
        }
        long result = deviceAddress + offsetBytes;
        if (Long.compareUnsigned(result, deviceAddress) < 0) {
            throw new IllegalArgumentException("device address overflow");
        }
        return result;
    }

    private static long checkedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("size overflow");
        }
        return result;
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int positiveOptionalIntProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static int descriptorSetCountForFrameSlots(int frameSlotCount) {
        return RtFrameDispatchPolicy.descriptorSetCountForFrameSlots(frameSlotCount);
    }

    static long frameBoundResourceRetirementGeneration(
            long activeDescriptorGeneration,
            long oldestPendingDescriptorGeneration
    ) {
        return RtFrameDispatchPolicy.frameBoundResourceRetirementGeneration(
                activeDescriptorGeneration,
                oldestPendingDescriptorGeneration
        );
    }

    static int stageableDescriptorIndex(long[] descriptorGenerations, int inFlightDescriptorIndex) {
        return RtFrameDispatchPolicy.stageableDescriptorIndex(descriptorGenerations, inFlightDescriptorIndex);
    }

    static int frameResourceRingSizeByProperties() {
        return frameResourceRingSizeByProperties(false, false, false);
    }

    static int frameResourceRingSizeByProperties(
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return RtFrameDispatchPolicy.frameResourceRingSize(
                positiveOptionalIntProperty(FRAME_RESOURCE_RING_SIZE_PROPERTY),
                gpuSharedPresentationEnabled,
                renderReplacementEnabled,
                visualOutputExperimentEnabled
        );
    }

    static int maxPendingFrameSubmissionsByProperties(
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled,
            int frameResourceRingSize
    ) {
        return RtFrameDispatchPolicy.maxPendingFrameSubmissions(
                positiveOptionalIntProperty(FRAME_MAX_PENDING_SUBMISSIONS_PROPERTY),
                gpuSharedPresentationEnabled,
                renderReplacementEnabled,
                visualOutputExperimentEnabled,
                frameResourceRingSize
        );
    }

    static RtFrameOutputConfig frameOutputConfigByProperties(boolean followsRenderTarget) {
        return frameOutputConfig(
                positiveOptionalIntProperty(FRAME_OUTPUT_WIDTH_PROPERTY),
                positiveOptionalIntProperty(FRAME_OUTPUT_HEIGHT_PROPERTY),
                positiveIntProperty(FRAME_OUTPUT_MAX_PIXELS_PROPERTY, DEFAULT_FRAME_MAX_PIXELS),
                followsRenderTarget,
                primaryRayUpscaleFactorByProperties(followsRenderTarget)
        );
    }

    static RtFrameOutputConfig frameOutputConfig(
            int configuredWidth,
            int configuredHeight,
            int maxPixels,
            boolean followsRenderTarget
    ) {
        return frameOutputConfig(
                configuredWidth,
                configuredHeight,
                maxPixels,
                followsRenderTarget,
                followsRenderTarget
                        ? DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR
                        : DEFAULT_BACKGROUND_PRIMARY_RAY_UPSCALE_FACTOR
        );
    }

    static RtFrameOutputConfig frameOutputConfig(
            int configuredWidth,
            int configuredHeight,
            int maxPixels,
            boolean followsRenderTarget,
            int primaryRayUpscaleFactor
    ) {
        RtFrameDispatchPolicy.OutputConfig config = RtFrameDispatchPolicy.outputConfig(
                configuredWidth,
                configuredHeight,
                maxPixels,
                followsRenderTarget,
                primaryRayUpscaleFactor
        );
        return new RtFrameOutputConfig(
                config.width(),
                config.height(),
                config.maxPixels(),
                config.followsRenderTarget(),
                config.primaryRayUpscaleFactor()
        );
    }

    private static int primaryRayUpscaleFactorByProperties(boolean followsRenderTarget) {
        int configuredFactor = positiveOptionalIntProperty(PRIMARY_RAY_UPSCALE_FACTOR_PROPERTY);
        if (followsRenderTarget) {
            return DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR;
        }
        int defaultFactor = followsRenderTarget
                ? DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR
                : DEFAULT_BACKGROUND_PRIMARY_RAY_UPSCALE_FACTOR;
        int selectedFactor = configuredFactor == 0 ? defaultFactor : configuredFactor;
        return Math.max(1, Math.min(MAX_PRIMARY_RAY_UPSCALE_FACTOR, selectedFactor));
    }

    static boolean shouldFollowRenderTargetForOutput(
            boolean frameReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return RtFrameDispatchPolicy.shouldFollowRenderTargetForOutput(
                frameReadbackEnabled,
                presentationEnabled,
                gpuSharedPresentationEnabled,
                renderReplacementEnabled,
                visualOutputExperimentEnabled
        );
    }

    static int frameDispatchIntervalByProperties(
            boolean frameReadbackEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled,
            boolean gpuSharedPresentationEnabled
    ) {
        return RtFrameDispatchPolicy.frameDispatchInterval(
                positiveOptionalIntProperty(FRAME_DISPATCH_INTERVAL_PROPERTY),
                frameReadbackEnabled,
                renderReplacementEnabled,
                visualOutputExperimentEnabled,
                gpuSharedPresentationEnabled
        );
    }

    static boolean shouldBypassFrameDispatchInterval(boolean presentationEligible) {
        /*
         * Presentation ineligibility is not a license to trace every host
         * frame. During warmup and chunk streaming the front buffer may be held
         * for several game frames; unconditionally bypassing cadence here queues
         * old frame states and recreates the GPU-bound stalls this path is meant
         * to avoid. Freshness catch-up below is the single policy that may break
         * cadence, because it can see both completed and queued frame sequences.
         */
        return false;
    }

    static boolean shouldDispatchForPresentationFreshness(
            long frameStateSequence,
            long latestCompletedFrameStateSequence,
            long newestPendingFrameStateSequence,
            int frameDispatchInterval
    ) {
        return RtFrameDispatchPolicy.shouldDispatchForPresentationFreshness(
                frameStateSequence,
                latestCompletedFrameStateSequence,
                newestPendingFrameStateSequence,
                frameDispatchInterval
        );
    }

    static long presentationFreshnessDispatchWatermark(int frameDispatchInterval) {
        return RtFrameDispatchPolicy.presentationFreshnessDispatchWatermark(frameDispatchInterval);
    }

    static int frameReadbackIntervalByProperties(
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return RtFrameDispatchPolicy.frameReadbackInterval(
                positiveOptionalIntProperty(FRAME_READBACK_INTERVAL_PROPERTY),
                explicitReadbackEnabled,
                presentationEnabled,
                gpuSharedPresentationEnabled,
                renderReplacementEnabled,
                visualOutputExperimentEnabled
        );
    }

    static boolean shouldCaptureFrameReadback(
            boolean frameReadbackEnabled,
            long completedFrameDispatches,
            int frameReadbackInterval
    ) {
        return RtFrameDispatchPolicy.shouldCaptureFrameReadback(
                frameReadbackEnabled,
                completedFrameDispatches,
                frameReadbackInterval
        );
    }

    static boolean shouldCaptureFrameReadback(
            boolean frameReadbackEnabled,
            long completedFrameDispatches,
            int frameReadbackInterval,
            long frameStateSequence,
            long lastReadbackFrameStateSequence
    ) {
        return RtFrameDispatchPolicy.shouldCaptureFrameReadback(
                frameReadbackEnabled,
                completedFrameDispatches,
                frameReadbackInterval,
                frameStateSequence,
                lastReadbackFrameStateSequence
        );
    }

    static boolean shouldEnableFrameReadback(
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled
    ) {
        return RtFrameDispatchPolicy.shouldEnableFrameReadback(
                explicitReadbackEnabled,
                presentationEnabled,
                gpuSharedPresentationEnabled,
                renderReplacementEnabled
        );
    }

    static boolean shouldRequirePresentationEligibleForFrameDispatch(
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled
    ) {
        return RtFrameDispatchPolicy.shouldRequirePresentationEligibleForFrameDispatch(
                explicitReadbackEnabled,
                presentationEnabled,
                gpuSharedPresentationEnabled,
                renderReplacementEnabled
        );
    }

    static boolean shouldDispatchPresentationGateProbe(long observedFrameStates, int probeInterval) {
        return RtFrameDispatchPolicy.shouldDispatchPresentationGateProbe(observedFrameStates, probeInterval);
    }

    static boolean externalFrameSemaphoreEnabled() {
        return Boolean.getBoolean(FRAME_EXTERNAL_SEMAPHORE_ENABLED_PROPERTY);
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            failure.addSuppressed(ex);
        }
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable resource) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            RuntimeException closeFailure = ex instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(ex);
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    static String imageLayoutName(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> "UNDEFINED";
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> "GENERAL";
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> "TRANSFER_SRC_OPTIMAL";
            default -> Integer.toString(layout);
        };
    }

    private static void recordByteArrayRangesUpload(
            VkCommandBuffer commandBuffer,
            long buffer,
            byte[] values,
            List<UploadRange> ranges
    ) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(ranges, "ranges");
        int maxBytes = 0;
        for (UploadRange range : ranges) {
            if (range.endOffsetBytes() > values.length) {
                throw new IllegalArgumentException("upload range exceeds packed payload");
            }
            maxBytes = Math.max(maxBytes, Math.min(MAX_UPDATE_BUFFER_BYTES, range.byteCount()));
        }
        if (maxBytes == 0) {
            return;
        }
        ByteBuffer upload = MemoryUtil.memAlloc(maxBytes);
        try {
            for (UploadRange range : ranges) {
                int consumed = 0;
                while (consumed < range.byteCount()) {
                    int count = Math.min(MAX_UPDATE_BUFFER_BYTES, range.byteCount() - consumed);
                    upload.clear();
                    upload.put(values, range.offsetBytes() + consumed, count).flip();
                    VK10.vkCmdUpdateBuffer(
                            commandBuffer,
                            buffer,
                            checkedAdd(range.offsetBytes(), consumed),
                            upload
                    );
                    consumed += count;
                }
            }
        } finally {
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(upload));
        }
    }

    /**
     * Package-visible verifier for the opt-in descriptor/shader ABI; it creates no Vulkan objects.
     */
    static byte[] compileDiagnosticShaderForVerification(String resourcePath, int shaderKind) {
        return RtShaderModuleCompiler.compileForDiagnosticVerification(resourcePath, shaderKind);
    }

    private static void checkVk(int result, String stage) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            default -> Integer.toString(result);
        };
    }

    /**
     * Begins the single non-blocking completion probe permitted for an outer
     * host frame.  Several renderer decisions query descriptor safety,
     * frame-slot availability and shared-frame state in one accept call.  They
     * must observe one coherent fence snapshot instead of issuing repeated
     * {@code vkGetFenceStatus} calls against the same unfinished submission.
     *
     * @param frameStateSequence outer frame sequence that owns this poll snapshot
     */
    public synchronized void beginFrameCompletionPoll(long frameStateSequence) {
        frameCompletionPoller.beginFrame(
                frameStateSequence, frameReadbackEnabled, this::completePendingFrameSubmission
        );
    }

    /**
     * Atomically binds world TLAS and material resources after waiting for descriptor safety.
     *
     * @param topLevelAccelerationStructure world TLAS
     * @param sceneMaterialTable            material resources
     * @param terrainMaterialCount          terrain material prefix size
     * @param materialBuffersChanged        whether material buffer handles changed
     * @return descriptor generation transition
     */
    public synchronized DescriptorGenerationBinding bindWorldScene(
            RtAccelerationStructure topLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            int terrainMaterialCount,
            boolean materialBuffersChanged
    ) {
        Objects.requireNonNull(topLevelAccelerationStructure, "topLevelAccelerationStructure");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        validateTerrainMaterialCount(terrainMaterialCount, sceneMaterialTable);
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }
        RtDescriptorGenerationState.Transition transition = descriptorGenerationState.prepareWorld(
                topLevelAccelerationStructure.handle(),
                terrainMaterialCount,
                materialBuffersChanged
        );
        if (!transition.changed()) {
            return DescriptorGenerationBinding.unchanged(transition.previousGeneration());
        }
        pollPendingFrameSubmissionsForActiveFrame();
        DescriptorGenerationBinding binding = new DescriptorGenerationBinding(
                transition.previousGeneration(),
                transition.nextGeneration(),
                true
        );
        /*
         * The assignments below are the descriptor-visible commit point. Construct every value
         * that can validate or allocate before crossing it, so an exception can never leave a new
         * resource generation active while the caller still believes rollback is safe.
         */
        descriptorGenerationState.commit(transition);
        boundSceneMaterialTable = sceneMaterialTable;
        return binding;
    }

    /**
     * Atomically binds world and dynamic TLAS plus material resources after descriptor safety.
     *
     * @param topLevelAccelerationStructure        world TLAS
     * @param dynamicTopLevelAccelerationStructure dynamic TLAS
     * @param sceneMaterialTable                   materials
     * @param terrainMaterialCount                 terrain material prefix size
     * @param materialBuffersChanged               whether material buffer handles changed
     * @return descriptor generation transition
     */
    public synchronized DescriptorGenerationBinding bindWorldScene(
            RtAccelerationStructure topLevelAccelerationStructure,
            RtAccelerationStructure dynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            int terrainMaterialCount,
            boolean materialBuffersChanged
    ) {
        Objects.requireNonNull(topLevelAccelerationStructure, "topLevelAccelerationStructure");
        Objects.requireNonNull(dynamicTopLevelAccelerationStructure, "dynamicTopLevelAccelerationStructure");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        validateTerrainMaterialCount(terrainMaterialCount, sceneMaterialTable);
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }
        RtDescriptorGenerationState.Transition transition = descriptorGenerationState.prepareWorldAndDynamic(
                topLevelAccelerationStructure.handle(),
                dynamicTopLevelAccelerationStructure.handle(),
                terrainMaterialCount,
                materialBuffersChanged
        );
        if (!transition.changed()) {
            return DescriptorGenerationBinding.unchanged(transition.previousGeneration());
        }
        pollPendingFrameSubmissionsForActiveFrame();
        DescriptorGenerationBinding binding = new DescriptorGenerationBinding(
                transition.previousGeneration(),
                transition.nextGeneration(),
                true
        );
        diagnostics.materials().descriptorGenerationBound(
                "worldScene",
                transition.previousGeneration(),
                transition.nextGeneration(),
                topLevelAccelerationStructure.handle(),
                materialBuffersChanged,
                sceneMaterialTable.sectionRecordBufferBytes(),
                sceneMaterialTable.faceRecordBufferBytes(),
                sceneMaterialTable.textureRecordBufferBytes(),
                sceneMaterialTable.texturePixelBufferBytes()
        );
        descriptorGenerationState.commit(transition);
        boundSceneMaterialTable = sceneMaterialTable;
        return binding;
    }

    /**
     * Commits world binding metadata without waiting for old frame descriptors to retire.
     *
     * @param topLevelAccelerationStructure world TLAS
     * @param sceneMaterialTable            materials
     * @param terrainMaterialCount          terrain material prefix size
     * @return generation transition
     */
    public synchronized DescriptorGenerationBinding bindWorldSceneLazily(
            RtAccelerationStructure topLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            int terrainMaterialCount
    ) {
        Objects.requireNonNull(topLevelAccelerationStructure, "topLevelAccelerationStructure");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        validateTerrainMaterialCount(terrainMaterialCount, sceneMaterialTable);
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }
        RtDescriptorGenerationState.Transition transition = descriptorGenerationState.prepareWorld(
                topLevelAccelerationStructure.handle(),
                terrainMaterialCount,
                false
        );
        if (!transition.changed()) {
            return DescriptorGenerationBinding.unchanged(transition.previousGeneration());
        }
        DescriptorGenerationBinding binding = new DescriptorGenerationBinding(
                transition.previousGeneration(),
                transition.nextGeneration(),
                true
        );
        descriptorGenerationState.commit(transition);
        boundSceneMaterialTable = sceneMaterialTable;
        return binding;
    }

    /**
     * Lazily commits world and dynamic TLAS binding metadata for later descriptor refresh.
     *
     * @param topLevelAccelerationStructure        world TLAS
     * @param dynamicTopLevelAccelerationStructure dynamic TLAS
     * @param sceneMaterialTable                   materials
     * @param terrainMaterialCount                 terrain material prefix size
     * @return generation transition
     */
    public synchronized DescriptorGenerationBinding bindWorldSceneLazily(
            RtAccelerationStructure topLevelAccelerationStructure,
            RtAccelerationStructure dynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            int terrainMaterialCount
    ) {
        Objects.requireNonNull(topLevelAccelerationStructure, "topLevelAccelerationStructure");
        Objects.requireNonNull(dynamicTopLevelAccelerationStructure, "dynamicTopLevelAccelerationStructure");
        Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        validateTerrainMaterialCount(terrainMaterialCount, sceneMaterialTable);
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }
        RtDescriptorGenerationState.Transition transition = descriptorGenerationState.prepareWorldAndDynamic(
                topLevelAccelerationStructure.handle(),
                dynamicTopLevelAccelerationStructure.handle(),
                terrainMaterialCount,
                false
        );
        if (!transition.changed()) {
            return DescriptorGenerationBinding.unchanged(transition.previousGeneration());
        }

        DescriptorGenerationBinding binding = new DescriptorGenerationBinding(
                transition.previousGeneration(),
                transition.nextGeneration(),
                true
        );
        diagnostics.materials().descriptorGenerationBound(
                "worldSceneLazy",
                transition.previousGeneration(),
                transition.nextGeneration(),
                topLevelAccelerationStructure.handle(),
                false,
                sceneMaterialTable.sectionRecordBufferBytes(),
                sceneMaterialTable.faceRecordBufferBytes(),
                sceneMaterialTable.textureRecordBufferBytes(),
                sceneMaterialTable.texturePixelBufferBytes()
        );
        descriptorGenerationState.commit(transition);
        boundSceneMaterialTable = sceneMaterialTable;
        return binding;
    }

    /**
     * Reports descriptor mutation safety.
     *
     * @return whether no in-flight frame still references the active world descriptor generation
     */
    public synchronized boolean canUpdateWorldSceneDescriptors() {
        pollPendingFrameSubmissionsForActiveFrame();
        return canStageDescriptorGeneration();
    }

    /**
     * Reports material mutation safety.
     *
     * @return whether material buffers may be mutated without invalidating an in-flight frame
     */
    public synchronized boolean canUpdateMaterialBuffersInPlace() {
        pollPendingFrameSubmissionsForActiveFrame();
        /*
         * A presented shared image is consumed by external graphics API, not by the Vulkan
         * descriptor set that produced it. The material SSBO is unsafe only
         * while a Vulkan trace using that descriptor generation is in flight.
         * Treating the displayed image as a live SSBO user forced a four-buffer
         * replacement for every streaming material delta and was the source of
         * the repeated 100+ MiB buffer retirements in 32-distance smoke.
         */
        return frameCompletionPoller.isEmpty();
    }

    /**
     * Reports bounded output-ring capacity.
     *
     * @return whether at least one output slot can accept a new submission
     */
    public synchronized boolean hasFrameSubmissionCapacity() {
        pollPendingFrameSubmissionsForActiveFrame();
        return hasFrameSubmissionCapacity(
                0,
                frameCompletionPoller.size(),
                maxPendingFrameSubmissions,
                hasWritableFrameSlot()
        );
    }

    /**
     * Reports frame-bound retirement protection.
     *
     * @return oldest descriptor generation still protected by frame-bound resources
     */
    public synchronized long frameBoundResourceRetirementGeneration() {
        pollPendingFrameSubmissionsForActiveFrame();
        RtPendingFrameSubmission oldestPending = oldestPendingFrameWork();
        return frameBoundResourceRetirementGeneration(
                descriptorGenerationState.generation(),
                oldestPending == null ? -1L : oldestPending.descriptorGeneration()
        );
    }

    /**
     * Encodes and stages a newer immutable dynamic scene.
     *
     * @param dynamicScene accepted scene value
     */
    public synchronized void acceptDynamicSceneUpdate(DynamicRenderScene dynamicScene) {
        Objects.requireNonNull(dynamicScene, "dynamicScene");
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }
        rememberDynamicSceneUpdate(dynamicScene);
    }

    /**
     * Attempts one non-blocking frame dispatch under cadence and capacity policy.
     *
     * @param commandContext                ordered frame command lane
     * @param causality                     frame causality proof
     * @param scenePublicationState         committed scene publication proof
     * @param frameState                    view/frame state
     * @param dynamicScene                  dynamic-scene state
     * @param boundTlasDynamicSceneRevision TLAS dynamic revision
     * @param boundSectionKeys              immutable bound section set
     * @param boundSectionContentRevisions  immutable section content revisions
     * @param boundViewState                view state used by bound scene resources
     * @param presentationEligible          whether the frame may be published
     * @return latest completed CPU snapshot, potentially from an earlier frame
     */
    public synchronized RtFrameSnapshot dispatchFrameIfDue(
            RtCommandContext commandContext,
            RendererFrameCausality causality,
            RtCore.ScenePublicationState scenePublicationState,
            RendererFrameState frameState,
            DynamicRenderScene dynamicScene,
            long boundTlasDynamicSceneRevision,
            PackedSectionMembership boundSectionKeys,
            SectionRevisionSnapshot boundSectionContentRevisions,
            RendererViewState boundViewState,
            boolean presentationEligible
    ) {
        return dispatchFrameIfDue(new RtFrameDispatchRequest(
                commandContext,
                causality,
                scenePublicationState,
                frameState,
                dynamicScene,
                boundTlasDynamicSceneRevision,
                boundSectionKeys,
                boundSectionContentRevisions,
                boundViewState,
                presentationEligible
        ));
    }

    private RtFrameSnapshot dispatchFrameIfDue(RtFrameDispatchRequest request) {
        request.requireDescriptorVisible(descriptorGenerationState.generation());
        if (closed) {
            throw new IllegalStateException("ray tracing pipeline is already closed");
        }

        RtCommandContext commandContext = request.commandContext();
        RendererFrameCausality causality = request.causality();
        RtCore.ScenePublicationState scenePublicationState = request.scenePublicationState();
        RendererFrameState frameState = request.frameState();
        DynamicRenderScene dynamicScene = request.dynamicScene();
        long boundTlasDynamicSceneRevision = request.boundTlasDynamicSceneRevision();
        PackedSectionMembership boundSectionKeys = request.boundSectionKeys();
        SectionRevisionSnapshot boundSectionContentRevisions = request.boundSectionContentRevisions();
        RendererViewState boundViewState = request.boundViewState();
        boolean presentationEligible = request.presentationEligible();

        long observedFrameStates = dispatchStatistics.observeFrameState();
        pollPendingFrameSubmissionsForActiveFrame();
        boolean presentationProbeDue = !frameDispatchRequiresPresentationEligibility
                || presentationEligible
                || shouldDispatchPresentationGateProbe(observedFrameStates, PRESENTATION_GATE_PROBE_INTERVAL);
        boolean intervalBlocked = frameState.valid()
                && shouldSkipFrameDispatchByInterval(frameState.sequence(), presentationEligible);
        RtFrameDispatchAdmission.Decision admission = RtFrameDispatchAdmission.decide(
                new RtFrameDispatchAdmission.State(
                        frameDispatchRequiresPresentationEligibility,
                        presentationEligible,
                        presentationProbeDue,
                        frameState.valid(),
                        intervalBlocked,
                        frameCompletionPoller.size(),
                        maxPendingFrameSubmissions,
                        sharedFrames.slotCount()
                )
        );
        if (!admission.accepted()) {
            dispatchStatistics.recordAdmissionRejection(admission);
            recordDispatchRejection(
                    causality,
                    admission == RtFrameDispatchAdmission.Decision.INVALID_FRAME_STATE
                            ? -1L
                            : frameState.sequence(),
                    dynamicScene.revision(),
                    boundTlasDynamicSceneRevision,
                    admission.rejectionReason()
            );
            return completedFrames.frameReadback();
        }
        DynamicRenderScene dispatchDynamicScene = resolveDynamicSceneForDispatch(dynamicScene);
        boolean recordDispatchFlight = RtFrameDispatchFlightRecorder.enabled();
        boolean profileDispatch = diagnostics.edges().enabled() || recordDispatchFlight;
        RtFrameDispatchTiming dispatchTiming = profileDispatch
                ? RtFrameDispatchTiming.createEnabled()
                : RtFrameDispatchTiming.createDisabled();
        long dispatchStageStart = profileDispatch ? System.nanoTime() : 0L;

        ensureFrameOutputResources(frameState);
        RtPipelineFrameSlot dispatchFrameSlot = acquireFrameSlotForDispatch();
        if (dispatchFrameSlot == null) {
            dispatchStatistics.recordNoFrameSlot();
            recordDispatchRejection(
                    causality, frameState.sequence(), dispatchDynamicScene.revision(),
                    boundTlasDynamicSceneRevision, "noWritableFrameSlot"
            );
            return completedFrames.frameReadback();
        }
        int dispatchDescriptorIndex;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dispatchDescriptorIndex = ensureFrameSlotDescriptors(stack, dispatchFrameSlot);
        }
        RtGpuImage dispatchOutputImage = dispatchFrameSlot.outputImage();
        RtGpuImage dispatchTraceImage = dispatchFrameSlot.traceImage();
        long dispatchDescriptorSet = dispatchFrameSlot.descriptorSet(dispatchDescriptorIndex);
        int dispatchOutputImageLayout = dispatchFrameSlot.imageLayout();
        int dispatchTraceImageLayout = dispatchFrameSlot.traceImageLayout();
        boolean scheduledFrameReadback = shouldCaptureFrameReadback(
                frameReadbackEnabled,
                dispatchStatistics.frameDispatches(),
                frameReadbackInterval,
                frameState.sequence(),
                completedFrames.frameReadback() == null ? -1L : completedFrames.frameReadback().frameStateSequence()
        );
        String gBufferCaptureReason = diagnosticGBufferEnabled ? RtGBufferCaptureRequests.claim() : null;
        boolean captureGBuffer = gBufferCaptureReason != null;
        boolean captureReadback = scheduledFrameReadback || captureGBuffer;
        /*
         * Dynamic scene revisions advance for transform collection and
         * environment time. They are not a GPU-copy contract: every ring slot
         * owns a persistent byte mirror and records only dirty ABI records.
         */
        boolean uploadDynamicScene = true;

        VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore signalSemaphore =
                sharedFrames.createSubmissionSignal(dispatchOutputImage);
        recordDispatchStage(dispatchTiming, RtFrameDispatchTiming.Stage.RESOURCE_PREP, dispatchStageStart);
        VulkanWin32ExternalSemaphoreProbe.ExportedSemaphore submissionSignalSemaphore = signalSemaphore;
        long dispatchStart = System.nanoTime();
        dispatchFrameSlot.beginWrite(dispatchDescriptorIndex, descriptorGenerationState.generation());
        RtCommandContext.AsyncSubmission submission;
        RtGpuTimestampPool.Capture gpuTimestamps;
        try {
            RtAsyncFrameDispatchRecorder.Result dispatchResult = RtAsyncFrameDispatchRecorder.submit(
                    new RtAsyncFrameDispatchRecorder.Request(
                            commandContext,
                            dispatchFrameSlot,
                            dispatchOutputImage,
                            dispatchTraceImage,
                            dispatchOutputImageLayout,
                            dispatchTraceImageLayout,
                            dispatchDescriptorSet,
                            pipelineLayout,
                            pipeline,
                            shaderBindingTable.buffer(),
                            shaderBindingTable.baseOffsetBytes(),
                            shaderBindingTable.layout(),
                            captureReadback,
                            captureGBuffer,
                            profileDispatch,
                            dispatchStart,
                            submissionSignalSemaphore,
                            dispatchTiming,
                            (commandBuffer, commandStack) -> recordFrameUniformUpload(
                                    commandBuffer,
                                    commandStack,
                                    dispatchFrameSlot.frameUniformBuffer(),
                                    frameState,
                                    dispatchTraceImage,
                                    WORLD_MODE,
                                    descriptorGenerationState.terrainMaterialCount()
                            ),
                            (commandBuffer, commandStack) -> {
                                DynamicSceneUploadTiming uploadTiming = recordDynamicSceneUpload(
                                        commandBuffer,
                                        commandStack,
                                        dispatchFrameSlot,
                                        dispatchDynamicScene,
                                        frameState
                                );
                                return new RtAsyncFrameDispatchRecorder.DynamicSceneUploadTiming(
                                        uploadTiming.packNanos(),
                                        uploadTiming.commandNanos()
                                );
                            }
                    )
            );
            submission = dispatchResult.submission();
            gpuTimestamps = dispatchResult.gpuTimestamps();
            dispatchOutputImageLayout = dispatchResult.outputImageLayout();
            dispatchTraceImageLayout = dispatchResult.traceImageLayout();
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            dispatchFrameSlot.abortWrite();
            RtGBufferCaptureRequests.restore(gBufferCaptureReason);
            closeSuppressing(ex, signalSemaphore);
            RtFrameDispatchFlightRecorder.recordFailed(
                    causality,
                    frameState.sequence(),
                    dispatchDynamicScene.revision(),
                    descriptorGenerationState.generation(),
                    boundTlasDynamicSceneRevision,
                    Math.max(0L, System.nanoTime() - dispatchStart),
                    "submissionFailure"
            );
            throw ex;
        }
        if (uploadDynamicScene) {
            dispatchFrameSlot.dynamicSceneRevision(dispatchDynamicScene.revision(), frameState.sequence());
            boolean dynamicSceneUploaded = dispatchFrameSlot.dynamicSceneUploadRecorded();
            if (dynamicSceneUploaded) {
                dispatchFrameSlot.commitDynamicSceneUpload();
                dynamicSceneFront.recordUpload();
            }
        }
        dispatchFrameSlot.imageLayout(dispatchOutputImageLayout);
        dispatchFrameSlot.traceImageLayout(dispatchTraceImageLayout);

        long dispatchOrdinal = dispatchStatistics.nextDispatchOrdinal();
        try {
            RtPendingFrameSubmission pendingFrame = new RtPendingFrameSubmission(
                    submission,
                    gpuTimestamps,
                    dispatchFrameSlot,
                    frameState.sequence(),
                    causality,
                    scenePublicationState,
                    boundSectionKeys,
                    boundSectionContentRevisions,
                    boundViewState,
                    dispatchOrdinal,
                    dispatchOutputImage,
                    captureReadback,
                    captureGBuffer,
                    gBufferCaptureReason,
                    signalSemaphore,
                    dispatchStart,
                    observedFrameStates,
                    descriptorGenerationState.generation(),
                    dispatchDynamicScene.revision(),
                    boundTlasDynamicSceneRevision
            );
            frameCompletionPoller.enqueue(pendingFrame);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            submission.close();
            if (gpuTimestamps != null) {
                gpuTimestamps.close();
            }
            dispatchFrameSlot.abortWrite();
            closeSuppressing(ex, signalSemaphore);
            throw ex;
        }
        dispatchStatistics.recordSubmission(dispatchOrdinal);
        diagnostics.edges().edge(
                "frameSubmitted",
                "dispatchOrdinal=" + dispatchOrdinal
                        + ", frameSequence=" + frameState.sequence()
                        + ", descriptorGeneration=" + descriptorGenerationState.generation()
                        + ", sections=" + boundSectionKeys.size()
                        + ", pendingFrames=" + frameCompletionPoller.size()
        );
        dynamicSceneFront.recordDispatched(dispatchDynamicScene.revision());
        recordDispatchDecision(frameState.sequence(), true, "pipeline", "submitted");
        signalSemaphore = null;
        if (!captureReadback && frameReadbackEnabled) {
            dispatchStatistics.recordReadbackIntervalSkip();
        }
        long dispatchElapsedNanos = Math.max(0L, System.nanoTime() - dispatchStart);
        recordFrameDispatchTelemetry(dispatchElapsedNanos);
        if (profileDispatch) {
            dispatchTiming.finishBookkeeping(dispatchStart);
            recordDispatchCpuWindow(dispatchTiming.snapshot());
        }
        if (recordDispatchFlight) {
            RtFrameDispatchFlightRecorder.recordSubmitted(
                    causality,
                    frameState.sequence(),
                    dispatchOrdinal,
                    dispatchDynamicScene.revision(),
                    descriptorGenerationState.generation(),
                    boundTlasDynamicSceneRevision,
                    dispatchElapsedNanos,
                    dispatchTiming.cpuTimings()
            );
        }
        return completedFrames.frameReadback();
    }

    private void recordDispatchCpuWindow(long[] stages) {
        String completedWindow = dispatchCpuWindow.record(stages, System.nanoTime());
        if (completedWindow != null) {
            diagnostics.builds().aggregate("rtDispatchCpu", completedWindow);
        }
    }

    /**
     * Returns the dispatch admission state.
     *
     * @return last structured decision
     */
    public synchronized RtCore.NativeDispatchDecision nativeDispatchDecision() {
        return latestDispatchDecision;
    }

    /**
     * Returns descriptor state.
     *
     * @return currently committed generation
     */
    public synchronized long activeDescriptorGeneration() {
        return descriptorGenerationState.generation();
    }

    /**
     * Returns dynamic-scene ingestion state.
     *
     * @return latest accepted revision
     */
    public synchronized long latestDynamicSceneRevision() {
        return dynamicSceneFront.revision();
    }

    /**
     * Returns dynamic-scene dispatch state.
     *
     * @return latest submitted revision
     */
    public synchronized long latestDispatchedDynamicSceneRevision() {
        return dynamicSceneFront.dispatchedRevision();
    }

    /**
     * Diagnostic-only exact progress for one immutable section content generation.
     *
     * @param key             section identity
     * @param contentRevision exact content revision
     * @return submitted, completed, and presented frame progress
     */
    public synchronized RtCore.FrameGenerationProgress sectionFrameProgress(
            SectionKey key,
            long contentRevision
    ) {
        Objects.requireNonNull(key, "key");
        if (contentRevision < 0L) {
            return RtCore.FrameGenerationProgress.unavailable();
        }
        long dispatched = -1L;
        for (RtPendingFrameSubmission pending : frameCompletionPoller.pendingSubmissions()) {
            Long revision = pending.sectionContentRevisions().get(key);
            if (revision != null && revision == contentRevision) {
                dispatched = Math.max(dispatched, pending.frameStateSequence());
            }
        }
        long completed = frameSequenceForSection(sharedFrames.completedState(), key, contentRevision);
        long presented = frameSequenceForSection(sharedFrames.presentedState(), key, contentRevision);
        if (completed >= 0L) {
            dispatched = Math.max(dispatched, completed);
        }
        if (presented >= 0L) {
            completed = Math.max(completed, presented);
            dispatched = Math.max(dispatched, presented);
        }
        return new RtCore.FrameGenerationProgress(dispatched, completed, presented, presented);
    }

    /**
     * Diagnostic-only progress for a dynamic scene generation.
     *
     * @param sceneRevision dynamic revision to query
     * @return accepted, submitted, and completed progress
     */
    public synchronized RtCore.FrameGenerationProgress dynamicFrameProgress(long sceneRevision) {
        if (sceneRevision < 0L) {
            return RtCore.FrameGenerationProgress.unavailable();
        }
        long dispatched = -1L;
        for (RtPendingFrameSubmission pending : frameCompletionPoller.pendingSubmissions()) {
            if (pending.dynamicSceneRevision() >= sceneRevision) {
                dispatched = Math.max(dispatched, pending.frameStateSequence());
            }
        }
        long completed = completedFrames.dynamicSceneRevision() >= sceneRevision
                ? completedFrames.frameStateSequence()
                : -1L;
        RtPipelineFrameSlot presentedFrameSlot = sharedFrames.presentedSlot();
        long presented = presentedFrameSlot != null
                && presentedFrameSlot.dynamicSceneRevision() >= sceneRevision
                ? sharedFrames.presentedState().frameStateSequence()
                : -1L;
        if (completed >= 0L) {
            dispatched = Math.max(dispatched, completed);
        }
        if (presented >= 0L) {
            completed = Math.max(completed, presented);
            dispatched = Math.max(dispatched, presented);
        }
        return new RtCore.FrameGenerationProgress(dispatched, completed, presented, presented);
    }

    /**
     * Returns dynamic-scene completion state.
     *
     * @return latest revision proven complete by a frame fence
     */
    public synchronized long latestCompletedDynamicSceneRevision() {
        pollPendingFrameSubmissionsForActiveFrame();
        return completedFrames.dynamicSceneRevision();
    }

    /**
     * Records an admission decision made by an outer scheduling layer.
     *
     * @param frameStateSequence affected frame sequence
     * @param stage              decision stage
     * @param reason             bounded diagnostic reason
     */
    public synchronized void recordExternalDispatchDecision(long frameStateSequence, String stage, String reason) {
        recordDispatchDecision(frameStateSequence, false, stage, reason);
    }

    private void recordDispatchRejection(
            RendererFrameCausality causality,
            long frameStateSequence,
            long dynamicSceneRevision,
            long boundTlasDynamicSceneRevision,
            String reason
    ) {
        recordDispatchDecision(frameStateSequence, false, "pipeline", reason);
        RtFrameDispatchFlightRecorder.recordRejected(
                causality,
                frameStateSequence >= 0L ? frameStateSequence : causality.frameSequence(),
                dynamicSceneRevision,
                descriptorGenerationState.generation(),
                boundTlasDynamicSceneRevision,
                reason
        );
    }

    private void recordDispatchDecision(long frameStateSequence, boolean dispatched, String stage, String reason) {
        diagnostics.causality().frameReason(
                dispatched ? RtCausalitySink.Stage.GPU_DISPATCH : RtCausalitySink.Stage.FRAME_REJECTED,
                frameStateSequence,
                completedFrames.frameStateSequence(),
                frameCompletionPoller.size(),
                RtCausalitySink.dispatchReason(reason)
        );
        if (!TAKEOVER_FLIGHT_RECORDER_ENABLED) {
            return;
        }
        RtPendingFrameSubmission oldestPending = oldestPendingFrameWork();
        latestDispatchDecision = new RtCore.NativeDispatchDecision(
                frameStateSequence,
                dispatched,
                stage,
                reason,
                frameCompletionPoller.size(),
                sharedFrames.slotCount(),
                maxPendingFrameSubmissions,
                dispatchStatistics.frameDispatches(),
                completedFrames.frameStateSequence(),
                oldestPending == null ? -1L : oldestPending.frameStateSequence(),
                dispatchStatistics.observedFrameStates(),
                dispatchStatistics.skippedPresentationGateFrames(),
                dispatchStatistics.skippedUnavailableFrameStates(),
                dispatchStatistics.skippedIntervalFrames(),
                dispatchStatistics.skippedPendingAsyncFrames(),
                dispatchStatistics.skippedNoFrameSlots()
        );
    }

    private DynamicRenderScene resolveDynamicSceneForDispatch(DynamicRenderScene dynamicScene) {
        rememberDynamicSceneUpdate(dynamicScene);
        return dynamicSceneFront.scene();
    }

    private void rememberDynamicSceneUpdate(DynamicRenderScene dynamicScene) {
        dynamicSceneFront.accept(dynamicScene);
    }

    /**
     * Returns CPU readback state.
     *
     * @return latest completed snapshot, or {@code null} when disabled or unavailable
     */
    public synchronized RtFrameSnapshot latestFrameSnapshot() {
        pollPendingFrameSubmissionsForActiveFrame();
        return completedFrames.frameReadback();
    }

    /**
     * Queues exactly one diagnostic readback; normal frame dispatches remain GPU-only.
     *
     * @return whether diagnostic capture is supported for the next dispatch
     */
    public synchronized boolean requestGBufferCapture() {
        if (!diagnosticGBufferEnabled || closed) {
            return false;
        }
        return RtGBufferCaptureRequests.request("explicitApiRequest");
    }

    /**
     * Returns diagnostic readback state.
     *
     * @return latest completed G-buffer, or {@code null}
     */
    public synchronized RtGBufferSnapshot latestGBufferSnapshot() {
        pollPendingFrameSubmissionsForActiveFrame();
        return completedFrames.gBufferReadback();
    }

    /**
     * Returns shared-frame sequence state.
     *
     * @return latest sequence, or a negative sentinel
     */
    public synchronized long latestSharedFrameSequence() {
        pollPendingFrameSubmissionsForActiveFrame();
        return sharedFrames.latestSequence(closed, this::pendingWritesImage);
    }

    /**
     * Returns shared-frame coverage.
     *
     * @return immutable section-key proof
     */
    public synchronized Set<SectionKey> latestSharedFrameSectionKeys() {
        pollPendingFrameSubmissionsForActiveFrame();
        return completedFrames.sectionKeys();
    }

    /**
     * Returns publication state.
     *
     * @return immutable latest shared-frame state
     */
    public synchronized RtCore.SharedFrameState latestSharedFrameState() {
        pollPendingFrameSubmissionsForActiveFrame();
        return sharedFrames.latestState(closed, this::pendingWritesImage);
    }

    /**
     * Leases the latest shared image.
     *
     * @return new lease, or {@code null}
     */
    public synchronized RtCore.SharedFrameImage exportLatestSharedFrameImage() {
        pollPendingFrameSubmissionsForActiveFrame();
        return sharedFrames.exportLatest(closed, this::pendingWritesImage);
    }

    /**
     * Exports a shared image only when its frame sequence satisfies the requested lower bound.
     *
     * @param requiredFrameStateSequence minimum acceptable sequence
     * @return new lease, or {@code null}
     */
    public synchronized RtCore.SharedFrameImage exportSharedFrameImage(long requiredFrameStateSequence) {
        if (requiredFrameStateSequence < 0L) {
            throw new IllegalArgumentException("requiredFrameStateSequence must not be negative");
        }
        pollPendingFrameSubmissionsForActiveFrame();
        return sharedFrames.exportRequired(requiredFrameStateSequence, closed, this::pendingWritesImage);
    }

    /**
     * Acknowledges consumer presentation so the publication ledger may advance retention state.
     *
     * @param frameStateSequence presented sequence
     * @param vulkanImage        presented image handle
     * @return whether the acknowledgement matched a retained publication
     */
    public synchronized boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage) {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (vulkanImage == 0L) {
            throw new IllegalArgumentException("vulkanImage must not be null");
        }
        pollPendingFrameSubmissionsForActiveFrame();

        return sharedFrames.acknowledgePresented(frameStateSequence, vulkanImage);
    }

    /**
     * Returns runtime activity.
     *
     * @return immutable queue, completion, and timing snapshot
     */
    public synchronized RtCore.RuntimeActivity runtimeActivity() {
        pollPendingFrameSubmissionsForActiveFrame();
        RtPendingFrameSubmission oldestPendingFrame = oldestPendingFrameWork();
        RtGpuTimestampPool.Snapshot gpuTiming = frameCommandContext.gpuTimestampSnapshot();
        return new RtCore.RuntimeActivity(
                dispatchStatistics.frameDispatches(),
                completedFrames.frameReadbacks(),
                completedFrames.frameReadback() == null ? -1L : completedFrames.frameReadback().frameStateSequence(),
                oldestPendingFrame != null,
                oldestPendingFrame == null ? -1L : oldestPendingFrame.frameStateSequence(),
                oldestPendingFrame == null ? 0L : pendingFrameAgeMillis(oldestPendingFrame, System.nanoTime()),
                oldestPendingFrame == null ? 0L : oldestPendingFrame.polls(),
                oldestPendingFrame == null
                        ? 0L
                        : dispatchStatistics.observedFrameStates() - oldestPendingFrame.observedFrameStatesAtSubmit(),
                completedFrames.dispatchOrdinal(),
                completedFrames.frameStateSequence(),
                new RtCore.GpuFrameTiming(
                        gpuTiming.enabled(),
                        gpuTiming.acquiredCaptures(),
                        gpuTiming.completedCaptures(),
                        gpuTiming.droppedCaptures(),
                        gpuTiming.failedCaptures(),
                        gpuTiming.lastFirstSegmentNanos(),
                        gpuTiming.lastSecondSegmentNanos(),
                        gpuTiming.lastTotalNanos(),
                        gpuTiming.averageFirstSegmentNanos(),
                        gpuTiming.averageSecondSegmentNanos(),
                        gpuTiming.averageTotalNanos(),
                        gpuTiming.maxTotalNanos()
                ),
                RtCore.GpuWorkTiming.unavailable()
        );
    }

    synchronized void waitForPendingFrameSubmission() {
        pollPendingFrameSubmissions(true);
    }

    private void ensureFrameOutputResources(RendererFrameState frameState) {
        RtFrameOutputConfig.Extent desiredExtent = frameOutputConfig.resolve(frameState);
        if (frameOutputResourcesMatch(desiredExtent)) {
            return;
        }

        /*
         * Reallocating the exportable storage image must wait for any command buffer
         * still using the old image. The common same-size path must stay asynchronous;
         * waiting there serialized every visible RT frame on the render thread.
         */
        pollPendingFrameSubmissions(true);
        if (frameOutputResourcesMatch(desiredExtent)) {
            return;
        }

        RtPipelineFrameSlot[] nextFrameSlots = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                nextFrameSlots = createFrameSlots(
                        stack,
                        physicalDevice,
                        device,
                        allocator,
                        externalOutputExportEnabled,
                        externalOutputDedicatedAllocation,
                        frameReadbackEnabled,
                        diagnosticGBufferEnabled,
                        frameSlotDescriptorSets(),
                        descriptorGenerationState.worldTlas(),
                        descriptorGenerationState.dynamicTlas(),
                        boundSceneMaterialTable,
                        desiredExtent,
                        frameOutputConfig.primaryRayUpscaleFactor(),
                        descriptorGenerationState.generation(),
                        diagnostics
                );
            }

            sharedFrames.replaceSlots(nextFrameSlots);
            completedFrames.resetForOutputReplacement();
            nextFrameSlots = null;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            sharedFrames.closeAbandonedSlots(nextFrameSlots, ex);
            throw ex;
        }
    }

    private boolean frameOutputResourcesMatch(RtFrameOutputConfig.Extent desiredExtent) {
        RtFrameOutputConfig.Extent desiredTraceExtent = primaryRayTraceExtent(
                desiredExtent,
                frameOutputConfig.primaryRayUpscaleFactor()
        );
        return sharedFrames.matches(desiredExtent, desiredTraceExtent);
    }

    private RtPipelineFrameSlot acquireFrameSlotForDispatch() {
        return sharedFrames.acquireWritableSlot();
    }

    private boolean hasWritableFrameSlot() {
        return sharedFrames.hasWritableSlot();
    }

    private boolean canStageDescriptorGeneration() {
        return sharedFrames.canStageDescriptorGeneration();
    }

    private int ensureFrameSlotDescriptors(MemoryStack stack, RtPipelineFrameSlot frameSlot) {
        int descriptorIndex = frameSlot.descriptorIndexForGeneration(descriptorGenerationState.generation());
        if (descriptorIndex >= 0) {
            return descriptorIndex;
        }
        descriptorIndex = frameSlot.stageableDescriptorIndex();
        RtFrameDescriptorWriter.updateSceneDescriptors(
                stack,
                device,
                frameSlot.descriptorSet(descriptorIndex),
                descriptorGenerationState.worldTlas(),
                descriptorGenerationState.dynamicTlas(),
                boundSceneMaterialTable,
                frameSlot.hasDiagnosticGBuffer() ? frameSlot.diagnosticGBuffer() : null
        );
        diagnostics.materials().descriptorSetWritten(
                descriptorGenerationState.generation(),
                descriptorIndex,
                frameSlot.descriptorSet(descriptorIndex),
                descriptorGenerationState.worldTlas(),
                descriptorGenerationState.dynamicTlas(),
                boundSceneMaterialTable.sectionRecordBuffer(),
                boundSceneMaterialTable.sectionRecordBufferBytes(),
                boundSceneMaterialTable.faceRecordBuffer(),
                boundSceneMaterialTable.faceRecordBufferBytes(),
                boundSceneMaterialTable.textureRecordBuffer(),
                boundSceneMaterialTable.textureRecordBufferBytes(),
                boundSceneMaterialTable.texturePixelBuffer(),
                boundSceneMaterialTable.texturePixelBufferBytes()
        );
        frameSlot.descriptorGeneration(descriptorIndex, descriptorGenerationState.generation());
        dispatchStatistics.recordFrameSlotDescriptorRefresh();
        return descriptorIndex;
    }

    private long[] frameSlotDescriptorSets() {
        RtPipelineFrameSlot[] frameSlots = sharedFrames.currentSlots();
        long[] descriptorSets = new long[descriptorSetCountForFrameSlots(frameSlots.length)];
        int descriptorSetIndex = 0;
        for (RtPipelineFrameSlot frameSlot : frameSlots) {
            for (int index = 0; index < frameSlot.descriptorSetCount(); index++) {
                descriptorSets[descriptorSetIndex++] = frameSlot.descriptorSet(index);
            }
        }
        return descriptorSets;
    }

    /**
     * Builds a bounded diagnostic summary.
     *
     * @param name diagnostic label
     * @return resource and scheduling state
     */
    public String summary(String name) {
        RtPendingFrameSubmission oldestPendingFrame = oldestPendingFrameWork();
        return name
                + "{pipeline=0x" + Long.toHexString(pipeline)
                + ", layout=0x" + Long.toHexString(pipelineLayout)
                + ", recursionDepth=" + recursionDepth
                + ", groups=" + RtRayTracingPipelineFactory.GROUP_COUNT
                + ", " + shaderBindingTable.layoutSummary()
                + ", frameSlots=" + sharedFrames.slotRingSummary()
                + ", " + sharedFrames.slotRingResourceSummary()
                + ", nextFrameSlotIndex=" + sharedFrames.nextSlotIndex()
                + ", activeDescriptorGeneration=" + descriptorGenerationState.generation()
                + ", frameSlotDescriptorRefreshes=" + dispatchStatistics.frameSlotDescriptorRefreshes()
                + ", pendingFrameSlotIndex=" + pendingFrameSlotIndex(oldestPendingFrame)
                + ", tlasDescriptor=0x" + Long.toHexString(descriptorGenerationState.worldTlas())
                + ", bootstrapDispatches=" + bootstrapDispatches
                + ", bootstrapDispatch=" + bootstrapDispatchWidth + "x" + bootstrapDispatchHeight
                + ", frameDispatches=" + dispatchStatistics.frameDispatches()
                + ", observedFrameStates=" + dispatchStatistics.observedFrameStates()
                + ", skippedPresentationGateFrames=" + dispatchStatistics.skippedPresentationGateFrames()
                + ", skippedUnavailableFrameStates=" + dispatchStatistics.skippedUnavailableFrameStates()
                + ", skippedIntervalFrames=" + dispatchStatistics.skippedIntervalFrames()
                + ", skippedPendingAsyncFrames=" + dispatchStatistics.skippedPendingAsyncFrames()
                + ", skippedPendingBacklogFrames=" + dispatchStatistics.skippedPendingBacklogFrames()
                + ", freshnessCatchUpFrameDispatches=" + dispatchStatistics.freshnessCatchUpFrameDispatches()
                + ", frameDispatchInterval=" + frameDispatchInterval
                + ", frameReadbackInterval=" + frameReadbackInterval
                + ", maxPendingFrameSubmissions=" + maxPendingFrameSubmissions
                + ", frameReadbackEnabled=" + frameReadbackEnabled
                + ", frameReadbacks=" + completedFrames.frameReadbacks()
                + ", skippedReadbackIntervalFrames=" + dispatchStatistics.skippedReadbackIntervalFrames()
                + ", skippedNoFrameSlots=" + dispatchStatistics.skippedNoFrameSlots()
                + ", dynamicSceneUploads=" + dynamicSceneFront.uploads()
                + ", dynamicSceneRevisionChanges=" + dynamicSceneFront.revisionChanges()
                + ", latestDynamicSceneRevision=" + dynamicSceneFront.revision()
                + ", latestDynamicSceneElements=" + dynamicSceneFront.elements()
                + ", asyncFrameSubmissions=" + dispatchStatistics.asyncFrameSubmissions()
                + ", asyncFrameCompletions=" + dispatchStatistics.asyncFrameCompletions()
                + ", asyncFramePollsNotReady=" + frameCompletionPoller.pollsNotReady()
                + ", coalescedFrameCompletionPolls=" + frameCompletionPoller.coalescedPolls()
                + ", asyncFrameCloseWaits=" + frameCompletionPoller.closeWaits()
                + ", pendingFrame=" + (oldestPendingFrame != null)
                + ", pendingFrameCount=" + frameCompletionPoller.size()
                + ", pendingFrameSequence=" + (oldestPendingFrame == null
                ? -1L
                : oldestPendingFrame.frameStateSequence())
                + ", pendingFrameAgeMillis=" + (oldestPendingFrame == null
                ? 0L
                : pendingFrameAgeMillis(oldestPendingFrame, System.nanoTime()))
                + ", pendingFramePolls=" + (oldestPendingFrame == null ? 0L : oldestPendingFrame.polls())
                + ", pendingFrameObservedLag=" + (oldestPendingFrame == null
                ? 0L
                : dispatchStatistics.observedFrameStates() - oldestPendingFrame.observedFrameStatesAtSubmit())
                + ", lastPendingFramePolls=" + frameCompletionPoller.lastCompletedPolls()
                + ", maxPendingFramePolls=" + frameCompletionPoller.maxPolls()
                + ", lastPendingFrameAgeMillis=" + frameCompletionPoller.lastAgeMillis()
                + ", maxPendingFrameAgeMillis=" + frameCompletionPoller.maxAgeMillis()
                + ", frameDispatchRequiresPresentationEligibility=" + frameDispatchRequiresPresentationEligibility
                + ", " + sharedFrames.outputResourceSummary()
                + ", " + frameOutputConfig.summary("frameOutputConfig")
                + ", lastFrameDispatchMillis=" + dispatchStatistics.lastFrameDispatchMillis()
                + ", maxFrameDispatchMillis=" + dispatchStatistics.maxFrameDispatchMillis()
                + ", totalFrameDispatchMillis=" + dispatchStatistics.totalFrameDispatchMillis()
                + ", latestCompletedFrameStateSequence=" + completedFrames.frameStateSequence()
                + ", " + sharedFrames.publicationSummary()
                + ", lastFrameReadback=" + (completedFrames.frameReadback() == null
                ? "none" : completedFrames.frameReadback().asLogFragment())
                + ", " + bootstrapReadback.summary("bootstrapReadback")
                + ", " + shaderBindingTable.bufferSummary()
                + "}";
    }

    /**
     * Rejects new work, drains submitted frames, and releases all native pipeline resources.
     *
     * @throws RuntimeException if draining or releasing an owned resource fails
     */
    @Override
    public synchronized void close() {
        if (resourcesClosed) {
            return;
        }
        /* Reject new work immediately, while retaining native ownership until all fences retire. */
        closed = true;
        sharedFrames.resetPublications();
        pollPendingFrameSubmissions(true);
        RuntimeException failure = null;
        if (!pipelineDestroyed) {
            VK10.vkDestroyPipeline(device, pipeline, null);
            pipelineDestroyed = true;
        }
        if (!pipelineLayoutDestroyed) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayoutDestroyed = true;
        }
        if (!descriptorPoolDestroyed) {
            VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPoolDestroyed = true;
        }
        if (!descriptorSetLayoutDestroyed) {
            VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayoutDestroyed = true;
        }
        failure = closeCollecting(failure, shaderBindingTable);
        failure = closeCollecting(failure, frameUniformBuffer);
        failure = closeCollecting(failure, sharedFrames);
        if (failure != null) {
            throw failure;
        }
        resourcesClosed = true;
    }

    private void pollPendingFrameSubmissions(boolean wait) {
        frameCompletionPoller.poll(wait, this::completePendingFrameSubmission);
    }

    private void pollPendingFrameSubmissionsForActiveFrame() {
        frameCompletionPoller.pollForActiveFrame(
                frameReadbackEnabled, this::completePendingFrameSubmission
        );
    }

    private RtPendingFrameSubmission oldestPendingFrameWork() {
        return frameCompletionPoller.oldest();
    }

    private void completePendingFrameSubmission(RtPendingFrameSubmission pending) {
        dispatchStatistics.recordCompletion();
        RtFrameCompletionPublisher.Completion completion = frameCompletionPublisher.publish(pending);
        completedFrames.accept(pending, completion);
        sharedFrames.acceptCompletedSignal(pending.signalSemaphore());
    }

    private boolean shouldSkipFrameDispatchByInterval(long frameStateSequence, boolean presentationEligible) {
        if ((dispatchStatistics.observedFrameStates() - 1L) % frameDispatchInterval == 0L) {
            return false;
        }
        if (shouldBypassFrameDispatchInterval(presentationEligible)) {
            return false;
        }
        if (shouldDispatchForPresentationFreshness(
                frameStateSequence,
                completedFrames.frameStateSequence(),
                newestPendingFrameStateSequence(),
                frameDispatchInterval
        )) {
            dispatchStatistics.recordFreshnessCatchUpDispatch();
            return false;
        }
        return true;
    }

    private long newestPendingFrameStateSequence() {
        return frameCompletionPoller.newestFrameStateSequence();
    }

    private boolean pendingWritesImage(RtGpuImage image) {
        return frameCompletionPoller.writesImage(image);
    }

    private DynamicSceneUploadTiming recordDynamicSceneUpload(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtPipelineFrameSlot frameSlot,
            DynamicRenderScene dynamicScene,
            RendererFrameState frameState
    ) {
        Objects.requireNonNull(frameSlot, "frameSlot");
        Objects.requireNonNull(dynamicScene, "dynamicScene");
        long packStartNanos = System.nanoTime();
        RtDynamicSceneUploadEncoder.Packet packedScene =
                dynamicSceneEncoder.encode(dynamicScene, frameState, frameSlot.dynamicSceneStaging);
        List<UploadRange> dirtyRanges = dynamicSceneDirtyUploadRangesWithinCandidates(
                frameSlot.committedDynamicSceneBytes(),
                packedScene.bytes(),
                frameSlot.dynamicSceneInitialized(),
                packedScene.candidateRanges()
        );
        frameSlot.dynamicSceneUploadRecorded(!dirtyRanges.isEmpty());
        dynamicSceneUploadTelemetry.record(dirtyRanges);
        long packNanos = System.nanoTime() - packStartNanos;
        long commandStartNanos = System.nanoTime();
        recordByteArrayRangesUpload(
                commandBuffer,
                frameSlot.dynamicSceneBuffer().buffer(),
                packedScene.bytes(),
                dirtyRanges
        );
        RtFrameDispatchCommands.recordMemoryBarrier(
                commandBuffer,
                stack,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
        );
        return new DynamicSceneUploadTiming(packNanos, System.nanoTime() - commandStartNanos);
    }

    private void recordFrameDispatchTelemetry(long elapsedNanos) {
        dispatchStatistics.recordDispatchDuration(elapsedNanos);
    }

    /**
     * Reason tile indexing fell back to a full particle scan.
     */
    public enum ParticleTileFallback {
        /**
         * Tile index is valid.
         */
        NONE,
        /**
         * Frame projection data was unavailable or invalid.
         */
        INVALID_FRAME,
        /**
         * A particle intersected the near plane and could not be bounded safely.
         */
        NEAR_PLANE_INTERSECTION,
        /**
         * Fixed reference storage would overflow.
         */
        REFERENCE_CAPACITY
    }

    /**
     * Aligned byte range for a partial dynamic-scene upload.
     *
     * @param offsetBytes non-negative four-byte-aligned offset
     * @param byteCount   positive four-byte-aligned length
     */
    public record UploadRange(int offsetBytes, int byteCount) {
        /**
         * Validates positive four-byte-aligned extent.
         */
        public UploadRange {
            if (offsetBytes < 0 || byteCount <= 0 || (offsetBytes & 3) != 0 || (byteCount & 3) != 0) {
                throw new IllegalArgumentException("upload ranges must be positive and 4-byte aligned");
            }
        }

        int endOffsetBytes() {
            return Math.addExact(offsetBytes, byteCount);
        }
    }

    private record DynamicSceneUploadTiming(long packNanos, long commandNanos) {
    }

    /**
     * Immutable particle-tile offsets, counts, references, and fallback evidence.
     *
     * @param offsets    per-tile reference offsets
     * @param counts     per-tile reference counts
     * @param references flattened particle indices
     * @param fallback   fallback reason
     */
    public record ParticleTileIndex(
            int[] offsets,
            int[] counts,
            int[] references,
            ParticleTileFallback fallback
    ) implements RtParticleTilePlanner.Metrics {
        private static final ParticleTileIndex EMPTY = new ParticleTileIndex(
                new int[PARTICLE_TILE_COUNT], new int[PARTICLE_TILE_COUNT], new int[0], ParticleTileFallback.NONE
        );

        /**
         * Defensively captures arrays and validates tile ranges.
         */
        public ParticleTileIndex {
            Objects.requireNonNull(fallback, "fallback");
            offsets = offsets.clone();
            counts = counts.clone();
            references = references.clone();
        }

        static ParticleTileIndex empty() {
            return EMPTY;
        }

        static ParticleTileIndex fullScan(ParticleTileFallback fallback) {
            if (fallback == ParticleTileFallback.NONE) {
                throw new IllegalArgumentException("full scan requires a failure reason");
            }
            return new ParticleTileIndex(
                    new int[PARTICLE_TILE_COUNT], new int[PARTICLE_TILE_COUNT], new int[0], fallback
            );
        }

        /**
         * Reports whether particle dispatch must ignore the tile reference index.
         *
         * @return {@code true} when the index records a full-scan fallback reason
         */
        public boolean fallbackToFullScan() {
            return fallback != ParticleTileFallback.NONE;
        }

        /**
         * Returns the number of particle references stored in the tile index.
         *
         * @return reference count
         */
        public int referenceCount() {
            return references.length;
        }
    }

    /**
     * Result of atomically binding a descriptor generation.
     *
     * @param previousGeneration generation visible before binding
     * @param activeGeneration   generation visible after binding
     * @param advanced           whether a new generation was committed
     */
    public record DescriptorGenerationBinding(
            long previousGeneration,
            long activeGeneration,
            boolean advanced
    ) {
        /**
         * Validates positive monotonic generation state.
         */
        public DescriptorGenerationBinding {
            if (previousGeneration <= 0L || activeGeneration <= 0L) {
                throw new IllegalArgumentException("descriptor generations must be positive");
            }
            if (advanced != (activeGeneration > previousGeneration)) {
                throw new IllegalArgumentException("descriptor generation transition is inconsistent");
            }
        }

        private static DescriptorGenerationBinding unchanged(long activeGeneration) {
            return new DescriptorGenerationBinding(activeGeneration, activeGeneration, false);
        }
    }

}
