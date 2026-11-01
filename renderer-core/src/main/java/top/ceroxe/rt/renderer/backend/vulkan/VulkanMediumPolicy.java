package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;

/** Keeps the ordinary renderer independent of the optional LuxCore execution lane. */
final class VulkanMediumPolicy {
    private VulkanMediumPolicy() { }

    static void requireSurfaceScene(SceneTransaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (transaction.upserts().instances().stream().anyMatch(i -> i.mediumBoundary().isPresent())) reject();
    }

    static void requireSurfaceFrame(RenderFrameRequest request) {
        if (!request.environment().cameraMedium().isVacuum()
                || !request.primitiveBatch().mediumBoundaryIndices().isEmpty()) reject();
    }

    private static void reject() {
        throw new FrameValidationException(FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,
                "Medium scenes are owned by LuxCore. Open the explicit luxcore backend for the complete scene.");
    }
}
