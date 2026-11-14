#include <jni.h>

#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>

#include <vulkan/vulkan.h>
#include <sl.h>

#include "streamline_jni.hpp"
#include "streamline_runtime.hpp"
#include "streamline_types.hpp"

namespace {
using rtrenderer::nvidia::StreamlineFrame;
using rtrenderer::nvidia::StreamlineVulkanBinding;
using rtrenderer::nvidia::kStreamlineDlss;
using rtrenderer::nvidia::kStreamlineNis;
using rtrenderer::nvidia::readStreamlineFrame;
using rtrenderer::nvidia::readStreamlineFrameGeneration;
using rtrenderer::nvidia::streamlineRuntime;

void throwIllegalState(JNIEnv* environment, const char* message) {
    jclass type = environment->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) environment->ThrowNew(type, message);
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlinePreflight(
        JNIEnv* env, jclass, jint requestedFeatures
) {
    try {
        const std::string result = streamlineRuntime().preflight(requestedFeatures);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& error) {
        const std::string result = std::string("failed\n") + error.what();
        return env->NewStringUTF(result.c_str());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeCloseStreamlinePreflight(JNIEnv*, jclass) {
    streamlineRuntime().close();
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineDiagnostic(JNIEnv* env, jclass) {
    const std::string diagnostic = streamlineRuntime().diagnostic();
    return env->NewStringUTF(diagnostic.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineSetVulkanInfo(
        JNIEnv* env,
        jclass,
        jlong instance,
        jlong physicalDevice,
        jlong device,
        jint computeQueueIndex,
        jint computeQueueFamily,
        jint graphicsQueueIndex,
        jint graphicsQueueFamily,
        jint opticalFlowQueueIndex,
        jint opticalFlowQueueFamily,
        jint requiredFeatures,
        jboolean useNativeOpticalFlowMode
) {
    try {
        return streamlineRuntime().setVulkanInfo(StreamlineVulkanBinding{
                static_cast<std::uint64_t>(instance),
                static_cast<std::uint64_t>(physicalDevice),
                static_cast<std::uint64_t>(device),
                computeQueueIndex,
                computeQueueFamily,
                graphicsQueueIndex,
                graphicsQueueFamily,
                opticalFlowQueueIndex,
                opticalFlowQueueFamily,
                requiredFeatures,
                useNativeOpticalFlowMode == JNI_TRUE
        });
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return static_cast<jint>(sl::Result::eErrorInvalidState);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineExecutionFeatureMask(
        JNIEnv*, jclass
) {
    return streamlineRuntime().executionFeatureMask();
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineCreateSwapchain(
        JNIEnv* env, jclass, jlong device, jlong createInfo, jlong output
) {
    try {
        return streamlineRuntime().createSwapchain(
                reinterpret_cast<VkDevice>(device),
                reinterpret_cast<const VkSwapchainCreateInfoKHR*>(createInfo),
                reinterpret_cast<VkSwapchainKHR*>(output)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return VK_ERROR_INITIALIZATION_FAILED;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineDestroySwapchain(
        JNIEnv* env, jclass, jlong device, jlong swapchain
) {
    try {
        streamlineRuntime().destroySwapchain(
                reinterpret_cast<VkDevice>(device), reinterpret_cast<VkSwapchainKHR>(swapchain)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineGetSwapchainImages(
        JNIEnv* env, jclass, jlong device, jlong swapchain, jlong count, jlong images
) {
    try {
        return streamlineRuntime().getSwapchainImages(
                reinterpret_cast<VkDevice>(device),
                reinterpret_cast<VkSwapchainKHR>(swapchain),
                reinterpret_cast<uint32_t*>(count),
                images == 0 ? nullptr : reinterpret_cast<VkImage*>(images)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return VK_ERROR_INITIALIZATION_FAILED;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineAcquireNextImage(
        JNIEnv* env, jclass, jlong device, jlong swapchain, jlong timeout, jlong semaphore,
        jlong fence, jlong imageIndex
) {
    try {
        return streamlineRuntime().acquireNextImage(
                reinterpret_cast<VkDevice>(device),
                reinterpret_cast<VkSwapchainKHR>(swapchain),
                static_cast<uint64_t>(timeout),
                reinterpret_cast<VkSemaphore>(semaphore),
                reinterpret_cast<VkFence>(fence),
                reinterpret_cast<uint32_t*>(imageIndex)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return VK_ERROR_INITIALIZATION_FAILED;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineQueuePresent(
        JNIEnv* env, jclass, jlong queue, jlong presentInfo, jint generatedFrames, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frame sequence must not be negative");
        if (generatedFrames < -3 || generatedFrames > 3) {
            throw std::invalid_argument("generated frame count must be in [-3, 3]");
        }
        return streamlineRuntime().queuePresent(
                reinterpret_cast<VkQueue>(queue),
                reinterpret_cast<const VkPresentInfoKHR*>(presentInfo),
                static_cast<std::int32_t>(generatedFrames),
                static_cast<std::uint64_t>(frameSequence)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return VK_ERROR_INITIALIZATION_FAILED;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRetireStreamlineFrame(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frame sequence must not be negative");
        streamlineRuntime().retireFrame(static_cast<std::uint64_t>(frameSequence));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineFrameGenerationStats(
        JNIEnv* env, jclass
) {
    try {
        const auto values = streamlineRuntime().frameGenerationStats();
        std::array<jlong, 18> stats{};
        for (std::size_t index = 0; index < stats.size(); index++) {
            stats[index] = static_cast<jlong>(values[index]);
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(stats.size()));
        if (result == nullptr) return nullptr;
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(stats.size()), stats.data());
        return result;
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRecordStreamlineFrame(
        JNIEnv* env,
        jclass,
        jlong commandBuffer,
        jint streamlineFeature,
        jint reconstructionMode,
        jint quality,
        jlong sequence,
        jobject resources,
        jobject constants
) {
    try {
        const StreamlineFrame frame = readStreamlineFrame(env, resources, constants, sequence);
        if (streamlineFeature == kStreamlineDlss) {
            streamlineRuntime().recordDlss(
                    static_cast<std::uint64_t>(commandBuffer), reconstructionMode, quality, frame
            );
        } else if (streamlineFeature == kStreamlineNis) {
            streamlineRuntime().recordNis(static_cast<std::uint64_t>(commandBuffer), quality, frame);
        } else {
            throw std::invalid_argument("unsupported Streamline frame feature");
        }
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRecordStreamlineFrameGeneration(
        JNIEnv* env,
        jclass,
        jlong commandBuffer,
        jlong sequence,
        jobject hudlessColor,
        jobject depth,
        jobject motionVectors,
        jobject constants
) {
    try {
        const StreamlineFrame frame = readStreamlineFrameGeneration(
                env, hudlessColor, depth, motionVectors, constants, sequence
        );
        streamlineRuntime().recordFrameGeneration(static_cast<std::uint64_t>(commandBuffer), frame);
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeAwaitStreamlineFrameInputReuse(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frameSequence must not be negative");
        const auto completion = streamlineRuntime().awaitFrameInputReuse(
                static_cast<std::uint64_t>(frameSequence)
        );
        jlongArray result = env->NewLongArray(2);
        if (result == nullptr) return nullptr;
        const jlong values[2] = {
                static_cast<jlong>(completion[0]), static_cast<jlong>(completion[1])
        };
        env->SetLongArrayRegion(result, 0, 2, values);
        return result;
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeDisableStreamlineFrameGeneration(
        JNIEnv* env, jclass
) {
    try {
        streamlineRuntime().disableFrameGeneration();
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeBeginStreamlineFramePreparation(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frameSequence must not be negative");
        streamlineRuntime().beginFramePreparation(static_cast<std::uint64_t>(frameSequence));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeCancelStreamlineFramePreparation(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frameSequence must not be negative");
        streamlineRuntime().cancelFramePreparation(static_cast<std::uint64_t>(frameSequence));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeBeginStreamlineFrameSubmission(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frameSequence must not be negative");
        streamlineRuntime().beginFrameSubmission(static_cast<std::uint64_t>(frameSequence));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeEndStreamlineFrameSubmission(
        JNIEnv* env, jclass, jlong frameSequence
) {
    try {
        if (frameSequence < 0) throw std::invalid_argument("frameSequence must not be negative");
        streamlineRuntime().endFrameSubmission(static_cast<std::uint64_t>(frameSequence));
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeStreamlineDlssOptimalSettings(
        JNIEnv* env, jclass, jint quality, jint outputWidth, jint outputHeight
) {
    try {
        const auto values = streamlineRuntime().dlssOptimalSettings(quality, outputWidth, outputHeight);
        const std::array<jint, 2> settings = {
                static_cast<jint>(values[0]), static_cast<jint>(values[1])
        };
        jintArray result = env->NewIntArray(2);
        if (result == nullptr) return nullptr;
        env->SetIntArrayRegion(result, 0, 2, settings.data());
        return result;
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}
