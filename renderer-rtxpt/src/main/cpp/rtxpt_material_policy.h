#pragma once

#include <cstddef>

namespace donut::engine { class SceneGraph; }

struct TransmissionDenoisingGuideStats {
    size_t transmissiveMaterials=0;
    size_t decompositionEnabled=0;
    size_t defaultedToTransmission=0;
    size_t gpuMaterialsUpdated=0;
};

TransmissionDenoisingGuideStats ApplyDefaultTransmissionDenoisingGuides(
    donut::engine::SceneGraph* sceneGraph, bool enabled);
