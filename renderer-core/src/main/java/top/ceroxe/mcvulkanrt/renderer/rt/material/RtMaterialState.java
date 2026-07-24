package top.ceroxe.mcvulkanrt.renderer.rt.material;

import top.ceroxe.mcvulkanrt.renderer.RtMaterialTelemetrySink;

import java.util.Objects;
import java.util.List;

/**
 * Material-owned stable slot state for one acceleration-instance lane.
 *
 * <p>Acceleration code submits immutable section material facts and reads the
 * resulting slot id or immutable snapshot. It never owns sparse face ranges,
 * tombstones, dirty-slot tracking, or snapshot reuse policy. This mirrors the
 * GPUScene ownership rule: geometry scheduling decides which primitive exists;
 * the material system alone decides where its shader material lives.</p>
 */
public final class RtMaterialState<K> {
    private final MaterialSlotAllocator<K> slots;
    private RtSceneMaterialTable.Snapshot cachedSnapshot = RtSceneMaterialTable.Snapshot.empty();

    public RtMaterialState() {
        this(RtMaterialTelemetrySink.NOOP);
    }

    public RtMaterialState(RtMaterialTelemetrySink materialTelemetry) {
        slots = new MaterialSlotAllocator<>(materialTelemetry);
    }

    /** Submits the complete immutable material fact for one stable key. */
    public void submit(K key, RtSceneMaterialTable.SectionMaterial material) {
        slots.update(Objects.requireNonNull(key, "key"), Objects.requireNonNull(material, "material"));
    }

    /** Removes one material fact and leaves a tombstone until slot reuse is safe. */
    public boolean remove(K key) {
        return slots.release(Objects.requireNonNull(key, "key"));
    }

    public void clear() {
        slots.clear();
        cachedSnapshot = RtSceneMaterialTable.Snapshot.empty();
    }

    public Integer slotFor(K key) {
        return slots.slotFor(Objects.requireNonNull(key, "key"));
    }

    public int slotCount() {
        return slots.slotCount();
    }

    public int activeSlotCount() {
        return slots.activeSlotCount();
    }

    public int freeSlotCount() {
        return slots.freeSlotCount();
    }

    public int faceCapacity() {
        return slots.faceCapacity();
    }

    public int freeFaceRangeCount() {
        return slots.freeFaceRangeCount();
    }

    public int freeFaceCapacity() {
        return slots.freeFaceCapacity();
    }

    public long reusedSlotAllocations() {
        return slots.reusedSlotAllocations();
    }

    public long reusedFaceRangeAllocations() {
        return slots.reusedFaceRangeAllocations();
    }

    public long movedFaceRangeAllocations() {
        return slots.movedFaceRangeAllocations();
    }

    public long tailExtendedFaceRangeAllocations() {
        return slots.tailExtendedFaceRangeAllocations();
    }

    public static int grownFaceCapacity(int currentCapacity, int requiredFaces) {
        return MaterialSlotAllocator.grownFaceCapacity(currentCapacity, requiredFaces);
    }

    public static RtSceneMaterialTable.Snapshot emptySnapshot() {
        return RtSceneMaterialTable.Snapshot.empty();
    }

    /** Returns the immutable generation for this lane without re-packing stable slots. */
    public RtSceneMaterialTable.Snapshot snapshot(long materialRevision, int instanceLayoutHash) {
        cachedSnapshot = RtSceneMaterialTable.Snapshot.fromMaterialSlotsIncremental(
                cachedSnapshot,
                slots,
                materialRevision,
                instanceLayoutHash
        );
        return cachedSnapshot;
    }

    /**
     * Owns composition history across independent lanes. Keeping this state in
     * material avoids an acceleration cache accidentally treating a stale
     * composed generation as geometry state.
     */
    public static final class Composer {
        private RtSceneMaterialTable.Snapshot previousBase = RtSceneMaterialTable.Snapshot.empty();
        private RtSceneMaterialTable.Snapshot previousFarField = RtSceneMaterialTable.Snapshot.empty();
        private RtSceneMaterialTable.Snapshot composed = RtSceneMaterialTable.Snapshot.empty();

        public RtSceneMaterialTable.Snapshot compose(
                RtSceneMaterialTable.Snapshot base,
                RtSceneMaterialTable.Snapshot farField,
                long materialRevision,
                int instanceLayoutHash
        ) {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(farField, "farField");
            RtSceneMaterialTable.Snapshot next = farField.sectionCount() == 0
                    ? base
                    : base.sectionCount() == 0
                    ? farField
                    : RtSceneMaterialTable.Snapshot.composeIncremental(
                    composed,
                    previousBase,
                    base,
                    previousFarField,
                    farField,
                    materialRevision,
                    instanceLayoutHash
            );
            previousBase = base;
            previousFarField = farField;
            composed = next;
            return next;
        }

        public RtSceneMaterialTable.Snapshot current() {
            return composed;
        }

        public void clear() {
            previousBase = RtSceneMaterialTable.Snapshot.empty();
            previousFarField = RtSceneMaterialTable.Snapshot.empty();
            composed = RtSceneMaterialTable.Snapshot.empty();
        }
    }

    /** Owns incremental snapshot history for a fact stream without stable keys. */
    public static final class FactStream {
        private RtSceneMaterialTable.Snapshot snapshot = RtSceneMaterialTable.Snapshot.empty();

        public RtSceneMaterialTable.Snapshot submit(
                List<RtSceneMaterialTable.SectionMaterial> materials,
                int[] dirtySlots,
                long materialRevision,
                int instanceLayoutHash
        ) {
            snapshot = RtSceneMaterialTable.Snapshot.fromSectionMaterialsIncremental(
                    snapshot,
                    List.copyOf(Objects.requireNonNull(materials, "materials")),
                    Objects.requireNonNull(dirtySlots, "dirtySlots"),
                    materialRevision,
                    instanceLayoutHash
            );
            return snapshot;
        }

        public RtSceneMaterialTable.Snapshot submit(
                List<RtSceneMaterialTable.SectionMaterial> materials,
                long materialRevision,
                int instanceLayoutHash
        ) {
            snapshot = RtSceneMaterialTable.Snapshot.fromSectionMaterialsIncremental(
                    snapshot,
                    List.copyOf(Objects.requireNonNull(materials, "materials")),
                    materialRevision,
                    instanceLayoutHash
            );
            return snapshot;
        }

        public void clear() {
            snapshot = RtSceneMaterialTable.Snapshot.empty();
        }
    }
}
