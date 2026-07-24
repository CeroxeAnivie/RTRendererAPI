package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/**
 * Atomically owns the renderer foreground publication consumed by section-BLAS scheduling.
 *
 * <p>Logical view, successor identity, presentation retention, authoritative membership, and its
 * monotonic revision are one publication. Splitting them across cache fields allowed callers to
 * observe equal membership through different identities or pair a new view with an old authority
 * revision. This owner computes and commits one transition before downstream reconciliation.</p>
 */
final class RtSectionForegroundState {
    private RendererViewState view = RendererViewState.allResident();
    private RendererForegroundWork work = RendererForegroundWork.untraced(view, Set.of());
    private Set<SectionKey> retainedPresentationKeys = Set.of();
    private PackedSectionMembership authority = PackedSectionMembership.empty();
    private long authorityRevision;

    Transition accept(RendererForegroundWork candidate) {
        Objects.requireNonNull(candidate, "candidate");
        candidate = rebaseEquivalentMembership(authority, candidate);
        Set<SectionKey> nextRetainedKeys = candidate.retainedPresentationSectionKeys();
        if (work.equals(candidate) && retainedPresentationKeys.equals(nextRetainedKeys)) {
            return Transition.unchanged();
        }

        RendererViewState nextView = candidate.viewState();
        boolean retentionChanged = !retainedPresentationKeys.equals(nextRetainedKeys);
        boolean admissionInputsChanged = !RtSectionActiveViewCache.sameAdmissionInputs(view, nextView);
        view = nextView;
        work = candidate;
        retainedPresentationKeys = nextRetainedKeys;
        if (!admissionInputsChanged && !retentionChanged) {
            return new Transition(true, false, false);
        }

        PackedSectionMembership nextAuthority = candidate.sectionKeys();
        boolean authorityChanged = !authority.equals(nextAuthority);
        long nextAuthorityRevision = authorityChanged
                ? Math.incrementExact(authorityRevision)
                : authorityRevision;
        authority = nextAuthority;
        authorityRevision = nextAuthorityRevision;
        return new Transition(true, true, authorityChanged);
    }

    RendererViewState view() {
        return view;
    }

    RendererForegroundWork work() {
        return work;
    }

    Set<SectionKey> retainedPresentationKeys() {
        return retainedPresentationKeys;
    }

    PackedSectionMembership authority() {
        return authority;
    }

    long authorityRevision() {
        return authorityRevision;
    }

    static RendererForegroundWork rebaseEquivalentMembership(
            PackedSectionMembership currentAuthority,
            RendererForegroundWork candidate
    ) {
        Objects.requireNonNull(currentAuthority, "currentAuthority");
        Objects.requireNonNull(candidate, "candidate");
        PackedSectionMembership candidateAuthority = candidate.sectionKeys();
        if (currentAuthority == candidateAuthority || !currentAuthority.equals(candidateAuthority)) {
            return candidate;
        }
        /*
         * Equal membership content and publication identity are separate facts. Preserve the
         * cache-owned identity so view admission, foreground recovery, and ownership generation
         * join against exactly one immutable authority object.
         */
        RendererViewState rebasedView = candidate.viewState().withVisibleSectionMembership(currentAuthority);
        return new RendererForegroundWork(
                rebasedView,
                candidate.successorGeneration(),
                candidate.retainedPresentationSectionKeys()
        );
    }

    record Transition(boolean changed, boolean reconciliationRequired, boolean authorityChanged) {
        private static final Transition UNCHANGED = new Transition(false, false, false);

        private static Transition unchanged() {
            return UNCHANGED;
        }

        Transition {
            if (!changed && (reconciliationRequired || authorityChanged)) {
                throw new IllegalArgumentException("unchanged foreground transition cannot request reconciliation");
            }
            if (authorityChanged && !reconciliationRequired) {
                throw new IllegalArgumentException("authority change requires foreground reconciliation");
            }
        }
    }
}
