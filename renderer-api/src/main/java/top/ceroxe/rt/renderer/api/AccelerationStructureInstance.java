package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** One explicit top-level instance referencing a completed bottom-level structure generation. */
public record AccelerationStructureInstance(
        AccelerationStructureResource bottomLevel,
        AffineTransform3x4 transform,
        int customIndex,
        int visibilityMask,
        int shaderBindingTableRecordOffset,
        boolean forceOpaque,
        boolean forceNoOpaque
) {
    /** Validates Vulkan-compatible packed instance fields without relying on object identity. */
    public AccelerationStructureInstance {
        bottomLevel = Objects.requireNonNull(bottomLevel, "bottomLevel");
        transform = Objects.requireNonNull(transform, "transform");
        if (bottomLevel.kind() != AccelerationStructureKind.BOTTOM_LEVEL) {
            throw new IllegalArgumentException("top-level instances must reference BOTTOM_LEVEL structures");
        }
        if (customIndex < 0 || customIndex > 0x00ff_ffff
                || visibilityMask < 0 || visibilityMask > 0xff
                || shaderBindingTableRecordOffset < 0 || shaderBindingTableRecordOffset > 0x00ff_ffff) {
            throw new IllegalArgumentException("AS instance packed index, mask, and SBT offset exceed their native bit domains");
        }
        if (forceOpaque && forceNoOpaque) throw new IllegalArgumentException("AS instance cannot force both opaque and non-opaque traversal");
    }
}
