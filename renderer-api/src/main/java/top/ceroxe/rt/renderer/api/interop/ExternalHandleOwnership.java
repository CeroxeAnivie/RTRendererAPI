package top.ceroxe.rt.renderer.api.interop;

/** Ownership rule applied after a successful native import. */
public enum ExternalHandleOwnership {
    /** A successful import consumes the exported operating-system handle. */
    IMPORT_CONSUMES_HANDLE,
    /** The exporter retains the handle and must close it after import. */
    EXPORTER_RETAINS_HANDLE
}
