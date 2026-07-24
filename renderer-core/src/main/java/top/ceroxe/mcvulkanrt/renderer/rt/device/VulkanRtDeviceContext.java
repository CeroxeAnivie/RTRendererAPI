package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork;
import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.NativeTerrainOwnership;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicBlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicResidencyState;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicEntityDebugState;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtSectionDebugState;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtFarFieldBlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtSectionBlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipeline;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipelineProperties;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkOffset3D;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCommitPlan;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameSubmission;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.DynamicModelIdentity;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.SectionLifecycleFlightRecorder;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Native Vulkan device context for RTCore.
 *
 * <p>The production path borrows host's already-created Vulkan device,
 * graphics queue and VMA allocator. It owns only RT child resources. This is
 * required because a Vulkan image can only be copied into host's render
 * target when both images belong to the same {@code VkDevice}.</p>
 */
public final class VulkanRtDeviceContext implements GuardedRtCore.NativeBackend {
    private static final long BOOTSTRAP_BUFFER_BYTES = 4096L;
    private static final long INITIAL_WORLD_TLAS_BIND_LOGS = 4L;
    private static final long DEFAULT_WORLD_TLAS_STATUS_LOG_INTERVAL = 10_000L;
    private static final long INITIAL_WORLD_TLAS_DISPATCH_LOGS = 4L;
    private static final long DEFAULT_MAX_PENDING_FRAME_AGE_BEFORE_BUILD_MILLIS = 32L;
    private static final long DEFAULT_MAX_CONVERGENCE_VISUAL_STALENESS_MILLIS = 50L;
    private static final long DEFAULT_MIN_STREAMING_SCENE_BIND_INTERVAL_MILLIS = 16L;
    private static final long DEFAULT_MAX_STREAMING_SCENE_BIND_DEFERRALS = 4L;
    private static final long DEFAULT_IMMEDIATE_STREAMING_BIND_MAX_FACES = 100_000L;
    private static final long DEFAULT_INTERACTIVE_MUTATION_RADIUS_SECTIONS = 2L;
    private static final long DEFAULT_MAX_INTERACTIVE_MUTATION_SECTIONS = 12L;
    private static final long DEFAULT_FOREGROUND_FRAME_BUDGET_MICROS = 500L;
    /* Causality-only diagnostic threshold; normal frames never format this record. */
    private static final long SLOW_PRE_BUILD_DIAGNOSTIC_NANOS = 2_000_000L;
    private static final long SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS = 1_000_000_000L;
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean TAKEOVER_FLIGHT_RECORDER_ENABLED =
            Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final String MAX_PENDING_FRAME_AGE_BEFORE_BUILD_MILLIS_PROPERTY =
            "mcvulkanrt.rt.scheduler.maxPendingFrameAgeBeforeBuildMillis";
    private static final String MAX_CONVERGENCE_VISUAL_STALENESS_MILLIS_PROPERTY =
            "mcvulkanrt.rt.scheduler.maxConvergenceVisualStalenessMillis";
    private static final String MIN_STREAMING_SCENE_BIND_INTERVAL_MILLIS_PROPERTY =
            "mcvulkanrt.rt.scheduler.minStreamingSceneBindIntervalMillis";
    private static final String MAX_STREAMING_SCENE_BIND_DEFERRALS_PROPERTY =
            "mcvulkanrt.rt.scheduler.maxStreamingSceneBindDeferrals";
    private static final String IMMEDIATE_STREAMING_BIND_MAX_FACES_PROPERTY =
            "mcvulkanrt.rt.scheduler.immediateStreamingBindMaxFaces";
    private static final String INTERACTIVE_MUTATION_RADIUS_SECTIONS_PROPERTY =
            "mcvulkanrt.rt.scheduler.interactiveMutationRadiusSections";
    private static final String MAX_INTERACTIVE_MUTATION_SECTIONS_PROPERTY =
            "mcvulkanrt.rt.scheduler.maxInteractiveMutationSections";
    private static final String FOREGROUND_FRAME_BUDGET_MICROS_PROPERTY =
            "mcvulkanrt.rt.scheduler.foregroundFrameBudgetMicros";
    private static final String WORLD_TLAS_STATUS_LOG_INTERVAL_PROPERTY =
            "mcvulkanrt.telemetry.worldTlasStatusLogInterval";
    private static final long WORLD_TLAS_STATUS_LOG_INTERVAL = positiveLongProperty(
            WORLD_TLAS_STATUS_LOG_INTERVAL_PROPERTY,
            DEFAULT_WORLD_TLAS_STATUS_LOG_INTERVAL
    );

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final RtDeviceQueueContexts queueContexts;
    private final long allocator;
    private final RtGpuBuffer bootstrapBuffer;
    private final RtAccelerationStructure bootstrapBlas;
    private final RtAccelerationStructure bootstrapTlas;
    private final RtSceneMaterialTable sceneMaterialTable;
    private final RtSectionBlasCache sectionBlasCache;
    private final RtDynamicBlasCache dynamicBlasCache;
    private final RtDynamicTlasCache dynamicTlasCache;
    private final RtWorldTlasCache worldTlasCache;
    private final RtRayTracingPipelineProperties rayTracingPipelineProperties;
    private final RtRayTracingPipeline rayTracingPipeline;
    private final String deviceName;
    private final int accelerationStructureScratchAlignment;
    private final List<String> enabledExtensions;
    private final RtExternalInteropCapabilities interopCapabilities;
    private final long maxPendingFrameAgeBeforeBuildMillis =
            positiveLongProperty(
                    MAX_PENDING_FRAME_AGE_BEFORE_BUILD_MILLIS_PROPERTY,
                    DEFAULT_MAX_PENDING_FRAME_AGE_BEFORE_BUILD_MILLIS
            );
    private final long maxConvergenceVisualStalenessNanos =
            positiveLongProperty(
                    MAX_CONVERGENCE_VISUAL_STALENESS_MILLIS_PROPERTY,
                    DEFAULT_MAX_CONVERGENCE_VISUAL_STALENESS_MILLIS
            ) * 1_000_000L;
    private final long minStreamingSceneBindIntervalNanos =
            positiveLongProperty(
                    MIN_STREAMING_SCENE_BIND_INTERVAL_MILLIS_PROPERTY,
                    DEFAULT_MIN_STREAMING_SCENE_BIND_INTERVAL_MILLIS
            ) * 1_000_000L;
    private final long maxStreamingSceneBindDeferrals =
            positiveLongProperty(
                    MAX_STREAMING_SCENE_BIND_DEFERRALS_PROPERTY,
                    DEFAULT_MAX_STREAMING_SCENE_BIND_DEFERRALS
            );
    private final long immediateStreamingBindMaxFaces =
            positiveLongProperty(
                    IMMEDIATE_STREAMING_BIND_MAX_FACES_PROPERTY,
                    DEFAULT_IMMEDIATE_STREAMING_BIND_MAX_FACES
            );
    private final long interactiveMutationRadiusSections =
            positiveLongProperty(
                    INTERACTIVE_MUTATION_RADIUS_SECTIONS_PROPERTY,
                    DEFAULT_INTERACTIVE_MUTATION_RADIUS_SECTIONS
            );
    private final long maxInteractiveMutationSections =
            positiveLongProperty(
                    MAX_INTERACTIVE_MUTATION_SECTIONS_PROPERTY,
                    DEFAULT_MAX_INTERACTIVE_MUTATION_SECTIONS
            );
    private final long foregroundFrameBudgetNanos =
            positiveLongProperty(
                    FOREGROUND_FRAME_BUDGET_MICROS_PROPERTY,
                    DEFAULT_FOREGROUND_FRAME_BUDGET_MICROS
            ) * 1_000L;
    private final RtSceneBindCoordinator sceneBindCoordinator;
    private final RtDynamicSceneBindLane dynamicSceneBindLane;
    private final RtWorldSceneMaterialUploadLane worldSceneMaterialUploadLane;
    private RendererFrameCausality latestTerrainCausality = RendererFrameCausality.untraced(0L);
    private RendererFrameCausality latestDynamicCausality = RendererFrameCausality.untraced(0L);
    private long latestViewRevision = -1L;
    /*
     * Explicit scene-removal provenance survives the asynchronous TLAS build
     * and descriptor-bind boundary. A mere set contraction is never deletion
     * evidence: successor views legitimately omit old front sections while
     * they are still required for fail-closed presentation.
     */
    private long worldCoverageContractionAuthorizationGeneration;
    private long boundWorldCoverageContractionAuthorizationGeneration;
    private long committedFrontInvalidationSectionRevision;
    private long boundCommittedFrontSectionRevision;
    private final RtInteractiveTerrainPublicationTracker interactiveTerrain =
            new RtInteractiveTerrainPublicationTracker();
    private final RtWorldSceneBindStatistics worldSceneBindStatistics = new RtWorldSceneBindStatistics();
    private long nextStreamingSceneBindNanos;
    private long frameFirstBuildBudgetDeferrals;
    private long framePendingBuildBudgetDeferrals;
    private long foregroundFrameBudgetDeferrals;
    private long foregroundFrameFirstDispatches;
    private long foregroundFrameCoverageDeferrals;
    private long stableFrameFastPathDispatches;
    private long stableFramePendingGpuSkips;
    private long loggedWorldTlasBinds;
    private long lastLoggedWorldTlasDispatches;
    private long dynamicWorldTlasDispatchBlocks;
    private long lastObservedBlockMutationMarks;
    private long lastObservedChunkPacketReplacementMarks;
    private long lastObservedChunkSnapshotReplacements;
    private long lastObservedChunkUnloadMarks;
    private long lastObservedSectionSnapshotRemovals;
    private String lastDynamicWorldTlasDispatchBlockReason = "none";
    private long lastSlowPreBuildDiagnosticNanos;
    private long lastSlowPreBuildPolicyDiagnosticNanos;
    private final VulkanAcceptFrameTiming acceptFrameTiming;
    private final VulkanAcceptFrameStallRecorder acceptFrameStallRecorder = new VulkanAcceptFrameStallRecorder();
    private final RendererRtDiagnostics diagnostics;

    private VulkanRtDeviceContext(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            RtDeviceQueueContexts queueContexts,
            long allocator,
            RtGpuBuffer bootstrapBuffer,
            RtAccelerationStructure bootstrapBlas,
            RtAccelerationStructure bootstrapTlas,
            RtSceneMaterialTable sceneMaterialTable,
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache,
            RtDynamicTlasCache dynamicTlasCache,
            RtWorldTlasCache worldTlasCache,
            RtRayTracingPipelineProperties rayTracingPipelineProperties,
            RtRayTracingPipeline rayTracingPipeline,
            String deviceName,
            int accelerationStructureScratchAlignment,
            List<String> enabledExtensions,
            RtExternalInteropCapabilities interopCapabilities,
            RendererRtDiagnostics diagnostics
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.physicalDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        this.queueContexts = Objects.requireNonNull(queueContexts, "queueContexts");
        this.allocator = allocator;
        this.bootstrapBuffer = Objects.requireNonNull(bootstrapBuffer, "bootstrapBuffer");
        this.bootstrapBlas = Objects.requireNonNull(bootstrapBlas, "bootstrapBlas");
        this.bootstrapTlas = Objects.requireNonNull(bootstrapTlas, "bootstrapTlas");
        this.sceneMaterialTable = Objects.requireNonNull(sceneMaterialTable, "sceneMaterialTable");
        this.sectionBlasCache = Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        this.dynamicBlasCache = Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        this.dynamicTlasCache = Objects.requireNonNull(dynamicTlasCache, "dynamicTlasCache");
        this.worldTlasCache = Objects.requireNonNull(worldTlasCache, "worldTlasCache");
        this.rayTracingPipelineProperties = Objects.requireNonNull(rayTracingPipelineProperties, "rayTracingPipelineProperties");
        this.rayTracingPipeline = Objects.requireNonNull(rayTracingPipeline, "rayTracingPipeline");
        this.sceneBindCoordinator = new RtSceneBindCoordinator(RtScenePublication.bootstrap(
                bootstrapTlas,
                rayTracingPipeline.activeDescriptorGeneration(),
                diagnostics.causality()
        ));
        this.deviceName = Objects.requireNonNull(deviceName, "deviceName");
        this.accelerationStructureScratchAlignment = accelerationStructureScratchAlignment;
        this.enabledExtensions = List.copyOf(enabledExtensions);
        this.interopCapabilities = Objects.requireNonNull(interopCapabilities, "interopCapabilities");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.acceptFrameTiming = new VulkanAcceptFrameTiming(diagnostics.builds());
        this.dynamicSceneBindLane = new RtDynamicSceneBindLane(
                dynamicBlasCache,
                dynamicTlasCache,
                worldTlasCache,
                rayTracingPipeline,
                sceneMaterialTable,
                queueContexts,
                sceneBindCoordinator,
                diagnostics,
                maxConvergenceVisualStalenessNanos
        );
        this.worldSceneMaterialUploadLane = new RtWorldSceneMaterialUploadLane(
                sceneBindCoordinator,
                worldTlasCache,
                sectionBlasCache,
                dynamicBlasCache,
                sceneMaterialTable,
                queueContexts.buildCommands(),
                diagnostics,
                worldSceneBindStatistics
        );
    }

    public static VulkanRtDeviceContext open(VulkanRtCapabilityProbe.Result capability, RtResourceScope parentScope) {
        return open(capability, parentScope, RendererRtDiagnostics.noop());
    }

