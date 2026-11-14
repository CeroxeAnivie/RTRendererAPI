package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable renderer view produced by host's completed section-visibility pass.
 *
 * <p>The resident RT scene and the active RT view deliberately have different
 * lifetimes. Section BLASes and material slots remain persistent, while this
 * value identifies which stable instances are eligible for the current TLAS.</p>
 */
public final class RendererViewState {
    private static final RendererViewState ALL_RESIDENT =
            new RendererViewState(0L, false, false, 0, 0, 0, List.of());

    private final long revision;
    private final boolean authoritative;
    private final boolean cameraValid;
    private final int cameraSectionX;
    private final int cameraSectionY;
    private final int cameraSectionZ;
    private final PackedSectionMembership visibleSectionMembership;
    private final List<SectionKey> visibleSectionKeys;

    /**
     * Creates an immutable renderer view from visible section keys.
     *
     * @param revision           nonnegative view revision
     * @param authoritative      whether the visibility list is authoritative
     * @param cameraValid        whether camera section coordinates are available
     * @param cameraSectionX     camera section X coordinate
     * @param cameraSectionY     camera section Y coordinate
     * @param cameraSectionZ     camera section Z coordinate
     * @param visibleSectionKeys visible sections to canonicalize and freeze
     */
    public RendererViewState(
            long revision,
            boolean authoritative,
            boolean cameraValid,
            int cameraSectionX,
            int cameraSectionY,
            int cameraSectionZ,
            List<SectionKey> visibleSectionKeys
    ) {
        this(
                revision,
                authoritative,
                cameraValid,
                cameraSectionX,
                cameraSectionY,
                cameraSectionZ,
                PackedSectionMembership.canonicalDistinct(
                        Objects.requireNonNull(visibleSectionKeys, "visibleSectionKeys")
                )
        );
    }

    private RendererViewState(
            long revision,
            boolean authoritative,
            boolean cameraValid,
            int cameraSectionX,
            int cameraSectionY,
            int cameraSectionZ,
            PackedSectionMembership visibleSectionMembership
    ) {
        if (revision < 0L) {
            throw new IllegalArgumentException("view revision must not be negative");
        }
        Objects.requireNonNull(visibleSectionMembership, "visibleSectionMembership");
        if (!visibleSectionMembership.canonicalOrder()) {
            throw new IllegalArgumentException("visible section membership must use canonical order");
        }
        if (!authoritative && !visibleSectionMembership.isEmpty()) {
            throw new IllegalArgumentException("non-authoritative view must not carry visible section keys");
        }
        if (!authoritative && cameraValid) {
            throw new IllegalArgumentException("non-authoritative view must not carry a camera section");
        }
        this.revision = revision;
        this.authoritative = authoritative;
        this.cameraValid = cameraValid;
        this.cameraSectionX = cameraSectionX;
        this.cameraSectionY = cameraSectionY;
        this.cameraSectionZ = cameraSectionZ;
        this.visibleSectionMembership = visibleSectionMembership;
        this.visibleSectionKeys = visibleSectionMembership.orderedKeys();
    }

    /**
     * Canonicalizes visible section keys for compatibility callers.
     *
     * @param sectionKeys source keys
     * @return immutable ordered distinct keys
     */
    public static List<SectionKey> canonicalVisibleSections(List<SectionKey> sectionKeys) {
        return canonicalSectionKeys(sectionKeys);
    }

    /**
     * Publishes section-key membership in the single canonical spatial order used
     * by renderer view, foreground epochs, and RT coverage snapshots.
     *
     * <p>Keeping this ordering here prevents downstream systems from rebuilding
     * ad-hoc {@code HashSet}/{@code Set.copyOf} mirrors merely to recover set
     * semantics on a hot path. The returned list is strictly ordered, immutable,
     * and duplicate-free.</p>
     *
     * @param sectionKeys source keys
     * @return immutable ordered distinct keys
     */
    public static List<SectionKey> canonicalSectionKeys(List<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        return PackedSectionMembership.canonicalDistinct(sectionKeys).orderedKeys();
    }

