package top.ceroxe.rt.renderer.scene;

import java.util.List;
import java.util.Objects;

/**
 * 一个区段完成网格化后的不可变 CPU 几何快照。
 *
 * @param key               几何所属的区段坐标
 * @param faces             已冻结的三角面输入
 * @param sourcePaletteSize 生成该快照的源材料调色板大小
 * @param sourceRunCount    生成该快照时解码的连续体素段数量
 * @param modelFacts        构建阶段汇总的模型事实
 */
public record SectionGeometrySnapshot(
        SectionKey key,
        List<SectionFace> faces,
        int sourcePaletteSize,
        int sourceRunCount,
        SectionModelFacts modelFacts
) {
    /**
     * 使用不可用的模型事实创建兼容快照。
     *
     * @param key               几何所属的区段坐标
     * @param faces             已冻结的三角面输入
     * @param sourcePaletteSize 源材料调色板大小
     * @param sourceRunCount    解码的连续体素段数量
     */
    public SectionGeometrySnapshot(
            SectionKey key,
            List<SectionFace> faces,
            int sourcePaletteSize,
            int sourceRunCount
    ) {
        this(key, faces, sourcePaletteSize, sourceRunCount, SectionModelFacts.unavailable());
    }

    /**
     * Freezes face storage and validates section identity.
     */
    public SectionGeometrySnapshot {
        key = Objects.requireNonNull(key, "key");
        faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
        modelFacts = Objects.requireNonNull(modelFacts, "modelFacts");
        if (sourcePaletteSize < 0) {
            throw new IllegalArgumentException("sourcePaletteSize must be non-negative");
        }
        if (sourceRunCount < 0) {
            throw new IllegalArgumentException("sourceRunCount must be non-negative");
        }
    }

    /**
     * 返回快照包含的面数量。
     *
     * @return 面数量
     */
    public int faceCount() {
        return faces.size();
    }

    /**
     * 估算稳定几何负载占用的字节数。
     *
     * @return 不包含 JVM 对象头的可重复内存估算值
     */
    public long estimatedBytes() {
        /*
         * 这里估算的是稳定 payload，不追求精确到 JVM object header。最终几何格式
         * 还没定型前，预算系统更需要可重复的压力信号，而不是虚假的精确数字。
         */
        return Integer.BYTES * 6L * faces.size()
                + Integer.BYTES * 2L
                + modelFacts.estimatedBytes();
    }
}
