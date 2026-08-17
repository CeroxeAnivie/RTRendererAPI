package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable stencil test and update state for one polygon orientation.
 *
 * @param stencilFail operation when the stencil comparison fails
 * @param depthFail operation when stencil passes and depth fails
 * @param pass operation when both tests pass
 * @param compare comparison against the masked reference
 * @param compareMask unsigned eight-bit comparison mask
 * @param writeMask unsigned eight-bit write mask
 * @param reference unsigned eight-bit reference value
 */
public record StencilFaceState(
        StencilOperation stencilFail,
        StencilOperation depthFail,
        StencilOperation pass,
        CompareOperation compare,
        int compareMask,
        int writeMask,
        int reference
) {
    /** Validates a complete eight-bit stencil-face declaration. */
    public StencilFaceState {
        stencilFail = Objects.requireNonNull(stencilFail, "stencilFail");
        depthFail = Objects.requireNonNull(depthFail, "depthFail");
        pass = Objects.requireNonNull(pass, "pass");
        compare = Objects.requireNonNull(compare, "compare");
        requireUnsignedByte(compareMask, "compareMask");
        requireUnsignedByte(writeMask, "writeMask");
        requireUnsignedByte(reference, "reference");
    }

    /** Returns state that leaves stencil storage unchanged and always passes. */
    public static StencilFaceState keep() {
        return new StencilFaceState(
                StencilOperation.KEEP,
                StencilOperation.KEEP,
                StencilOperation.KEEP,
                CompareOperation.ALWAYS,
                0xFF,
                0xFF,
                0);
    }

    private static void requireUnsignedByte(int value, String name) {
        if ((value & ~0xFF) != 0) throw new IllegalArgumentException(name + " must be in [0, 255]");
    }
}
