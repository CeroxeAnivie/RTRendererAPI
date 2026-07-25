package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Scalar readiness snapshot for section-owned world-TLAS inputs.
 *
 * @param revision                input publication revision
 * @param resourceRevision        resident resource revision
 * @param materialRevision        material snapshot revision
 * @param instances               total active instances
 * @param activeViewSections      active visible sections
 * @param activeExactInstances    exact-section instances
 * @param farFieldInstances       far-field proxy instances
 * @param authoritativeView       whether the view membership is authoritative
 * @param authoritativeForeground whether retained foreground coverage is authoritative
 * @param pendingSectionBuilds    pending section build count
 * @param pendingTriangles        pending triangle count
 * @param cachedTriangles         cached triangle count
 */
public record RtSectionTlasBuildStats(
        long revision,
        long resourceRevision,
        long materialRevision,
        int instances,
        int activeViewSections,
        int activeExactInstances,
        int farFieldInstances,
        boolean authoritativeView,
        boolean authoritativeForeground,
        int pendingSectionBuilds,
        long pendingTriangles,
        long cachedTriangles
) {
    /**
     * Validates revision monotonicity and the exact instance-count decomposition.
     */
    public RtSectionTlasBuildStats {
        if (revision < 0L || resourceRevision < 0L || materialRevision < 0L) {
            throw new IllegalArgumentException("section revisions must not be negative");
        }
        if (instances < 0 || activeViewSections < 0 || activeExactInstances < 0 || farFieldInstances < 0) {
            throw new IllegalArgumentException("section instance counts must not be negative");
        }
        if (instances != Math.addExact(activeExactInstances, farFieldInstances)) {
            throw new IllegalArgumentException("section instance breakdown must equal total instances");
        }
        if (pendingSectionBuilds < 0) {
            throw new IllegalArgumentException("pendingSectionBuilds must not be negative");
        }
        if (pendingTriangles < 0L) {
            throw new IllegalArgumentException("pendingTriangles must not be negative");
        }
        if (cachedTriangles < 0L) {
            throw new IllegalArgumentException("cachedTriangles must not be negative");
        }
    }

    /**
     * Reports whether section BLAS work remains.
     *
     * @return whether pending section builds exist
     */
    public boolean hasPendingSectionBuilds() {
        return pendingSectionBuilds > 0;
    }
}
