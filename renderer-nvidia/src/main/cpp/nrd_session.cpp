#include "nrd_session.hpp"
#include "nrd_reblur_policy.hpp"

#include <algorithm>
#include <limits>
#include <stdexcept>

#include <NRI.h>
#include <Extensions/NRIHelper.h>
#include <Extensions/NRIRayTracing.h>
#include <Extensions/NRIWrapperVK.h>
#include <NRD.h>
#include <NRDIntegration.h>
#include <NRDIntegration.hpp>

namespace rtrenderer::nvidia {
namespace {

constexpr nrd::Identifier kReblurIdentifier = 1;
constexpr std::int32_t kFormatR16G16B16A16Sfloat = 97;
constexpr std::int32_t kFormatR32Sfloat = 100;

bool validExtent(std::int32_t width, std::int32_t height) {
    return width > 0 && height > 0
            && width <= std::numeric_limits<std::uint16_t>::max()
            && height <= std::numeric_limits<std::uint16_t>::max();
}

nrd::Resource resource(std::uint64_t image, std::int32_t format) {
    nrd::Resource result = {};
    result.vk.image = image;
    result.vk.format = format;
    // GPUScene wrote every input through storage images immediately before this dispatch.
    result.state = {
            nri::AccessBits::SHADER_RESOURCE_STORAGE,
            nri::Layout::GENERAL,
            nri::StageBits::RAYGEN_SHADER
    };
    return result;
}

}

class NrdSession::Impl final {
public:
    Impl(
            std::uint64_t instance,
            std::uint64_t physicalDevice,
            std::uint64_t device,
            std::uint32_t queueFamilyIndex
    ) {
        queueFamily_.queueNum = 1;
        queueFamily_.queueType = nri::QueueType::GRAPHICS;
        queueFamily_.familyIndex = queueFamilyIndex;

        deviceDescription_.vkInstance = reinterpret_cast<void*>(instance);
        deviceDescription_.vkPhysicalDevice = reinterpret_cast<void*>(physicalDevice);
        deviceDescription_.vkDevice = reinterpret_cast<void*>(device);
        deviceDescription_.queueFamilies = &queueFamily_;
        deviceDescription_.queueFamilyNum = 1;
        // Java requires Vulkan 1.3 synchronization2 before this borrowed-device handoff.
        deviceDescription_.minorVersion = 3;

        denoiser_.identifier = kReblurIdentifier;
        denoiser_.denoiser = nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR;
        instanceDescription_.denoisers = &denoiser_;
        instanceDescription_.denoisersNum = 1;

        const char name[] = "RTRenderer";
        std::copy(name, name + sizeof(name) - 1, integrationDescription_.name);
        integrationDescription_.queuedFrameNum = 3;
        // Extent recreation must wait before cached descriptors can outlive frame-slot images.
        integrationDescription_.autoWaitForIdle = true;
        integrationDescription_.resourceWidth = 1;
        integrationDescription_.resourceHeight = 1;
        if (integration_.RecreateVK(integrationDescription_, instanceDescription_, deviceDescription_)
                != nrd::Result::SUCCESS) {
            throw std::runtime_error("NRD device preflight RecreateVK failed");
        }
    }

