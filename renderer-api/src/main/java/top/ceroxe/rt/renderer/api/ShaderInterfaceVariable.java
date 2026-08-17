package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Exact location and type of one user-defined shader stage input or output. */
public record ShaderInterfaceVariable(int location, ShaderInterfaceType type, Interpolation interpolation) {
    /** Portable interpolation behavior for raster stage linkage. */
    public enum Interpolation { SMOOTH, FLAT, NO_PERSPECTIVE }

    /** Validates a non-built-in location declaration. */
    public ShaderInterfaceVariable {
        if (location < 0) throw new IllegalArgumentException("shader interface location must not be negative");
        type = Objects.requireNonNull(type, "type");
        interpolation = Objects.requireNonNull(interpolation, "interpolation");
        if (type.numericType() != ShaderInterfaceType.NumericType.FLOATING_POINT
                && interpolation != Interpolation.FLAT) {
            throw new IllegalArgumentException("integer shader interfaces require flat interpolation");
        }
    }
}
