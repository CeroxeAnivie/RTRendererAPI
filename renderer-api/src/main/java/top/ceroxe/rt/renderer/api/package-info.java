/**
 * Immutable host-facing contracts for the NVIDIA RTXPT D3D12 backend.
 * Spatial facts are right-handed and Y-up, with camera forward along -Z.
 * Transforms are row-major 3x4 object-to-world matrices; all spatial inputs
 * use the same host-selected world units. Packed RGBA8 stores red in bits 0-7
 * and alpha in bits 24-31. Retained transactions preserve input ownership.
 * Shared lighting output uses explicit GPU leases, ready/completion fences
 * and one creating renderer thread. Native beauty owns its presentation.
 * Generic GPU commands, CPU-frame readback and custom shader execution are
 * outside this API. Optional native features report their actual native state.
 */
package top.ceroxe.rt.renderer.api;
