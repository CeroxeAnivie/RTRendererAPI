package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.Objects;
import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtMaterialState;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/**
 * Owns section material slots, composition history, and their publication revision.
 *
 * <p>A slot mutation without a matching revision leaves descriptor upload and TLAS input caches on
 * stale material data. Every live mutation computes the next revision before touching slot state
 * and commits it immediately after, while terminal discard has an explicit no-publication path.</p>
 */
final class RtSectionMaterialPublicationState {
    private final RtMaterialState<SectionKey> slots;
    private final RtMaterialState.Composer composer = new RtMaterialState.Composer();
    private long revision;

    RtSectionMaterialPublicationState(RtMaterialTelemetrySink telemetry) {
        slots = new RtMaterialState<>(Objects.requireNonNull(telemetry, "telemetry"));
    }

    void submit(SectionKey key, RtSceneMaterialTable.SectionMaterial material) {
        long nextRevision = Math.incrementExact(revision);
        slots.submit(Objects.requireNonNull(key, "key"), Objects.requireNonNull(material, "material"));
        revision = nextRevision;
    }

    void remove(SectionKey key) {
        long nextRevision = Math.incrementExact(revision);
        slots.remove(Objects.requireNonNull(key, "key"));
        revision = nextRevision;
    }

    void clearAndAdvance() {
        long nextRevision = Math.incrementExact(revision);
        slots.clear();
        composer.clear();
        revision = nextRevision;
    }

    /** Releases terminal CPU state after the cache is closed; no consumer can observe a successor. */
    void discard() {
        slots.clear();
        composer.clear();
    }

    /** Advances composition identity for a FarField material mutation owned by the sibling cache. */
    void advanceExternalRevision() {
        revision = Math.incrementExact(revision);
    }

    long revision() {
        return revision;
    }

    Composition compose(RtSceneMaterialTable.Snapshot farField, int instanceLayoutHash) {
        Objects.requireNonNull(farField, "farField");
        RtSceneMaterialTable.Snapshot previous = composer.current();
        RtSceneMaterialTable.Snapshot base = slots.snapshot(revision, instanceLayoutHash);
        RtSceneMaterialTable.Snapshot composed = composer.compose(
                base,
                farField,
                revision,
                instanceLayoutHash
        );
        return new Composition(composed, !composed.signature().equals(previous.signature()));
    }

    Integer slotFor(SectionKey key) {
        return slots.slotFor(Objects.requireNonNull(key, "key"));
    }

    int slotCount() {
        return slots.slotCount();
    }

    int activeSlotCount() {
        return slots.activeSlotCount();
    }

    int freeSlotCount() {
        return slots.freeSlotCount();
    }

    int faceCapacity() {
        return slots.faceCapacity();
    }

    int freeFaceRangeCount() {
        return slots.freeFaceRangeCount();
    }

    int freeFaceCapacity() {
        return slots.freeFaceCapacity();
    }

    long reusedSlotAllocations() {
        return slots.reusedSlotAllocations();
    }

    long reusedFaceRangeAllocations() {
        return slots.reusedFaceRangeAllocations();
    }

    long movedFaceRangeAllocations() {
        return slots.movedFaceRangeAllocations();
    }

    long tailExtendedFaceRangeAllocations() {
        return slots.tailExtendedFaceRangeAllocations();
    }

    record Composition(RtSceneMaterialTable.Snapshot snapshot, boolean changed) {
        Composition {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
