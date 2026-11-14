package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.rt.RtSceneReadiness;

/**
 * Pure admission rules for dispatching a dynamic scene against a bound world TLAS.
 */
final class RtDynamicSceneDispatchPolicy {
    private RtDynamicSceneDispatchPolicy() {
    }

    static boolean canDispatch(
            boolean hasBoundWorldScene,
            boolean hasBoundDynamicLane,
            boolean targetGenerationHasTlasGeometry,
            boolean boundRevisionCurrent,
            boolean boundStructureCurrent
    ) {
        return RtSceneReadiness.READY_REASON.equals(blockReason(
                hasBoundWorldScene,
                hasBoundDynamicLane,
                targetGenerationHasTlasGeometry,
                boundRevisionCurrent,
                boundStructureCurrent
        ));
    }

    /**
     * A descriptor-visible in-place upload invalidates the current frame generation.
     */
    static boolean descriptorGenerationCanDispatch(
            boolean hasBoundWorldScene,
            boolean pendingWorldUploadIsCopyOnWrite,
            boolean pendingMaterialUploadIsCopyOnWrite,
            boolean pendingDynamicUploadIsCopyOnWrite
    ) {
        return hasBoundWorldScene
                && pendingWorldUploadIsCopyOnWrite
                && pendingMaterialUploadIsCopyOnWrite
                && pendingDynamicUploadIsCopyOnWrite;
    }

    static boolean shouldBlockForInteractiveWorldSceneBind(
            boolean hasBoundWorldScene,
            boolean interactiveWorldSceneBindPending
    ) {
        return interactiveWorldSceneBindPending && !hasBoundWorldScene;
    }

    /**
     * Frame-slot-local analytic content is safe only while neither generation carries TLAS geometry.
     */
    static boolean shouldDispatchCurrentSsboScene(
            boolean allowBoundDynamicGeneration,
            boolean publishedSceneHasTlasGeometry,
            boolean updateHasTlasGeometry
    ) {
        return allowBoundDynamicGeneration && !publishedSceneHasTlasGeometry && !updateHasTlasGeometry;
    }

    static String committedFrontBlockReason(
            boolean hasBoundWorldScene,
            boolean publishedSceneHasTlasGeometry,
            int builtDynamicInstances
    ) {
        if (builtDynamicInstances < 0) {
            throw new IllegalArgumentException("builtDynamicInstances must not be negative");
        }
        if (!hasBoundWorldScene) {
            return "worldTlasNotBound";
        }
        return publishedSceneHasTlasGeometry && builtDynamicInstances <= 0
                ? RtSceneReadiness.RT_DYNAMIC_BUILD_PENDING_REASON
                : RtSceneReadiness.READY_REASON;
    }

    static String blockReason(
            boolean hasBoundWorldScene,
            boolean hasBoundDynamicLane,
            boolean targetGenerationHasTlasGeometry,
            boolean boundRevisionCurrent,
            boolean boundStructureCurrent
    ) {
        if (hasBoundWorldScene && targetGenerationHasTlasGeometry) {
            if (!hasBoundDynamicLane) {
                return "worldTlasDynamicStructureRevisionNotBound";
            }
            if (boundRevisionCurrent) {
                return RtSceneReadiness.READY_REASON;
            }
            return boundStructureCurrent
                    ? "worldTlasDynamicTransformRevisionNotBound"
                    : "worldTlasDynamicStructureRevisionNotBound";
        }
        if (hasBoundWorldScene && !targetGenerationHasTlasGeometry) {
            if (!hasBoundDynamicLane || boundRevisionCurrent) {
                return RtSceneReadiness.READY_REASON;
            }
            return "dynamicClearRevisionNotBound";
        }
        if (hasBoundWorldScene && boundRevisionCurrent) {
            return RtSceneReadiness.READY_REASON;
        }
        return boundStructureCurrent
                ? "worldTlasDynamicTransformRevisionNotBound"
                : "worldTlasDynamicStructureRevisionNotBound";
    }
}
