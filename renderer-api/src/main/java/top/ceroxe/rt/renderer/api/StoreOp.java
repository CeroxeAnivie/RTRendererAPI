package top.ceroxe.rt.renderer.api;

/** Defines whether a render attachment's final contents remain observable. */
public enum StoreOp {
    /** Preserve the rendered attachment contents for subsequent commands. */
    STORE,
    /** Permit the backend to discard the rendered attachment contents. */
    DISCARD
}
