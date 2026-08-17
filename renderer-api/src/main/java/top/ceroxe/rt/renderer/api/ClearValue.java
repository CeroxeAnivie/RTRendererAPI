package top.ceroxe.rt.renderer.api;

/**
 * Immutable, aspect-typed render-attachment clear value.
 *
 * <p>The distinct variants prevent a color clear from being reinterpreted as depth or stencil
 * data by a backend. Every floating-point component must be finite; infinities and NaN fail at
 * the public boundary rather than reaching a driver.</p>
 */
public sealed interface ClearValue permits ClearValue.Color, ClearValue.Depth, ClearValue.Stencil {
    /** @return the one texture aspect cleared by this value */
    TextureAspect aspect();

    /** Linear RGBA color clear. No transfer-function conversion is implied. */
    record Color(float red, float green, float blue, float alpha) implements ClearValue {
        /** Rejects values that cannot be represented deterministically by portable backends. */
        public Color {
            requireFinite(red, "red");
            requireFinite(green, "green");
            requireFinite(blue, "blue");
            requireFinite(alpha, "alpha");
        }

        @Override
        public TextureAspect aspect() {
            return TextureAspect.COLOR;
        }
    }

    /** Normalized depth clear in the portable zero-to-one depth interval. */
    record Depth(float value) implements ClearValue {
        /** Rejects non-finite values and values outside the portable depth interval. */
        public Depth {
            requireFinite(value, "depth");
            if (value < 0.0f || value > 1.0f) {
                throw new IllegalArgumentException("depth clear must be in [0, 1]");
            }
        }

        @Override
        public TextureAspect aspect() {
            return TextureAspect.DEPTH;
        }
    }

    /** Unsigned stencil clear represented by its exact 32-bit bit pattern. */
    record Stencil(int value) implements ClearValue {
        @Override
        public TextureAspect aspect() {
            return TextureAspect.STENCIL;
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " clear component must be finite");
        }
    }
}
