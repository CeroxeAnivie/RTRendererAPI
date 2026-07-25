package top.ceroxe.rt.renderer.rt.material;

import top.ceroxe.rt.renderer.RtMaterialTelemetrySink;

import java.util.List;
import java.util.Objects;

/**
 * Material-owned stable slot state for one acceleration-instance lane.
 *
 * <p>Acceleration code submits immutable section material facts and reads the
 * resulting slot id or immutable snapshot. It never owns sparse face ranges,
 * tombstones, dirty-slot tracking, or snapshot reuse policy. This mirrors the
 * GPUScene ownership rule: geometry scheduling decides which primitive exists;
 * the material system alone decides where its shader material lives.</p>
 *
 * @param <K> stable material-owner key type
 */
public final class RtMaterialState<K> {
    private final MaterialSlotAllocator<K> slots;
    private RtSceneMaterialTable.Snapshot cachedSnapshot = RtSceneMaterialTable.Snapshot.empty();

    /**
     * Creates material state with telemetry disabled.
     */
    public RtMaterialState() {
        this(RtMaterialTelemetrySink.NOOP);
    }

    /**
     * Creates material state with the supplied lifecycle observer.
     *
     * @param materialTelemetry non-null telemetry sink
     */
    public RtMaterialState(RtMaterialTelemetrySink materialTelemetry) {
        slots = new MaterialSlotAllocator<>(materialTelemetry);
    }

    /**
     * Computes the deterministic face-range capacity needed for growth.
     *
     * @param currentCapacity current range capacity
     * @param requiredFaces   required record count
     * @return deterministic grown capacity
     */
    public static int grownFaceCapacity(int currentCapacity, int requiredFaces) {
        return MaterialSlotAllocator.grownFaceCapacity(currentCapacity, requiredFaces);
    }

    /**
     * Returns the shared immutable empty material snapshot.
     *
     * @return immutable empty snapshot
     */
    public static RtSceneMaterialTable.Snapshot emptySnapshot() {
        return RtSceneMaterialTable.Snapshot.empty();
    }

    /**
     * Submits the complete immutable material fact for one stable key.
     *
     * @param key      stable owner key
     * @param material complete immutable material record
     */
    public void submit(K key, RtSceneMaterialTable.SectionMaterial material) {
        slots.update(Objects.requireNonNull(key, "key"), Objects.requireNonNull(material, "material"));
    }

    /**
     * Removes one material fact and leaves a tombstone until slot reuse is safe.
     *
     * @param key stable owner key
     * @return whether an active slot was removed
     */
    public boolean remove(K key) {
        return slots.release(Objects.requireNonNull(key, "key"));
    }

    /**
     * Removes all slots and resets incremental snapshot history.
     */
    public void clear() {
        slots.clear();
        cachedSnapshot = RtSceneMaterialTable.Snapshot.empty();
    }

    /**
     * Finds the stable slot for an owner.
     *
     * @param key stable owner key
     * @return allocated slot, or {@code null}
     */
    public Integer slotFor(K key) {
        return slots.slotFor(Objects.requireNonNull(key, "key"));
    }

    /**
     * Returns the physical slot count including tombstones.
     *
     * @return physical slot count
     */
    public int slotCount() {
        return slots.slotCount();
    }

    /**
     * Returns the active slot count.
     *
     * @return active slot count
     */
    public int activeSlotCount() {
        return slots.activeSlotCount();
    }

    /**
     * Returns the reusable slot count.
     *
     * @return reusable slot count
     */
    public int freeSlotCount() {
        return slots.freeSlotCount();
    }

    /**
     * Returns total allocated face-record capacity.
     *
     * @return face-record capacity
     */
    public int faceCapacity() {
        return slots.faceCapacity();
    }

    /**
     * Returns the number of reusable face ranges.
     *
     * @return reusable range count
     */
    public int freeFaceRangeCount() {
        return slots.freeFaceRangeCount();
    }

    /**
     * Returns reusable face-record capacity.
     *
     * @return reusable face-record capacity
     */
    public int freeFaceCapacity() {
        return slots.freeFaceCapacity();
    }

    /**
     * Returns cumulative reused-slot allocations.
     *
     * @return reused-slot allocation count
     */
    public long reusedSlotAllocations() {
        return slots.reusedSlotAllocations();
    }

    /**
     * Returns cumulative reused-face-range allocations.
     *
     * @return reused-range allocation count
     */
    public long reusedFaceRangeAllocations() {
        return slots.reusedFaceRangeAllocations();
    }

    /**
     * Returns cumulative allocations that relocated an active range.
     *
     * @return relocation count
     */
    public long movedFaceRangeAllocations() {
        return slots.movedFaceRangeAllocations();
    }

    /**
     * Returns cumulative tail-extension allocations.
     *
     * @return tail-extension count
     */
    public long tailExtendedFaceRangeAllocations() {
        return slots.tailExtendedFaceRangeAllocations();
    }

    /**
     * Returns the immutable generation for this lane without re-packing stable slots.
     *
     * @param materialRevision   authoritative material revision
     * @param instanceLayoutHash instance-to-slot layout hash
     * @return immutable incremental snapshot
     */
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

        /**
         * Creates an empty composition history.
         */
        public Composer() {
        }

        /**
         * Composes base and far-field lanes while reusing unchanged ranges.
         *
         * @param base               base-lane snapshot
         * @param farField           far-field-lane snapshot
         * @param materialRevision   composed revision
         * @param instanceLayoutHash composed instance layout hash
         * @return immutable composed snapshot
         */
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

        /**
         * Returns the most recently composed snapshot.
         *
         * @return current composed snapshot
         */
        public RtSceneMaterialTable.Snapshot current() {
            return composed;
        }

        /**
         * Resets both lane histories and the composed generation.
         */
        public void clear() {
            previousBase = RtSceneMaterialTable.Snapshot.empty();
            previousFarField = RtSceneMaterialTable.Snapshot.empty();
            composed = RtSceneMaterialTable.Snapshot.empty();
        }
    }

    /**
     * Owns incremental snapshot history for a fact stream without stable keys.
     */
    public static final class FactStream {
        private RtSceneMaterialTable.Snapshot snapshot = RtSceneMaterialTable.Snapshot.empty();

        /**
         * Creates an empty fact-stream history.
         */
        public FactStream() {
        }

        /**
         * Submits facts with an exact dirty-slot set.
         *
         * @param materials          immutable slot-ordered facts
         * @param dirtySlots         changed slot indices
         * @param materialRevision   authoritative revision
         * @param instanceLayoutHash instance layout hash
         * @return new incremental snapshot
         */
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

        /**
         * Submits facts and derives changes against the previous generation.
         *
         * @param materials          immutable slot-ordered facts
         * @param materialRevision   authoritative revision
         * @param instanceLayoutHash instance layout hash
         * @return new incremental snapshot
         */
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

        /**
         * Resets incremental fact-stream history.
         */
        public void clear() {
            snapshot = RtSceneMaterialTable.Snapshot.empty();
        }
    }
}
