/**
 * Stable, host-facing contracts for the standalone Java Vulkan RT renderer.
 *
 * <p>This package contains facts and ownership protocols only. It deliberately
 * excludes scene caches, scheduling policy, acceleration structures, descriptor
 * state, shader tables, and host-runtime concepts. A host translates its world
 * into immutable transactions; the renderer exclusively decides how those facts
 * become persistent GPU scene state.</p>
 *
 * <p>Spatial facts use a right-handed, Y-up coordinate system with canonical forward along -Z.
 * Transforms are row-major 3x4 object-to-world matrices. Distances are expressed in host-selected
 * world units, which must remain consistent across geometry, transforms, camera, lights, and
 * environment data. Packed RGBA8 integers store red in bits 0-7, green in 8-15, blue in 16-23,
 * and alpha in 24-31.</p>
 *
 * <p>The safe default path contains no Vulkan handles or synchronization values. Applications that
 * deliberately need zero-copy Vulkan access opt into the separately named
 * {@link top.ceroxe.rt.renderer.api.interop.vulkan} package. Production renderer defaults
 * capability-gate suitable optional technologies as preferred requests. CPU-readable defaults
 * omit presentation-time generation, while GPU-presentation defaults additionally prefer ordinary
 * FG 2x and never auto-select MFG. Explicit option builders provide the equivalent expert controls
 * without bypassing negotiation or lifecycle safety.</p>
 */
package top.ceroxe.rt.renderer.api;
