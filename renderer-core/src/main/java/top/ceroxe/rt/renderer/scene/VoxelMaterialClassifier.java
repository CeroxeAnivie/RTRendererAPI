package top.ceroxe.rt.renderer.scene;

/**
 * 判断体素是否需要进入渲染几何构建阶段。
 *
 * <p>实现必须是无副作用且可从并行网格构建线程调用的。分类结果只决定是否生成候选几何，
 * 不替代后续的面剔除、材质解析或透明度判定。</p>
 */
@FunctionalInterface
public interface VoxelMaterialClassifier {
    /**
     * 默认保守分类器：具有可见渲染形状或非空介质的体素均视为可渲染。
     */
    VoxelMaterialClassifier DEFAULT_CONSERVATIVE = (voxelTypeId, mediumAmount, materialFlags) ->
            (materialFlags & SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE) != 0 || mediumAmount > 0;

    /**
     * 判断一个体素是否可能产生渲染几何。
     *
     * @param voxelTypeId   渲染器内部的体素类型标识
     * @param mediumAmount  介质填充量；零表示没有介质
     * @param materialFlags {@link SectionVoxelSnapshot} 定义的材料标志位
     * @return 需要进入几何构建阶段时返回 {@code true}
     */
    boolean isRenderable(int voxelTypeId, int mediumAmount, int materialFlags);
}