    void record(
            std::uint64_t commandBuffer,
            std::uint64_t normalRoughness,
            std::uint64_t viewZ,
            std::uint64_t motionVectors,
            std::uint64_t diffuseInput,
            std::uint64_t specularInput,
            std::uint64_t diffuseOutput,
            std::uint64_t specularOutput,
            const NrdFrameConstants& constants,
            std::int32_t width,
            std::int32_t height
    ) {
        if (!validExtent(width, height) || commandBuffer == 0 || normalRoughness == 0 || viewZ == 0
                || motionVectors == 0 || diffuseInput == 0 || specularInput == 0
                || diffuseOutput == 0 || specularOutput == 0) {
            throw std::invalid_argument("invalid NRD frame resources");
        }
        const bool recreated = recreateForExtent(width, height);

        nrd::CommonSettings settings = {};
        std::copy(constants.viewToClip.begin(), constants.viewToClip.end(), settings.viewToClipMatrix);
        std::copy(constants.viewToClipPrev.begin(), constants.viewToClipPrev.end(), settings.viewToClipMatrixPrev);
        std::copy(constants.worldToView.begin(), constants.worldToView.end(), settings.worldToViewMatrix);
        std::copy(constants.worldToViewPrev.begin(), constants.worldToViewPrev.end(), settings.worldToViewMatrixPrev);
        settings.resourceSize[0] = static_cast<std::uint16_t>(width);
        settings.resourceSize[1] = static_cast<std::uint16_t>(height);
        settings.resourceSizePrev[0] = settings.resourceSize[0];
        settings.resourceSizePrev[1] = settings.resourceSize[1];
        settings.rectSize[0] = settings.resourceSize[0];
        settings.rectSize[1] = settings.resourceSize[1];
        settings.rectSizePrev[0] = settings.resourceSize[0];
        settings.rectSizePrev[1] = settings.resourceSize[1];
        if (constants.reset || recreated) frameIndex_ = 0;
        settings.frameIndex = frameIndex_++;
        settings.motionVectorScale[0] = constants.motionScaleX;
        settings.motionVectorScale[1] = constants.motionScaleY;
        // GPUScene writes MV.z = previousViewZ - currentViewZ in view-space units.
        settings.motionVectorScale[2] = 1.0f;
        settings.isMotionVectorInWorldSpace = false;
        settings.cameraJitter[0] = constants.jitterX;
        settings.cameraJitter[1] = constants.jitterY;
        settings.cameraJitterPrev[0] = constants.jitterPrevX;
        settings.cameraJitterPrev[1] = constants.jitterPrevY;
        settings.accumulationMode = (constants.reset || recreated)
                ? nrd::AccumulationMode::CLEAR_AND_RESTART
                : nrd::AccumulationMode::CONTINUE;

        integration_.NewFrame();
        if (integration_.SetCommonSettings(settings) != nrd::Result::SUCCESS) {
            throw std::runtime_error("NRD SetCommonSettings failed");
        }
        if (integration_.SetDenoiserSettings(kReblurIdentifier, &reblurSettings_)
                != nrd::Result::SUCCESS) {
            throw std::runtime_error("NRD SetDenoiserSettings failed");
        }

        nrd::ResourceSnapshot resources;
        // NRD owns the transitions it records for these borrowed application images. Restoring
        // every image to its declared GENERAL/storage state keeps the renderer's descriptor
        // contract valid across frames and avoids duplicating NRD's evolving internal state
        // machine in Java.
        resources.restoreInitialState = true;
        resources.SetResource(
                nrd::ResourceType::IN_NORMAL_ROUGHNESS,
                resource(normalRoughness, kFormatR16G16B16A16Sfloat)
        );
        resources.SetResource(nrd::ResourceType::IN_VIEWZ, resource(viewZ, kFormatR32Sfloat));
        resources.SetResource(
                nrd::ResourceType::IN_MV,
                resource(motionVectors, kFormatR16G16B16A16Sfloat)
        );
        resources.SetResource(
                nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST,
                resource(diffuseInput, kFormatR16G16B16A16Sfloat)
        );
        resources.SetResource(
                nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST,
                resource(specularInput, kFormatR16G16B16A16Sfloat)
        );
        resources.SetResource(
                nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST,
                resource(diffuseOutput, kFormatR16G16B16A16Sfloat)
        );
        resources.SetResource(
                nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST,
                resource(specularOutput, kFormatR16G16B16A16Sfloat)
        );

        nri::CommandBufferVKDesc commandBufferDescription = {};
        commandBufferDescription.vkCommandBuffer = reinterpret_cast<void*>(commandBuffer);
        commandBufferDescription.queueType = nri::QueueType::GRAPHICS;
        const nrd::Identifier identifier = kReblurIdentifier;
        integration_.DenoiseVK(&identifier, 1, commandBufferDescription, resources);
    }

private:
    bool recreateForExtent(std::int32_t width, std::int32_t height) {
        if (width == width_ && height == height_) return false;
        integrationDescription_.resourceWidth = static_cast<std::uint16_t>(width);
        integrationDescription_.resourceHeight = static_cast<std::uint16_t>(height);
        if (integration_.RecreateVK(integrationDescription_, instanceDescription_, deviceDescription_)
                != nrd::Result::SUCCESS) {
            throw std::runtime_error("NRD RecreateVK failed");
        }
        width_ = width;
        height_ = height;
        frameIndex_ = 0;
        return true;
    }

    nrd::Integration integration_;
    nrd::IntegrationCreationDesc integrationDescription_ = {};
    nri::QueueFamilyVKDesc queueFamily_ = {};
    nri::DeviceCreationVKDesc deviceDescription_ = {};
    nrd::DenoiserDesc denoiser_ = {};
    nrd::InstanceCreationDesc instanceDescription_ = {};
    std::int32_t width_ = 0;
    std::int32_t height_ = 0;
    std::uint32_t frameIndex_ = 0;
    const nrd::ReblurSettings reblurSettings_ = NrdReblurPolicy::production();
};

NrdSession::NrdSession(
        std::uint64_t instance,
        std::uint64_t physicalDevice,
        std::uint64_t device,
        std::uint32_t queueFamilyIndex
) : impl_(std::make_unique<Impl>(instance, physicalDevice, device, queueFamilyIndex)) {
}

NrdSession::~NrdSession() = default;

void NrdSession::record(
        std::uint64_t commandBuffer,
        std::uint64_t normalRoughness,
        std::uint64_t viewZ,
        std::uint64_t motionVectors,
        std::uint64_t diffuseInput,
        std::uint64_t specularInput,
        std::uint64_t diffuseOutput,
        std::uint64_t specularOutput,
        const NrdFrameConstants& constants,
        std::int32_t width,
        std::int32_t height
) {
    impl_->record(
            commandBuffer, normalRoughness, viewZ, motionVectors,
            diffuseInput, specularInput, diffuseOutput, specularOutput,
            constants, width, height
    );
}

}
