package top.ceroxe.rt.renderer.rt.device;

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

    /**
     * Returns the world transaction currently waiting for descriptor commit.
     *
     * @return the pending world transaction, or {@code null} when none owns the slot
     */
    public PendingWorldSceneBind pendingWorld() {
        return pendingWorld;
    }

    /**
     * Reports whether a world transaction owns the descriptor commit slot.
     *
     * @return {@code true} when a world transaction is pending
     */
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

    /**
     * Returns the material-only transaction currently waiting for descriptor commit.
     *
     * @return the pending material transaction, or {@code null} when none owns the slot
     */
    public PendingMaterialOnlyBind pendingMaterial() {
        return pendingMaterial;
    }

    /**
     * Reports whether a material-only transaction owns the descriptor commit slot.
     *
     * @return {@code true} when a material-only transaction is pending
     */
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

    /**
     * Returns the dynamic-TLAS transaction currently waiting for descriptor commit.
     *
     * @return the pending dynamic transaction, or {@code null} when none owns the slot
     */
    public PendingDynamicTlasBind pendingDynamic() {
        return pendingDynamic;
    }

    /**
     * Reports whether a dynamic-TLAS transaction owns the descriptor commit slot.
     *
     * @return {@code true} when a dynamic transaction is pending
     */
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

    /**
     * Returns the world update deferred until its scheduling gate opens.
     *
     * @return the deferred world update, or {@code null} when none is queued
     */
    public DeferredWorldSceneBind deferredWorld() {
        return deferredWorld;
    }

    /**
     * Reports whether a world update is waiting outside the descriptor transaction slot.
     *
     * @return {@code true} when a deferred world update is queued
     */
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

    /**
     * Reports whether any mutually exclusive descriptor transaction owns the commit slot.
     *
     * @return {@code true} when a world, material, or dynamic transaction is pending
     */
    public boolean hasDescriptorTransaction() {
        return pendingWorld != null || pendingMaterial != null || pendingDynamic != null;
    }
}
