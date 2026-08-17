package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Explicit aligned push-constant update for selected shader stages. */
public record SetPushConstantsCommand(
        Set<ShaderStage> stages,
        int offsetBytes,
        ResourceData data
) implements RenderCommand {
    public SetPushConstantsCommand {
        Objects.requireNonNull(stages, "stages");
        EnumSet<ShaderStage> checked = EnumSet.noneOf(ShaderStage.class);
        for (ShaderStage stage : stages) checked.add(Objects.requireNonNull(stage, "push-constant stage"));
        if (checked.isEmpty()) throw new IllegalArgumentException("push-constant stage set must not be empty");
        stages = Collections.unmodifiableSet(checked);
        if (offsetBytes < 0 || (offsetBytes & 3) != 0) throw new IllegalArgumentException("push-constant offset must be non-negative and four-byte aligned");
        data = Objects.requireNonNull(data, "data");
        if ((data.byteSize() & 3) != 0) throw new IllegalArgumentException("push-constant data must be four-byte aligned");
    }
}
