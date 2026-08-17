package top.ceroxe.rt.renderer.api.interop;

/** Mutually exclusive lifecycle state of one exported operating-system handle. */
public enum ExternalHandleState {
    /** The exporter owns a valid handle that has not been imported successfully. */
    EXPORTED,
    /** Successful import has been recorded and the declared ownership rule was applied. */
    IMPORTED,
    /** All exporter-owned native resources have been closed. */
    CLOSED
}
