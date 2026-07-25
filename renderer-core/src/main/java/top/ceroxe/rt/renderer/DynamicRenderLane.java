package top.ceroxe.rt.renderer;

import java.util.List;
import java.util.Objects;

/**
 * Instance-level depth lane resolved before acceleration-structure scheduling.
 */
public enum DynamicRenderLane {
    /**
     * 参与普通世界深度测试的实例。
     */
    WORLD,
    /**
     * 使用始终置顶深度语义的实例。
     */
    ALWAYS_ON_TOP;

    /**
     * 从完整面材料集合解析唯一的实例深度通道。
     *
     * @param faceMaterials 非空且深度语义一致的面材料列表
     * @return 解析后的实例通道
     */
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
