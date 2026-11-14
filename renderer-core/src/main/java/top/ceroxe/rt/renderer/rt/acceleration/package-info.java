/**
 * Renderer-owned acceleration-structure convergence.
 *
 * <p>This package owns the complete geometry-to-traversal chain:
 * section and dynamic scene facts enter the BLAS caches, are ordered through
 * bounded build queues, become completed {@code RtAccelerationStructure}
 * resources, and finally converge into world and dynamic TLAS revisions.
 * Every revision exposed from this package names the resource generation that
 * produced it; callers must never infer readiness from queue emptiness alone.</p>
 *
 * <p>Dependencies are intentionally one way. {@code scene} supplies immutable
 * meshes and view facts; {@code material} supplies immutable material
 * snapshots; {@code device} supplies Vulkan allocation and command services.
 * This package never imports host bridge state, owns no descriptor
 * generation, and never presents a frame. The runtime coordinator is the sole
 * consumer allowed to bind a completed TLAS into a frame generation.</p>
 */
package top.ceroxe.rt.renderer.rt.acceleration;
