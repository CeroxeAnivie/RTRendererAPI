package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Reflection for one standalone numeric uniform represented in push-constant storage.
 *
 * <p>This contract intentionally covers scalar and vector numeric values only. Matrices,
 * structures, booleans, and implementation-specific packing require a richer reflection type.</p>
 */
public record ImmediateUniform(
        String name,
        ShaderInterfaceType type,
        int arrayCount,
        int offsetBytes,
        int byteSize,
        Set<ShaderStage> stages
) {
    public ImmediateUniform {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("immediate uniform name must not be blank");
        type = Objects.requireNonNull(type, "type");
        if (arrayCount <= 0) throw new IllegalArgumentException("immediate uniform array count must be positive");
        if (offsetBytes < 0 || (offsetBytes & 3) != 0) {
            throw new IllegalArgumentException("immediate uniform offset must be non-negative and four-byte aligned");
        }
        if (byteSize <= 0 || (byteSize & 3) != 0) {
            throw new IllegalArgumentException("immediate uniform byte size must be positive and four-byte aligned");
        }
        try {
            Math.addExact(offsetBytes, byteSize);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("immediate uniform range overflows integer address space", overflow);
        }
        Objects.requireNonNull(stages, "stages");
        EnumSet<ShaderStage> checked = EnumSet.noneOf(ShaderStage.class);
        for (ShaderStage stage : stages) checked.add(Objects.requireNonNull(stage, "immediate uniform stage"));
        if (checked.isEmpty()) throw new IllegalArgumentException("immediate uniform stage visibility must not be empty");
        stages = Collections.unmodifiableSet(checked);
    }

    /** End-exclusive byte offset in the program push-constant block. */
    public int endBytes() { return Math.addExact(offsetBytes, byteSize); }
}
