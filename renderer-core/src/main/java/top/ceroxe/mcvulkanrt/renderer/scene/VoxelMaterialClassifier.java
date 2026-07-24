package top.ceroxe.mcvulkanrt.renderer.scene;

@FunctionalInterface
public interface VoxelMaterialClassifier {
    VoxelMaterialClassifier DEFAULT_CONSERVATIVE = (voxelTypeId, mediumAmount, materialFlags) ->
            (materialFlags & SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE) != 0 || mediumAmount > 0;

    boolean isRenderable(int voxelTypeId, int mediumAmount, int materialFlags);
}
