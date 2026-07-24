package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.List;
import java.util.Objects;

public record ChunkSnapshot(
        ChunkKey chunkKey,
        int minSectionY,
        List<SectionVoxelSnapshot> sections
) {
    public ChunkSnapshot {
        chunkKey = Objects.requireNonNull(chunkKey, "chunkKey");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
    }

    public int sectionCount() {
        return sections.size();
    }
}
