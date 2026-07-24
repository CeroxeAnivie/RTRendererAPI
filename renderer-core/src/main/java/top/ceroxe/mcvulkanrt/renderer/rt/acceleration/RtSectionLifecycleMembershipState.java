package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Owns revisioned immutable publications for resident, active, and bound section-BLAS stages.
 *
 * <p>CPU recording and GPU-build membership belongs to {@link RtSectionAsyncBuildInventory};
 * keeping those stages with their mutable native-work inventory prevents parallel ownership. This
 * owner retains only memberships whose mutations it can publish atomically.</p>
 */
final class RtSectionLifecycleMembershipState {
    private final Set<SectionKey> residentKeys = new LinkedHashSet<>();
    private final Set<SectionKey> residentKeysView = Collections.unmodifiableSet(residentKeys);
    private final Set<SectionKey> activeKeys = new LinkedHashSet<>();
    private final PackedSectionMembership.Builder residentBuilder = PackedSectionMembership.builder(0);
    private final PackedSectionMembership.Builder activeBuilder = PackedSectionMembership.builder(0);
    private final PackedSectionMembership.Builder boundBuilder = PackedSectionMembership.builder(0);

    private PackedSectionMembership resident = PackedSectionMembership.empty();
    private PackedSectionMembership active = PackedSectionMembership.empty();
    private PackedSectionMembership bound = PackedSectionMembership.empty();
    private long residentRevision;
    private long activeRevision;
    private long publishedResidentRevision = -1L;
    private long publishedActiveRevision = -1L;
    private long publishedBoundRevision = Long.MIN_VALUE;

    Set<SectionKey> residentKeys() {
        return residentKeysView;
    }

    boolean hasResidents() {
        return !residentKeys.isEmpty();
    }

    boolean containsResident(SectionKey key) {
        return residentKeys.contains(Objects.requireNonNull(key, "key"));
    }

    int residentSize() {
        return residentKeys.size();
    }

    boolean containsActive(SectionKey key) {
        return activeKeys.contains(Objects.requireNonNull(key, "key"));
    }

    int activeSize() {
        return activeKeys.size();
    }

    void addResident(SectionKey key) {
        if (residentKeys.add(Objects.requireNonNull(key, "key"))) {
            residentRevision = Math.incrementExact(residentRevision);
        }
    }

    void removeResident(SectionKey key) {
        if (residentKeys.remove(Objects.requireNonNull(key, "key"))) {
            residentRevision = Math.incrementExact(residentRevision);
        }
    }

    void clearResidents() {
        if (residentKeys.isEmpty()) {
            return;
        }
        residentKeys.clear();
        residentRevision = Math.incrementExact(residentRevision);
    }

    void addActive(SectionKey key) {
        if (!activeKeys.add(Objects.requireNonNull(key, "key"))) {
            throw new IllegalStateException("active BLAS membership already contains " + key);
        }
        activeRevision = Math.incrementExact(activeRevision);
    }

    void removeActive(SectionKey key) {
        if (!activeKeys.remove(Objects.requireNonNull(key, "key"))) {
            throw new IllegalStateException("active BLAS membership does not contain " + key);
        }
        activeRevision = Math.incrementExact(activeRevision);
    }

    void clearActive() {
        if (activeKeys.isEmpty()) {
            throw new IllegalStateException("active BLAS membership is already empty");
        }
        activeKeys.clear();
        active = PackedSectionMembership.empty();
        activeRevision = Math.incrementExact(activeRevision);
        publishedActiveRevision = activeRevision;
    }

    PackedSectionMembership resident() {
        if (publishedResidentRevision != residentRevision) {
            residentBuilder.reset(residentKeys.size());
            for (SectionKey key : residentKeys) {
                residentBuilder.addPacked(pack(key));
            }
            resident = residentBuilder.buildCanonical(resident);
            publishedResidentRevision = residentRevision;
        }
        return resident;
    }

    PackedSectionMembership active() {
        if (publishedActiveRevision != activeRevision) {
            activeBuilder.reset(activeKeys.size());
            for (SectionKey key : activeKeys) {
                activeBuilder.addPacked(pack(key));
            }
            active = activeBuilder.buildCanonical(active);
            publishedActiveRevision = activeRevision;
        }
        return active;
    }

    PackedSectionMembership bound(PackedSectionMembership boundKeys, long boundWorldRevision) {
        Objects.requireNonNull(boundKeys, "boundKeys");
        if (publishedBoundRevision != boundWorldRevision) {
            boundBuilder.reset(boundKeys.size());
            for (SectionKey key : boundKeys) {
                boundBuilder.addPacked(pack(key));
            }
            bound = boundBuilder.buildCanonical(bound);
            publishedBoundRevision = boundWorldRevision;
        }
        return bound;
    }

    long residentRevision() {
        return residentRevision;
    }

    long activeRevision() {
        return activeRevision;
    }

    private static long pack(SectionKey key) {
        return key.packed();
    }
}
