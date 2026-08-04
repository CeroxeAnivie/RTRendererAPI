package top.ceroxe.rt.renderer.api;

/**
 * Exact depth projection used to produce reconstruction depth for a frame.
 *
 * <p>Temporal reconstruction cannot infer these values from the camera FOV or fog range. Its
 * depth reprojection is defined by the same finite perspective transform that produced the tagged
 * depth image. {@link #unknown()} deliberately remains the default until a host can publish that
 * transform; reconstruction implementations must decline activation rather than invent planes.</p>
 */
public final class DepthProjectionState {
    private static final DepthProjectionState UNKNOWN = new DepthProjectionState(false, 0.0F, 0.0F);

    private final boolean known;
    private final float nearPlane;
    private final float farPlane;

    private DepthProjectionState(boolean known, float nearPlane, float farPlane) {
        this.known = known;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    /**
     * Returns the canonical state for hosts that cannot publish their exact depth transform.
     *
     * @return immutable unknown-projection marker
     */
    public static DepthProjectionState unknown() {
        return UNKNOWN;
    }

    /**
     * Creates a finite forward-Z Vulkan perspective projection with depth in {@code [0, 1]}.
     *
     * @param nearPlane positive near distance in camera-view units
     * @param farPlane finite distance strictly greater than {@code nearPlane}
     * @return immutable exact projection state
     * @throws IllegalArgumentException if either plane is not finite or the interval is invalid
     */
    public static DepthProjectionState vulkanPerspective(float nearPlane, float farPlane) {
        if (!Float.isFinite(nearPlane) || nearPlane <= 0.0F) {
            throw new IllegalArgumentException("nearPlane must be finite and positive");
        }
        if (!Float.isFinite(farPlane) || farPlane <= nearPlane) {
            throw new IllegalArgumentException("farPlane must be finite and greater than nearPlane");
        }
        return new DepthProjectionState(true, nearPlane, farPlane);
    }

    /**
     * Returns whether this state identifies the transform that produced the depth image.
     *
     * @return {@code true} only when near and far planes are known exactly
     */
    public boolean known() {
        return known;
    }

    /**
     * Returns the positive near clip plane.
     *
     * @return finite positive near plane
     * @throws IllegalStateException if the host did not publish the projection
     */
    public float nearPlane() {
        requireKnown();
        return nearPlane;
    }

    /**
     * Returns the finite far clip plane.
     *
     * @return finite far plane greater than {@link #nearPlane()}
     * @throws IllegalStateException if the host did not publish the projection
     */
    public float farPlane() {
        requireKnown();
        return farPlane;
    }

    private void requireKnown() {
        if (!known) throw new IllegalStateException("depth projection is unknown");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DepthProjectionState projection)) return false;
        return known == projection.known
                && Float.compare(nearPlane, projection.nearPlane) == 0
                && Float.compare(farPlane, projection.farPlane) == 0;
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(known);
        result = 31 * result + Float.hashCode(nearPlane);
        return 31 * result + Float.hashCode(farPlane);
    }

    @Override
    public String toString() {
        return known
                ? "DepthProjectionState[vulkanPerspective, nearPlane=" + nearPlane + ", farPlane=" + farPlane + ']'
                : "DepthProjectionState[unknown]";
    }
}
