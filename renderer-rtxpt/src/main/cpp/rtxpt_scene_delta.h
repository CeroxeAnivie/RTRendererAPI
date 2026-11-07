#pragma once
#include <memory>
#include <vector>

namespace donut::engine {class MeshInstance;struct MeshInfo;}

// API-owned owner-thread journal. The unmodified upstream graph owns indices;
// explicit retire callbacks remove old identities before collecting new slots.
struct RtrSceneDelta {
    // Collection visits each original graph instance exactly once. A reusable
    // linear journal needs neither identity hashing nor one heap node per row.
    std::vector<std::shared_ptr<donut::engine::MeshInstance>> instances;
    void Clear(){instances.clear();}
};
