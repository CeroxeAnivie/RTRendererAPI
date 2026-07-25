package top.ceroxe.rt.renderer.rt.device;

/**
 * Read-only debugger and scheduling view of descriptor-commit ownership.
 *
 * <p>The mutable implementation remains private to {@link RtSceneBindCoordinator}; exposing this
 * interface instead of {@link RtSceneBindState} makes it impossible for frame scheduling code to
 * publish, clear, or replace a transaction behind the coordinator's resource-lifetime rules.</p>
 */
interface RtSceneBindView {
    PendingWorldSceneBind pendingWorld();

    boolean hasPendingWorld();

    PendingMaterialOnlyBind pendingMaterial();

    boolean hasPendingMaterial();

    PendingDynamicTlasBind pendingDynamic();

    boolean hasPendingDynamic();

    DeferredWorldSceneBind deferredWorld();

    boolean hasDeferredWorld();

    boolean hasDescriptorTransaction();
}
