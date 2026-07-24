package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.List;

/**
 * Internal ownership marker for a TLAS instance table that is immutable for its complete lifetime.
 *
 * <p>Only renderer-owned publication values may implement this contract. It lets the asynchronous
 * Vulkan build retain the exact generation instead of defensively copying the full physical slot
 * table at every handoff. External collections still cross a {@link List#copyOf(java.util.Collection)}
 * boundary before command recording.</p>
 */
interface RtImmutableTlasInstances extends List<RtAccelerationStructure.TlasInstance> {
}
