package top.ceroxe.rt.renderer.api;

/** Defines how a render attachment obtains its initial contents. */
public enum LoadOp {
    /** Preserve and consume the attachment's preceding contents. */
    LOAD,
    /** Replace the addressed attachment contents with an explicit clear value. */
    CLEAR,
    /** Treat the attachment's preceding contents as undefined. */
    DISCARD
}
