package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.List;
import java.util.Objects;

public record SectionGeometrySnapshot(
        SectionKey key,
        List<SectionFace> faces,
        int sourcePaletteSize,
        int sourceRunCount,
        SectionModelFacts modelFacts
) {
    public SectionGeometrySnapshot(
            SectionKey key,
            List<SectionFace> faces,
            int sourcePaletteSize,
            int sourceRunCount
    ) {
        this(key, faces, sourcePaletteSize, sourceRunCount, SectionModelFacts.unavailable());
    }

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

    public int faceCount() {
        return faces.size();
    }

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
