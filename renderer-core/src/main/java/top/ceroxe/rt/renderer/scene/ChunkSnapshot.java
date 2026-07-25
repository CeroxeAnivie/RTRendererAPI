package top.ceroxe.rt.renderer.scene;

import java.util.List;
import java.util.Objects;

/**
 * 一个区块内连续区段快照的不可变发布。
 *
 * @param chunkKey    区块坐标
 * @param minSectionY {@code sections} 首个元素对应的区段 Y 坐标
 * @param sections    按 Y 坐标递增排列的区段快照
 */
public record ChunkSnapshot(
        ChunkKey chunkKey,
        int minSectionY,
        List<SectionVoxelSnapshot> sections
) {
    /**
     * 分离调用方集合并校验必要字段。
     */
    public ChunkSnapshot {
        chunkKey = Objects.requireNonNull(chunkKey, "chunkKey");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    }

    /**
     * 返回此发布包含的区段数量。
     *
     * @return 此区块发布包含的区段数量
     */
    public int sectionCount() {
        return sections.size();
    }
}
