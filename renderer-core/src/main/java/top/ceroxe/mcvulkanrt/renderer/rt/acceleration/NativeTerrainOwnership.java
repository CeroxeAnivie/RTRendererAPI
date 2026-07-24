package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable projection of the sections currently owned by native terrain
 * lifecycle stages.
 *
 * <p>This type belongs to acceleration ownership, not to the RT runtime
 * capability interface. The authoritative foreground set is retained as a
 * demand projection for diagnostics, but is intentionally excluded from
 * {@link #ownsSection(SectionKey)}: desired work is not proof of native
 * ownership.</p>
 */
public final class NativeTerrainOwnership {
    private static final NativeTerrainOwnership UNAVAILABLE = fromFrozenSets(
            -1L, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()
    );

    private final long ownershipGeneration;
    private final Set<SectionKey> sourceSectionKeys;
    private final Set<SectionKey> queuedSectionKeys;
    private final Set<SectionKey> recordingSectionKeys;
    private final Set<SectionKey> gpuInFlightSectionKeys;
    private final Set<SectionKey> activeSectionKeys;
    private final Set<SectionKey> boundSectionKeys;
    private final Set<SectionKey> authoritativeForegroundSectionKeys;
    private final boolean authoritativeForegroundFullyActive;
    private final PackedSectionMembership sourcePackedSectionKeys;
    private final PackedSectionMembership queuedPackedSectionKeys;
    private final PackedSectionMembership recordingPackedSectionKeys;
    private final PackedSectionMembership gpuInFlightPackedSectionKeys;
    private final PackedSectionMembership activePackedSectionKeys;
    private final PackedSectionMembership boundPackedSectionKeys;
    private final PackedSectionMembership authoritativeForegroundPackedSectionKeys;

    public NativeTerrainOwnership(
            Set<SectionKey> sourceSectionKeys,
            Set<SectionKey> queuedSectionKeys,
            Set<SectionKey> recordingSectionKeys,
            Set<SectionKey> gpuInFlightSectionKeys,
            Set<SectionKey> activeSectionKeys,
            Set<SectionKey> boundSectionKeys,
            Set<SectionKey> authoritativeForegroundSectionKeys
    ) {
        this(
                0L,
                Set.copyOf(sourceSectionKeys), Set.copyOf(queuedSectionKeys), Set.copyOf(recordingSectionKeys),
                Set.copyOf(gpuInFlightSectionKeys), Set.copyOf(activeSectionKeys), Set.copyOf(boundSectionKeys),
                Set.copyOf(authoritativeForegroundSectionKeys),
                !authoritativeForegroundSectionKeys.isEmpty()
                        && activeSectionKeys.containsAll(authoritativeForegroundSectionKeys),
                true
        );
    }

    /** Creates a snapshot from sets already frozen by their owning lifecycle stage. */
    public static NativeTerrainOwnership fromFrozenSets(
            Set<SectionKey> sourceSectionKeys,
            Set<SectionKey> queuedSectionKeys,
            Set<SectionKey> recordingSectionKeys,
            Set<SectionKey> gpuInFlightSectionKeys,
            Set<SectionKey> activeSectionKeys,
            Set<SectionKey> boundSectionKeys,
            Set<SectionKey> authoritativeForegroundSectionKeys
    ) {
        return fromFrozenSets(
                0L,
                sourceSectionKeys,
                queuedSectionKeys,
                recordingSectionKeys,
                gpuInFlightSectionKeys,
                activeSectionKeys,
                boundSectionKeys,
                authoritativeForegroundSectionKeys
        );
    }

    /** Creates a versioned snapshot from sets already frozen by their owning lifecycle stage. */
    public static NativeTerrainOwnership fromFrozenSets(
            long ownershipGeneration,
            Set<SectionKey> sourceSectionKeys,
            Set<SectionKey> queuedSectionKeys,
            Set<SectionKey> recordingSectionKeys,
            Set<SectionKey> gpuInFlightSectionKeys,
            Set<SectionKey> activeSectionKeys,
            Set<SectionKey> boundSectionKeys,
            Set<SectionKey> authoritativeForegroundSectionKeys
    ) {
        return new NativeTerrainOwnership(
                ownershipGeneration,
                sourceSectionKeys, queuedSectionKeys, recordingSectionKeys, gpuInFlightSectionKeys,
                activeSectionKeys, boundSectionKeys, authoritativeForegroundSectionKeys,
                !authoritativeForegroundSectionKeys.isEmpty()
                        && activeSectionKeys.containsAll(authoritativeForegroundSectionKeys),
                true
        );
    }

    /**
     * Creates a versioned snapshot with the scalar coverage proof maintained by
     * the BLAS lifecycle owner.  Consumers must not reconstruct this fact by
     * scanning the six stage memberships on every frame.
     */
    static NativeTerrainOwnership fromFrozenSets(
            long ownershipGeneration,
            Set<SectionKey> sourceSectionKeys,
            Set<SectionKey> queuedSectionKeys,
            Set<SectionKey> recordingSectionKeys,
            Set<SectionKey> gpuInFlightSectionKeys,
            Set<SectionKey> activeSectionKeys,
            Set<SectionKey> boundSectionKeys,
            Set<SectionKey> authoritativeForegroundSectionKeys,
            boolean authoritativeForegroundFullyActive
    ) {
        return new NativeTerrainOwnership(
                ownershipGeneration,
                sourceSectionKeys, queuedSectionKeys, recordingSectionKeys, gpuInFlightSectionKeys,
                activeSectionKeys, boundSectionKeys, authoritativeForegroundSectionKeys,
                authoritativeForegroundFullyActive,
                true
        );
    }

    private NativeTerrainOwnership(
            long ownershipGeneration,
            Set<SectionKey> sourceSectionKeys,
            Set<SectionKey> queuedSectionKeys,
            Set<SectionKey> recordingSectionKeys,
            Set<SectionKey> gpuInFlightSectionKeys,
            Set<SectionKey> activeSectionKeys,
            Set<SectionKey> boundSectionKeys,
            Set<SectionKey> authoritativeForegroundSectionKeys,
            boolean authoritativeForegroundFullyActive,
            boolean frozen
    ) {
        if (ownershipGeneration < -1L) {
            throw new IllegalArgumentException("ownershipGeneration must be -1 or greater");
        }
        this.ownershipGeneration = ownershipGeneration;
        this.sourceSectionKeys = Objects.requireNonNull(sourceSectionKeys, "sourceSectionKeys");
        this.queuedSectionKeys = Objects.requireNonNull(queuedSectionKeys, "queuedSectionKeys");
        this.recordingSectionKeys = Objects.requireNonNull(recordingSectionKeys, "recordingSectionKeys");
        this.gpuInFlightSectionKeys = Objects.requireNonNull(gpuInFlightSectionKeys, "gpuInFlightSectionKeys");
        this.activeSectionKeys = Objects.requireNonNull(activeSectionKeys, "activeSectionKeys");
        this.boundSectionKeys = Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        this.authoritativeForegroundSectionKeys = Objects.requireNonNull(
                authoritativeForegroundSectionKeys, "authoritativeForegroundSectionKeys"
        );
        if (authoritativeForegroundFullyActive && authoritativeForegroundSectionKeys.isEmpty()) {
            throw new IllegalArgumentException("empty foreground cannot publish complete active coverage");
        }
        this.authoritativeForegroundFullyActive = authoritativeForegroundFullyActive;
        this.sourcePackedSectionKeys = packedView(sourceSectionKeys);
        this.queuedPackedSectionKeys = packedView(queuedSectionKeys);
        this.recordingPackedSectionKeys = packedView(recordingSectionKeys);
        this.gpuInFlightPackedSectionKeys = packedView(gpuInFlightSectionKeys);
        this.activePackedSectionKeys = packedView(activeSectionKeys);
        this.boundPackedSectionKeys = packedView(boundSectionKeys);
        this.authoritativeForegroundPackedSectionKeys = packedView(authoritativeForegroundSectionKeys);
    }

    public Set<SectionKey> sourceSectionKeys() { return sourceSectionKeys; }
    public Set<SectionKey> queuedSectionKeys() { return queuedSectionKeys; }
    public Set<SectionKey> recordingSectionKeys() { return recordingSectionKeys; }
    public Set<SectionKey> gpuInFlightSectionKeys() { return gpuInFlightSectionKeys; }
    public Set<SectionKey> activeSectionKeys() { return activeSectionKeys; }
    public Set<SectionKey> boundSectionKeys() { return boundSectionKeys; }
    public Set<SectionKey> authoritativeForegroundSectionKeys() { return authoritativeForegroundSectionKeys; }
    public boolean authoritativeForegroundFullyActive() { return authoritativeForegroundFullyActive; }
    public long ownershipGeneration() { return ownershipGeneration; }

    public static NativeTerrainOwnership unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Tests lifecycle ownership without treating the authoritative demand set
     * as ownership. Backfill only needs the six native lifecycle stages.
     */
    public boolean ownsSection(SectionKey key) {
        Objects.requireNonNull(key, "section key");
        long packedSection = key.packed();
        return containsOwned(sourcePackedSectionKeys, sourceSectionKeys, key, packedSection)
                || containsOwned(queuedPackedSectionKeys, queuedSectionKeys, key, packedSection)
                || containsOwned(recordingPackedSectionKeys, recordingSectionKeys, key, packedSection)
                || containsOwned(gpuInFlightPackedSectionKeys, gpuInFlightSectionKeys, key, packedSection)
                || containsOwned(activePackedSectionKeys, activeSectionKeys, key, packedSection)
                || containsOwned(boundPackedSectionKeys, boundSectionKeys, key, packedSection);
    }

    /**
     * Tests ownership capable of producing an exact Base instance.
     *
     * <p>The source stage also retains compact FarField-only publications after its heavyweight
     * mesh payload is released. Such a publication is valid proxy ownership but cannot rebuild an
     * exact BLAS. Callers deciding whether host must re-extract a full source must therefore
     * ignore source-only membership and require a queued, recording, GPU, active, or bound owner.</p>
     */
    public boolean ownsExactSection(SectionKey key) {
        Objects.requireNonNull(key, "section key");
        long packedSection = key.packed();
        return containsOwned(queuedPackedSectionKeys, queuedSectionKeys, key, packedSection)
                || containsOwned(recordingPackedSectionKeys, recordingSectionKeys, key, packedSection)
                || containsOwned(gpuInFlightPackedSectionKeys, gpuInFlightSectionKeys, key, packedSection)
                || containsOwned(activePackedSectionKeys, activeSectionKeys, key, packedSection)
                || containsOwned(boundPackedSectionKeys, boundSectionKeys, key, packedSection);
    }

    private static PackedSectionMembership packedView(Set<SectionKey> sectionKeys) {
        return sectionKeys instanceof PackedSectionMembership packed ? packed : null;
    }

    private static boolean containsOwned(
            PackedSectionMembership packedSectionKeys,
            Set<SectionKey> sectionKeys,
            SectionKey key,
            long packedSection
    ) {
        return packedSectionKeys != null
                ? packedSectionKeys.containsPacked(packedSection)
                : sectionKeys.contains(key);
    }
}
