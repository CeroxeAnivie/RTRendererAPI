package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.FrameCompositionEvidence;
import top.ceroxe.rt.renderer.api.FrameCompositionRequest;

import java.util.Optional;

/** Internal seam keeping composition out of the renderer host's lifecycle machinery. */
interface VulkanFrameCompositionRuntime {
    FrameCompositionEvidence compose(FrameCompositionRequest request, VulkanGenericCommandSession genericCommands);

    Optional<FrameCompositionEvidence> compositionEvidence(long frameSequence);

    void observeCompositionConsumerAccepted(long frameSequence);

    boolean compositionExecutable();
}
