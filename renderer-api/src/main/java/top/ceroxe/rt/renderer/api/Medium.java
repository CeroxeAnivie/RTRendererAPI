package top.ceroxe.rt.renderer.api;
import java.util.Objects;
/** Homogeneous RGB absorption per world unit, inside a closed dielectric surface.
 * Scattering, emission and density grids are not part of the 7.0 managed contract.
 * @param absorption nonnegative linear RGB absorption coefficients
 * @param indexOfRefraction refractive index in [1,4]
 */
public record Medium(Rgb absorption, float indexOfRefraction) {
    private static final Medium VACUUM = new Medium(Rgb.ZERO, 1);
    public Medium {
        Objects.requireNonNull(absorption, "absorption");
        if (!Float.isFinite(indexOfRefraction) || indexOfRefraction < 1 || indexOfRefraction > 4)
            throw new IllegalArgumentException("indexOfRefraction must be within [1,4]");
    }
    public static Medium vacuum() { return VACUUM; }
    public boolean isVacuum() { return indexOfRefraction == 1 && absorption.isZero(); }
}
