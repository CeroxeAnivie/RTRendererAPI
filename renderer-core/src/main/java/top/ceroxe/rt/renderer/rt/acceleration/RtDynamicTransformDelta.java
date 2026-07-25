package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Immutable classification of transform changes produced by one dynamic-scene accept.
 */
record RtDynamicTransformDelta(
        int changedInstances,
        int translationChangedInstances,
        int linearChangedInstances,
        boolean uniformTranslation
) {
    static final RtDynamicTransformDelta EMPTY = new RtDynamicTransformDelta(0, 0, 0, false);
}
