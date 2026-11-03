#include "rtxpt_material_policy.h"

#include <Materials/MaterialsBaker.h>
#include <donut/engine/SceneGraph.h>

template<class Materials>
static TransmissionDenoisingGuideStats ApplyGuides(const Materials& materials,bool enabled) {
    TransmissionDenoisingGuideStats stats;
    if(!enabled)return stats;

    for(const auto& material:materials) {
        auto* pt=PTMaterial::SafeCast(material);
        if(!pt || !pt->EnableTransmission || pt->TransmissionFactor<=0.f)continue;
        ++stats.transmissiveMaterials;
        bool updated=false;
        if(pt->PSDExclude) {
            pt->PSDExclude=false;
            ++stats.decompositionEnabled;
            updated=true;
        }
        if(pt->PSDDominantDeltaLobe==-1) {
            pt->PSDDominantDeltaLobe=0;
            ++stats.defaultedToTransmission;
            updated=true;
        }
        if(updated) {
            pt->GPUDataDirty=true;
            ++stats.gpuMaterialsUpdated;
        }
    }
    return stats;
}

TransmissionDenoisingGuideStats ApplyDefaultTransmissionDenoisingGuides(
    donut::engine::SceneGraph* sceneGraph, bool enabled) {
    return sceneGraph?ApplyGuides(sceneGraph->GetMaterials(),enabled):TransmissionDenoisingGuideStats{};
}

TransmissionDenoisingGuideStats ApplyDefaultTransmissionDenoisingGuides(
    const std::vector<std::shared_ptr<donut::engine::Material>>& materials, bool enabled) {
    return ApplyGuides(materials,enabled);
}
