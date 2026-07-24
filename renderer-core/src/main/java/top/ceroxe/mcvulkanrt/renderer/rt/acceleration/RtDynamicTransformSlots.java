package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicModelTransformSnapshot;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Native-side mutable owner of dynamic model transform slots.
 *
 * <p>Model metadata changes rarely; transforms may change every frame. This table prevents the
 * acceleration-structure cache from encoding animation by replacing complete model objects. It
 * is never published directly: immutable TLAS records are produced from its numeric components,
 * and authoritative rebases stage a complete table before swapping it into the cache.</p>
 */
final class RtDynamicTransformSlots {
    private static final int COMPONENTS = DynamicModelTransformSnapshot.COMPONENTS;

    private float[] values = new float[0];
    private final BitSet activeSlots = new BitSet();
    private int capacity;

    static RtDynamicTransformSlots fromAuthoritative(DynamicRenderScene.DynamicModelFrameDelta delta) {
        Objects.requireNonNull(delta, "delta");
        DynamicRenderScene.DynamicModelSlotSnapshot membership = delta.membershipSnapshot();
        DynamicModelTransformSnapshot transforms = delta.transformSnapshot();
        RtDynamicTransformSlots staged = new RtDynamicTransformSlots();
        staged.resize(membership.physicalSlotCount());
        for (int index = 0; index < membership.activeSlotCount(); index++) {
            int slot = membership.slotAt(index);
            staged.set(slot, transforms);
        }
        if (staged.activeCount() != membership.activeSlotCount()) {
            throw new IllegalStateException("authoritative dynamic transform table lost active slots");
        }
        return staged;
    }

    void set(int slot, DynamicRenderScene.DynamicModelObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireWritableSlot(slot);
        int offset = slot * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            float value = observation.transformValue(component);
            requireFinite(value);
            values[offset + component] = value;
        }
        activeSlots.set(slot);
    }

    void set(int slot, DynamicModelTransformSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireWritableSlot(slot);
        if (snapshot.physicalSlotCount() != capacity) {
            throw new IllegalArgumentException("authoritative transform capacity does not match native slots");
        }
        int offset = slot * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            float value = snapshot.value(slot, component);
            requireFinite(value);
            values[offset + component] = value;
        }
        activeSlots.set(slot);
    }

    void set(int slot, DynamicRenderScene.DynamicModelFrameDelta delta, int update) {
        Objects.requireNonNull(delta, "delta");
        requireWritableSlot(slot);
        if (delta.slotAt(update) != slot
                || (delta.dirtyMaskAt(update) & DynamicRenderScene.DynamicModelFrameDelta.TRANSFORM) == 0) {
            throw new IllegalArgumentException("dynamic transform update does not target the requested slot");
        }
        int offset = slot * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            float value = delta.transformAt(update, component);
            requireFinite(value);
            values[offset + component] = value;
        }
        activeSlots.set(slot);
    }

    boolean matches(int slot, DynamicRenderScene.DynamicModelObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireActiveSlot(slot);
        int offset = slot * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            if (Float.floatToIntBits(values[offset + component])
                    != Float.floatToIntBits(observation.transformValue(component))) {
                return false;
            }
        }
        return true;
    }

    void remove(int slot) {
        requireActiveSlot(slot);
        activeSlots.clear(slot);
    }

    void resize(int nextCapacity) {
        if (nextCapacity < 0) {
            throw new IllegalArgumentException("dynamic transform capacity must not be negative");
        }
        if (nextCapacity < activeSlots.length()) {
            throw new IllegalStateException("cannot truncate active dynamic transform slots");
        }
        if (nextCapacity != capacity) {
            values = Arrays.copyOf(values, Math.multiplyExact(nextCapacity, COMPONENTS));
            capacity = nextCapacity;
        }
    }

    void clear() {
        values = new float[0];
        activeSlots.clear();
        capacity = 0;
    }

    int capacity() {
        return capacity;
    }

    int activeCount() {
        return activeSlots.cardinality();
    }

    float value(int slot, int component) {
        requireActiveSlot(slot);
        Objects.checkIndex(component, COMPONENTS);
        return values[slot * COMPONENTS + component];
    }

    DynamicMeshInstance.AffineTransform materialize(int slot) {
        return new DynamicMeshInstance.AffineTransform(
                value(slot, 0), value(slot, 1), value(slot, 2), value(slot, 3),
                value(slot, 4), value(slot, 5), value(slot, 6), value(slot, 7),
                value(slot, 8), value(slot, 9), value(slot, 10), value(slot, 11)
        );
    }

    RtAccelerationStructure.TlasInstance tlasInstance(
            int slot,
            long blasDeviceAddress,
            int customIndex,
            int visibilityMask
    ) {
        return new RtAccelerationStructure.TlasInstance(
                blasDeviceAddress,
                value(slot, 0), value(slot, 1), value(slot, 2), value(slot, 3),
                value(slot, 4), value(slot, 5), value(slot, 6), value(slot, 7),
                value(slot, 8), value(slot, 9), value(slot, 10), value(slot, 11),
                customIndex,
                visibilityMask
        );
    }

    private void requireWritableSlot(int slot) {
        Objects.checkIndex(slot, capacity);
    }

    private void requireActiveSlot(int slot) {
        Objects.checkIndex(slot, capacity);
        if (!activeSlots.get(slot)) {
            throw new IllegalStateException("dynamic transform slot is inactive: " + slot);
        }
    }

    private static void requireFinite(float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("dynamic transform component must be finite");
        }
    }
}
