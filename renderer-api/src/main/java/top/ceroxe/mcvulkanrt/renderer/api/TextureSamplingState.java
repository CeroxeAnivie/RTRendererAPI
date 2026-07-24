package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.Objects;

/**
 * Frame-local texture minification policy, independent of any source engine's option names.
 *
 * <p>The texture asset still owns addressing and within-level filtering. This value controls how
 * a renderer derives and integrates the screen-space footprint when a texture becomes smaller
 * than one output pixel.</p>
 */
public record TextureSamplingState(MinificationMode minificationMode, int maxAnisotropy) {
    public static final int MAX_SUPPORTED_ANISOTROPY = 16;

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

    public static TextureSamplingState pixelStable() {
        return new TextureSamplingState(MinificationMode.PIXEL_STABLE, 1);
    }

    public static TextureSamplingState rotatedGridSupersampling() {
        return new TextureSamplingState(MinificationMode.ROTATED_GRID_SUPERSAMPLING, 1);
    }

    public static TextureSamplingState anisotropic(int maxAnisotropy) {
        return new TextureSamplingState(MinificationMode.ANISOTROPIC, maxAnisotropy);
    }

    public enum MinificationMode {
        PIXEL_STABLE,
        ROTATED_GRID_SUPERSAMPLING,
        ANISOTROPIC
    }
}
