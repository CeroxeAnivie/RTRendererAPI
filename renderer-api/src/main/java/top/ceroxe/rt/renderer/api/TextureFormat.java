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
    D24_UNORM_S8_UINT(TextureAspect.DEPTH, TextureAspect.STENCIL);

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
