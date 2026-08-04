/**
 * Managed and expert Vulkan frame-consumption contracts.
 *
 * <p>{@code VulkanFramePresenter} is the ordinary GPU-presentation path: it owns swapchain
 * cadence and hides external-memory handles, queue synchronization, and lease retirement.
 * {@code VulkanFrameInterop} and {@code GpuFrameLease} are expert contracts that expose those
 * Vulkan and Win32 ownership details to the application. None of these types is required for
 * ordinary renderer creation, scene submission, or managed CPU-frame consumption.</p>
 */
package top.ceroxe.rt.renderer.api.interop.vulkan;
