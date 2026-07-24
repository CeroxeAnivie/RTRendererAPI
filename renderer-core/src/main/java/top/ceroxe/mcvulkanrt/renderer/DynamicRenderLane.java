package top.ceroxe.mcvulkanrt.renderer;

import java.util.List;
import java.util.Objects;

/** Instance-level depth lane resolved before acceleration-structure scheduling. */
public enum DynamicRenderLane {
    WORLD,
    ALWAYS_ON_TOP;

    public static DynamicRenderLane fromFaceMaterials(List<DynamicMeshInstance.FaceMaterial> faceMaterials) {
        Objects.requireNonNull(faceMaterials, "faceMaterials");
        if (faceMaterials.isEmpty()) {
            throw new IllegalArgumentException("dynamic model instance requires at least one face material");
        }
        boolean alwaysOnTop = faceMaterials.getFirst().alwaysOnTop();
        for (int index = 1; index < faceMaterials.size(); index++) {
            if (faceMaterials.get(index).alwaysOnTop() != alwaysOnTop) {
                throw new IllegalArgumentException(
                        "one dynamic model instance cannot mix world and always-on-top face materials"
                );
            }
        }
        return alwaysOnTop ? ALWAYS_ON_TOP : WORLD;
    }
}
