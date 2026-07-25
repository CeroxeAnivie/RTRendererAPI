/**
 * Vulkan backend lifecycle implementation for the standalone ray tracing renderer.
 *
 * <p>This is an implementation package, not a host contract. Embedding applications compose
 * through {@link top.ceroxe.rt.renderer.api.RayTracingRenderer}; the backend provider
 * translates those immutable API facts into renderer-owned runtime state.</p>
 */
package top.ceroxe.rt.renderer.backend.vulkan;
