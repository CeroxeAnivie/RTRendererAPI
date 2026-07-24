package top.ceroxe.mcvulkanrt.renderer.rt.device;

import java.util.Objects;

/**
 * Single mutable owner for descriptor-visible scene bind transactions.
 *
 * <p>Each slot represents a different producer lane, but all three mutate one descriptor-visible
 * scene and therefore share a single commit token. Build work may progress independently; at most
 * one world/material/dynamic bind transaction may own descriptor commit. Every replacement or
 * clear remains explicit, keeping ownership inspectable from one debugger object.</p>
 */
final class RtSceneBindState implements RtSceneBindView {
    private PendingWorldSceneBind pendingWorld;
    private PendingMaterialOnlyBind pendingMaterial;
    private PendingDynamicTlasBind pendingDynamic;
    private DeferredWorldSceneBind deferredWorld;

    public PendingWorldSceneBind pendingWorld() {
        return pendingWorld;
    }

    public boolean hasPendingWorld() {
        return pendingWorld != null;
    }

    PendingWorldSceneBind replacePendingWorld(PendingWorldSceneBind replacement) {
        requireNoForeignDescriptorTransaction("world", pendingMaterial, pendingDynamic);
        PendingWorldSceneBind previous = pendingWorld;
        pendingWorld = Objects.requireNonNull(replacement, "replacement");
        return previous;
    }

    PendingWorldSceneBind clearPendingWorld(PendingWorldSceneBind expected) {
        requireOwner(pendingWorld, expected, "pending world scene bind");
        pendingWorld = null;
        return expected;
    }

    PendingWorldSceneBind takePendingWorld() {
        PendingWorldSceneBind current = pendingWorld;
        pendingWorld = null;
        return current;
    }

    public PendingMaterialOnlyBind pendingMaterial() {
        return pendingMaterial;
    }

    public boolean hasPendingMaterial() {
        return pendingMaterial != null;
    }

    void publishPendingMaterial(PendingMaterialOnlyBind pending) {
        requireNoForeignDescriptorTransaction("material", pendingWorld, pendingDynamic);
        if (pendingMaterial != null) {
            throw new IllegalStateException("only one pending material-only bind is allowed");
        }
        pendingMaterial = Objects.requireNonNull(pending, "pending");
    }

    PendingMaterialOnlyBind clearPendingMaterial(PendingMaterialOnlyBind expected) {
        requireOwner(pendingMaterial, expected, "pending material-only bind");
        pendingMaterial = null;
        return expected;
    }

    PendingMaterialOnlyBind takePendingMaterial() {
        PendingMaterialOnlyBind current = pendingMaterial;
        pendingMaterial = null;
        return current;
    }

    public PendingDynamicTlasBind pendingDynamic() {
        return pendingDynamic;
    }

    public boolean hasPendingDynamic() {
        return pendingDynamic != null;
    }

    void publishPendingDynamic(PendingDynamicTlasBind pending) {
        requireNoForeignDescriptorTransaction("dynamic", pendingWorld, pendingMaterial);
        if (pendingDynamic != null) {
            throw new IllegalStateException("only one pending dynamic TLAS bind is allowed");
        }
        pendingDynamic = Objects.requireNonNull(pending, "pending");
    }

    PendingDynamicTlasBind clearPendingDynamic(PendingDynamicTlasBind expected) {
        requireOwner(pendingDynamic, expected, "pending dynamic TLAS bind");
        pendingDynamic = null;
        return expected;
    }

    public DeferredWorldSceneBind deferredWorld() {
        return deferredWorld;
    }

    public boolean hasDeferredWorld() {
        return deferredWorld != null;
    }

    void publishDeferredWorld(DeferredWorldSceneBind deferred) {
        if (deferredWorld != null) {
            throw new IllegalStateException("only one deferred world scene bind is allowed");
        }
        deferredWorld = Objects.requireNonNull(deferred, "deferred");
    }

    DeferredWorldSceneBind replaceDeferredWorld(DeferredWorldSceneBind expected, DeferredWorldSceneBind replacement) {
        requireOwner(deferredWorld, expected, "deferred world scene bind");
        deferredWorld = Objects.requireNonNull(replacement, "replacement");
        return expected;
    }

    DeferredWorldSceneBind clearDeferredWorld(DeferredWorldSceneBind expected) {
        requireOwner(deferredWorld, expected, "deferred world scene bind");
        deferredWorld = null;
        return expected;
    }

    DeferredWorldSceneBind takeDeferredWorld() {
        DeferredWorldSceneBind current = deferredWorld;
        deferredWorld = null;
        return current;
    }

    public boolean hasDescriptorTransaction() {
        return pendingWorld != null || pendingMaterial != null || pendingDynamic != null;
    }

    private static void requireNoForeignDescriptorTransaction(String owner, Object first, Object second) {
        if (first != null || second != null) {
            throw new IllegalStateException(owner + " bind cannot overlap another descriptor transaction");
        }
    }

    private static void requireOwner(Object current, Object expected, String label) {
        Objects.requireNonNull(expected, "expected");
        if (current != expected) {
            throw new IllegalStateException(label + " no longer owns the transaction");
        }
    }
}
