package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Typed handoff between successor admission and BLAS foreground recovery.
 *
 * @param viewState                       immutable authoritative or observational view state
 * @param successorGeneration             successor generation, or {@code -1} for untraced work
 * @param retainedPresentationSectionKeys immutable keys retained by the committed presentation front
 */
public record RendererForegroundWork(
        RendererViewState viewState,
        long successorGeneration,
        Set<SectionKey> retainedPresentationSectionKeys
) {
    /**
     * Validates the view and freezes presentation-retention membership.
     */
    public RendererForegroundWork {
        viewState = Objects.requireNonNull(viewState, "viewState");
        retainedPresentationSectionKeys = Set.copyOf(Objects.requireNonNull(
                retainedPresentationSectionKeys,
                "retainedPresentationSectionKeys"
        ));
        if (successorGeneration < -1L) {
            throw new IllegalArgumentException("successorGeneration must be -1 or greater");
        }
        /* The retained committed front may intentionally outlive the successor build view. */
    }

    /**
     * Creates foreground work without a successor-generation trace.
     *
     * @param viewState                       immutable view state
     * @param retainedPresentationSectionKeys sections retained by presentation
     * @return untraced foreground handoff
     */
    public static RendererForegroundWork untraced(
            RendererViewState viewState,
            Set<SectionKey> retainedPresentationSectionKeys
    ) {
        return new RendererForegroundWork(viewState, -1L, retainedPresentationSectionKeys);
    }

    /**
     * Returns the authority revision carried by the view.
     *
     * @return view revision
     */
    public long authorityRevision() {
        return viewState.revision();
    }

    /**
     * Returns authoritative foreground membership when the view has authority.
     *
     * @return packed authoritative membership, or the empty membership for observational views
     */
    public PackedSectionMembership sectionKeys() {
        return viewState.authoritative()
                ? viewState.visibleSectionMembership()
                : PackedSectionMembership.empty();
    }
}
