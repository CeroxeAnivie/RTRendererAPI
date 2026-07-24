package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Map;
import java.util.Objects;

/** Immutable causality identities aligned with one section revision publication. */
public final class SectionCausalitySnapshot {
    private static final int PAGE_SHIFT = 8;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final RendererFrameCausality[][] EMPTY_PAGES = new RendererFrameCausality[0][];
    private static final SectionCausalitySnapshot EMPTY = new SectionCausalitySnapshot(
            SectionRevisionSnapshot.empty(),
            EMPTY_PAGES
    );

    private final SectionRevisionSnapshot revisions;
    private final RendererFrameCausality[][] causalityPages;

    private SectionCausalitySnapshot(
            SectionRevisionSnapshot revisions,
            RendererFrameCausality[][] causalityPages
    ) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.causalityPages = Objects.requireNonNull(causalityPages, "causalityPages");
        validatePages(revisions.size(), causalityPages);
    }

    public static SectionCausalitySnapshot empty() {
        return EMPTY;
    }

    public static SectionCausalitySnapshot constant(
            SectionRevisionSnapshot revisions,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(causality, "causality");
        if (revisions.isEmpty()) {
            return EMPTY;
        }
        RendererFrameCausality[][] pages = allocatePages(revisions.size());
        for (RendererFrameCausality[] page : pages) {
            java.util.Arrays.fill(page, causality);
        }
        return new SectionCausalitySnapshot(revisions, pages);
    }

    public static SectionCausalitySnapshot select(
            SectionRevisionSnapshot revisions,
            Map<SectionKey, RendererFrameCausality> source
    ) {
        return select(revisions, source, null);
    }

    /**
     * Selects one causality identity per revision slot while sharing every unchanged 256-slot page
     * with the previous publication. Terrain convergence normally changes one section at a time;
     * rebuilding the entire reference array on each BLAS completion made causality bookkeeping a
     * hidden O(active sections) allocator despite the revision lane already being paged COW.
     */
    public static SectionCausalitySnapshot select(
            SectionRevisionSnapshot revisions,
            Map<SectionKey, RendererFrameCausality> source,
            SectionCausalitySnapshot previous
    ) {
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(source, "source");
        if (revisions.isEmpty()) {
            return EMPTY;
        }
        if (previous != null && previous.membership() == revisions.membership()) {
            RendererFrameCausality[][] nextPages = previous.causalityPages;
            for (int index = 0; index < revisions.size(); index++) {
                SectionKey key = revisions.sectionKeys().get(index);
                RendererFrameCausality next = source.get(key);
                if (next == null) {
                    throw new IllegalStateException("missing section causality for " + key);
                }
                if (Objects.equals(previous.causalityAt(index), next)) {
                    continue;
                }
                if (nextPages == previous.causalityPages) {
                    nextPages = previous.causalityPages.clone();
                }
                int pageIndex = index >>> PAGE_SHIFT;
                if (nextPages[pageIndex] == previous.causalityPages[pageIndex]) {
                    nextPages[pageIndex] = previous.causalityPages[pageIndex].clone();
                }
                nextPages[pageIndex][index & PAGE_MASK] = next;
            }
            return nextPages == previous.causalityPages && previous.revisions == revisions
                    ? previous
                    : new SectionCausalitySnapshot(revisions, nextPages);
        }
        RendererFrameCausality[][] pages = allocatePages(revisions.size());
        for (int index = 0; index < revisions.sectionKeys().size(); index++) {
            SectionKey key = revisions.sectionKeys().get(index);
            RendererFrameCausality causality = source.get(key);
            if (causality == null) {
                throw new IllegalStateException("missing section causality for " + key);
            }
            pages[index >>> PAGE_SHIFT][index & PAGE_MASK] = causality;
        }
        return new SectionCausalitySnapshot(revisions, pages);
    }

    public SectionCausalitySnapshot rebase(SectionRevisionSnapshot targetRevisions) {
        Objects.requireNonNull(targetRevisions, "targetRevisions");
        if (!membership().equals(targetRevisions.membership())) {
            throw new IllegalArgumentException("section causality rebase requires equal memberships");
        }
        if (revisions == targetRevisions) {
            return this;
        }
        /* Equal canonical memberships have identical slot order; only the revision owner changes. */
        return new SectionCausalitySnapshot(targetRevisions, causalityPages);
    }

    public PackedSectionMembership membership() {
        return revisions.membership();
    }

    public SectionRevisionSnapshot revisions() {
        return revisions;
    }

    public int size() {
        return revisions.size();
    }

    public RendererFrameCausality causality(SectionKey key) {
        Objects.requireNonNull(key, "section key");
        int index = membership().canonicalIndex(key);
        return index < 0 ? null : causalityAt(index);
    }

    private RendererFrameCausality causalityAt(int index) {
        return causalityPages[index >>> PAGE_SHIFT][index & PAGE_MASK];
    }

    private static RendererFrameCausality[][] allocatePages(int size) {
        if (size == 0) {
            return EMPTY_PAGES;
        }
        int pageCount = Math.addExact(size, PAGE_MASK) >>> PAGE_SHIFT;
        RendererFrameCausality[][] pages = new RendererFrameCausality[pageCount][];
        for (int page = 0; page < pageCount; page++) {
            int remaining = size - (page << PAGE_SHIFT);
            pages[page] = new RendererFrameCausality[Math.min(PAGE_SIZE, remaining)];
        }
        return pages;
    }

    private static void validatePages(int size, RendererFrameCausality[][] pages) {
        int expectedPages = size == 0 ? 0 : Math.addExact(size, PAGE_MASK) >>> PAGE_SHIFT;
        if (pages.length != expectedPages) {
            throw new IllegalArgumentException("section causality page count must match revisions");
        }
        int observed = 0;
        for (int page = 0; page < pages.length; page++) {
            RendererFrameCausality[] values = Objects.requireNonNull(pages[page], "causality page");
            int expectedLength = Math.min(PAGE_SIZE, size - (page << PAGE_SHIFT));
            if (values.length != expectedLength) {
                throw new IllegalArgumentException("section causality page length must match revisions");
            }
            observed = Math.addExact(observed, values.length);
        }
        if (observed != size) {
            throw new IllegalArgumentException("section causality count must match section revisions");
        }
    }
}
