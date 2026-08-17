package top.ceroxe.rt.renderer.api;

/** Primitive assembly topology for a graphics pipeline. */
public enum PrimitiveTopology {
    POINT_LIST(false),
    LINE_LIST(false),
    LINE_STRIP(false),
    TRIANGLE_LIST(false),
    TRIANGLE_STRIP(false),
    TRIANGLE_FAN(false),
    LINE_LIST_WITH_ADJACENCY(false),
    LINE_STRIP_WITH_ADJACENCY(false),
    TRIANGLE_LIST_WITH_ADJACENCY(false),
    TRIANGLE_STRIP_WITH_ADJACENCY(false),
    PATCH_LIST(true);

    private final boolean patch;

    PrimitiveTopology(boolean patch) {
        this.patch = patch;
    }

    /** @return whether pipeline state must specify patch control points */
    public boolean isPatchList() { return patch; }
}
