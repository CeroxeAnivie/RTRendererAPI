package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.List;
import java.util.Objects;

/**
 * Immutable visibility publication captured from one host raster frame.
 */
record RendererRasterSectionObservation(
        long revision,
        PackedSectionMembership visibleMembership,
        PackedSectionMembership drawableMembership
) {
    RendererRasterSectionObservation {
        if (revision < 0L) {
            throw new IllegalArgumentException("raster observation revision must be non-negative");
        }
        Objects.requireNonNull(visibleMembership, "visibleMembership");
        Objects.requireNonNull(drawableMembership, "drawableMembership");
        if (!visibleMembership.containsAll(drawableMembership)) {
            throw new IllegalArgumentException("drawable membership must be covered by visible membership");
        }
    }

    static RendererRasterSectionObservation empty() {
        return new RendererRasterSectionObservation(
                0L,
                PackedSectionMembership.empty(),
                PackedSectionMembership.empty()
        );
    }

    static RendererRasterSectionObservation capture(
            long revision,
            PackedSectionMembership visibleMembership,
            PackedSectionMembership drawableMembership
    ) {
        return new RendererRasterSectionObservation(revision, visibleMembership, drawableMembership);
    }

    static RendererRasterSectionObservation capture(
            long revision,
            List<SectionKey> visibleSectionKeys,
            List<SectionKey> drawableSectionKeys
    ) {
        return new RendererRasterSectionObservation(
                revision,
                PackedSectionMembership.canonicalDistinct(visibleSectionKeys),
                PackedSectionMembership.canonicalDistinct(drawableSectionKeys)
        );
    }

    List<SectionKey> visibleSectionKeys() {
        return visibleMembership.orderedKeys();
    }

    List<SectionKey> drawableSectionKeys() {
        return drawableMembership.orderedKeys();
    }
}
