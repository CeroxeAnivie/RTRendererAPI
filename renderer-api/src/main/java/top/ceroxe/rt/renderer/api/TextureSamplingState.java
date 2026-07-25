package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Frame-local texture minification policy, independent of any host runtime's option names.
 *
 * <p>The texture asset still owns addressing and within-level filtering. This value controls how
 * a renderer derives and integrates the screen-space footprint when a texture becomes smaller
 * than one output pixel.</p>
 *
 * @param minificationMode texture-footprint integration strategy
 * @param maxAnisotropy    anisotropy limit in {@code [1, 16]}, or {@code 1} for non-anisotropic modes
 */
public record TextureSamplingState(MinificationMode minificationMode, int maxAnisotropy) {
    public static final int MAX_SUPPORTED_ANISOTROPY = 16;

    /**
     * Validates and creates a texture minification policy.
     *
     * @param minificationMode texture-footprint integration strategy
     * @param maxAnisotropy    anisotropy limit in {@code [1, 16]}, or one for other modes
     */
    public TextureSamplingState {
        minificationMode = Objects.requireNonNull(minificationMode, "minificationMode");
        if (maxAnisotropy < 1 || maxAnisotropy > MAX_SUPPORTED_ANISOTROPY) {
            throw new IllegalArgumentException("maxAnisotropy must be between 1 and 16");
        }
        if (minificationMode != MinificationMode.ANISOTROPIC && maxAnisotropy != 1) {
            throw new IllegalArgumentException(
                    "maxAnisotropy must be 1 unless anisotropic minification is selected"
            );
        }
    }

    /**
     * Returns the single-sample pixel-stable policy.
     *
     * @return immutable pixel-stable policy
     */
    public static TextureSamplingState pixelStable() {
        return new TextureSamplingState(MinificationMode.PIXEL_STABLE, 1);
    }

    /**
     * Returns the rotated-grid supersampling policy.
     *
     * @return immutable rotated-grid policy
     */
    public static TextureSamplingState rotatedGridSupersampling() {
        return new TextureSamplingState(MinificationMode.ROTATED_GRID_SUPERSAMPLING, 1);
    }

    /**
     * Creates an anisotropic minification policy.
     *
     * @param maxAnisotropy anisotropy limit in {@code [1, 16]}
     * @return immutable anisotropic policy
     */
    public static TextureSamplingState anisotropic(int maxAnisotropy) {
        return new TextureSamplingState(MinificationMode.ANISOTROPIC, maxAnisotropy);
    }

    /**
     * Texture-footprint integration strategy used during minification.
     */
    public enum MinificationMode {
        /**
         * Stable single-sample footprint.
         */
        PIXEL_STABLE,
        /**
         * Rotated-grid multisample footprint.
         */
        ROTATED_GRID_SUPERSAMPLING,
        /**
         * Direction-aware anisotropic footprint.
         */
        ANISOTROPIC
    }
}
