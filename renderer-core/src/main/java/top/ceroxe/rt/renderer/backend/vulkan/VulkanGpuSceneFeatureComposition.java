package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.feature.VulkanDenoisingResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameGenerationResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanNrdComposePipeline;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFramePublicationPipeline;

import java.util.Objects;
import java.util.Optional;

/**
 * Owns optional NVIDIA-facing GPUScene resources and their frame-boundary activation state.
 *
 * <p>A session may disable a feature after a runtime failure, but it may never resurrect it from
 * a stale capability snapshot. Resource allocation remains stable until the session closes, while
 * each free frame slot is reconfigured immediately before reuse.</p>
 */
final class VulkanGpuSceneFeatureComposition implements AutoCloseable {
    private boolean denoisingActive;
    private boolean reconstructionActive;
    private boolean frameGenerationActive;
    private boolean temporalReconstructionActive;
    private final Selection reservedSelection;
    private final VulkanDenoisingDescriptorPlaceholders placeholders;
    private final VulkanNrdComposePipeline nrdComposePipeline;
    private final VulkanFramePublicationPipeline publicationPipeline;
    private boolean closed;

    static Selection select(RenderingFeatureCapabilities capabilities) {
        RenderingFeatureCapabilities checked = Objects.requireNonNull(capabilities, "capabilities");
        boolean reconstruction = executable(checked.feature(Feature.FRAME_RECONSTRUCTION));
        boolean temporalReconstruction = reconstruction && (
                executable(checked.technology(Technology.TEMPORAL_SUPER_RESOLUTION))
                        || executable(checked.technology(Technology.NATIVE_TEMPORAL_ANTI_ALIASING))
        );
        return new Selection(
                executable(checked.feature(Feature.DENOISING)),
                reconstruction,
                executable(checked.feature(Feature.FRAME_GENERATION)),
                temporalReconstruction
        );
    }

