#pragma once

#include <nvrhi/nvrhi.h>
#include <cstdint>
#include <vector>

// Valid, zero-filled bindings for shaders that use the host's analytic lighting.
// The shared layout still needs these views, but no baker may write to them.
struct RtrInactiveLighting {
    nvrhi::TextureHandle cube, importance, lookup, feedbackWeight, feedbackCandidate;
    nvrhi::BufferHandle control, lights, lightsEx, uints, floats;
    nvrhi::SamplerHandle sampler;

    void Initialize(nvrhi::IDevice* device, nvrhi::ICommandList* commands,
                    uint32_t controlStride, uint32_t lightStride, uint32_t lightExStride) {
        if (cube) return;
        auto buffer = [&](const char* name, uint32_t stride, nvrhi::Format format) {
            nvrhi::BufferDesc desc;
            desc.byteSize = stride;
            desc.structStride = format == nvrhi::Format::UNKNOWN ? stride : 0;
            desc.format = format;
            desc.canHaveTypedViews = format != nvrhi::Format::UNKNOWN;
            desc.initialState = nvrhi::ResourceStates::ShaderResource;
            desc.keepInitialState = true;
            desc.debugName = name;
            auto result = device->createBuffer(desc);
            std::vector<uint8_t> zeroes(stride, 0);
            commands->writeBuffer(result, zeroes.data(), zeroes.size());
            return result;
        };
        control = buffer("InactiveLightingControl", controlStride, nvrhi::Format::UNKNOWN);
        lights = buffer("InactiveLights", lightStride, nvrhi::Format::UNKNOWN);
        lightsEx = buffer("InactiveLightsEx", lightExStride, nvrhi::Format::UNKNOWN);
        uints = buffer("InactiveLightingUint", 4, nvrhi::Format::R32_UINT);
        floats = buffer("InactiveLightingFloat", 4, nvrhi::Format::R32_FLOAT);
        auto texture = [&](const char* name, nvrhi::Format format, bool isCube) {
            nvrhi::TextureDesc desc;
            desc.width = desc.height = 1;
            desc.arraySize = isCube ? 6 : 1;
            desc.dimension = isCube ? nvrhi::TextureDimension::TextureCube : nvrhi::TextureDimension::Texture2D;
            desc.format = format;
            desc.isUAV = true;
            desc.initialState = nvrhi::ResourceStates::ShaderResource;
            desc.keepInitialState = true;
            desc.debugName = name;
            auto result = device->createTexture(desc);
            if (format == nvrhi::Format::R32_UINT)
                commands->clearTextureUInt(result, nvrhi::AllSubresources, 0);
            else
                commands->clearTextureFloat(result, nvrhi::AllSubresources, nvrhi::Color(0.f));
            return result;
        };
        cube = texture("InactiveEnvironmentCube", nvrhi::Format::RGBA16_FLOAT, true);
        importance = texture("InactiveEnvironmentImportance", nvrhi::Format::R32_FLOAT, false);
        lookup = texture("InactiveEnvironmentLookup", nvrhi::Format::R32_UINT, false);
        feedbackWeight = texture("InactiveLightingFeedbackWeight", nvrhi::Format::R32_FLOAT, false);
        feedbackCandidate = texture("InactiveLightingFeedbackCandidate", nvrhi::Format::R32_UINT, false);
        sampler = device->createSampler(nvrhi::SamplerDesc());
    }
};
