package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.RendererDeviceException;

/**
 * Maps backend-native result codes to the stable public renderer failure model.
 */
public final class VulkanFailures {
    private VulkanFailures() {
    }

    /**
     * Throws a stable renderer exception when a Vulkan operation did not succeed.
     *
     * @param result    Vulkan result code returned by the driver
     * @param operation operation label included in diagnostics
     * @throws RendererDeviceException when {@code result} is not {@link VK10#VK_SUCCESS}
     */
    public static void check(int result, String operation) {
        if (result == VK10.VK_SUCCESS) return;
        throw exception(result, operation);
    }

    /**
     * Maps a failed Vulkan result into the public device-failure taxonomy.
     *
     * @param result    failed Vulkan result code
     * @param operation operation label included in the exception
     * @return a new exception carrying the native result and recovery guidance
     */
    public static RendererDeviceException exception(int result, String operation) {
        return switch (result) {
            case VK10.VK_ERROR_DEVICE_LOST -> failure(
                    result,
                    operation,
                    RendererDeviceException.Reason.DEVICE_LOST,
                    RendererDeviceException.RecoveryAction.RECREATE_RENDERER
            );
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> failure(
                    result,
                    operation,
                    RendererDeviceException.Reason.DEVICE_OUT_OF_MEMORY,
                    RendererDeviceException.RecoveryAction.REDUCE_MEMORY_AND_RECREATE
            );
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> failure(
                    result,
                    operation,
                    RendererDeviceException.Reason.HOST_OUT_OF_MEMORY,
                    RendererDeviceException.RecoveryAction.ABORT
            );
            default -> failure(
                    result,
                    operation,
                    RendererDeviceException.Reason.DRIVER_FAILURE,
                    RendererDeviceException.RecoveryAction.ABORT
            );
        };
    }

    private static RendererDeviceException failure(
            int result,
            String operation,
            RendererDeviceException.Reason reason,
            RendererDeviceException.RecoveryAction action
    ) {
        return new RendererDeviceException(
                operation + " failed with " + resultName(result),
                reason,
                action,
                operation,
                result
        );
    }

    private static String resultName(int result) {
        return switch (result) {
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            default -> Integer.toString(result);
        };
    }
}
