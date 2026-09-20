package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Portable texture format subset with explicit view aspects.
 *
 * <p>Capabilities decide whether a backend can execute a particular format/usage combination.
 * The format value itself never implies that a backend has allocated or initialized the texture.</p>
 */
public enum TextureFormat {
    R8_UNORM(TextureAspect.COLOR),
    RG8_UNORM(TextureAspect.COLOR),
    RGBA8_UNORM(TextureAspect.COLOR),
    RGBA8_SRGB(TextureAspect.COLOR),
    R16_FLOAT(TextureAspect.COLOR),
    RG16_FLOAT(TextureAspect.COLOR),
    RGBA16_FLOAT(TextureAspect.COLOR),
    R32_FLOAT(TextureAspect.COLOR),
    RG32_FLOAT(TextureAspect.COLOR),
    RGBA32_FLOAT(TextureAspect.COLOR),
    D32_FLOAT(TextureAspect.DEPTH),
    D24_UNORM_S8_UINT(TextureAspect.DEPTH, TextureAspect.STENCIL),
    R8_SINT(TextureAspect.COLOR), R8_UINT(TextureAspect.COLOR),
    RG8_SINT(TextureAspect.COLOR), RG8_UINT(TextureAspect.COLOR),
    RGBA8_SINT(TextureAspect.COLOR), RGBA8_UINT(TextureAspect.COLOR),
    R16_SINT(TextureAspect.COLOR), R16_UINT(TextureAspect.COLOR),
    RG16_SINT(TextureAspect.COLOR), RG16_UINT(TextureAspect.COLOR),
    RGBA16_SINT(TextureAspect.COLOR), RGBA16_UINT(TextureAspect.COLOR),
    R32_SINT(TextureAspect.COLOR), R32_UINT(TextureAspect.COLOR),
    RG32_SINT(TextureAspect.COLOR), RG32_UINT(TextureAspect.COLOR),
    RGBA32_SINT(TextureAspect.COLOR), RGBA32_UINT(TextureAspect.COLOR);

    /** Shader-visible numeric class; normalized storage is sampled as floating-point values. */
    public enum NumericClass { FLOATING_POINT, SIGNED_INTEGER, UNSIGNED_INTEGER, DEPTH_STENCIL }

    /** @return the exact numeric class required by image/sampler shader operations */
    public NumericClass numericClass() {
        return switch (this) {
            case R8_SINT, RG8_SINT, RGBA8_SINT, R16_SINT, RG16_SINT, RGBA16_SINT,
                    R32_SINT, RG32_SINT, RGBA32_SINT -> NumericClass.SIGNED_INTEGER;
            case R8_UINT, RG8_UINT, RGBA8_UINT, R16_UINT, RG16_UINT, RGBA16_UINT,
                    R32_UINT, RG32_UINT, RGBA32_UINT -> NumericClass.UNSIGNED_INTEGER;
            case D32_FLOAT, D24_UNORM_S8_UINT -> NumericClass.DEPTH_STENCIL;
            default -> NumericClass.FLOATING_POINT;
        };
    }

    /** @return tightly packed bytes for one texel, including both aspects of packed depth/stencil */
    public int bytesPerTexel() {
        return switch (this) {
            case R8_UNORM, R8_SINT, R8_UINT -> 1;
            case RG8_UNORM, R16_FLOAT, RG8_SINT, RG8_UINT, R16_SINT, R16_UINT -> 2;
            case RGBA8_UNORM, RGBA8_SRGB, RG16_FLOAT, R32_FLOAT, D32_FLOAT, D24_UNORM_S8_UINT,
                    RGBA8_SINT, RGBA8_UINT, RG16_SINT, RG16_UINT, R32_SINT, R32_UINT -> 4;
            case RGBA16_FLOAT, RG32_FLOAT, RGBA16_SINT, RGBA16_UINT, RG32_SINT, RG32_UINT -> 8;
            case RGBA32_FLOAT, RGBA32_SINT, RGBA32_UINT -> 16;
        };
    }

    private final Set<TextureAspect> aspects;

    TextureFormat(TextureAspect first, TextureAspect... additional) {
        EnumSet<TextureAspect> values = EnumSet.of(first, additional);
        aspects = Collections.unmodifiableSet(values);
    }

    /**
     * Returns immutable view aspects supported by this format.
     *
     * @return non-empty immutable aspect set
     */
    public Set<TextureAspect> aspects() {
        return aspects;
    }

    /**
     * Returns whether this format exposes an aspect to views.
     *
     * @param aspect non-null requested aspect
     * @return {@code true} when views may address the aspect
     */
    public boolean supports(TextureAspect aspect) {
        return aspects.contains(java.util.Objects.requireNonNull(aspect, "aspect"));
    }
}
