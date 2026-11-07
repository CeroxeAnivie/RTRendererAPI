/**
 * Stable, host-facing contracts shared by the Java renderer backends.
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
 * <p>The default renderer owns native presentation. Optional reconstruction, denoising,
 * generated frames and low-latency controls use the selected native backend. Explicit
 * configuration and backend extensions describe supported controls without requiring
 * host graphics handles for ordinary scene submission.</p>
 *
 * <p>Ordinary presets and explicit expert builders use the same immutable values.
 * Retained scenes need no native handles; external lighting signals and their ownership
 * protocols remain explicitly selected backend extensions.</p>
 */
package top.ceroxe.rt.renderer.api;
