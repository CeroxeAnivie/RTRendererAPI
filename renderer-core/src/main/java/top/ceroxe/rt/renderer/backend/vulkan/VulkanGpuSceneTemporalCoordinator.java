package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.HistoryInvalidationReason;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneTemporalFrameResources;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Coordinates CPU temporal provenance and GPU ping-pong history as one transaction. */
final class VulkanGpuSceneTemporalCoordinator implements AutoCloseable {
    private final RendererConfig configuration;
    private final TemporalHistoryTracker sourceHistory;
    private final VulkanTemporalHistory gpuHistory;
    private final VulkanMediumBoundaryIndex mediumBoundaries = new VulkanMediumBoundaryIndex();

    VulkanGpuSceneTemporalCoordinator(VulkanDeviceRuntime device, RendererConfig configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        sourceHistory = new TemporalHistoryTracker(
                configuration.temporalRendering(), vendorTemporalProvenanceRequested(configuration)
        );
        gpuHistory = new VulkanTemporalHistory(
                Objects.requireNonNull(device, "device"), configuration.temporalRendering().enabled()
        );
    }

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        sourceHistory.sceneApplied(changes);
        mediumBoundaries.sceneApplied(changes);
    }

    long requiredGrowthBytes(int width, int height) {
        return gpuHistory.requiredGrowthBytes(width, height);
    }

    boolean extentMatches(int width, int height) {
        return gpuHistory.extentMatches(width, height);
    }

    void ensureExtent(int width, int height) {
        gpuHistory.ensureExtent(width, height);
    }

    Prepared prepare(
            RenderFrameRequest request,
            VulkanFrameExtents extents,
            int lightSlotUpperBound,
            long sceneRevision,
            VulkanFrameSlot slot,
            boolean denoisingActive,
            boolean streamlineResourcesActive,
            boolean temporalReconstructionActive
    ) {
        RenderFrameRequest checkedRequest = Objects.requireNonNull(request, "request");
        VulkanFrameExtents checkedExtents = Objects.requireNonNull(extents, "extents");
        VulkanFrameSlot checkedSlot = Objects.requireNonNull(slot, "slot");
        TemporalHistoryTracker.PreparedFrame source = sourceHistory.prepare(
                checkedRequest, sceneRevision
        );
        boolean jitterActive = configuration.temporalRendering().enabled()
                || temporalReconstructionActive;
        float[] currentJitter = VulkanFrameUniformPacker.temporalJitter(
                checkedRequest.sequence(), jitterActive
        );
        float[] previousJitter = VulkanFrameUniformPacker.temporalJitter(
                source.previousSequence(), jitterActive
        );
        byte[] uniforms = VulkanFrameUniformPacker.pack(
                checkedRequest,
                checkedExtents,
                lightSlotUpperBound,
                sceneRevision,
                source,
                configuration.temporalRendering(),
                denoisingActive,
                streamlineResourcesActive,
                jitterActive
        );
        var mediumUniforms = java.nio.ByteBuffer.wrap(uniforms).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int[] containingMedia = mediumBoundaries.containing(checkedRequest);
        mediumUniforms.putInt(VulkanGpuSceneAbi.FRAME_TRANSMISSIVE_SURFACE_COUNT_WORD * 4, mediumBoundaries.transmissiveCount());
        mediumUniforms.putInt(VulkanGpuSceneAbi.FRAME_CAMERA_MEDIUM_COUNT_WORD * 4, containingMedia.length);
        mediumUniforms.putInt(VulkanGpuSceneAbi.FRAME_MEDIUM_BOUNDARY_COUNT_WORD * 4, mediumBoundaries.frameBoundaryCount(checkedRequest.primitiveBatch()));
        for (int i = 0; i < containingMedia.length; i++)
            mediumUniforms.putInt((VulkanGpuSceneAbi.FRAME_CAMERA_MEDIUM_SLOTS_WORD + i) * 4, containingMedia[i]);
        VulkanTemporalHistory.PreparedFrame gpu = gpuHistory.prepareFrame(
                configuration.temporalRendering().enabled() ? checkedSlot.motionImage() : null,
                configuration.temporalRendering().enabled() && checkedSlot.motionLayoutInitialized()
        );
        GpuSceneTemporalFrameResources resources = new GpuSceneTemporalFrameResources(
                gpu.colorInput(),
                gpu.colorOutput(),
                gpu.geometryInput(),
                gpu.geometryOutput(),
                gpu.motionOutput(),
                gpu.inputLayoutInitialized(),
                gpu.outputLayoutInitialized(),
                gpu.motionLayoutInitialized()
        );
        Optional<VulkanTemporalFrameInput> featureInput = denoisingActive || streamlineResourcesActive
                ? Optional.of(new VulkanTemporalFrameInput(
                        checkedRequest,
                        source.previousCamera(),
                        source.previousDepthProjection(),
                        source.previousSequence(),
                        source.historyValid(),
                        currentJitter[0],
                        currentJitter[1],
                        previousJitter[0],
                        previousJitter[1]
                ))
                : Optional.empty();
        return new Prepared(source, gpu, uniforms, resources, featureInput);
    }

    private static boolean vendorTemporalProvenanceRequested(
            RendererConfig configuration
    ) {
        boolean temporalReconstruction = configuration.frameReconstruction()
                .preference().requested()
                && configuration.frameReconstruction().mode()
                != top.ceroxe.rt.renderer.api.FrameReconstructionOptions.Mode.SPATIAL_UPSCALING;
        return configuration.denoising().preference().requested()
                || configuration.frameGeneration().preference().requested()
                || temporalReconstruction;
    }

    void commit(Prepared prepared) {
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        // GPU state is committed first because the CPU provenance commit cannot fail after its
        // stale-source checks pass. A rejected command recording never reaches either commit.
        gpuHistory.commit(checked.gpu());
        sourceHistory.commit(checked.source());
    }

    void invalidate(HistoryInvalidationReason reason) {
        sourceHistory.invalidate(reason);
    }

    @Override
    public void close() {
        try { gpuHistory.close(); } finally { mediumBoundaries.clear(); }
    }

    record Prepared(
            TemporalHistoryTracker.PreparedFrame source,
            VulkanTemporalHistory.PreparedFrame gpu,
            byte[] uniforms,
            GpuSceneTemporalFrameResources resources,
            Optional<VulkanTemporalFrameInput> featureInput
    ) {
        Prepared {
            source = Objects.requireNonNull(source, "source");
            gpu = Objects.requireNonNull(gpu, "gpu");
            uniforms = Objects.requireNonNull(uniforms, "uniforms").clone();
            resources = Objects.requireNonNull(resources, "resources");
            featureInput = Objects.requireNonNull(featureInput, "featureInput");
        }

        @Override
        public byte[] uniforms() {
            return uniforms.clone();
        }

        Set<HistoryInvalidationReason> invalidations() {
            return source.invalidations();
        }
    }
}