    /**
     * Opens a renderer-owned Vulkan instance, logical device, queues, allocator, and RT resources.
     * The core never borrows an embedding application's device or command encoder.
     */
    public static VulkanRtDeviceContext open(
            VulkanRtCapabilityProbe.Result capability,
            RtResourceScope parentScope,
            RendererRtDiagnostics diagnostics
    ) {
        return openIndependentUnsafe(capability, parentScope, diagnostics);
    }

    /** Independent device entry retained under its historical name for diagnostic callers. */
    public static VulkanRtDeviceContext openIndependentUnsafe(VulkanRtCapabilityProbe.Result capability, RtResourceScope parentScope) {
        return openIndependentUnsafe(capability, parentScope, RendererRtDiagnostics.noop());
    }

    public static VulkanRtDeviceContext openIndependentUnsafe(
            VulkanRtCapabilityProbe.Result capability,
            RtResourceScope parentScope,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(parentScope, "parentScope");
        Objects.requireNonNull(diagnostics, "diagnostics");
        RtResourceScope contextScope = new RtResourceScope();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RtVulkanDeviceBootstrap bootstrap = RtVulkanDeviceBootstrap.open(capability);
            contextScope.retain("vulkan device bootstrap", bootstrap);
            VkDevice device = bootstrap.device();
            VkPhysicalDevice physicalDevice = bootstrap.physicalDevice();
            long allocator = bootstrap.allocator();
            int scratchAlignment = bootstrap.accelerationStructureScratchAlignment();
            List<String> enabledExtensions = bootstrap.enabledExtensions();
            RtRayTracingPipelineProperties rayTracingPipelineProperties =
                    RtRayTracingPipelineProperties.query(stack, physicalDevice);
            RtExternalInteropCapabilities interopCapabilities = RtExternalInteropCapabilities.create(
                    stack,
                    physicalDevice,
                    device,
                    bootstrap.properties().apiVersion(),
                    enabledExtensions
            );

            RtDeviceQueueContexts queueContexts = RtDeviceQueueContexts.create(
                    stack,
                    physicalDevice,
                    device,
                    bootstrap.queueFamilyIndex(),
                    bootstrap.requestedQueueCount(),
                    diagnostics.stalls()
            );
            contextScope.retain("rt device queue contexts", queueContexts);
            RtCommandContext frameCommandContext = queueContexts.frameCommands();
            RtCommandContext buildCommandContext = queueContexts.buildCommands();
            RtCommandContext sectionBlasCommandContext = queueContexts.sectionBlasCommands();

            RtGpuBuffer bootstrapBuffer = RtGpuBuffer.createDeviceAddressBuffer(
                    device,
                    allocator,
                    BOOTSTRAP_BUFFER_BYTES,
                    bootstrapBufferUsageFlags(),
                    diagnostics.stalls()
            );
            contextScope.retain("rt bootstrap device-address buffer", bootstrapBuffer);

            RtAccelerationStructure bootstrapBlas = RtAccelerationStructure.buildBootstrapTriangleBlas(
                    device,
                    allocator,
                    buildCommandContext,
                    scratchAlignment
            );
            contextScope.retain("rt bootstrap blas", bootstrapBlas);
            RtAccelerationStructure bootstrapTlas = RtAccelerationStructure.buildBootstrapTlas(
                    device,
                    allocator,
                    buildCommandContext,
                    scratchAlignment,
                    bootstrapBlas
            );
            contextScope.retain("rt bootstrap tlas", bootstrapTlas);
            RtSceneMaterialTable sceneMaterialTable = new RtSceneMaterialTable(
                    device, allocator, diagnostics.materials(), diagnostics.stalls());
            sceneMaterialTable.upload(buildCommandContext, RtSceneMaterialTable.bootstrapSnapshot());
            contextScope.retain("rt scene material table", sceneMaterialTable);
            RtSectionBlasCache sectionBlasCache = new RtSectionBlasCache(
                    device,
                    allocator,
                    sectionBlasCommandContext,
                    scratchAlignment,
                    diagnostics
            );
            contextScope.retain("rt section blas cache", sectionBlasCache);
            RtDynamicBlasCache dynamicBlasCache = new RtDynamicBlasCache(
                    device,
                    allocator,
                    buildCommandContext,
                    scratchAlignment,
                    bootstrapBlas.deviceAddress(),
                    diagnostics
            );
            contextScope.retain("rt dynamic blas cache", dynamicBlasCache);
            RtDynamicTlasCache dynamicTlasCache = new RtDynamicTlasCache(
                    device,
                    allocator,
                    buildCommandContext,
                    scratchAlignment,
                    bootstrapBlas.deviceAddress()
            );
            contextScope.retain("rt dynamic tlas cache", dynamicTlasCache);
            RtWorldTlasCache worldTlasCache = new RtWorldTlasCache(
                    device,
                    allocator,
                    buildCommandContext,
                    scratchAlignment,
                    bootstrapBlas.deviceAddress(),
                    diagnostics
            );
            contextScope.retain("rt world tlas cache", worldTlasCache);
            RtRayTracingPipeline rayTracingPipeline = RtRayTracingPipeline.create(
                    device,
                    physicalDevice,
                    allocator,
                    sharedPresentationReady(
                            interopCapabilities.gpuInteropCandidate(),
                            interopCapabilities.memoryProbe().successful(),
                            interopCapabilities.semaphoreProbe().successful()
                    ),
                    interopCapabilities.dedicatedOnly(),
                    frameCommandContext,
                    bootstrapTlas,
                    sceneMaterialTable,
                    rayTracingPipelineProperties,
                    diagnostics
            );
            contextScope.retain("rt ray tracing pipeline", rayTracingPipeline);
            contextScope.retain("rt queues idle before native resource destroy", queueContexts::waitForIdle);

            VulkanRtDeviceContext context = new VulkanRtDeviceContext(
                    device,
                    physicalDevice,
                    queueContexts,
                    allocator,
                    bootstrapBuffer,
                    bootstrapBlas,
                    bootstrapTlas,
                    sceneMaterialTable,
                    sectionBlasCache,
                    dynamicBlasCache,
                    dynamicTlasCache,
                    worldTlasCache,
                    rayTracingPipelineProperties,
                    rayTracingPipeline,
                    bootstrap.properties().deviceNameString(),
                    scratchAlignment,
                    enabledExtensions,
                    interopCapabilities,
                    diagnostics
            );
            contextScope.retain("pending world scene bind", context::closePendingWorldSceneBind);
            contextScope.retain("pending material-only bind", context::closePendingMaterialOnlyBind);
            contextScope.retain("pending dynamic TLAS bind", context::closePendingDynamicTlasBind);
            contextScope.retain("deferred world scene bind", context::closeDeferredWorldSceneBind);
            parentScope.retain("vulkan RT device context", contextScope);
            return context;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            contextScope.close();
            throw ex;
        }
    }

    @Override
    public void acceptViewState(RendererViewState viewState) {
        acceptViewState(viewState, Set.of());
    }

    @Override
    public void acceptViewState(
            RendererViewState viewState,
            Set<SectionKey> retainedPresentationSections
    ) {
        acceptForegroundWork(RendererForegroundWork.untraced(viewState, retainedPresentationSections));
    }

    @Override
    public void acceptForegroundWork(RendererForegroundWork work) {
        RendererForegroundWork acceptedWork = Objects.requireNonNull(work, "work");
        latestViewRevision = acceptedWork.authorityRevision();
        sectionBlasCache.acceptForegroundWork(acceptedWork);
    }

    @Override
    public void acceptFrameUpdate(RendererFrameUpdate update) {
        acceptFrameSubmission(RendererFrameSubmission.untraced(update));
    }

    @Override
    public void acceptFrameSubmission(RendererFrameSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        RendererFrameUpdate update = submission.update();
        RendererFrameCausality causality = submission.causality();
        long acceptStartNanos = System.nanoTime();
        acceptFrameTiming.beginFrame();
        long stageStartNanos = acceptStartNanos;
        long detailStartNanos;
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        boolean invalidatesCommittedFront = RtCommittedFrontPolicy.invalidatesCommittedFront(
                update,
                sceneBindCoordinator.publication().worldSectionKeys()
        );
        if (commitPlan.fullResyncRequested()) {
            interactiveTerrain.clear();
        }
        if (commitPlan.fullResyncRequested()
                || !commitPlan.removedSections().isEmpty()
                || !commitPlan.unloadedChunks().isEmpty()) {
            worldCoverageContractionAuthorizationGeneration = Math.incrementExact(
                    worldCoverageContractionAuthorizationGeneration
            );
        }
        detailStartNanos = System.nanoTime();
        rayTracingPipeline.beginFrameCompletionPoll(update.frameState().sequence());
        acceptFrameTiming.framePoll.record(System.nanoTime() - detailStartNanos);
        if (update.hasDynamicSceneUpdate()) {
            latestDynamicCausality = causality;
            detailStartNanos = System.nanoTime();
            rayTracingPipeline.acceptDynamicSceneUpdate(update.dynamicScene());
            acceptFrameTiming.dynamicPipelineIngest.record(System.nanoTime() - detailStartNanos);
            detailStartNanos = System.nanoTime();
            dynamicBlasCache.acceptDynamicScene(update.dynamicScene(), causality);
            acceptFrameTiming.dynamicBlasIngest.record(System.nanoTime() - detailStartNanos);
            acceptFrameTiming.recordDynamicLaneShadow(
                    sceneBindCoordinator.publication().worldTlas() != null,
                    worldTlasCache.boundDynamicRevision(),
                    worldTlasCache.boundDynamicTopologyRevision(),
                    worldTlasCache.boundDynamicGeometryRevision(),
                    dynamicBlasCache.revision(),
                    dynamicBlasCache.topologyRevision(),
                    dynamicBlasCache.geometryRevision()
            );
        }
        if (commitPlan.hasTerrainWork()) {
            latestTerrainCausality = causality;
            detailStartNanos = System.nanoTime();
            sectionBlasCache.enqueue(
                    commitPlan.sectionMeshes(),
                    commitPlan.sectionContentRevisions(),
                    commitPlan.sectionSourceFlags(),
                    commitPlan.removedSections(),
                    commitPlan.fullResyncRequested(),
                    latestTerrainCausality
            );
            captureInteractiveTerrainTargets(update);
            acceptFrameTiming.sectionEnqueue.record(System.nanoTime() - detailStartNanos);
            if (invalidatesCommittedFront) {
                committedFrontInvalidationSectionRevision = Math.max(
                        committedFrontInvalidationSectionRevision,
                        sectionBlasCache.snapshotTlasBuildStats().revision()
                );
            }
        }
        if (RtInteractiveTerrainUpdatePolicy.shouldPreserveCurrentWorldTlasForAcceptedTerrainUpdate(
                update,
                interactiveMutationRadiusSections,
                maxInteractiveMutationSections
        )
                && !hasPendingInteractiveTerrainTargets()) {
            interactiveTerrain.markUrgent("acceptedInteractiveTerrain");
        }
        acceptFrameTiming.ingest.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        detailStartNanos = System.nanoTime();
        boolean worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        acceptFrameTiming.descriptorPoll.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        acceptFrameTiming.preBuildRetirement.record(System.nanoTime() - detailStartNanos);
        /*
         * Completed section BLAS builds hold the ordered Vulkan submission window until their
         * results are applied. Presentation pacing below may return early, so this maintenance
         * phase must run before every such return while remaining forbidden from admitting new
         * recording or GPU work.
         */
        detailStartNanos = System.nanoTime();
        sectionBlasCache.pumpCompletedAsyncBuilds();
        acceptFrameTiming.buildSectionCompletionPump.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        boolean completedMaterialOnlyThisFrame =
                completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        sceneBindCoordinator.completePendingDynamicTlasBindIfReady(
                worldSceneDescriptorsCanBeUpdated,
                rayTracingPipeline,
                sceneMaterialTable,
                dynamicTlasCache,
                worldTlasCache,
                diagnostics
        );
        long pendingBindCompletionNanos = System.nanoTime() - detailStartNanos;
        /*
         * A deferred world publication represents terrain work that has already waited behind a
         * descriptor owner. Give it the first opportunity to acquire the now-free commit token.
         * Replenishing the continuously changing dynamic lane first can otherwise starve terrain
         * forever; attempting both transactions is worse because it violates atomic descriptor
         * publication and makes the guarded backend fail closed.
         */
        detailStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        long deferredBindSubmitNanos = System.nanoTime() - detailStartNanos;
        detailStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        advanceDynamicTlasLane(
                worldSceneDescriptorsCanBeUpdated,
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(observedSceneReadiness()),
                latestDynamicCausality
        );
        acceptFrameTiming.pendingBindCompletion.record(
                pendingBindCompletionNanos + System.nanoTime() - detailStartNanos
        );
        acceptFrameTiming.deferredBindSubmit.record(deferredBindSubmitNanos);
        if (textureCatalogRevisionOutranBoundMaterialSnapshot(sceneBindCoordinator.publication().materialSnapshot(), RtTextureCatalog.revision())) {
            detailStartNanos = System.nanoTime();
            submitMaterialOnlyUploadIfNeeded(
                    update,
                    rayTracingPipeline.canUpdateMaterialBuffersInPlace(),
                    causality
            );
            completedMaterialOnlyThisFrame |= completePendingMaterialOnlyBindIfReady(
                    update,
                    rayTracingPipeline.canUpdateWorldSceneDescriptors()
            );
            acceptFrameTiming.textureCatchUp.record(System.nanoTime() - detailStartNanos);
        }
        detailStartNanos = System.nanoTime();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        acceptFrameTiming.preBuildRetirement.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean skipStableFrame = shouldSkipStableFrameWhileGpuFramePending(update);
        acceptFrameTiming.stableBackpressure.record(System.nanoTime() - detailStartNanos);
        if (skipStableFrame) {
            recordNativeDispatchDecision(update, "accept", "stableFrameGpuBackpressure");
            finishAcceptStage(acceptFrameTiming.preBuild, stageStartNanos, acceptStartNanos);
            maybeLogSlowPreBuildDiagnostic(update);
            return;
        }
        detailStartNanos = System.nanoTime();
        boolean stableFastPathDispatched = tryDispatchStableFrameFastPath(update, causality);
        acceptFrameTiming.stableFastPath.record(System.nanoTime() - detailStartNanos);
        if (stableFastPathDispatched) {
            finishAcceptStage(acceptFrameTiming.preBuild, stageStartNanos, acceptStartNanos);
            maybeLogSlowPreBuildDiagnostic(update);
            return;
        }
        detailStartNanos = System.nanoTime();
        boolean allowPreBuildDispatch = shouldDispatchBeforeBuildBudget(update);
        acceptFrameTiming.preBuildPolicy.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean dispatchedBeforeBuildBudget = allowPreBuildDispatch
                && dispatchCurrentFrameIfReady(update, causality);
        acceptFrameTiming.preBuildDispatch.record(System.nanoTime() - detailStartNanos);
        if (dispatchedBeforeBuildBudget) {
            foregroundFrameFirstDispatches++;
        } else if (!allowPreBuildDispatch) {
            foregroundFrameCoverageDeferrals++;
        }

        if (allowPreBuildDispatch && !worldSceneDescriptorsCanBeUpdated && !dispatchedBeforeBuildBudget) {
            detailStartNanos = System.nanoTime();
            worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
            completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            completedMaterialOnlyThisFrame |=
                    completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            if (dispatchCurrentFrameIfReady(update, causality)) {
                dispatchedBeforeBuildBudget = true;
            }
            acceptFrameTiming.preBuildRetry.record(System.nanoTime() - detailStartNanos);
        }
        acceptFrameTiming.preBuild.record(System.nanoTime() - stageStartNanos);
        maybeLogSlowPreBuildDiagnostic(update);
        stageStartNanos = System.nanoTime();

        detailStartNanos = System.nanoTime();
        if (shouldDeferBackgroundConvergence(update, acceptStartNanos, dispatchedBeforeBuildBudget)) {
            acceptFrameTiming.buildDeferPolicy.record(System.nanoTime() - detailStartNanos);
            framePendingBuildBudgetDeferrals++;
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "backgroundConvergenceDeferred");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildDeferPolicy.record(System.nanoTime() - detailStartNanos);

        detailStartNanos = System.nanoTime();
        long remainingForegroundBudgetNanos = remainingForegroundBudgetNanos(acceptStartNanos);
        acceptFrameTiming.buildRemainingBudget.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        sectionBlasCache.recordFirstFrontProgress(
                sceneBindCoordinator.publication().worldSectionKeys(),
                rayTracingPipeline.runtimeActivity()
        );
        acceptFrameTiming.buildFirstFront.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean firstWorldFrontPending =
                !sectionBlasCache.boundFrontCoversAuthoritativeForeground(sceneBindCoordinator.publication().worldSectionKeys());
        acceptFrameTiming.buildCoverage.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        sectionBlasCache.processFrameBudget(
                remainingForegroundBudgetNanos,
                firstWorldFrontPending
        );
        promoteBoundWorldViewIfCovered(causality);
        acceptFrameTiming.buildSection.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        if (foregroundBudgetExhausted(acceptStartNanos) && !shouldContinueForWorldTlasConvergence()) {
            acceptFrameTiming.buildPostSectionPolicy.record(System.nanoTime() - detailStartNanos);
            framePendingBuildBudgetDeferrals++;
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "foregroundBudgetExhaustedBeforeDynamicBuild");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildPostSectionPolicy.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        dynamicBlasCache.processFrameBudget();
        acceptFrameTiming.buildDynamic.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        if (foregroundBudgetExhausted(acceptStartNanos)
                && !update.commitPlan().dynamicTlasGeometryContent()
                && !shouldContinueForWorldTlasConvergence()) {
            acceptFrameTiming.buildPostDynamicPolicy.record(System.nanoTime() - detailStartNanos);
            framePendingBuildBudgetDeferrals++;
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "foregroundBudgetExhaustedAfterDynamicBuild");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildPostDynamicPolicy.record(System.nanoTime() - detailStartNanos);
        acceptFrameTiming.buildBudget.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        completedMaterialOnlyThisFrame |=
                completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        worldSceneDescriptorsCanBeUpdated =
                worldSceneDescriptorsCanBeUpdated
                        && !sceneBindCoordinator.state().hasPendingWorld()
                        && !sceneBindCoordinator.state().hasPendingMaterial()
                        && !sceneBindCoordinator.state().hasDeferredWorld();
        boolean interactiveTargetsReady = promoteInteractiveTerrainUrgencyIfReady();
        boolean forceCurrentWorldTlas = consumeWorldTlasUrgency(update);
        if (hasPendingInteractiveTerrainTargets() && !interactiveTargetsReady) {
            /* Never consume interactive urgency with a TLAS that still references pre-edit BLAS ownership. */
            forceCurrentWorldTlas = false;
        }
        if (forceCurrentWorldTlas && update.commitPlan().hasTerrainWork()) {
            interactiveTerrain.markUrgent("consumedInteractiveTerrain");
        }
        boolean forceWorldSceneBind = RtInteractiveTerrainUpdatePolicy.shouldSubmitUrgentWorldSceneBind(
                forceCurrentWorldTlas,
                interactiveTerrain.urgent(),
                sceneBindCoordinator.state().hasPendingWorld()
        );
        if (forceWorldSceneBind && sceneBindCoordinator.state().hasDeferredWorld()) {
            submitDeferredWorldSceneBindIfReady(
                    update,
                    rayTracingPipeline.canUpdateWorldSceneDescriptors(),
                    true
            );
            worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors()
                    && !sceneBindCoordinator.state().hasPendingWorld()
                    && !sceneBindCoordinator.state().hasPendingMaterial()
                    && !sceneBindCoordinator.state().hasDeferredWorld();
        }
        acceptFrameTiming.postBuild.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas =
                shouldRunWorldTlasScheduler(sceneBindCoordinator.state().hasPendingWorld(), sceneBindCoordinator.state().hasDeferredWorld())
                        ? worldTlasCache.processFrameBudget(
                        sectionBlasCache,
                        dynamicBlasCache,
                        update.backlogSnapshot(),
                        worldSceneDescriptorsCanBeUpdated,
                        forceWorldSceneBind,
                        interactiveTerrain.urgencySource(),
                        worldCoverageContractionAuthorizationGeneration,
                        worldBuildCausality(update, causality)
                        )
                        : null;
        acceptFrameTiming.worldTlasScheduler.record(System.nanoTime() - stageStartNanos);
        detailStartNanos = System.nanoTime();
        if (updatedWorldTlas != null) {
            bindWorldTlasForRtDispatch(
                    updatedWorldTlas,
                    update,
                    forceWorldSceneBind,
                    worldSceneDescriptorsCanBeUpdated
            );
            releaseRetiredFrameBoundResourcesIfFrameIdle();
        } else if (!completedMaterialOnlyThisFrame) {
            submitMaterialOnlyUploadIfNeeded(
                    update,
                    rayTracingPipeline.canUpdateMaterialBuffersInPlace(),
                    causality
            );
        }
        acceptFrameTiming.worldTlasBind.record(System.nanoTime() - detailStartNanos);
        acceptFrameTiming.worldTlas.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        releaseRetiredBlasesThroughProtectedRevisions();

        if (!dispatchedBeforeBuildBudget) {
            dispatchVisibleFrontAfterResourceProgress(update, causality);
        }
        finishAcceptStage(acceptFrameTiming.dispatch, stageStartNanos, acceptStartNanos);
    }

    /**
     * Publishes a completed logical successor without rebuilding an identical
     * persistent terrain TLAS. Coverage and content proof stays in the BLAS
     * owner; this device method owns only the atomic scene-publication swap.
     */
    private void promoteBoundWorldViewIfCovered(RendererFrameCausality causality) {
        if (sceneBindCoordinator.publication().worldTlas() == null) {
            return;
        }
        RendererViewState promotedView = sectionBlasCache.boundWorldViewPromotion(
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldSectionContentRevisions(),
                sceneBindCoordinator.publication().worldViewRevision()
        );
        if (promotedView == null) {
            return;
        }
        sceneBindCoordinator.promoteWorldView(promotedView, causality);
        diagnostics.edges().edge(
                "worldViewMetadataPromoted",
                "viewRevision=" + promotedView.revision()
                        + ", sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", worldTlasRevision=" + sceneBindCoordinator.publication().worldTlasRevision()
        );
    }

    private RendererFrameCausality worldBuildCausality(
            RendererFrameUpdate update,
            RendererFrameCausality currentCausality
    ) {
        if (update.commitPlan().hasTerrainWork()) {
            return latestTerrainCausality;
        }
        if (update.commitPlan().dynamicSceneUpdate()) {
            return latestDynamicCausality;
        }
        return currentCausality;
    }

    private void finishAcceptStage(
            VulkanAcceptNanoTiming stage,
            long stageStartNanos,
            long acceptStartNanos
    ) {
        stage.record(System.nanoTime() - stageStartNanos);
        long elapsedNanos = System.nanoTime() - acceptStartNanos;
        acceptFrameTiming.total.record(elapsedNanos);
        acceptFrameStallRecorder.record(
                elapsedNanos,
                stage.name(),
                observedSceneReadiness(),
                rayTracingPipeline.runtimeActivity(),
                acceptFrameTiming.frameBreakdown()
        );
        acceptFrameTiming.maybeLogSmokeWindow();
    }

    private void finishAcceptWithCommittedFront(
            RendererFrameUpdate update,
            RendererFrameCausality causality,
            VulkanAcceptNanoTiming completedStage,
            long completedStageStartNanos,
            long acceptStartNanos,
            boolean frameAlreadyDispatched
    ) {
        completedStage.record(System.nanoTime() - completedStageStartNanos);
        long dispatchStartNanos = System.nanoTime();
        if (!frameAlreadyDispatched) {
            dispatchVisibleFrontAfterResourceProgress(update, causality);
        }
        finishAcceptStage(acceptFrameTiming.dispatch, dispatchStartNanos, acceptStartNanos);
    }

    /**
     * Emits one bounded causality vector only after the already-measured
     * pre-build stage breaches its high-FPS budget. This keeps normal frames
     * allocation- and I/O-free while making a real client stall attributable
     * to bind completion, dynamic descriptors, or dispatch policy rather than
     * inferring a cause from an aggregate counter.
     */
    private void maybeLogSlowPreBuildDiagnostic(RendererFrameUpdate update) {
        long preBuildNanos = acceptFrameTiming.preBuild.lastMicros * 1_000L;
        if (preBuildNanos < SLOW_PRE_BUILD_DIAGNOSTIC_NANOS) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSlowPreBuildDiagnosticNanos < SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastSlowPreBuildDiagnosticNanos = now;
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        RtSceneReadiness readiness = observedSceneReadiness();
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt pre-build budget breach: frameSequence={}, preBuildUs={}, detailUs={pendingBind={}, deferredBind={}, textureCatchUp={}, stableBackpressure={}, stableFastPath={}, policy={}, dispatch={}, retry={}}, pending={world={}, material={}, dynamic={}, deferred={}}, descriptorsReady={}, framePending={}, dynamic={revision={}, topology={}, transform={}, geometry={}, material={}, boundMaterial={}}, scene={builtCurrent={}, pendingSections={}, pendingTriangles={}, observedSections={}, builtSections={}}",
                update.frameState().sequence(),
                acceptFrameTiming.preBuild.lastMicros,
                acceptFrameTiming.pendingBindCompletion.lastMicros,
                acceptFrameTiming.deferredBindSubmit.lastMicros,
                acceptFrameTiming.textureCatchUp.lastMicros,
                acceptFrameTiming.stableBackpressure.lastMicros,
                acceptFrameTiming.stableFastPath.lastMicros,
                acceptFrameTiming.preBuildPolicy.lastMicros,
                acceptFrameTiming.preBuildDispatch.lastMicros,
                acceptFrameTiming.preBuildRetry.lastMicros,
                sceneBindCoordinator.state().hasPendingWorld(),
                sceneBindCoordinator.state().hasPendingMaterial(),
                sceneBindCoordinator.state().hasPendingDynamic(),
                sceneBindCoordinator.state().hasDeferredWorld(),
                rayTracingPipeline.canUpdateWorldSceneDescriptors(),
                activity.pendingFrame(),
                dynamicBlasCache.revision(),
                dynamicBlasCache.topologyRevision(),
                dynamicBlasCache.transformRevision(),
                dynamicBlasCache.geometryRevision(),
                dynamicBlasCache.materialRevision(),
                sceneBindCoordinator.publication().dynamicMaterialRevision(),
                readiness.builtRevisionIsCurrent(),
                readiness.pendingRtSectionBuilds(),
                readiness.pendingRtTriangles(),
                readiness.observedSectionInstances(),
                readiness.builtSectionInstances()
        );
    }

    private long remainingForegroundBudgetNanos(long acceptStartNanos) {
        long elapsed = System.nanoTime() - acceptStartNanos;
        if (elapsed >= foregroundFrameBudgetNanos) {
            return 1L;
        }
        return foregroundFrameBudgetNanos - elapsed;
    }

    private boolean foregroundBudgetExhausted(long acceptStartNanos) {
        return System.nanoTime() - acceptStartNanos >= foregroundFrameBudgetNanos;
    }

    private boolean shouldContinueForWorldTlasConvergence() {
        RtSceneReadiness readiness = observedSceneReadiness();
        long observedMaterialRevision = worldTlasCache.latestObservedSectionMaterialRevision();
        return shouldContinueForWorldTlasConvergence(
                readiness,
                sceneBindCoordinator.state().hasPendingWorld(),
                sceneBindCoordinator.state().hasDeferredWorld(),
                observedMaterialRevision >= 0L
                        && sectionMaterialRevisionOutranBoundSnapshot(
                        observedMaterialRevision,
                        sceneBindCoordinator.publication().materialSnapshot()
                )
        );
    }

    static boolean shouldContinueForWorldTlasConvergence(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent
    ) {
        return shouldContinueForWorldTlasConvergence(
                readiness,
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent,
                false
        );
    }

    static boolean shouldContinueForWorldTlasConvergence(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent,
            boolean materialRevisionPending
    ) {
        return RtWorldSceneConvergencePolicy.shouldContinue(
                readiness,
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent,
                materialRevisionPending
        );
    }

    static boolean sectionMaterialRevisionOutranBoundSnapshot(
            long sectionMaterialRevision,
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot
    ) {
        return RtWorldSceneConvergencePolicy.sectionMaterialRevisionOutranBoundSnapshot(
                sectionMaterialRevision,
                boundMaterialSnapshot
        );
    }

    static boolean shouldRunWorldTlasScheduler(
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent
    ) {
        return RtWorldSceneConvergencePolicy.shouldRunWorldTlasScheduler(
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent
        );
    }

    static boolean shouldRunDynamicTlasScheduler(boolean pendingDynamicTlasBindPresent) {
        return RtWorldSceneConvergencePolicy.shouldRunDynamicTlasScheduler(pendingDynamicTlasBindPresent);
    }

    static boolean capturedDescriptorGenerationIsStale(
            long capturedDescriptorGeneration,
            long currentDescriptorGeneration
    ) {
        if (capturedDescriptorGeneration <= 0L || currentDescriptorGeneration <= 0L) {
            throw new IllegalArgumentException("descriptor generations must be positive");
        }
        return capturedDescriptorGeneration != currentDescriptorGeneration;
    }

    static boolean dynamicTlasCanReuseBoundMaterialSnapshot(
            RtSceneMaterialTable.Snapshot boundSnapshot,
            RtSceneMaterialTable.Snapshot candidateSnapshot
    ) {
        return RtDynamicSceneBindLane.canReuseBoundMaterialSnapshot(boundSnapshot, candidateSnapshot);
    }

    static boolean initialWorldFrontBuildReady(
            boolean currentWorldTlasPresent,
            boolean authoritativeViewEstablished,
            boolean authoritativeForegroundEstablished,
            boolean foregroundCoverageIncomplete
    ) {
        return RtWorldSceneConvergencePolicy.initialWorldFrontBuildReady(
                currentWorldTlasPresent,
                authoritativeViewEstablished,
                authoritativeForegroundEstablished,
                foregroundCoverageIncomplete
        );
    }

    private boolean tryDispatchStableFrameFastPath(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * Once the world scene is current, most high-FPS camera frames only need
         * a fresh frame-state/dynamic SSBO dispatch. Re-entering the BLAS/TLAS
         * scheduler on those frames duplicates work that UE-style GPUScene paths
         * keep behind dirty-resource gates, and it was visible as fixed
         * frame-end CPU cost during camera motion.
         */
        if (!shouldEvaluateStableFramePath(update.commitPlan().hasTerrainWork())
                || !isStableFrameFastPathEligible(update, sceneReadiness())) {
            return false;
        }
        if (!dispatchCurrentFrameIfReady(update, causality)) {
            return false;
        }
        stableFrameFastPathDispatches++;
        return true;
    }

    private boolean dispatchVisibleFrontAfterResourceProgress(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * Resource convergence receives the queue prefix for this frame before
         * this method runs. The immutable front remains a valid read generation,
         * so it must continue rendering current camera/dynamic state while its
         * successor builds. This is the same separation UE keeps between scene
         * updates and rendering from the committed GPUScene generation.
         */
        RtCommittedFrontPolicy.Decision decision = RtCommittedFrontPolicy.classify(
                RtCommittedFrontPolicy.generationIsCurrent(
                        committedFrontInvalidationSectionRevision,
                        boundCommittedFrontSectionRevision
                ),
                sceneBindCoordinator.publication().worldTlas() != null,
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(observedSceneReadiness())
        );
        if (decision != RtCommittedFrontPolicy.Decision.CONVERGED
                && decision != RtCommittedFrontPolicy.Decision.ELIGIBLE) {
            acceptFrameTiming.recordCommittedFrontDecision(decision);
            return false;
        }
        if (!dispatchCurrentFrameIfReadyAllowingBoundDynamicGeneration(update, causality)) {
            acceptFrameTiming.recordCommittedFrontDecision(RtCommittedFrontPolicy.Decision.DISPATCH_REJECTED);
            return false;
        }
        acceptFrameTiming.recordCommittedFrontDecision(decision);
        if (decision == RtCommittedFrontPolicy.Decision.ELIGIBLE) {
            acceptFrameTiming.recordCommittedFrontDispatch();
        }
        return true;
    }

    private boolean shouldSkipStableFrameWhileGpuFramePending(RendererFrameUpdate update) {
        /*
         * Mirror UE's bounded RHI-frame overlap: pending work is normal until
         * the configured submission window or resident output ring is full.
         * Only a genuinely saturated queue may bypass the full scene scheduler.
         */
        if (!shouldEvaluateStableFramePath(update.commitPlan().hasTerrainWork())
                || !isStableFrameFastPathEligible(update, sceneReadiness())) {
            return false;
        }
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        if (!shouldApplyStableFrameSubmissionBackpressure(
                rayTracingPipeline.hasFrameSubmissionCapacity(),
                activity.pendingFrame(),
                activity.pendingFrameAgeMillis(),
                maxPendingFrameAgeBeforeBuildMillis
        )) {
            return false;
        }
        stableFramePendingGpuSkips++;
        return true;
    }

    /** Dirty terrain frames belong to the scene scheduler, never the stable-frame path. */
    static boolean shouldEvaluateStableFramePath(boolean terrainWork) {
        return RtWorldSceneConvergencePolicy.shouldEvaluateStableFramePath(terrainWork);
    }

    static boolean shouldApplyStableFrameSubmissionBackpressure(
            boolean hasSubmissionCapacity,
            boolean pendingFrame,
            long pendingFrameAgeMillis,
            long maxPendingFrameAgeBeforeBuildMillis
    ) {
        return RtWorldSceneConvergencePolicy.shouldApplyStableFrameSubmissionBackpressure(
                hasSubmissionCapacity,
                pendingFrame,
                pendingFrameAgeMillis,
                maxPendingFrameAgeBeforeBuildMillis
        );
    }

    private boolean isStableFrameFastPathEligible(RendererFrameUpdate update, RtSceneReadiness readiness) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        if (update.commitPlan().hasTerrainWork()) {
            return false;
        }
        if (!RtSceneReadiness.READY_REASON.equals(readiness.frameDispatchBlockReason(update.backlogSnapshot()))) {
            return false;
        }
        if (interactiveTerrain.urgent()
                || sceneBindCoordinator.state().hasPendingWorld()
                || sceneBindCoordinator.state().hasPendingMaterial()
                || sceneBindCoordinator.state().hasDeferredWorld()) {
            return false;
        }
        if (sectionMaterialRevisionOutranBoundSnapshot(
                sectionBlasCache.snapshotTlasBuildStats().materialRevision(),
                sceneBindCoordinator.publication().materialSnapshot()
        ) || dynamicBlasCache.materialRevision() != sceneBindCoordinator.publication().dynamicMaterialRevision()
                || textureCatalogRevisionOutranBoundMaterialSnapshot(
                sceneBindCoordinator.publication().materialSnapshot(),
                RtTextureCatalog.revision()
        )) {
            return false;
        }
        return worldTlasCache.boundDynamicRevisionIsCurrent(dynamicBlasCache.revision())
                && rayTracingPipeline.canUpdateWorldSceneDescriptors();
    }

    static boolean textureCatalogRevisionOutranBoundMaterialSnapshot(
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot,
            long textureCatalogRevision
    ) {
        return RtWorldSceneConvergencePolicy.textureCatalogRevisionOutranBoundSnapshot(
                boundMaterialSnapshot,
                textureCatalogRevision
        );
    }

    static boolean shouldDispatchBeforeBuildBudget(RendererFrameUpdate update, RtSceneReadiness readiness) {
        return RtWorldSceneConvergencePolicy.shouldDispatchBeforeBuildBudget(update, readiness);
    }

    private boolean shouldDispatchBeforeBuildBudget(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!diagnostics.edges().enabled()) {
            return shouldDispatchBeforeBuildBudgetWithoutDiagnostics(update);
        }

        long wallStartNanos = System.nanoTime();
        long cpuStartNanos = currentThreadCpuTimeNanos();
        boolean requiresReadiness = preBuildDispatchRequiresReadiness(update);
        long preconditionNanos = System.nanoTime();
        if (!requiresReadiness) {
            return shouldDispatchBeforeBuildBudgetWithoutDiagnostics(update);
        }
        RtSceneReadiness readiness = observedSceneReadiness();
        long readinessNanos = System.nanoTime();
        boolean dispatch = shouldDispatchBeforeBuildBudget(update, readiness);
        long decisionNanos = System.nanoTime();
        maybeLogSlowPreBuildPolicyDiagnostic(
                update,
                readiness,
                dispatch,
                preconditionNanos - wallStartNanos,
                readinessNanos - preconditionNanos,
                decisionNanos - readinessNanos,
                decisionNanos - wallStartNanos,
                cpuDurationNanos(cpuStartNanos, currentThreadCpuTimeNanos())
        );
        return dispatch;
    }

    private boolean shouldDispatchBeforeBuildBudgetWithoutDiagnostics(RendererFrameUpdate update) {
        if (!preBuildDispatchRequiresReadiness(update)) {
            RendererFrameCommitPlan commitPlan = update.commitPlan();
            if (!commitPlan.hasTerrainWork()) {
                return true;
            }
            if (commitPlan.fullResyncRequested()) {
                return false;
            }
            SceneUpdateBatch batch = update.batch();
            return !batch.hasBatchBlockMutationSource() || batch.hasBatchChunkStreamingSource();
        }
        /*
         * Removal batches are common while the scheduler snapshots BLAS and
         * material state.  This policy needs the last committed coverage fact,
         * not a synchronous refresh that can contend with that transaction.
        */
        return shouldDispatchBeforeBuildBudget(update, observedSceneReadiness());
    }

    /**
     * A policy decision must be CPU-only and sub-microsecond in the steady
     * state.  When a smoke frame says otherwise, distinguish actual CPU work
     * from a JVM safepoint or OS deschedule before changing renderer logic.
     */
    private void maybeLogSlowPreBuildPolicyDiagnostic(
            RendererFrameUpdate update,
            RtSceneReadiness readiness,
            boolean dispatch,
            long preconditionNanos,
            long readinessNanos,
            long decisionNanos,
            long totalNanos,
            long cpuNanos
    ) {
        if (totalNanos < SLOW_PRE_BUILD_DIAGNOSTIC_NANOS) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSlowPreBuildPolicyDiagnosticNanos < SLOW_PRE_BUILD_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastSlowPreBuildPolicyDiagnosticNanos = now;
        RendererFrameCommitPlan plan = update.commitPlan();
        SceneUpdateBatch batch = update.batch();
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt pre-build policy causality: frameSequence={}, wallUs={}, cpuUs={}, descheduledUs={}, stagesUs={precondition={}, readiness={}, decision={}}, result={}, plan={terrain={}, fullResync={}, removed={}, unloaded={}, sectionMeshes={}, materialSections={}}, sources={removal={}, streaming={}, mutation={}}, coverage={builtSections={}, pendingRtSections={}, referenceSections={}, gap={}}",
                update.frameState().sequence(),
                totalNanos / 1_000L,
                cpuNanos < 0L ? -1L : cpuNanos / 1_000L,
                cpuNanos < 0L ? -1L : Math.max(0L, totalNanos - cpuNanos) / 1_000L,
                preconditionNanos / 1_000L,
                readinessNanos / 1_000L,
                decisionNanos / 1_000L,
                dispatch,
                plan.hasTerrainWork(),
                plan.fullResyncRequested(),
                plan.removedSectionCount(),
                plan.unloadedChunks().size(),
                plan.sectionMeshCount(),
                plan.materialSectionCount(),
                batch.hasBatchSectionRemovalSource(),
                batch.hasBatchChunkStreamingSource(),
                batch.hasBatchBlockMutationSource(),
                readiness.builtSectionInstances(),
                readiness.pendingRtSectionBuilds(),
                update.backlogSnapshot().rtCoverageReferenceSections(),
                update.backlogSnapshot().hasUnaccountedRtCoverageGap(
                        readiness.builtSectionInstances(),
                        readiness.pendingRtSectionBuilds()
                )
        );
    }

    private static long currentThreadCpuTimeNanos() {
        return THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                && THREAD_MX_BEAN.isThreadCpuTimeEnabled()
                ? THREAD_MX_BEAN.getCurrentThreadCpuTime()
                : -1L;
    }

    private static long cpuDurationNanos(long startNanos, long endNanos) {
        return startNanos < 0L || endNanos < startNanos ? -1L : endNanos - startNanos;
    }

    static boolean preBuildDispatchRequiresReadiness(RendererFrameUpdate update) {
        return RtWorldSceneConvergencePolicy.preBuildDispatchRequiresReadiness(update);
    }

    static boolean isBoundWorldTlasSafeForForegroundStreaming(
            RendererFrameUpdate update,
            RtSceneReadiness readiness
    ) {
        return RtWorldSceneConvergencePolicy.isBoundWorldTlasSafeForForegroundStreaming(update, readiness);
    }

    private void releaseRetiredFrameBoundResourcesIfFrameIdle() {
        long completedDescriptorGeneration = rayTracingPipeline.frameBoundResourceRetirementGeneration();
        worldTlasCache.releaseRetiredWorldTlasesThrough(completedDescriptorGeneration);
        dynamicTlasCache.releaseRetiredThrough(completedDescriptorGeneration);
        sceneMaterialTable.releaseRetiredMaterialBuffersThrough(completedDescriptorGeneration);
    }

    /** Advances completed dynamic-TLAS work before the stable-frame fast path may return. */
    private void advanceDynamicTlasLane(
            boolean descriptorsCanBeUpdated,
            boolean resourceConvergencePending,
            RendererFrameCausality causality
    ) {
        dynamicSceneBindLane.advance(descriptorsCanBeUpdated, resourceConvergencePending, causality);
    }

    private void captureInteractiveTerrainTargets(RendererFrameUpdate update) {
        SceneUpdateBatch batch = update.batch();
        if (!RtInteractiveTerrainUpdatePolicy.isInteractiveBlockMutationBatch(
                batch,
                update.frameState(),
                interactiveMutationRadiusSections,
                maxInteractiveMutationSections
        )) {
            return;
        }
        interactiveTerrain.capture(update);
    }

    private boolean hasPendingInteractiveTerrainTargets() {
        return interactiveTerrain.hasPendingTargets();
    }

    private boolean promoteInteractiveTerrainUrgencyIfReady() {
        return interactiveTerrain.promoteIfReady(sectionBlasCache, sceneBindCoordinator.publication());
    }

    private void settlePublishedInteractiveTerrainTargets() {
        interactiveTerrain.settle(sceneBindCoordinator.publication());
    }

    private boolean consumeWorldTlasUrgency(RendererFrameUpdate update) {
        if (!update.commitPlan().hasTerrainWork()) {
            /* Dynamic geometry owns an independent TLAS and descriptor lane. */
            return false;
        }
        SceneUpdateBatch batch = update.batch();
        long blockMutationMarks = batch.totalBlockMutationMarks();
        long chunkPacketReplacementMarks = batch.totalChunkPacketReplacementMarks();
        long chunkSnapshotReplacements = batch.totalChunkSnapshotReplacements();
        long chunkUnloadMarks = batch.totalChunkUnloadMarks();
        long sectionSnapshotRemovals = batch.totalSectionSnapshotRemovals();
        lastObservedBlockMutationMarks = Math.max(lastObservedBlockMutationMarks, blockMutationMarks);
        lastObservedChunkPacketReplacementMarks =
                Math.max(lastObservedChunkPacketReplacementMarks, chunkPacketReplacementMarks);
        lastObservedChunkSnapshotReplacements =
                Math.max(lastObservedChunkSnapshotReplacements, chunkSnapshotReplacements);
        lastObservedChunkUnloadMarks = Math.max(lastObservedChunkUnloadMarks, chunkUnloadMarks);
        lastObservedSectionSnapshotRemovals = Math.max(lastObservedSectionSnapshotRemovals, sectionSnapshotRemovals);
        boolean blockMutationSource = batch.hasBatchBlockMutationSource();
        boolean chunkStreamingSource = batch.hasBatchChunkStreamingSource();
        boolean removalSource = batch.hasBatchSectionRemovalSource() || !batch.removedSections().isEmpty();
        boolean terrainTopologyChanged = blockMutationSource || removalSource;
        if (!terrainTopologyChanged) {
            return false;
        }
        /*
         * host may merge a direct block edit and unrelated chunk streaming
         * into one frame batch. The persistent interactive target owner already
         * fences the exact edited section revision, so batch-wide streaming must
         * not demote that section after its own BLAS is ready. Pure streaming and
         * far/oversized mutation bursts still remain on the coalesced lane.
         */
        boolean interactiveBlockMutation = RtInteractiveTerrainUpdatePolicy.isInteractiveBlockMutationBatch(
                batch,
                update.frameState(),
                interactiveMutationRadiusSections,
                maxInteractiveMutationSections
        );
        if (!RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                terrainTopologyChanged,
                blockMutationSource,
                chunkStreamingSource,
                interactiveBlockMutation,
                update.backlogSnapshot()
        )) {
            return false;
        }
        return true;
    }

    private boolean shouldDeferBackgroundConvergence(
            RendererFrameUpdate update,
            long acceptStartNanos,
            boolean dispatchedBeforeBuildBudget
    ) {
        Objects.requireNonNull(update, "update");
        long observedMaterialRevision = worldTlasCache.latestObservedSectionMaterialRevision();
        if (interactiveTerrain.urgent()
                || observedMaterialRevision >= 0L
                && sectionMaterialRevisionOutranBoundSnapshot(
                observedMaterialRevision,
                sceneBindCoordinator.publication().materialSnapshot()
        )) {
            return false;
        }
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        RtSceneReadiness readiness = observedSceneReadiness();
        boolean defer = shouldDeferBackgroundConvergence(
                update,
                readiness,
                dispatchedBeforeBuildBudget,
                activity.pendingFrame(),
                activity.pendingFrameAgeMillis(),
                maxPendingFrameAgeBeforeBuildMillis,
                System.nanoTime() - acceptStartNanos,
                foregroundFrameBudgetNanos
        );
        if (defer) {
            foregroundFrameBudgetDeferrals++;
        }
        return defer;
    }

    static boolean shouldDeferBackgroundConvergence(
            RendererFrameUpdate update,
            RtSceneReadiness readiness,
            boolean dispatchedBeforeBuildBudget,
            boolean pendingFrame,
            long pendingFrameAgeMillis,
            long maxPendingFrameAgeBeforeBuildMillis,
            long elapsedNanos,
            long foregroundFrameBudgetNanos
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        if (pendingFrameAgeMillis < 0L) {
            throw new IllegalArgumentException("pendingFrameAgeMillis must not be negative");
        }
        if (maxPendingFrameAgeBeforeBuildMillis < 0L) {
            throw new IllegalArgumentException("maxPendingFrameAgeBeforeBuildMillis must not be negative");
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (foregroundFrameBudgetNanos < 0L) {
            throw new IllegalArgumentException("foregroundFrameBudgetNanos must not be negative");
        }

        /*
         * A stale streaming TLAS is useful for player-visible continuity, but it
         * is not a reason to starve BLAS/TLAS convergence. The native fluid gate
         * caught the failure mode here: dispatching the same partial 32/96 scene
         * forever kept the probe-visible water section out of the world TLAS.
         *
         * Once the scene is current, foreground pacing may protect an in-flight
         * frame. Before that, every pump must continue feeding bounded background
         * resource work so streaming, block edits and fluid replacements converge.
         */
        if (!readiness.builtRevisionIsCurrent() || readiness.hasPendingRtBuilds()) {
            return false;
        }
        boolean foregroundSensitive = dispatchedBeforeBuildBudget
                || update.backlogSnapshot().hasPresentationBlockingRendererWork()
                || readiness.isFrameDispatchEligible(update.backlogSnapshot());
        if (!foregroundSensitive) {
            return false;
        }
        if (pendingFrame && pendingFrameAgeMillis >= maxPendingFrameAgeBeforeBuildMillis) {
            return true;
        }
        if (pendingFrame) {
            return true;
        }
        return elapsedNanos >= foregroundFrameBudgetNanos;
    }

    private boolean dispatchCurrentFrameIfReady(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * The active world TLAS is an immutable scene generation. Dynamic
         * collection commonly starts its successor every host frame, so
         * requiring that successor before dispatching the active generation
         * starves first presentation indefinitely.
         */
        return dispatchCurrentFrameIfReady(update, causality, true);
    }

    private boolean dispatchCurrentFrameIfReadyAllowingBoundDynamicGeneration(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        return dispatchCurrentFrameIfReady(update, causality, true);
    }

    private boolean dispatchCurrentFrameIfReady(
            RendererFrameUpdate update,
            RendererFrameCausality causality,
            boolean allowBoundDynamicGeneration
    ) {
        acceptFrameTiming.dispatchAttempts++;
        long dispatchStageStartNanos = System.nanoTime();
        RtSceneReadiness readiness = observedSceneReadiness();
        acceptFrameTiming.dispatchReadiness.record(System.nanoTime() - dispatchStageStartNanos);
        /*
         * A staged descriptor generation is a write-side concern. The active
         * generation remains immutable and valid until every frame that uses it
         * retires, so a streaming material/TLAS upload must not stall dispatch
         * through that already-bound scene. This mirrors UE's RDG/RHI lifetime:
         * stage the next resource generation independently while the current
         * generation continues producing frames from its bounded output ring.
         */
        dispatchStageStartNanos = System.nanoTime();
        if (!rayTracingPipeline.hasFrameSubmissionCapacity()) {
            acceptFrameTiming.dispatchCapacityRejects++;
            acceptFrameTiming.dispatchCapacity.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "frameSubmissionCapacity");
            return false;
        }
        acceptFrameTiming.dispatchCapacity.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        if (!currentDescriptorGenerationCanDispatch()) {
            acceptFrameTiming.dispatchDescriptorRejects++;
            acceptFrameTiming.dispatchDescriptor.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "descriptorGenerationNotCurrent");
            return false;
        }
        acceptFrameTiming.dispatchDescriptor.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        String readinessBlockReason = readiness.frameProductionBlockReason(update.backlogSnapshot());
        if (!RtSceneReadiness.READY_REASON.equals(readinessBlockReason)) {
            acceptFrameTiming.dispatchReadinessRejects++;
            acceptFrameTiming.dispatchReadinessPolicy.record(System.nanoTime() - dispatchStageStartNanos);
            dynamicWorldTlasDispatchBlocks++;
            lastDynamicWorldTlasDispatchBlockReason = readinessBlockReason;
            recordNativeDispatchDecision(update, "context", "frameProduction:" + readinessBlockReason);
            return false;
        }
        acceptFrameTiming.dispatchReadinessPolicy.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        String dynamicBlockReason = allowBoundDynamicGeneration
                ? dynamicWorldTlasCommittedFrontBlockReason()
                : dynamicWorldTlasDispatchBlockReason();
        if (!RtSceneReadiness.READY_REASON.equals(dynamicBlockReason)) {
            acceptFrameTiming.dispatchDynamicRejects++;
            acceptFrameTiming.dispatchDynamicPolicy.record(System.nanoTime() - dispatchStageStartNanos);
            dynamicWorldTlasDispatchBlocks++;
            lastDynamicWorldTlasDispatchBlockReason = dynamicBlockReason;
            recordNativeDispatchDecision(update, "context", "dynamicWorldTlas:" + dynamicBlockReason);
            return false;
        }
        acceptFrameTiming.dispatchDynamicPolicy.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        if (RtDynamicSceneDispatchPolicy.shouldBlockForInteractiveWorldSceneBind(
                sceneBindCoordinator.publication().worldTlas() != null,
                interactiveWorldSceneBindPending()
        )) {
            acceptFrameTiming.dispatchInteractiveRejects++;
            acceptFrameTiming.dispatchInteractivePolicy.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "interactiveWorldSceneBindPendingWithoutCommittedFront");
            return false;
        }
        acceptFrameTiming.dispatchInteractivePolicy.record(System.nanoTime() - dispatchStageStartNanos);
        long previousDispatches = rayTracingPipeline.runtimeActivity().frameDispatches();
        dispatchStageStartNanos = System.nanoTime();
        DynamicRenderScene dispatchDynamicScene = shouldDispatchCurrentDynamicSsboScene(
                update,
                allowBoundDynamicGeneration
        ) ? update.dynamicScene() : sceneBindCoordinator.publication().dynamicScene();
        rayTracingPipeline.dispatchFrameIfDue(
                queueContexts.frameCommands(),
                causality,
                sceneBindCoordinator.publication().snapshot(),
                update.frameState(),
                dispatchDynamicScene,
                sceneBindCoordinator.publication().dynamicScene().revision(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldSectionContentRevisions(),
                sceneBindCoordinator.publication().worldViewState(),
                true
        );
        acceptFrameTiming.dispatchPipeline.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        maybeLogWorldTlasDispatch(previousDispatches, true, readiness);
        long dispatchesAfterAttempt = rayTracingPipeline.runtimeActivity().frameDispatches();
        diagnostics.causality().firstFrontFrame(
                "nativeDispatchAttempt",
                update.frameState().sequence(),
                "boundWorldScene=" + (sceneBindCoordinator.publication().worldTlas() != null)
                        + ", sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", submitted=" + (dispatchesAfterAttempt > previousDispatches)
        );
        diagnostics.causality().frame(
                top.ceroxe.mcvulkanrt.renderer.RtCausalitySink.Stage.GPU_DISPATCH,
                update.frameState().sequence(),
                sceneBindCoordinator.publication().worldViewRevision(),
                sceneBindCoordinator.publication().worldSectionKeys().size(),
                dispatchesAfterAttempt > previousDispatches ? 1 : 0
        );
        diagnostics.causality().dispatch(
                latestTerrainCausality,
                update.frameState().sequence(),
                sceneBindCoordinator.publication().generation(),
                sceneBindCoordinator.publication().descriptorGeneration(),
                dispatchesAfterAttempt,
                dispatchesAfterAttempt > previousDispatches
                        ? top.ceroxe.mcvulkanrt.renderer.RtCausalitySink.Reason.SUBMITTED
                        : top.ceroxe.mcvulkanrt.renderer.RtCausalitySink.Reason.NONE
        );
        acceptFrameTiming.dispatchRecorders.record(System.nanoTime() - dispatchStageStartNanos);
        if (dispatchesAfterAttempt > previousDispatches) {
            acceptFrameTiming.dispatchSubmissions++;
        } else {
            acceptFrameTiming.dispatchPipelineNoSubmits++;
        }
        return dispatchesAfterAttempt > previousDispatches;
    }

    /**
     * The dynamic SSBO is frame-slot-local, while the dynamic TLAS and material
     * table are descriptor-visible generations. Pure analytic primitives,
     * particles, weather, sky, and their clear transition therefore do not
     * need to wait for a descriptor bind. Passing the published scene back to
     * the pipeline in this case would overwrite the update it already accepted
     * at ingress and visibly resurrect stale dynamic content.
     *
     * <p>Once either side contains TLAS geometry, this optimization is forbidden:
     * the published scene remains the sole dispatch source until the matching
     * TLAS/material transaction commits.</p>
     */
    private boolean shouldDispatchCurrentDynamicSsboScene(
            RendererFrameUpdate update,
            boolean allowBoundDynamicGeneration
    ) {
        return RtDynamicSceneDispatchPolicy.shouldDispatchCurrentSsboScene(
                allowBoundDynamicGeneration,
                sceneBindCoordinator.publication().dynamicScene().hasTlasGeometryContent(),
                update.commitPlan().dynamicTlasGeometryContent()
        );
    }

    private String dynamicWorldTlasCommittedFrontBlockReason() {
        return RtDynamicSceneDispatchPolicy.committedFrontBlockReason(
                sceneBindCoordinator.publication().worldTlas() != null,
                sceneBindCoordinator.publication().dynamicScene().hasTlasGeometryContent(),
                worldTlasCache.publishedReadiness().builtDynamicInstances()
        );
    }

    private boolean currentDescriptorGenerationCanDispatch() {
        return RtDynamicSceneDispatchPolicy.descriptorGenerationCanDispatch(
                sceneBindCoordinator.publication().worldTlas() != null,
                !sceneBindCoordinator.state().hasPendingWorld()
                        || sceneBindCoordinator.state().pendingWorld().materialUpload().materialBuffersChanged(),
                !sceneBindCoordinator.state().hasPendingMaterial()
                        || sceneBindCoordinator.state().pendingMaterial().materialUpload().materialBuffersChanged(),
                !sceneBindCoordinator.state().hasPendingDynamic()
                        || sceneBindCoordinator.state().pendingDynamic().materialUpload().materialBuffersChanged()
        );
    }

    private void recordNativeDispatchDecision(RendererFrameUpdate update, String stage, String reason) {
        if (!TAKEOVER_FLIGHT_RECORDER_ENABLED) {
            return;
        }
        RendererFrameState frameState = update.frameState();
        rayTracingPipeline.recordExternalDispatchDecision(
                frameState.valid() ? frameState.sequence() : -1L,
                stage,
                reason
        );
    }

    private boolean interactiveWorldSceneBindPending() {
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        return interactiveTerrain.urgent()
                || (pending != null && pending.urgentWorldSceneBind());
    }

    private String dynamicWorldTlasDispatchBlockReason() {
        if (dynamicBlasCache.activeSceneHasTlasGeometryContent()
                && worldTlasCache.publishedReadiness().builtDynamicInstances() <= 0) {
            return RtSceneReadiness.RT_DYNAMIC_BUILD_PENDING_REASON;
        }
        return RtDynamicSceneDispatchPolicy.blockReason(
                sceneBindCoordinator.publication().worldTlas() != null,
                worldTlasCache.hasBoundDynamicLane(),
                dynamicBlasCache.activeSceneHasTlasGeometryContent(),
                worldTlasCache.boundDynamicRevisionIsCurrent(dynamicBlasCache.revision()),
                worldTlasCache.boundDynamicStructureIsCurrent(
                        dynamicBlasCache.topologyRevision(),
                        dynamicBlasCache.geometryRevision()
                )
        );
    }

    private void bindWorldTlasForRtDispatch(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            RendererFrameUpdate update,
            boolean forceCurrentWorldTlas,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        boolean hasExplicitWorldRemoval = coverageContractionAuthorized(
                updatedWorldTlas.coverageContractionAuthorizationGeneration(),
                boundWorldCoverageContractionAuthorizationGeneration
        );
        if (shouldProtectBoundWorldSceneCoverage(
                sceneBindCoordinator.publication().worldTlas() != null,
                latestViewRevision,
                sceneBindCoordinator.publication().worldViewRevision(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                updatedWorldTlas.sectionKeys(),
                updatedWorldTlas.viewState().visibleSectionKeys(),
                hasExplicitWorldRemoval
        )) {
            worldTlasCache.discardUnboundWorldTlas(updatedWorldTlas, "frontCoverageIncomplete");
            worldSceneBindStatistics.coverageProtectedDiscarded();
            return;
        }
        if (sceneBindCoordinator.state().hasPendingDynamic()) {
            deferWorldSceneBind(updatedWorldTlas, "dynamicDescriptorBindPending");
            return;
        }
        if (RtWorldSceneConvergencePolicy.isTransformOnlyWorldTlasUpdate(
                updatedWorldTlas,
                sceneBindCoordinator.publication().worldTlas(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().materialSnapshot()
        )) {
            if (sceneBindCoordinator.state().hasPendingMaterial()) {
                deferWorldSceneBind(updatedWorldTlas, "dynamicTransformMaterialBindPending");
                return;
            }
            bindTransformOnlyWorldScene(updatedWorldTlas);
            return;
        }
        if (!worldSceneDescriptorsCanBeUpdated || sceneBindCoordinator.state().hasPendingMaterial()) {
            deferWorldSceneBind(updatedWorldTlas, "worldSceneDescriptorBusy");
            return;
        }
        RtSceneReadiness readiness = worldTlasCache.readiness();
        String bindReason = readiness.frameDispatchBlockReason(update.backlogSnapshot());
        boolean streamingWorldSceneUpdate = RtWorldSceneConvergencePolicy.isStreamingWorldSceneUpdate(
                updatedWorldTlas,
                bindReason,
                immediateStreamingBindMaxFaces
        );
        if (RtWorldSceneConvergencePolicy.shouldDeferWorldSceneMaterialUpload(
                forceCurrentWorldTlas || shouldBindStreamingWorldSceneImmediately(updatedWorldTlas),
                streamingWorldSceneUpdate,
                update.backlogSnapshot(),
                sceneBindCoordinator.state().hasPendingWorld(),
                System.nanoTime(),
                nextStreamingSceneBindNanos,
                0L,
                maxStreamingSceneBindDeferrals
        )) {
            if (streamingWorldSceneUpdate) {
                worldSceneBindStatistics.streamingIntervalDeferred();
            }
            deferWorldSceneBind(updatedWorldTlas, bindReason);
            return;
        }
        rememberStreamingSceneBindWindow(streamingWorldSceneUpdate);
        submitWorldSceneMaterialUpload(updatedWorldTlas, bindReason, forceCurrentWorldTlas);
    }

    static boolean shouldProtectBoundWorldSceneCoverage(
            boolean hasBoundWorldScene,
            long latestViewRevision,
            long publishedWorldViewRevision,
            Set<SectionKey> publishedSectionKeys,
            Set<SectionKey> candidateSectionKeys,
            List<SectionKey> candidateViewSectionKeys,
            boolean hasExplicitWorldRemoval
    ) {
        Objects.requireNonNull(publishedSectionKeys, "publishedSectionKeys");
        Objects.requireNonNull(candidateSectionKeys, "candidateSectionKeys");
        Objects.requireNonNull(candidateViewSectionKeys, "candidateViewSectionKeys");
        if (!hasBoundWorldScene
                || publishedSectionKeys.isEmpty()
                || hasExplicitWorldRemoval
                || latestViewRevision < 0L
                || publishedWorldViewRevision < 0L
                || candidateSectionKeys.containsAll(publishedSectionKeys)) {
            return false;
        }
        return candidateViewSectionKeys.isEmpty()
                || !candidateSectionKeys.containsAll(candidateViewSectionKeys);
    }

    static boolean coverageContractionAuthorized(long candidateGeneration, long boundGeneration) {
        if (candidateGeneration < 0L || boundGeneration < 0L) {
            throw new IllegalArgumentException("coverage contraction generations must not be negative");
        }
        return candidateGeneration > boundGeneration;
    }

    private void rememberStreamingSceneBindWindow(boolean streamingWorldSceneUpdate) {
        if (streamingWorldSceneUpdate && minStreamingSceneBindIntervalNanos > 0L) {
            nextStreamingSceneBindNanos = System.nanoTime() + minStreamingSceneBindIntervalNanos;
        }
    }

    private void submitWorldSceneMaterialUpload(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            String bindReason,
            boolean urgentWorldSceneBind
    ) {
        RtWorldSceneMaterialUploadLane.WorldSubmission submission = worldSceneMaterialUploadLane.submitWorld(
                updatedWorldTlas,
                bindReason,
                urgentWorldSceneBind,
                rayTracingPipeline.canUpdateMaterialBuffersInPlace()
        );
        switch (submission.status()) {
            case DEFERRED -> {
                deferWorldSceneBind(updatedWorldTlas, bindReason);
                return;
            }
            case IMMEDIATE_BIND -> bindWorldSceneDescriptors(
                    updatedWorldTlas,
                    submission.materialSnapshot(),
                    submission.dynamicMaterialRevision(),
                    false,
                    bindReason
            );
            case PENDING_BIND -> {
                // The upload lane transferred ownership into RtSceneBindCoordinator.
            }
        }
        if (urgentWorldSceneBind) {
            interactiveTerrain.clearUrgency();
        }
    }

    private void submitMaterialOnlyUploadIfNeeded(
            RendererFrameUpdate update,
            boolean allowInPlaceMaterialUpdate,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(update, "update");
        worldSceneMaterialUploadLane.submitMaterialOnly(allowInPlaceMaterialUpdate, causality);
    }

    private boolean shouldBindStreamingWorldSceneImmediately(RtWorldTlasCache.WorldTlasUpdate update) {
        Objects.requireNonNull(update, "update");
        return RtWorldSceneConvergencePolicy.shouldBindStreamingImmediately(
                update, immediateStreamingBindMaxFaces
        );
    }

    private void deferWorldSceneBind(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas, String bindReason) {
        sceneBindCoordinator.publishDeferredWorld(new DeferredWorldSceneBind(updatedWorldTlas, bindReason, 0L));
        worldSceneBindStatistics.deferredWorldCreated();
    }

    private void submitDeferredWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated, false);
    }

    private void submitDeferredWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean forceCurrentWorldTlas
    ) {
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred == null) {
            return;
        }
        RtDeferredWorldSceneBindScheduler.Decision decision = RtDeferredWorldSceneBindScheduler.decide(
                deferred,
                sceneBindCoordinator.publication(),
                worldSceneDescriptorsCanBeUpdated,
                sceneBindCoordinator.state().hasDescriptorTransaction(),
                update,
                forceCurrentWorldTlas,
                immediateStreamingBindMaxFaces,
                System.nanoTime(),
                nextStreamingSceneBindNanos,
                maxStreamingSceneBindDeferrals
        );
        switch (decision.action()) {
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.worldDescriptorDeferred();
                return;
            }
            case BIND_TRANSFORM_ONLY -> {
                sceneBindCoordinator.clearDeferredWorld(deferred);
                worldSceneBindStatistics.deferredWorldSubmitted();
                bindTransformOnlyWorldScene(deferred.worldTlasUpdate());
                return;
            }
            case RETAIN_DEFERRED -> {
                if (decision.streamingWorldSceneUpdate()) {
                    worldSceneBindStatistics.streamingIntervalDeferred();
                }
                worldSceneBindStatistics.deferredWorldDeferred();
                sceneBindCoordinator.replaceDeferredWorld(deferred, deferred.withAdditionalDeferral());
                return;
            }
            case SUBMIT_MATERIAL_UPLOAD -> {
                sceneBindCoordinator.clearDeferredWorld(deferred);
                worldSceneBindStatistics.deferredWorldSubmitted();
                rememberStreamingSceneBindWindow(decision.streamingWorldSceneUpdate());
                submitWorldSceneMaterialUpload(
                        deferred.worldTlasUpdate(), deferred.bindReason(update), forceCurrentWorldTlas
                );
                return;
            }
        }
        throw new IllegalStateException("unhandled deferred world-scene bind action: " + decision.action());
    }

    private void bindTransformOnlyWorldScene(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas) {
        RtSceneBindCoordinator.WorldBindCompletion completion = sceneBindCoordinator.bindTransformOnlyWorldScene(
                updatedWorldTlas,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache
        );
        rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
        recordBoundWorldTlas(completion.worldTlasUpdate());
        maybeLogWorldTlasBound(
                completion.worldTlasUpdate().revision(),
                completion.worldTlasUpdate().instanceCount(),
                completion.bindReason()
        );
    }

    private void completePendingWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        RtSceneBindCoordinator.WorldBindResult result = sceneBindCoordinator.completePendingWorldSceneBindIfReady(
                update,
                worldSceneDescriptorsCanBeUpdated,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache,
                diagnostics,
                worldSceneBindStatistics.nextWorldPollOrdinal()
        );
        switch (result.status()) {
            case ABSENT, STALE_DISCARDED -> {
                return;
            }
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.worldDescriptorDeferred();
                return;
            }
            case UPLOAD_PENDING -> {
                worldSceneBindStatistics.worldPollNotReady();
                return;
            }
            case COMPLETED -> {
                RtSceneBindCoordinator.WorldBindCompletion completion = result.completion();
                rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
                settlePublishedInteractiveTerrainTargets();
                worldSceneBindStatistics.worldCompleted();
                recordBoundWorldTlas(completion.worldTlasUpdate());
                maybeLogWorldTlasBound(
                        completion.worldTlasUpdate().revision(),
                        completion.worldTlasUpdate().instanceCount(),
                        completion.bindReason()
                );
            }
        }
    }

    private boolean completePendingMaterialOnlyBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        Objects.requireNonNull(update, "update");
        RtSceneBindCoordinator.MaterialBindStatus status =
                sceneBindCoordinator.completePendingMaterialOnlyBindIfReady(
                        worldSceneDescriptorsCanBeUpdated,
                        rayTracingPipeline,
                        sceneMaterialTable,
                        diagnostics
                );
        switch (status) {
            case ABSENT, STALE_DISCARDED -> {
                return false;
            }
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.materialDescriptorDeferred();
                return false;
            }
            case UPLOAD_PENDING -> {
                worldSceneBindStatistics.materialPollNotReady();
                return false;
            }
            case COMPLETED -> {
                if (interactiveTerrain.urgent()
                        && materialOnlyBindSettlesInteractiveUrgency(
                        sceneReadiness(),
                        sceneBindCoordinator.state().hasPendingWorld(),
                        sceneBindCoordinator.state().hasDeferredWorld(),
                        sectionBlasCache.snapshotTlasBuildStats().materialRevision(),
                        sceneBindCoordinator.publication().materialSnapshot().revision()
                )) {
                    interactiveTerrain.clearUrgency();
                }
                worldSceneBindStatistics.materialCompleted();
                return true;
            }
        }
        throw new IllegalStateException("unhandled material bind status: " + status);
    }

    static boolean materialOnlyBindSettlesInteractiveUrgency(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent,
            long sectionMaterialRevision,
            long boundMaterialRevision
    ) {
        Objects.requireNonNull(readiness, "readiness");
        if (sectionMaterialRevision < 0L || boundMaterialRevision < 0L) {
            throw new IllegalArgumentException("material revisions must not be negative");
        }
        return !pendingWorldSceneBindPresent
                && !deferredWorldSceneBindPresent
                && readiness.worldTlasReady()
                && readiness.builtRevisionIsCurrent()
                && !readiness.hasPendingRtBuilds()
                && boundMaterialRevision >= sectionMaterialRevision;
    }

    private void bindWorldSceneDescriptors(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long dynamicMaterialRevision,
            boolean materialBuffersChanged,
            String bindReason
    ) {
        RtSceneBindCoordinator.WorldBindCompletion completion = sceneBindCoordinator.bindWorldSceneForDispatch(
                updatedWorldTlas,
                materialSnapshot,
                dynamicMaterialRevision,
                materialBuffersChanged,
                bindReason,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache
        );
        rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
        settlePublishedInteractiveTerrainTargets();
        recordBoundWorldTlas(completion.worldTlasUpdate());
        maybeLogWorldTlasBound(
                completion.worldTlasUpdate().revision(),
                completion.worldTlasUpdate().instanceCount(),
                completion.bindReason()
        );
    }

    private void recordBoundWorldTlas(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas) {
        diagnostics.causality().firstFrontWorld(
                "worldTlasBound",
                updatedWorldTlas.revision(),
                "sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", viewRevision=" + sceneBindCoordinator.publication().worldViewRevision()
        );
        diagnostics.causality().world(
                updatedWorldTlas.revision(),
                sceneBindCoordinator.publication().worldSectionKeys().size(),
                sceneBindCoordinator.publication().worldViewRevision(),
                0
        );
        for (SectionKey key : sceneBindCoordinator.publication().worldSectionKeys()) {
            long contentRevision = sceneBindCoordinator.publication().worldSectionContentRevisions().getOrDefault(key, -1L);
            diagnostics.causality().section(
                    top.ceroxe.mcvulkanrt.renderer.RtCausalitySink.Stage.SECTION_TLAS_BOUND,
                    key,
                    contentRevision,
                    updatedWorldTlas.revision(),
                    sceneBindCoordinator.publication().worldViewRevision(),
                    0
            );
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_TLAS_BOUND,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    key,
                    0,
                    updatedWorldTlas.revision(),
                    contentRevision,
                    sceneBindCoordinator.publication().worldViewRevision(),
                    0,
                    sceneBindCoordinator.publication().worldSectionKeys().size(),
                    sceneBindCoordinator.publication().generation()
            );
        }
    }

    private void rememberBoundCoverageContractionAuthorization(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas
    ) {
        boundWorldCoverageContractionAuthorizationGeneration = Math.max(
                boundWorldCoverageContractionAuthorizationGeneration,
                updatedWorldTlas.coverageContractionAuthorizationGeneration()
        );
        boundCommittedFrontSectionRevision = Math.max(
                boundCommittedFrontSectionRevision,
                updatedWorldTlas.sectionRevision()
        );
    }

    private void maybeLogWorldTlasBound(long revision, int instances, String bindReason) {
        loggedWorldTlasBinds++;
        if (loggedWorldTlasBinds > INITIAL_WORLD_TLAS_BIND_LOGS
                && loggedWorldTlasBinds % WORLD_TLAS_STATUS_LOG_INTERVAL != 0L) {
            return;
        }
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt world TLAS bound for dispatch: revision={}, instances={}, bindReason={}, {}",
                revision,
                instances,
                bindReason,
                worldTlasCache.readiness().asLogFragment()
        );
    }

    private long protectedSectionRevision() {
        long protectedRevision = worldTlasCache.protectedSectionRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    pending.worldTlasUpdate().previousSectionResourceRevision()
            );
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    deferred.worldTlasUpdate().previousSectionResourceRevision()
            );
        }
        return protectedRevision;
    }

    private long protectedDynamicRevision() {
        long protectedRevision = worldTlasCache.protectedDynamicRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(protectedRevision, pending.worldTlasUpdate().previousDynamicRevision());
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(protectedRevision, deferred.worldTlasUpdate().previousDynamicRevision());
        }
        return protectedRevision;
    }

    private long protectedSectionSceneRevision() {
        long protectedRevision = worldTlasCache.protectedSectionSceneRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    pending.worldTlasUpdate().previousSectionRevision()
            );
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    deferred.worldTlasUpdate().previousSectionRevision()
            );
        }
        return protectedRevision;
    }

    private void releaseRetiredBlasesThroughProtectedRevisions() {
        sectionBlasCache.releaseRetiredBlasesThrough(
                protectedSectionRevision(),
                protectedSectionSceneRevision()
        );
        dynamicBlasCache.releaseRetiredBlasesThrough(protectedDynamicRevision());
    }

    private void closePendingWorldSceneBind() {
        sceneBindCoordinator.closePendingWorld();
    }

    private void closePendingMaterialOnlyBind() {
        sceneBindCoordinator.closePendingMaterial();
    }

    private void closePendingDynamicTlasBind() {
        sceneBindCoordinator.discardPendingDynamicTlasBindIfPresent(
                "contextClosed", dynamicTlasCache, diagnostics
        );
    }

    private void discardDeferredWorldSceneBind(String reason) {
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred == null) {
            return;
        }
        sceneBindCoordinator.clearDeferredWorld(deferred);
        worldSceneBindStatistics.deferredWorldDiscarded();
        worldTlasCache.discardUnboundWorldTlas(deferred.worldTlasUpdate(), reason);
    }

    private void closeDeferredWorldSceneBind() {
        discardDeferredWorldSceneBind("closed");
    }

    private void maybeLogWorldTlasDispatch(
            long previousDispatches,
            boolean frameDispatchUseful,
            RtSceneReadiness readiness
    ) {
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        if (activity.frameDispatches() <= previousDispatches
                || activity.frameDispatches() == lastLoggedWorldTlasDispatches
                || !readiness.worldTlasReady()
                || !frameDispatchUseful) {
            return;
        }
        lastLoggedWorldTlasDispatches = activity.frameDispatches();
        if (activity.frameDispatches() > INITIAL_WORLD_TLAS_DISPATCH_LOGS
                && activity.frameDispatches() % WORLD_TLAS_STATUS_LOG_INTERVAL != 0L) {
            return;
        }
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt world TLAS dispatch active: {}, frameDispatchUseful={}, {}",
                activity.asLogFragment(),
                frameDispatchUseful,
                readiness.asLogFragment()
        );
    }

    @Override
    public RtFrameSnapshot latestFrameSnapshot() {
        return rayTracingPipeline.latestFrameSnapshot();
    }

    @Override
    public boolean requestGBufferCapture() {
        return rayTracingPipeline.requestGBufferCapture();
    }

    @Override
    public RtGBufferSnapshot latestGBufferSnapshot() {
        return rayTracingPipeline.latestGBufferSnapshot();
    }

    @Override
    public long latestSharedFrameSequence() {
        return rayTracingPipeline.latestSharedFrameSequence();
    }

    @Override
    public RtCore.SharedFrameState latestSharedFrameState() {
        return rayTracingPipeline.latestSharedFrameState();
    }

    @Override
    public RtCore.SharedFrameImage exportLatestSharedFrameImage() {
        if (!sharedPresentationReady()) {
            return null;
        }
        return rayTracingPipeline.exportLatestSharedFrameImage();
    }

    @Override
    public RtCore.SharedFrameImage exportSharedFrameImage(long requiredFrameStateSequence) {
        if (!sharedPresentationReady()) {
            return null;
        }
        return rayTracingPipeline.exportSharedFrameImage(requiredFrameStateSequence);
    }

    @Override
    public boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage) {
        return rayTracingPipeline.acknowledgeSharedFramePresented(frameStateSequence, vulkanImage);
    }

    @Override
    public RtCore.RuntimeActivity runtimeActivity() {
        RtCommandContext buildCommands = queueContexts.buildCommands();
        RtCommandContext sectionCommands = queueContexts.sectionBlasCommands();
        return rayTracingPipeline.runtimeActivity().withGpuWorkTiming(new RtCore.GpuWorkTiming(
                gpuStageTiming(sectionCommands, RtGpuWorkLabels.SECTION_BLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.DYNAMIC_BLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.DYNAMIC_TLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.WORLD_TLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.MATERIAL_UPLOAD)
        ));
    }

    private static RtCore.GpuStageTiming gpuStageTiming(RtCommandContext context, String label) {
        RtGpuTimestampPool.StageSnapshot snapshot = context.gpuStageTimestampSnapshot(label);
        return new RtCore.GpuStageTiming(
                snapshot.enabled(),
                snapshot.acquiredCaptures(),
                snapshot.completedCaptures(),
                snapshot.droppedCaptures(),
                snapshot.failedCaptures(),
                snapshot.lastNanos(),
                snapshot.averageNanos(),
                snapshot.maxNanos()
        );
    }

    @Override
    public RtCore.NativeFrameTiming nativeFrameTiming() {
        return acceptFrameTiming.snapshot();
    }

    @Override
    public RtCore.NativeDispatchDecision nativeDispatchDecision() {
        return rayTracingPipeline.nativeDispatchDecision();
    }

    @Override
    public RtCore.DynamicGenerationState dynamicGenerationState() {
        RtDynamicResidencyState residency = dynamicBlasCache.snapshotResidencyState();
        return new RtCore.DynamicGenerationState(
                dynamicBlasCache.latestObservedSceneRevision(),
                dynamicBlasCache.revision(),
                dynamicBlasCache.topologyRevision(),
                dynamicBlasCache.geometryRevision(),
                dynamicBlasCache.latestCausality(),
                residency.pendingAssetBuilds(),
                residency.queuedAssetBuilds(),
                residency.inactiveAssetSlots(),
                residency.replacementAssetSlots(),
                worldTlasCache.boundDynamicRevision(),
                worldTlasCache.boundDynamicTopologyRevision(),
                worldTlasCache.boundDynamicGeometryRevision(),
                rayTracingPipeline.activeDescriptorGeneration(),
                rayTracingPipeline.latestDynamicSceneRevision(),
                rayTracingPipeline.latestDispatchedDynamicSceneRevision(),
                rayTracingPipeline.latestCompletedDynamicSceneRevision()
        );
    }

    @Override
    public RtCore.SectionGenerationState sectionGenerationState(SectionKey key) {
        Objects.requireNonNull(key, "key");
        RtSectionDebugState cache = sectionBlasCache.snapshotSectionDebugState(key);
        long publishedRevision = sceneBindCoordinator.publication().worldSectionContentRevisions().getOrDefault(key, -1L);
        boolean published = publishedRevision >= 0L && sceneBindCoordinator.publication().worldSectionKeys().contains(key);
        RtCore.FrameGenerationProgress progress = published
                ? rayTracingPipeline.sectionFrameProgress(key, publishedRevision)
                : RtCore.FrameGenerationProgress.unavailable();
        return new RtCore.SectionGenerationState(
                key,
                cache.desiredContentRevision(),
                cache.activeContentRevision(),
                publishedRevision,
                cache.geometryGeneration(),
                cache.materialGeneration(),
                cache.buildSequence(),
                cache.queued(),
                cache.recording(),
                cache.gpuInFlight(),
                cache.active(),
                published,
                published,
                published ? sceneBindCoordinator.publication().worldTlasRevision() : -1L,
                published ? sceneBindCoordinator.publication().generation() : -1L,
                progress,
                cache.causality(),
                published ? sceneBindCoordinator.publication().worldSectionCausality(key) : RendererFrameCausality.untraced(0L)
        );
    }

    @Override
    public RtCore.DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
        RtDynamicEntityDebugState cache = dynamicBlasCache.snapshotEntityDebugState(entityId);
        int publishedPrimitiveCount = 0;
        long publishedNewestAssetRevision = -1L;
        for (DynamicRenderScene.DynamicModelInstance instance : sceneBindCoordinator.publication().dynamicScene().modelInstances()) {
            if (DynamicModelIdentity.entityIdFromPrimitiveId(instance.id()) == entityId) {
                publishedPrimitiveCount++;
                publishedNewestAssetRevision = Math.max(
                        publishedNewestAssetRevision,
                        instance.asset().revision()
                );
            }
        }
        boolean published = publishedPrimitiveCount > 0;
        if (!cache.observed() && !published) {
            return RtCore.DynamicEntityGenerationState.unavailable(entityId);
        }
        long publishedSceneRevision = published ? sceneBindCoordinator.publication().dynamicScene().revision() : -1L;
        RtCore.FrameGenerationProgress progress = published
                ? rayTracingPipeline.dynamicFrameProgress(publishedSceneRevision)
                : RtCore.FrameGenerationProgress.unavailable();
        return new RtCore.DynamicEntityGenerationState(
                entityId,
                cache.sceneRevision(),
                cache.primitiveCount(),
                cache.assetCount(),
                cache.queuedAssetCount(),
                cache.pendingAssetCount(),
                cache.residentAssetCount(),
                cache.newestAssetRevision(),
                cache.observed() ? dynamicBlasCache.revision() : -1L,
                worldTlasCache.boundDynamicRevision(),
                published,
                publishedPrimitiveCount,
                publishedNewestAssetRevision,
                published ? sceneBindCoordinator.publication().generation() : -1L,
                published ? sceneBindCoordinator.publication().dynamicTlasRevision() : -1L,
                publishedSceneRevision,
                progress,
                cache.causality(),
                published ? sceneBindCoordinator.publication().causality() : RendererFrameCausality.untraced(0L)
        );
    }

    @Override
    public RtCore.ScenePublicationState scenePublicationState() {
        return sceneBindCoordinator.publication().snapshot();
    }

    @Override
    public NativeTerrainOwnership nativeTerrainOwnership() {
        return sectionBlasCache.terrainOwnership(
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldTlasRevision()
        );
    }

    @Override
    public long nativeTerrainOwnershipGeneration() {
        return sectionBlasCache.terrainOwnershipGeneration(sceneBindCoordinator.publication().worldTlasRevision());
    }

    @Override
    public RtSceneReadiness sceneReadiness() {
        RtSceneReadiness readiness = worldTlasCache.readiness(sectionBlasCache, dynamicBlasCache);
        return applyPendingWorldSceneReadiness(readiness);
    }

    private RtSceneReadiness observedSceneReadiness() {
        return applyPendingWorldSceneReadiness(worldTlasCache.publishedReadiness());
    }

    private RtSceneReadiness applyPendingWorldSceneReadiness(RtSceneReadiness readiness) {
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            return unboundWorldSceneReadiness(readiness, pending.worldTlasUpdate());
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            return unboundWorldSceneReadiness(readiness, deferred.worldTlasUpdate());
        }
        return readiness;
    }

    @Override
    public Set<SectionKey> latestSharedFrameSectionKeys() {
        return rayTracingPipeline.latestSharedFrameSectionKeys();
    }

    private static RtSceneReadiness unboundWorldSceneReadiness(
            RtSceneReadiness readiness,
            RtWorldTlasCache.WorldTlasUpdate unboundUpdate
    ) {
        return new RtSceneReadiness(
                unboundUpdate.previousTopLevelAccelerationStructure() != null,
                readiness.observedInstances(),
                readiness.observedSectionInstances(),
                readiness.observedDynamicInstances(),
                unboundUpdate.previousSectionInstanceCount(),
                unboundUpdate.previousDynamicInstanceCount(),
                readiness.pendingRtSectionBuilds(),
                readiness.pendingRtDynamicBuild(),
                readiness.pendingRtTriangles(),
                readiness.cachedRtTriangles(),
                unboundUpdate.previousRevision(),
                readiness.latestRevision(),
                true
        );
    }

    @Override
    public RtCore.ExternalMemoryInteropProbe probeExternalMemoryInterop() {
        return RtExternalInteropProbeOrchestrator.probeMemory(
                physicalDevice,
                device,
                interopCapabilities.gpuInteropCandidate(),
                interopCapabilities.reason(),
                interopCapabilities.memoryProbe().successful(),
                interopCapabilities.memoryProbe().reason(),
                interopCapabilities.dedicatedOnly()
        );
    }

    @Override
    public RtCore.ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop() {
        return RtExternalInteropProbeOrchestrator.probeSemaphore(
                device,
                interopCapabilities.gpuInteropCandidate(),
                interopCapabilities.reason(),
                interopCapabilities.semaphoreProbe().successful(),
                interopCapabilities.semaphoreProbe().reason()
        );
    }

    @Override
    public String summary() {
        return "nativeBackend=vulkanDevice"
                + ", device=" + deviceName
                + ", queueFamily=" + queueContexts.queueFamilyIndex()
                + ", requestedQueueCount=" + queueContexts.requestedQueueCount()
                + ", queueHandle=0x" + Long.toHexString(queueContexts.frameQueue().address())
                + ", frameQueueHandle=0x" + Long.toHexString(queueContexts.frameQueue().address())
                + ", buildQueueHandle=" + "0x" + Long.toHexString(queueContexts.buildQueue().address())
                + ", queuesSeparated=" + queueContexts.queuesSeparated()
                + ", allocator=0x" + Long.toHexString(allocator)
                + ", outputOwnership=rendererSharedImage"
                + ", gpuSharedReady=" + sharedPresentationReady()
                + ", pendingWorldSceneBind=" + sceneBindCoordinator.state().hasPendingWorld()
                + ", pendingWorldSceneBindRevision=" + (!sceneBindCoordinator.state().hasPendingWorld()
                ? -1L
                : sceneBindCoordinator.state().pendingWorld().worldTlasUpdate().revision())
                + ", pendingMaterialOnlyBind=" + sceneBindCoordinator.state().hasPendingMaterial()
                + ", pendingDynamicTlasBind=" + sceneBindCoordinator.state().hasPendingDynamic()
                + ", boundDynamicTlas=" + (sceneBindCoordinator.publication().dynamicTlas() != null)
                + ", latestViewRevision=" + latestViewRevision
                + ", publishedWorldViewRevision=" + sceneBindCoordinator.publication().worldViewRevision()
                + ", boundMaterialRevision=" + sceneBindCoordinator.publication().materialSnapshot().revision()
                + ", boundMaterialTextureRevision=" + sceneBindCoordinator.publication().materialSnapshot().textureSnapshot().revision()
                + ", deferredWorldSceneBind=" + sceneBindCoordinator.state().hasDeferredWorld()
                + ", deferredWorldSceneBindRevision=" + (!sceneBindCoordinator.state().hasDeferredWorld()
                ? -1L
                : sceneBindCoordinator.state().deferredWorld().worldTlasUpdate().revision())
                + ", " + worldSceneBindStatistics.summary()
                + ", minStreamingSceneBindIntervalMillis=" + minStreamingSceneBindIntervalNanos / 1_000_000L
                + ", maxStreamingSceneBindDeferrals=" + maxStreamingSceneBindDeferrals
                + ", immediateStreamingBindMaxFaces=" + immediateStreamingBindMaxFaces
                + ", pendingInteractiveWorldSceneBindUrgency=" + interactiveTerrain.urgent()
                + ", interactivePresentTargets=" + interactiveTerrain.presentTargetCount()
                + ", interactiveAbsentTargets=" + interactiveTerrain.absentTargetCount()
                + ", frameFirstBuildBudgetDeferrals=" + frameFirstBuildBudgetDeferrals
                + ", framePendingBuildBudgetDeferrals=" + framePendingBuildBudgetDeferrals
                + ", foregroundFrameBudgetDeferrals=" + foregroundFrameBudgetDeferrals
                + ", foregroundFrameFirstDispatches=" + foregroundFrameFirstDispatches
                + ", foregroundFrameCoverageDeferrals=" + foregroundFrameCoverageDeferrals
                + ", stableFrameFastPathDispatches=" + stableFrameFastPathDispatches
                + ", stableFramePendingGpuSkips=" + stableFramePendingGpuSkips
                + ", dynamicWorldTlasDispatchBlocks=" + dynamicWorldTlasDispatchBlocks
                + ", lastDynamicWorldTlasDispatchBlockReason=" + lastDynamicWorldTlasDispatchBlockReason
                + ", maxPendingFrameAgeBeforeBuildMillis=" + maxPendingFrameAgeBeforeBuildMillis
                + ", maxConvergenceVisualStalenessMillis="
                + maxConvergenceVisualStalenessNanos / 1_000_000L
                + ", foregroundFrameBudgetMicros=" + foregroundFrameBudgetNanos / 1_000L
                + ", " + acceptFrameTiming.summary()
                + ", " + queueContexts.frameCommands().summary("commandContext")
                + ", " + queueContexts.buildCommands().summary("buildCommandContext")
                + ", " + queueContexts.sectionBlasCommands().summary("sectionBlasCommandContext")
                + ", " + bootstrapBuffer.summary("bootstrapBuffer")
                + ", asScratchAlign=" + accelerationStructureScratchAlignment
                + ", " + bootstrapBlas.summary("bootstrapBlas")
                + ", " + bootstrapTlas.summary("bootstrapTlas")
                + ", " + sceneMaterialTable.summary("sceneMaterialTable")
                + ", " + sectionBlasCache.summary("sectionBlasCache")
                + ", " + dynamicBlasCache.summary("dynamicBlasCache")
                + ", " + dynamicTlasCache.summary()
                + ", " + worldTlasCache.summary("worldTlasCache")
                + ", " + rayTracingPipelineProperties.summary("rayTracingPipelineProperties")
                + ", " + rayTracingPipeline.summary("rayTracingPipeline")
                + ", " + interopCapabilities.summary()
                + ", enabledExtensions=" + enabledExtensions;
    }

    private boolean sharedPresentationReady() {
        return interopCapabilities.sharedPresentationReady();
    }

    static boolean sharedPresentationReady(
            boolean gpuInteropCandidate,
            boolean externalMemoryProbeSuccessful,
            boolean externalSemaphoreProbeSuccessful
    ) {
        return gpuInteropCandidate && externalMemoryProbeSuccessful && externalSemaphoreProbeSuccessful;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    static int requestedQueueCount(int availableQueues) {
        if (availableQueues <= 0) {
            throw new IllegalArgumentException("availableQueues must be positive");
        }
        /*
         * Build products are consumed by frame dispatch through acceleration-
         * structure, descriptor, and material-buffer dependencies. Until those
         * dependencies are represented by explicit cross-queue timeline values,
         * assigning producer and consumer work to different VkQueue instances is
         * invalid: a host-observed fence completion does not create a device-side
         * semaphore dependency for submissions already resident on another queue.
         * Keep one ordered RHI queue and overlap CPU recording plus bounded frame
         * resources instead, matching UE's rule that queue separation only occurs
         * behind an explicit render-graph synchronization contract.
         */
        return 1;
    }

    static VkQueue getDeviceQueue(MemoryStack stack, VkDevice device, int queueFamilyIndex, int queueIndex) {
        PointerBuffer queueHandle = stack.mallocPointer(1);
        VK10.vkGetDeviceQueue(device, queueFamilyIndex, queueIndex, queueHandle);
        long address = queueHandle.get(0);
        if (address == 0L) {
            throw new IllegalStateException("vkGetDeviceQueue returned null for queueIndex=" + queueIndex);
        }
        return new VkQueue(address, device);
    }

    private static int bootstrapBufferUsageFlags() {
        return VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    }

}
