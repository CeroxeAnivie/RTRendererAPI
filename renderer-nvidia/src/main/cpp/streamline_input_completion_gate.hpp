#pragma once

#include <array>
#include <cstdint>
#include <stdexcept>
#include <string>

#include <vulkan/vulkan.h>
#include <sl_dlss_g.h>

namespace rtrenderer::nvidia {

/**
 * Owns the Vulkan synchronization debt created when DLSS-G consumes tagged inputs on an SDK queue.
 * The SDK owns the timeline semaphore; this gate only borrows its latest handle/value until the
 * renderer has proved that those inputs may be written again.
 */
class StreamlineInputCompletionGate final {
public:
    void bind(
            VkDevice device,
            PFN_vkWaitSemaphores waitSemaphores,
            bool completionFenceRequired
    ) noexcept {
        device_ = device;
        waitSemaphores_ = waitSemaphores;
        completionFenceRequired_ = completionFenceRequired;
    }

    void capture(const sl::DLSSGState& state) {
        const bool hasSemaphore = state.inputsProcessingCompletionFence != nullptr;
        const bool hasValue = state.lastPresentInputsProcessingCompletionFenceValue != 0;
        if (hasValue && !hasSemaphore) {
            throw std::runtime_error(
                    "DLSS-G published an input-completion value without its timeline semaphore"
            );
        }
        if (!hasValue) {
            // The first proxy present has no previously consumed tagged frame, so there is no
            // input lifetime debt to wait on yet. Every later present in no-client-queues mode
            // must publish the fence for the frame just consumed.
            if (completionFenceRequired_ && observedPresent_) {
                throw std::runtime_error(
                        "DLSS-G eBlockNoClientQueues requires an input-processing completion fence"
                );
            }
            // eBlockPresentingClientQueue permits the SDK to omit a completion fence. Clearing
            // stale debt is essential: carrying an older value into a no-debt frame would wait on
            // the wrong generation.
            semaphore_ = VK_NULL_HANDLE;
            value_ = 0;
            observedPresent_ = true;
            return;
        }
        semaphore_ = reinterpret_cast<VkSemaphore>(state.inputsProcessingCompletionFence);
        value_ = state.lastPresentInputsProcessingCompletionFenceValue;
        observedPresent_ = true;
    }

    std::array<std::uint64_t, 2> await() {
        if (semaphore_ == VK_NULL_HANDLE) return {0, 0};
        if (device_ == VK_NULL_HANDLE || waitSemaphores_ == nullptr) {
            throw std::runtime_error("DLSS-G input completion cannot be awaited without Vulkan 1.2");
        }
        const VkSemaphoreWaitInfo waitInfo = {
                VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO,
                nullptr,
                0,
                1,
                &semaphore_,
                &value_
        };
        // A normal generated-frame batch completes within a few display intervals. A bounded wait
        // converts a stalled vendor queue into one deterministic frame failure instead of hanging
        // the renderer or allowing unsafe input reuse.
        constexpr std::uint64_t kTimeoutNanoseconds = 2'000'000'000ULL;
        const VkResult result = waitSemaphores_(device_, &waitInfo, kTimeoutNanoseconds);
        if (result == VK_SUCCESS) {
            // Keep the latest completed value publishable until the next proxy present replaces
            // it. The presenter needs the host wait to satisfy VkQueue external synchronization,
            // while the renderer still needs the same semaphore/value as a GPU memory dependency.
            // Consuming it in either caller would make the other path race the SDK.
            return {reinterpret_cast<std::uint64_t>(semaphore_), value_};
        }
        if (result == VK_TIMEOUT) {
            throw std::runtime_error("timed out awaiting DLSS-G input-processing completion");
        }
        throw std::runtime_error(
                "failed to await DLSS-G input-processing completion: VkResult="
                        + std::to_string(static_cast<std::int32_t>(result))
        );
    }

    void clear() noexcept {
        device_ = VK_NULL_HANDLE;
        waitSemaphores_ = nullptr;
        completionFenceRequired_ = false;
        semaphore_ = VK_NULL_HANDLE;
        value_ = 0;
        observedPresent_ = false;
    }

private:
    VkDevice device_ = VK_NULL_HANDLE;
    PFN_vkWaitSemaphores waitSemaphores_ = nullptr;
    bool completionFenceRequired_ = false;
    VkSemaphore semaphore_ = VK_NULL_HANDLE;
    std::uint64_t value_ = 0;
    bool observedPresent_ = false;
};

}
