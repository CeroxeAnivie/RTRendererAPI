#pragma once
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace donut::engine {class MeshInstance;struct MeshInfo;struct Material;}

// Owner-thread journal. Strong references last only until the metadata consumer
// acknowledges the update. Removal identities are captured before node detach.
struct RtrSceneDelta {
    std::unordered_map<donut::engine::MeshInstance*,std::shared_ptr<donut::engine::MeshInstance>> instances;
    std::unordered_map<donut::engine::MeshInfo*,std::shared_ptr<donut::engine::MeshInfo>> meshes;
    std::unordered_map<donut::engine::Material*,std::shared_ptr<donut::engine::Material>> materials;
    std::unordered_set<std::string> removedInstances;
    std::vector<std::pair<std::string,size_t>> removedMeshes;
    void Clear(){instances.clear();meshes.clear();materials.clear();removedInstances.clear();removedMeshes.clear();}
};