    /**
     * Tests whether keys are non-null, distinct and in canonical spatial order.
     *
     * @param sectionKeys keys to test
     * @return {@code true} when the list is canonical
     */
    public static boolean isCanonicalSectionKeyList(List<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        for (int index = 0; index < sectionKeys.size(); index++) {
            SectionKey current = Objects.requireNonNull(sectionKeys.get(index), "section key");
            if (index > 0 && compareSectionKeys(sectionKeys.get(index - 1), current) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests membership in a canonical section-key list.
     *
     * @param canonicalSectionKeys canonical ordered keys
     * @param key                  key to locate
     * @return {@code true} when present
     */
    public static boolean containsCanonicalSection(List<SectionKey> canonicalSectionKeys, SectionKey key) {
        Objects.requireNonNull(canonicalSectionKeys, "canonicalSectionKeys");
        Objects.requireNonNull(key, "section key");
        return canonicalSectionIndex(canonicalSectionKeys, key) >= 0;
    }

    /**
     * Returns the stable index of a key in a canonical section publication.
     *
     * @param canonicalSectionKeys canonical ordered keys
     * @param key                  key to locate
     * @return index when present, otherwise the binary-search insertion-point encoding
     */
    public static int canonicalSectionIndex(List<SectionKey> canonicalSectionKeys, SectionKey key) {
        Objects.requireNonNull(canonicalSectionKeys, "canonicalSectionKeys");
        Objects.requireNonNull(key, "section key");
        int packedIndex = PackedSectionMembership.canonicalIndex(canonicalSectionKeys, key);
        if (packedIndex != Integer.MIN_VALUE) {
            return packedIndex;
        }
        int low = 0;
        int high = canonicalSectionKeys.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = compareSectionKeys(canonicalSectionKeys.get(middle), key);
            if (comparison < 0) {
                low = middle + 1;
            } else if (comparison > 0) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        return -(low + 1);
    }

    /**
     * Returns the shared non-authoritative all-resident view.
     *
     * @return all-resident sentinel
     */
    public static RendererViewState allResident() {
        return ALL_RESIDENT;
    }

    /**
     * Creates an authoritative view without camera coordinates.
     *
     * @param revision           nonnegative view revision
     * @param visibleSectionKeys visible sections
     * @return authoritative view
     */
    public static RendererViewState host(long revision, List<SectionKey> visibleSectionKeys) {
        return new RendererViewState(revision, true, false, 0, 0, 0, visibleSectionKeys);
    }

    /**
     * Creates an authoritative view from canonical packed membership without camera coordinates.
     *
     * @param revision                 nonnegative view revision
     * @param visibleSectionMembership canonical visible membership
     * @return authoritative view
     */
    public static RendererViewState host(
            long revision,
            PackedSectionMembership visibleSectionMembership
    ) {
        return new RendererViewState(revision, true, false, 0, 0, 0, visibleSectionMembership);
    }

    /**
     * Creates an authoritative view with camera coordinates projected from frame state.
     *
     * @param revision           nonnegative view revision
     * @param frameState         source frame state
     * @param visibleSectionKeys visible sections
     * @return authoritative view
     */
    public static RendererViewState host(
            long revision,
            RendererFrameState frameState,
            List<SectionKey> visibleSectionKeys
    ) {
        Objects.requireNonNull(frameState, "frameState");
        if (!frameState.valid()) {
            return host(revision, visibleSectionKeys);
        }
        return new RendererViewState(
                revision,
                true,
                true,
                blockToSection(frameState.cameraX()),
                blockToSection(frameState.cameraY()),
                blockToSection(frameState.cameraZ()),
                visibleSectionKeys
        );
    }

    /**
     * Creates an authoritative view with camera coordinates and packed membership.
     *
     * @param revision                 nonnegative view revision
     * @param frameState               source frame state
     * @param visibleSectionMembership canonical visible membership
     * @return authoritative view
     */
    public static RendererViewState host(
            long revision,
            RendererFrameState frameState,
            PackedSectionMembership visibleSectionMembership
    ) {
        Objects.requireNonNull(frameState, "frameState");
        if (!frameState.valid()) {
            return host(revision, visibleSectionMembership);
        }
        return new RendererViewState(
                revision,
                true,
                true,
                blockToSection(frameState.cameraX()),
                blockToSection(frameState.cameraY()),
                blockToSection(frameState.cameraZ()),
                visibleSectionMembership
        );
    }

    /**
     * Exposes an already-canonical publication as immutable set semantics without a hash-table copy.
     *
     * <p>This is intentionally separate from {@link #canonicalSectionKeys(List)}: callers that merely
     * need membership must not pay to rebuild a {@code Set.copyOf} graph for an immutable generation.</p>
     *
     * @param canonicalSectionKeys canonical ordered keys
     * @return immutable set-semantics view
     */
    public static Set<SectionKey> canonicalSectionKeySet(List<SectionKey> canonicalSectionKeys) {
        Objects.requireNonNull(canonicalSectionKeys, "canonicalSectionKeys");
        if (!isCanonicalSectionKeyList(canonicalSectionKeys)) {
            throw new IllegalArgumentException("section-key set requires canonical keys");
        }
        return PackedSectionMembership.canonicalDistinct(canonicalSectionKeys);
    }

    private static int blockToSection(double coordinate) {
        return (int) Math.floor(coordinate / 16.0D);
    }

    private static int compareSectionKeys(SectionKey left, SectionKey right) {
        int comparison = Integer.compare(left.x(), right.x());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.y(), right.y());
        return comparison != 0 ? comparison : Integer.compare(left.z(), right.z());
    }

    /**
     * Returns the view revision.
     *
     * @return nonnegative revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Tests whether this view owns visibility authority.
     *
     * @return whether visibility is authoritative
     */
    public boolean authoritative() {
        return authoritative;
    }

    /**
     * Tests whether camera section coordinates are valid.
     *
     * @return whether camera coordinates are available
     */
    public boolean cameraValid() {
        return cameraValid;
    }

    /**
     * Returns the camera section X coordinate.
     *
     * @return camera section X
     */
    public int cameraSectionX() {
        return cameraSectionX;
    }

    /**
     * Returns the camera section Y coordinate.
     *
     * @return camera section Y
     */
    public int cameraSectionY() {
        return cameraSectionY;
    }

    /**
     * Returns the camera section Z coordinate.
     *
     * @return camera section Z
     */
    public int cameraSectionZ() {
        return cameraSectionZ;
    }

    /**
     * Returns canonical visible-section order.
     *
     * @return immutable ordered section keys
     */
    public List<SectionKey> visibleSectionKeys() {
        return visibleSectionKeys;
    }

    /**
     * Returns packed visible-section membership.
     *
     * @return immutable packed membership
     */
    public PackedSectionMembership visibleSectionMembership() {
        return visibleSectionMembership;
    }

    /**
     * Returns an equivalent view with replacement canonical membership.
     *
     * @param membership canonical visible-section membership
     * @return this instance when membership is identical, otherwise a replacement view
     */
    public RendererViewState withVisibleSectionMembership(PackedSectionMembership membership) {
        Objects.requireNonNull(membership, "membership");
        if (membership == visibleSectionMembership) {
            return this;
        }
        return new RendererViewState(
                revision,
                authoritative,
                cameraValid,
                cameraSectionX,
                cameraSectionY,
                cameraSectionZ,
                membership
        );
    }

    /**
     * Tests membership against the canonical visible-section publication
     * without materializing a second 32-distance hash set.
     *
     * <p>The constructor guarantees {@link PackedSectionMembership#canonicalOrder() canonical ordering}, so
     * each retained presentation section can be located in logarithmic time.
     * This is intentionally owned by the value that owns the ordering
     * invariant; downstream RT admission must not duplicate that comparator or
     * fall back to {@link List#containsAll(Collection)}'s quadratic scan.</p>
     *
     * @param sectionKeys sections that must all be visible
     * @return {@code true} when every supplied section is visible
     */
    public boolean containsAllVisibleSections(Collection<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        return visibleSectionMembership.containsAll(sectionKeys);
    }

    /**
     * Exposes the canonical visible generation as immutable set semantics
     * without materializing a second hash table.
     *
     * <p>The view is safe to retain because {@link #visibleSectionKeys} is an
     * immutable publication. Membership remains logarithmic and iteration
     * preserves the canonical spatial order used by successor admission.</p>
     *
     * @return immutable visible-section set view
     */
    public Set<SectionKey> visibleSectionKeySet() {
        return visibleSectionMembership;
    }

    /**
     * Formats view identity and coverage for diagnostics.
     *
     * @return stable single-line log fragment
     */
    public String asLogFragment() {
        return "rendererView{revision=" + revision
                + ", authoritative=" + authoritative
                + ", cameraValid=" + cameraValid
                + ", visibleSections=" + visibleSectionKeys.size()
                + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RendererViewState state)) {
            return false;
        }
        return revision == state.revision
                && authoritative == state.authoritative
                && cameraValid == state.cameraValid
                && cameraSectionX == state.cameraSectionX
                && cameraSectionY == state.cameraSectionY
                && cameraSectionZ == state.cameraSectionZ
                && visibleSectionKeys.equals(state.visibleSectionKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                revision,
                authoritative,
                cameraValid,
                cameraSectionX,
                cameraSectionY,
                cameraSectionZ,
                visibleSectionKeys
        );
    }

    @Override
    public String toString() {
        return "RendererViewState[revision=" + revision
                + ", authoritative=" + authoritative
                + ", cameraValid=" + cameraValid
                + ", cameraSectionX=" + cameraSectionX
                + ", cameraSectionY=" + cameraSectionY
                + ", cameraSectionZ=" + cameraSectionZ
                + ", visibleSectionKeys=" + visibleSectionKeys + ']';
    }

}
