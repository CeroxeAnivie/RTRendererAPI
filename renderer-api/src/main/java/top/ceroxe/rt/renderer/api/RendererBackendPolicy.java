package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Selects one backend for a complete scene, without combining two light-transport results. */
public enum RendererBackendPolicy {
    /** Every scene uses the existing Vulkan provider, including its ordinary and expert lanes. */
    VULKAN_ONLY,
    /** Every scene uses RTXPT; unsupported Vulkan-specific requests remain errors. */
    RTXPT_ONLY,
    /** Scenes with optical media use RTXPT; other scenes use Vulkan. */
    RTXPT_FOR_MEDIA;

    /**
     * Plans a scene without opening a device or window. The transaction must reset the scene and
     * include all meshes/materials referenced by its instances and the first frame's primitives.
     * Unused transmissive materials do not turn a surface scene into a medium scene.
     *
     * @param initialScene complete reset transaction
     * @param firstFrame first frame, including any frame-local optical objects
     * @return immutable exact-provider decision; opening never falls back to another provider
     */
    public Selection select(SceneTransaction initialScene, RenderFrameRequest firstFrame) {
        return select(SceneRoutingState.initial(initialScene), Objects.requireNonNull(firstFrame,"firstFrame"));
    }

    Selection select(SceneRoutingState state, RenderFrameRequest frame) {
        state.validatePrimitiveReferences(frame.primitiveBatch());
        boolean media = state.hasMedia(frame.primitiveBatch());
        Backend backend = backendFor(media);
        String reason = switch(this) {
            case VULKAN_ONLY -> "Caller requires Vulkan for this complete scene";
            case RTXPT_ONLY -> "Caller requires RTXPT for this complete scene";
            case RTXPT_FOR_MEDIA -> media
                ? "The complete scene contains a medium boundary or referenced transmissive material"
                : "The complete scene contains only ordinary surfaces";
        };
        return new Selection(this,backend,media,reason);
    }

    Backend backendFor(boolean media) {
        return switch(this) {
            case VULKAN_ONLY -> Backend.VULKAN;
            case RTXPT_ONLY -> Backend.RTXPT;
            case RTXPT_FOR_MEDIA -> media ? Backend.RTXPT : Backend.VULKAN;
        };
    }

    /** Exact provider identity, independent of service-provider priority. */
    public enum Backend {
        /** Existing Vulkan implementation. */
        VULKAN("vulkan-rt"),
        /** NVIDIA RTXPT managed implementation. */
        RTXPT("rtxpt");

        private final String providerId;
        Backend(String providerId) { this.providerId=providerId; }
        /** Returns the id accepted by {@link Renderer#open(String, RendererConfig)}. */
        public String providerId() { return providerId; }
    }

    /**
     * Observable choice for one fixed scene session.
     * @param policy requested selection policy
     * @param backend exact chosen provider
     * @param mediaPresent whether the analyzed scene contains optical media
     * @param reason nonempty explanation of the choice
     */
    public record Selection(RendererBackendPolicy policy, Backend backend, boolean mediaPresent, String reason) {
        /** Validates immutable selection evidence. */
        public Selection {
            Objects.requireNonNull(policy,"policy");
            Objects.requireNonNull(backend,"backend");
            Objects.requireNonNull(reason,"reason");
            if(reason.isBlank())throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
