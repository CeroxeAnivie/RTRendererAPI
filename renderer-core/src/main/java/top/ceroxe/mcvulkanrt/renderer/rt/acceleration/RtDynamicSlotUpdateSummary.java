package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;

/** Transient dirty-slot and transform-delta evidence for one dynamic scene accept. */
final class RtDynamicSlotUpdateSummary {
    private int reusedSlots;
    private int addedSlots;
    private int removedSlots;
    private int replacedSlots;
    private int transformDirtySlots;
    private int tlasInstanceDirtySlots;
    private int materialDirtySlots;
    private int translationDirtySlots;
    private int linearDirtySlots;
    private boolean haveReferenceTranslation;
    private boolean uniformTranslation = true;
    private float referenceTranslationX;
    private float referenceTranslationY;
    private float referenceTranslationZ;

    void addedSlot() { addedSlots++; }
    void removedSlot() { removedSlots++; }
    void replacedSlot() { replacedSlots++; }

    void rebased(int previousActiveSlots, int authoritativeActiveSlots) {
        if (previousActiveSlots < 0 || authoritativeActiveSlots < 0) {
            throw new IllegalArgumentException("dynamic slot rebase counts must not be negative");
        }
        removedSlots = Math.addExact(removedSlots, previousActiveSlots);
        addedSlots = Math.addExact(addedSlots, authoritativeActiveSlots);
    }

    void reusedSlot(boolean transformChanged, boolean renderLaneChanged, boolean materialChanged,
                    DynamicMeshInstance.AffineTransform before, DynamicMeshInstance.AffineTransform after) {
        beginReusedSlot(transformChanged, renderLaneChanged, materialChanged);
        if (transformChanged) {
            recordTransformDelta(after.translateX() - before.translateX(), after.translateY() - before.translateY(),
                    after.translateZ() - before.translateZ(), before.m00() != after.m00() || before.m01() != after.m01()
                            || before.m02() != after.m02() || before.m10() != after.m10() || before.m11() != after.m11()
                            || before.m12() != after.m12() || before.m20() != after.m20() || before.m21() != after.m21()
                            || before.m22() != after.m22());
        }
    }

    void reusedSlot(boolean transformChanged, boolean renderLaneChanged, boolean materialChanged,
                    RtDynamicTransformSlots before, int slot, DynamicRenderScene.DynamicModelFrameDelta after, int update) {
        beginReusedSlot(transformChanged, renderLaneChanged, materialChanged);
        if (transformChanged) {
            recordTransformDelta(after.transformAt(update, 3) - before.value(slot, 3),
                    after.transformAt(update, 7) - before.value(slot, 7), after.transformAt(update, 11) - before.value(slot, 11),
                    linearChanged(before, slot, after, update));
        }
    }

    void reusedSlot(boolean transformChanged, boolean renderLaneChanged, boolean materialChanged,
                    RtDynamicTransformSlots before, int slot, DynamicRenderScene.DynamicModelObservation after) {
        beginReusedSlot(transformChanged, renderLaneChanged, materialChanged);
        if (transformChanged) {
            recordTransformDelta(after.transformValue(3) - before.value(slot, 3),
                    after.transformValue(7) - before.value(slot, 7), after.transformValue(11) - before.value(slot, 11),
                    linearChanged(before, slot, after));
        }
    }

    private void beginReusedSlot(boolean transformChanged, boolean renderLaneChanged, boolean materialChanged) {
        reusedSlots++;
        if (materialChanged) materialDirtySlots++;
        if (transformChanged || renderLaneChanged) tlasInstanceDirtySlots++;
    }

    private void recordTransformDelta(float deltaX, float deltaY, float deltaZ, boolean linearChanged) {
        transformDirtySlots++;
        if (deltaX != 0.0F || deltaY != 0.0F || deltaZ != 0.0F) {
            translationDirtySlots++;
            if (!haveReferenceTranslation) {
                haveReferenceTranslation = true;
                referenceTranslationX = deltaX;
                referenceTranslationY = deltaY;
                referenceTranslationZ = deltaZ;
            } else if (Float.compare(referenceTranslationX, deltaX) != 0 || Float.compare(referenceTranslationY, deltaY) != 0
                    || Float.compare(referenceTranslationZ, deltaZ) != 0) {
                uniformTranslation = false;
            }
        }
        if (linearChanged) linearDirtySlots++;
    }

    private static boolean linearChanged(RtDynamicTransformSlots before, int slot,
                                         DynamicRenderScene.DynamicModelFrameDelta after, int update) {
        return before.value(slot, 0) != after.transformAt(update, 0) || before.value(slot, 1) != after.transformAt(update, 1)
                || before.value(slot, 2) != after.transformAt(update, 2) || before.value(slot, 4) != after.transformAt(update, 4)
                || before.value(slot, 5) != after.transformAt(update, 5) || before.value(slot, 6) != after.transformAt(update, 6)
                || before.value(slot, 8) != after.transformAt(update, 8) || before.value(slot, 9) != after.transformAt(update, 9)
                || before.value(slot, 10) != after.transformAt(update, 10);
    }

    private static boolean linearChanged(RtDynamicTransformSlots before, int slot,
                                         DynamicRenderScene.DynamicModelObservation after) {
        return before.value(slot, 0) != after.transformValue(0) || before.value(slot, 1) != after.transformValue(1)
                || before.value(slot, 2) != after.transformValue(2) || before.value(slot, 4) != after.transformValue(4)
                || before.value(slot, 5) != after.transformValue(5) || before.value(slot, 6) != after.transformValue(6)
                || before.value(slot, 8) != after.transformValue(8) || before.value(slot, 9) != after.transformValue(9)
                || before.value(slot, 10) != after.transformValue(10);
    }

    boolean topologyChanged() { return addedSlots > 0 || removedSlots > 0 || replacedSlots > 0; }
    int reusedSlots() { return reusedSlots; }
    int addedSlots() { return addedSlots; }
    int removedSlots() { return removedSlots; }
    int transformDirtySlots() { return transformDirtySlots; }
    int tlasInstanceDirtySlots() { return tlasInstanceDirtySlots; }
    int materialDirtySlots() { return materialDirtySlots; }
    RtDynamicTransformDelta transformDelta() {
        return new RtDynamicTransformDelta(transformDirtySlots, translationDirtySlots, linearDirtySlots,
                translationDirtySlots > 0 && uniformTranslation);
    }
}

record RtDynamicTransformDelta(int changedInstances, int translationChangedInstances,
                               int linearChangedInstances, boolean uniformTranslation) {
    static final RtDynamicTransformDelta EMPTY = new RtDynamicTransformDelta(0, 0, 0, false);
}