    static VulkanGpuSceneFeatureComposition open(
            VulkanDeviceRuntime device,
            int frameCount,
            boolean linearHdr,
            Selection selection
    ) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        Selection checkedSelection = Objects.requireNonNull(selection, "selection");
        VulkanDenoisingDescriptorPlaceholders placeholders = null;
        VulkanNrdComposePipeline composePipeline = null;
        VulkanFramePublicationPipeline publicationPipeline = null;
        try {
            placeholders = VulkanDenoisingDescriptorPlaceholders.create(checkedDevice);
            if (checkedSelection.denoising()) {
                composePipeline = VulkanNrdComposePipeline.open(
                        checkedDevice,
                        frameCount,
                        linearHdr || checkedSelection.reconstruction()
                );
            }
            if (publicationRequired(linearHdr, checkedSelection)) {
                publicationPipeline = VulkanFramePublicationPipeline.open(checkedDevice, frameCount);
            }
            VulkanGpuSceneFeatureComposition result = new VulkanGpuSceneFeatureComposition(
                    checkedSelection, placeholders, composePipeline, publicationPipeline
            );
            placeholders = null;
            composePipeline = null;
            publicationPipeline = null;
            return result;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(failure, publicationPipeline);
            closeSuppressing(failure, composePipeline);
            closeSuppressing(failure, placeholders);
            throw failure;
        }
    }

    private VulkanGpuSceneFeatureComposition(
            Selection selection,
            VulkanDenoisingDescriptorPlaceholders placeholders,
            VulkanNrdComposePipeline nrdComposePipeline,
            VulkanFramePublicationPipeline publicationPipeline
    ) {
        denoisingActive = selection.denoising();
        reconstructionActive = selection.reconstruction();
        frameGenerationActive = selection.frameGeneration();
        temporalReconstructionActive = selection.temporalReconstruction();
        reservedSelection = selection;
        this.placeholders = Objects.requireNonNull(placeholders, "placeholders");
        this.nrdComposePipeline = nrdComposePipeline;
        this.publicationPipeline = publicationPipeline;
        if (denoisingActive != (nrdComposePipeline != null)) {
            throw new IllegalArgumentException("active denoising requires its compose pipeline");
        }
    }

    static boolean publicationRequired(boolean linearHdrOutput, Selection selection) {
        return !linearHdrOutput && Objects.requireNonNull(selection, "selection").reconstruction();
    }

    boolean denoisingActive() {
        return denoisingActive;
    }

    boolean reconstructionActive() {
        return reconstructionActive;
    }

    boolean frameGenerationActive() {
        return frameGenerationActive;
    }

    Selection frameSelection(boolean exactDepthProjection) {
        Selection retained = selection();
        // NRD, reconstruction, and generated-frame tagging all consume the exact depth contract.
        // Missing per-frame input is not a session failure: preferred features sit out this frame
        // and may resume on a later frame whose projection is known.
        return retained.forTemporalContract(exactDepthProjection);
    }

    Selection frameSelection(VulkanFrameSlot slot) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        return requireFrameSelection(new Selection(
                checked.denoisingEnabled(),
                checked.reconstructionEnabled(),
                checked.frameGenerationEnabled(),
                checked.reconstructionEnabled() && temporalReconstructionActive
        ));
    }

    void refresh(RenderingFeatureCapabilities capabilities) {
        Selection retained = selection().retain(select(capabilities));
        // Recovery requires a newly negotiated session. A stale ACTIVE observation must not
        // resurrect resources after a runtime feature state machine has selected fallback.
        denoisingActive = retained.denoising();
        reconstructionActive = retained.reconstruction();
        frameGenerationActive = retained.frameGeneration();
        temporalReconstructionActive = retained.temporalReconstruction();
    }

    void applyReconfiguration(RenderingFeatureCapabilities capabilities) {
        Selection selected = reservedSelection.retain(select(capabilities));
        denoisingActive = selected.denoising();
        reconstructionActive = selected.reconstruction();
        frameGenerationActive = selected.frameGeneration();
        temporalReconstructionActive = selected.temporalReconstruction();
    }

    private Selection selection() {
        return new Selection(
                denoisingActive, reconstructionActive, frameGenerationActive,
                reconstructionActive && temporalReconstructionActive
        );
    }

    void reconfigure(VulkanFrameSlot slot, Selection frameSelection) {
        Selection selected = requireFrameSelection(frameSelection);
        Objects.requireNonNull(slot, "slot").reconfigureFeatures(
                selected.denoising(), selected.reconstruction(), selected.frameGeneration()
        );
    }

    Optional<VulkanDenoisingResourceContract> denoisingResources(
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        return requireFrameSelection(frameSelection).denoising()
                ? Optional.of(Objects.requireNonNull(slot, "slot").denoisingResources().contract())
                : Optional.empty();
    }

    Optional<VulkanFrameReconstructionResourceContract> reconstructionResources(
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        return requireFrameSelection(frameSelection).reconstruction()
                ? Optional.of(Objects.requireNonNull(slot, "slot").reconstructionResources().contract())
                : Optional.empty();
    }

    Optional<VulkanFrameGenerationResourceContract> frameGenerationResources(
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        Selection selected = requireFrameSelection(frameSelection);
        return selected.frameGeneration() && !selected.reconstruction()
                ? Optional.of(Objects.requireNonNull(slot, "slot").frameGenerationResources().contract())
                : Optional.empty();
    }

    RtGpuImage traceOutput(VulkanFrameSlot slot, Selection frameSelection) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        Selection selected = requireFrameSelection(frameSelection);
        if (selected.denoising()) return checked.traceImage();
        if (selected.reconstruction()) return checked.reconstructionResources().inputColor();
        return checked.outputImage();
    }

    RtGpuImage reconstructionOutput(VulkanFrameSlot slot, Selection frameSelection) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        return requireFrameSelection(frameSelection).reconstruction()
                ? checked.reconstructionOutputImage()
                : checked.outputImage();
    }

    GpuSceneDescriptorResources.DenoisingImageViews denoisingDescriptorViews(
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        return requireFrameSelection(frameSelection).denoising()
                ? Objects.requireNonNull(slot, "slot").denoisingResources().descriptorViews()
                : placeholders.views();
    }

    GpuSceneDescriptorResources.ReconstructionImageViews reconstructionDescriptorViews(
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        Selection selected = requireFrameSelection(frameSelection);
        if (selected.reconstruction()) return checked.reconstructionResources().descriptorViews();
        if (selected.frameGeneration()) return checked.frameGenerationResources().descriptorViews();
        return placeholders.reconstructionViews();
    }

    boolean denoisingLayoutsInitialized(VulkanFrameSlot slot, Selection frameSelection) {
        return requireFrameSelection(frameSelection).denoising()
                && Objects.requireNonNull(slot, "slot").denoisingResources().layoutsInitialized();
    }

    boolean reconstructionLayoutsInitialized(VulkanFrameSlot slot, Selection frameSelection) {
        return requireFrameSelection(frameSelection).reconstruction()
                && Objects.requireNonNull(slot, "slot").reconstructionResources().layoutsInitialized();
    }

    boolean frameGenerationLayoutsInitialized(VulkanFrameSlot slot, Selection frameSelection) {
        Selection selected = requireFrameSelection(frameSelection);
        return selected.frameGeneration() && !selected.reconstruction()
                && Objects.requireNonNull(slot, "slot").frameGenerationResources().layoutsInitialized();
    }

    boolean traceLayoutInitialized(VulkanFrameSlot slot, Selection frameSelection) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        Selection selected = requireFrameSelection(frameSelection);
        if (selected.reconstruction()) return checked.reconstructionResources().layoutsInitialized();
        return selected.denoising() && checked.traceLayoutInitialized();
    }

    void markLayoutsInitialized(VulkanFrameSlot slot, Selection frameSelection) {
        VulkanFrameSlot checked = Objects.requireNonNull(slot, "slot");
        Selection selected = requireFrameSelection(frameSelection);
        if (selected.denoising()) checked.denoisingResources().markLayoutsInitialized();
        if (selected.reconstruction()) checked.reconstructionResources().markLayoutsInitialized();
        if (selected.frameGeneration() && !selected.reconstruction()) {
            checked.frameGenerationResources().markLayoutsInitialized();
        }
    }

    void recordNrdComposition(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameSlot slot,
            int width,
            int height,
            Selection frameSelection
    ) {
        Selection selected = requireFrameSelection(frameSelection);
        if (!selected.denoising()) return;
        VulkanNrdComposePipeline compose = Objects.requireNonNull(
                nrdComposePipeline, "active denoising compose pipeline"
        );
        GpuSceneDescriptorResources.DenoisingImageViews views =
                Objects.requireNonNull(slot, "slot").denoisingResources().descriptorViews();
        compose.record(
                commandBuffer,
                stack,
                slot.index(),
                width,
                height,
                slot.traceImage().imageView(),
                views.diffuseRadianceHitDistance(),
                views.specularRadianceHitDistance(),
                views.viewZ(),
                views.denoisedDiffuseRadianceHitDistance(),
                views.denoisedSpecularRadianceHitDistance(),
                views.diffuseMaterialFactor(),
                views.specularMaterialFactor(),
                selected.reconstruction()
                        ? slot.reconstructionResources().inputColor().imageView()
                        : slot.outputImage().imageView()
        );
        if (selected.reconstruction()) {
            VulkanNrdComposePipeline.recordCompletionBarrier(commandBuffer, stack);
        }
    }

    void recordPublication(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanFrameSlot slot,
            Selection frameSelection
    ) {
        Selection selected = requireFrameSelection(frameSelection);
        if (!selected.reconstruction()) return;
        RtGpuImage reconstructionOutput = Objects.requireNonNull(slot, "slot")
                .reconstructionOutputImage();
        if (reconstructionOutput.image() == slot.outputImage().image()) return;
        Objects.requireNonNull(publicationPipeline, "SDR reconstruction publication pipeline")
                .record(commandBuffer, stack, slot.index(), reconstructionOutput, slot.outputImage());
    }

    private Selection requireFrameSelection(Selection frameSelection) {
        Selection checked = Objects.requireNonNull(frameSelection, "frameSelection");
        if (!selection().retain(checked).equals(checked)) {
            throw new IllegalArgumentException("frame selection cannot enable a session-disabled feature");
        }
        return checked;
    }

    @Override
    public void close() {
        if (closed) return;
        // Reverse creation order: the compose pipeline may reference placeholder-compatible
        // layouts, so it must be destroyed before the placeholder images and views.
        if (publicationPipeline != null) publicationPipeline.close();
        if (nrdComposePipeline != null) nrdComposePipeline.close();
        placeholders.close();
        closed = true;
    }

    private static boolean executable(RenderingFeatureCapabilities.Entry entry) {
        Status status = entry.status();
        if (status == Status.ACTIVE || status == Status.AVAILABLE) return true;
        return (status == Status.FALLBACK_PENDING || status == Status.FALLBACK)
                && !"builtin.temporal".equals(entry.implementation())
                && !"renderer.temporal".equals(entry.implementation())
                && !"native.presentation".equals(entry.implementation())
                && !"renderer.native-presentation".equals(entry.implementation())
                && !"none".equals(entry.implementation());
    }

    private static void closeSuppressing(Throwable failure, AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    record Selection(
            boolean denoising,
            boolean reconstruction,
            boolean frameGeneration,
            boolean temporalReconstruction
    ) {
        Selection {
            if (temporalReconstruction && !reconstruction) {
                throw new IllegalArgumentException(
                        "temporal reconstruction requires reconstruction resources"
                );
            }
        }

        Selection(boolean denoising, boolean reconstruction, boolean frameGeneration) {
            this(denoising, reconstruction, frameGeneration, reconstruction);
        }

        static Selection disabled() {
            return new Selection(false, false, false, false);
        }

        Selection forTemporalContract(boolean exactDepthProjection) {
            if (exactDepthProjection) return this;
            // An unknown projection invalidates every consumer of depth, motion, or temporal
            // history. Spatial reconstruction is intentionally retained: NIS consumes only the
            // current color input/output pair and has no depth-projection contract.
            return new Selection(false, reconstruction && !temporalReconstruction, false, false);
        }

        Selection retain(Selection next) {
            Selection checked = Objects.requireNonNull(next, "next");
            return new Selection(
                    denoising && checked.denoising,
                    reconstruction && checked.reconstruction,
                    frameGeneration && checked.frameGeneration,
                    temporalReconstruction && checked.temporalReconstruction
            );
        }
    }
}
