package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable, generation-bound assessment of one requested feature-profile transition.
 *
 * @param id controller-local single-use plan identifier
 * @param expectedGeneration renderer profile generation on which the plan was based
 * @param source profile observed when the plan was created
 * @param target requested profile
 * @param disposition exact transition classification
 * @param boundary earliest safe boundary required by the classification
 * @param reason bounded explanation of the assessment
 */
public record RendererFeaturePlan(
        long id,
        long expectedGeneration,
        RendererFeatureProfile source,
        RendererFeatureProfile target,
        Disposition disposition,
        Boundary boundary,
        String reason
) {
    /** Validates a complete plan without granting it authority over another controller. */
    public RendererFeaturePlan {
        if (id <= 0L) throw new IllegalArgumentException("plan id must be positive");
        if (expectedGeneration < 0L) {
            throw new IllegalArgumentException("expectedGeneration must not be negative");
        }
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        disposition = Objects.requireNonNull(disposition, "disposition");
        boundary = Objects.requireNonNull(boundary, "boundary");
        reason = requireText(reason, "reason");
        if (disposition == Disposition.UNCHANGED && !source.equals(target)) {
            throw new IllegalArgumentException("UNCHANGED plan requires identical profiles");
        }
        if (disposition == Disposition.UNCHANGED && boundary != Boundary.NEXT_FRAME) {
            throw new IllegalArgumentException("UNCHANGED plan must use the NEXT_FRAME boundary");
        }
        if (disposition == Disposition.APPLICABLE
                && boundary != Boundary.FRAME_DRAIN
                && boundary != Boundary.NEXT_FRAME) {
            throw new IllegalArgumentException("applicable plan requires an in-session boundary");
        }
        if (disposition.requiredBoundary() != null
                && disposition.requiredBoundary() != boundary) {
            throw new IllegalArgumentException("rebuild disposition and boundary disagree");
        }
    }

    /** Exhaustive planning outcome; rebuild requirements are never reported as applicable. */
    public enum Disposition {
        /** The provider can apply the target at the reported boundary. */
        APPLICABLE(null),
        /** The target is already effective. */
        UNCHANGED(null),
        /** A swapchain rebuild is required. */
        REQUIRES_SWAPCHAIN_REBUILD(Boundary.SWAPCHAIN_REBUILD),
        /** A pipeline rebuild is required. */
        REQUIRES_PIPELINE_REBUILD(Boundary.PIPELINE_REBUILD),
        /** A scene rebuild is required. */
        REQUIRES_SCENE_REBUILD(Boundary.SCENE_REBUILD),
        /** A full renderer rebuild is required. */
        REQUIRES_RENDERER_REBUILD(Boundary.RENDERER_REBUILD);

        private final Boundary requiredBoundary;

        Disposition(Boundary requiredBoundary) {
            this.requiredBoundary = requiredBoundary;
        }

        private Boundary requiredBoundary() {
            return requiredBoundary;
        }
    }

    /** Smallest synchronization/rebuild boundary capable of honoring the target profile. */
    public enum Boundary {
        /** Apply at the next frame boundary. */
        NEXT_FRAME,
        /** Apply after all in-flight frames drain. */
        FRAME_DRAIN,
        /** Recreate the swapchain. */
        SWAPCHAIN_REBUILD,
        /** Rebuild rendering pipelines. */
        PIPELINE_REBUILD,
        /** Rebuild scene resources. */
        SCENE_REBUILD,
        /** Recreate the renderer. */
        RENDERER_REBUILD
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }
}
