package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.orchestration.work.SectionWorkLane;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns the mutually exclusive queued-versus-async section BLAS build claim. */
class RtPendingBlasBuildOwnership {
    private final Map<SectionKey, SectionTriangleMesh> asyncOwners = new HashMap<>();

    boolean enqueueIfUnowned(
            SectionTriangleMesh mesh,
            RtPendingBlasBuildQueue<?> queue,
            SectionWorkLane lane
    ) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(lane, "lane");
        if (queue.owns(mesh) || asyncOwners.get(mesh.key()) == mesh) {
            return false;
        }
        queue.enqueue(mesh, lane);
        return true;
    }

    boolean ownsSection(SectionKey key, RtPendingBlasBuildQueue<?> queue) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(queue, "queue");
        return queue.contains(key) || asyncOwners.containsKey(key);
    }

    List<SectionTriangleMesh> claimAsync(List<SectionTriangleMesh> meshes) {
        Objects.requireNonNull(meshes, "meshes");
        LinkedHashMap<SectionKey, SectionTriangleMesh> claimed = new LinkedHashMap<>();
        for (SectionTriangleMesh mesh : meshes) {
            Objects.requireNonNull(mesh, "mesh");
            SectionTriangleMesh duplicate = claimed.putIfAbsent(mesh.key(), mesh);
            if (duplicate != null && duplicate != mesh) {
                throw new IllegalArgumentException(
                        "async BLAS batch contains multiple source generations: " + mesh.key()
                );
            }
        }
        List<SectionTriangleMesh> accepted = new ArrayList<>(claimed.size());
        for (SectionTriangleMesh mesh : claimed.values()) {
            SectionTriangleMesh currentOwner = asyncOwners.get(mesh.key());
            if (currentOwner == mesh) {
                continue;
            }
            asyncOwners.put(mesh.key(), mesh);
            accepted.add(mesh);
        }
        return List.copyOf(accepted);
    }

    void invalidateAsync(SectionKey key) {
        asyncOwners.remove(Objects.requireNonNull(key, "key"));
    }

    void releaseAsync(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        asyncOwners.remove(mesh.key(), mesh);
    }

    void releaseAsync(Collection<SectionTriangleMesh> meshes) {
        Objects.requireNonNull(meshes, "meshes");
        for (SectionTriangleMesh mesh : meshes) {
            releaseAsync(mesh);
        }
    }

    void clearAsync() {
        asyncOwners.clear();
    }
}
