package top.ceroxe.rt.renderer.api;

/**
 * Immutable deterministic per-pixel sampling policy for one submitted frame.
 *
 * <p>The renderer uses a fixed subpixel sequence, so identical scene and frame inputs produce
 * identical samples across runs. This state does not retain temporal history.</p>
 *
 * @param samplesPerPixel supported primary-ray sample count: {@code 1}, {@code 2}, {@code 4}, or
 *                        {@code 8}
 */
public record AntiAliasingState(int samplesPerPixel) {
    /**
     * Validates the bounded deterministic sample count.
     */
    public AntiAliasingState {
        if (samplesPerPixel != 1 && samplesPerPixel != 2
                && samplesPerPixel != 4 && samplesPerPixel != 8) {
            throw new IllegalArgumentException("samplesPerPixel must be one of [1, 2, 4, 8]");
        }
    }

    /**
     * Returns the single-center-sample policy.
     *
     * @return immutable one-sample policy
     */
    public static AntiAliasingState disabled() {
        return new AntiAliasingState(1);
    }

    /**
     * Returns a deterministic multisample policy.
     *
     * @param samplesPerPixel supported sample count: {@code 2}, {@code 4}, or {@code 8}
     * @return validated immutable policy
     */
    public static AntiAliasingState multisampled(int samplesPerPixel) {
        if (samplesPerPixel == 1) {
            throw new IllegalArgumentException("use disabled() for one sample per pixel");
        }
        return new AntiAliasingState(samplesPerPixel);
    }
}
