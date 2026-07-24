package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/** Typed handoff between successor admission and BLAS foreground recovery. */
public record RendererForegroundWork(
        RendererViewState viewState,
        long successorGeneration,
        Set<SectionKey> retainedPresentationSectionKeys
) {
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

    public long authorityRevision() {
        return viewState.revision();
    }

    public PackedSectionMembership sectionKeys() {
        return viewState.authoritative()
                ? viewState.visibleSectionMembership()
                : PackedSectionMembership.empty();
    }

    public static RendererForegroundWork untraced(
            RendererViewState viewState,
            Set<SectionKey> retainedPresentationSectionKeys
    ) {
        return new RendererForegroundWork(viewState, -1L, retainedPresentationSectionKeys);
    }
}
