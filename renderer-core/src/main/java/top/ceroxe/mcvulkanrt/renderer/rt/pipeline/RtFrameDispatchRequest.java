package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameState;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;

import java.util.Objects;

/**
 * Immutable input proof for one ray-tracing frame submission.
 *
 * <p>This boundary deliberately contains no Vulkan resource. It proves that the scene publication,
 * frozen section membership, material descriptors, dynamic TLAS, and camera state all describe the
 * same renderer-owned front before the pipeline acquires a command buffer. Keeping that proof out of
 * the command-recording method makes a rejected frame explainable without partially touching a slot.
 */
final class RtFrameDispatchRequest {
    private final RtCommandContext commandContext;
    private final RendererFrameCausality causality;
    private final RtCore.ScenePublicationState scenePublicationState;
    private final RendererFrameState frameState;
    private final DynamicRenderScene dynamicScene;
    private final long boundTlasDynamicSceneRevision;
    private final PackedSectionMembership boundSectionKeys;
    private final SectionRevisionSnapshot boundSectionContentRevisions;
    private final RendererViewState boundViewState;
    private final boolean presentationEligible;

    RtFrameDispatchRequest(
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
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.causality = Objects.requireNonNull(causality, "causality");
        this.scenePublicationState = Objects.requireNonNull(scenePublicationState, "scenePublicationState");
        this.frameState = Objects.requireNonNull(frameState, "frameState");
        this.dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        if (boundTlasDynamicSceneRevision < 0L) {
            throw new IllegalArgumentException("boundTlasDynamicSceneRevision must not be negative");
        }
        this.boundTlasDynamicSceneRevision = boundTlasDynamicSceneRevision;
        this.boundSectionKeys = Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        this.boundSectionContentRevisions = Objects.requireNonNull(
                boundSectionContentRevisions,
                "boundSectionContentRevisions"
        );
        if (this.boundSectionContentRevisions.membership() != this.boundSectionKeys) {
            throw new IllegalArgumentException(
                    "bound section revisions must retain the exact bound section membership publication"
            );
        }
        this.boundViewState = Objects.requireNonNull(boundViewState, "boundViewState");
        this.presentationEligible = presentationEligible;
    }

    void requireDescriptorVisible(long activeDescriptorGeneration) {
        if (activeDescriptorGeneration <= 0L) {
            throw new IllegalArgumentException("activeDescriptorGeneration must be positive");
        }
        if (!scenePublicationState.available()
                || scenePublicationState.descriptorGeneration() != activeDescriptorGeneration
                || scenePublicationState.worldTlasRevision() < 0L
                || scenePublicationState.materialRevision() < 0L
                || scenePublicationState.sectionCount() != boundSectionKeys.size()
                || scenePublicationState.viewRevision() != boundViewState.revision()
                || scenePublicationState.dynamicSceneRevision() != boundTlasDynamicSceneRevision) {
            throw new IllegalArgumentException(
                    "frame dispatch publication proof does not match active descriptor-visible scene"
            );
        }
    }

    RtCommandContext commandContext() {
        return commandContext;
    }

    RendererFrameCausality causality() {
        return causality;
    }

    RtCore.ScenePublicationState scenePublicationState() {
        return scenePublicationState;
    }

    RendererFrameState frameState() {
        return frameState;
    }

    DynamicRenderScene dynamicScene() {
        return dynamicScene;
    }

    long boundTlasDynamicSceneRevision() {
        return boundTlasDynamicSceneRevision;
    }

    PackedSectionMembership boundSectionKeys() {
        return boundSectionKeys;
    }

    SectionRevisionSnapshot boundSectionContentRevisions() {
        return boundSectionContentRevisions;
    }

    RendererViewState boundViewState() {
        return boundViewState;
    }

    boolean presentationEligible() {
        return presentationEligible;
    }
}
