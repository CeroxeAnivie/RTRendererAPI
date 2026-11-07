#pragma once
#include <memory>
#include <cstdint>
#include <vector>

namespace donut::engine {class MeshInstance;struct MeshInfo;}

// Produced by the adapter's existing native-table reconciliation, independently
// of diagnostics. The original graph remains the sole dense-index authority.
struct RtrSceneRowChanges {
    std::vector<std::shared_ptr<donut::engine::MeshInfo>> meshes;
    std::vector<std::shared_ptr<donut::engine::MeshInstance>> instances;
    uint64_t revision=0;
    bool snapshot=false,prepared=false;
    void Clear(){meshes.clear();instances.clear();snapshot=false;prepared=false;}
};

// API-owned owner-thread journal. The unmodified upstream graph owns indices;
// explicit retire callbacks remove old identities before collecting new slots.
struct RtrSceneDelta {
    // Collection visits each original graph instance exactly once. A reusable
    // linear journal needs neither identity hashing nor one heap node per row.
    struct InstanceChange {
        std::shared_ptr<donut::engine::MeshInstance> instance;
        // unordered_map node references survive rehash. Collection and upload
        // run on one owner thread without intervening identity retirement.
        uint32_t* previousSlot=nullptr;
    };
    std::vector<InstanceChange> instances;
    void Clear(){instances.clear();}
};
