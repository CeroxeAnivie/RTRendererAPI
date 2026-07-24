package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/**
 * Owns the two priority lanes used by section-BLAS admission.
 *
 * <p>Interactive topology is an urgency overlay, while preferred work is either a provisional
 * first batch or the authoritative foreground publication. Keeping both sets behind one state
 * owner makes reset and authority promotion explicit and prevents scheduler call sites from
 * retaining a provisional set after authoritative view state arrives.</p>
 */
final class RtSectionBuildPriorityState {
    private final LinkedHashSet<SectionKey> interactiveKeys = new LinkedHashSet<>();
    private final Set<SectionKey> interactiveView = Collections.unmodifiableSet(interactiveKeys);
    private Set<SectionKey> preferredKeys = Set.of();

    Set<SectionKey> interactiveKeys() {
        return interactiveView;
    }

    Set<SectionKey> preferredKeys() {
        return preferredKeys;
    }

    int interactiveCount() {
        return interactiveKeys.size();
    }

    boolean isInteractive(SectionKey key) {
        return interactiveKeys.contains(Objects.requireNonNull(key, "key"));
    }

    void markInteractive(SectionKey key) {
        interactiveKeys.add(Objects.requireNonNull(key, "key"));
    }

    void resolveInteractive(SectionKey key) {
        interactiveKeys.remove(Objects.requireNonNull(key, "key"));
    }

    /** Clears both lanes for a full scene resynchronization. */
    void clear() {
        interactiveKeys.clear();
        preferredKeys = Set.of();
    }

    /** Clears retained urgency during terminal cache shutdown. */
    void clearInteractive() {
        interactiveKeys.clear();
    }

    void admitProvisional(boolean authoritativeForegroundEstablished, Collection<SectionKey> incomingMeshKeys) {
        preferredKeys = provisionalKeys(
                authoritativeForegroundEstablished,
                preferredKeys,
                incomingMeshKeys
        );
    }

    /** Replaces provisional priority with the immutable authoritative foreground publication. */
    void publishAuthority(Set<SectionKey> authoritativeKeys) {
        Objects.requireNonNull(authoritativeKeys, "authoritativeKeys");
        preferredKeys = authoritativeKeys instanceof PackedSectionMembership
                ? authoritativeKeys
                : Set.copyOf(authoritativeKeys);
    }

    static Set<SectionKey> provisionalKeys(
            boolean authoritativeForegroundEstablished,
            Set<SectionKey> currentPriorityKeys,
            Collection<SectionKey> incomingMeshKeys
    ) {
        Objects.requireNonNull(currentPriorityKeys, "currentPriorityKeys");
        Objects.requireNonNull(incomingMeshKeys, "incomingMeshKeys");
        if (authoritativeForegroundEstablished || !currentPriorityKeys.isEmpty() || incomingMeshKeys.isEmpty()) {
            return currentPriorityKeys;
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(incomingMeshKeys));
    }
}
