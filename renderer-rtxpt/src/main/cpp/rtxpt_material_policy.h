#pragma once

#include <cstddef>
#include <memory>
#include <vector>

namespace donut::engine { class SceneGraph; struct Material; }

struct TransmissionDenoisingGuideStats {
    size_t transmissiveMaterials=0;
    size_t decompositionEnabled=0;
    size_t defaultedToTransmission=0;
    size_t gpuMaterialsUpdated=0;
};

TransmissionDenoisingGuideStats ApplyDefaultTransmissionDenoisingGuides(
    donut::engine::SceneGraph* sceneGraph, bool enabled);
TransmissionDenoisingGuideStats ApplyDefaultTransmissionDenoisingGuides(
    const std::vector<std::shared_ptr<donut::engine::Material>>& materials, bool enabled);
