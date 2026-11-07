#pragma once
#include <memory>
#include <unordered_map>

namespace donut::engine {class MeshInstance;struct MeshInfo;}

// API-owned owner-thread journal. The unmodified upstream graph owns indices;
// explicit retire callbacks remove old identities before collecting new slots.
struct RtrSceneDelta {
    std::unordered_map<donut::engine::MeshInstance*,std::shared_ptr<donut::engine::MeshInstance>> instances;
    std::unordered_map<donut::engine::MeshInfo*,std::shared_ptr<donut::engine::MeshInfo>> meshes;
    void Clear(){instances.clear();meshes.clear();}
};
