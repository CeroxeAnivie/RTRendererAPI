package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Optical coefficients per world-distance unit, in linear RGB. Total extinction is
 * absorption plus scattering; density and the density field scale all three coefficients.
 * A boundary selects one interior medium. Overlap does not implicitly add coefficients.
 * Density does not change the index of refraction. Construction rejects coefficient sums
 * or density-scaled coefficients that overflow float storage.
 *
 * @param absorption absorption coefficient per world-distance unit
 * @param scattering scattering coefficient per world-distance unit
 * @param emission emitted radiance per world-distance unit
 * @param density finite non-negative coefficient multiplier
 * @param indexOfRefraction finite refractive index, at least one
 * @param anisotropy Henyey-Greenstein asymmetry in (-1, 1); zero is isotropic,
 *                   positive values scatter forward
 * @param densityField spatial coefficient multiplier, uniform or a normalized density grid
 */
public record Medium(Rgb absorption, Rgb scattering, Rgb emission, float density,
        float indexOfRefraction, float anisotropy, MediumDensityField densityField) {
    private static final Medium VACUUM = new Medium(Rgb.ZERO, Rgb.ZERO, Rgb.ZERO, 0, 1, 0);

    public Medium {
        Objects.requireNonNull(absorption, "absorption");
        Objects.requireNonNull(scattering, "scattering");
        Objects.requireNonNull(emission, "emission");
        Objects.requireNonNull(densityField, "densityField");
        if (!Float.isFinite(density) || density < 0)
            throw new IllegalArgumentException("density must be finite and non-negative");
        if (!Float.isFinite(indexOfRefraction) || indexOfRefraction < 1)
            throw new IllegalArgumentException("indexOfRefraction must be finite and at least one");
        if (!Float.isFinite(anisotropy) || anisotropy <= -1 || anisotropy >= 1)
            throw new IllegalArgumentException("anisotropy must be finite and within (-1, 1)");
        // Validate both stored sums and effective coefficients before uploading to float shaders.
        absorption.add(scattering).multiply(density);
        emission.multiply(density);
        density = density == 0 ? 0 : density;
        anisotropy = anisotropy == 0 ? 0 : anisotropy;
    }

    /** Creates a homogeneous medium with the given optical coefficients. */
    public Medium(Rgb absorption, Rgb scattering, Rgb emission, float density,
            float indexOfRefraction, float anisotropy) {
        this(absorption, scattering, emission, density, indexOfRefraction, anisotropy,
                MediumDensityField.uniform());
    }

    /** Returns the shared nonparticipating medium with refractive index one. */
    public static Medium vacuum() { return VACUUM; }
    /** Returns absorption plus scattering, before applying density. */
    public Rgb extinction() { return absorption.add(scattering); }
    /** Returns whether absorption, scattering or emission can be nonzero within the density field. */
    public boolean participating() {
        return density > 0 && (!absorption.isZero() || !scattering.isZero() || !emission.isZero());
    }
    /** Returns whether this medium has neither volume transport nor a refractive-index change. */
    public boolean isVacuum() { return indexOfRefraction == 1 && !participating(); }
    public Medium withDensity(float value) {
        return new Medium(absorption, scattering, emission, value, indexOfRefraction, anisotropy, densityField);
    }
    public Medium withDensityField(MediumDensityField value) {
        return new Medium(absorption, scattering, emission, density, indexOfRefraction, anisotropy, value);
    }
}
