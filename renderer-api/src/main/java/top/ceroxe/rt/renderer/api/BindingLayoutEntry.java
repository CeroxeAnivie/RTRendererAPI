package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable declaration of one shader resource binding.
 *
 * @param key exact group and binding location
 * @param type required resource category
 * @param arrayCount positive fixed descriptor count
 * @param visibleStages non-empty immutable stage visibility
 * @param dynamicOffset whether command-time byte offsets are permitted for this buffer binding
 */
public record BindingLayoutEntry(
        BindingKey key,
        BindingType type,
        int arrayCount,
        Set<ShaderStage> visibleStages,
        boolean dynamicOffset
) {
    /** Validates the complete binding declaration. */
    public BindingLayoutEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        if (arrayCount <= 0) throw new IllegalArgumentException("binding array count must be positive");
        Objects.requireNonNull(visibleStages, "visibleStages");
        if (visibleStages.isEmpty()) throw new IllegalArgumentException("binding stage visibility must not be empty");
        EnumSet<ShaderStage> checkedStages = EnumSet.noneOf(ShaderStage.class);
        for (ShaderStage stage : visibleStages) {
            checkedStages.add(Objects.requireNonNull(stage, "visible shader stage"));
        }
        visibleStages = Collections.unmodifiableSet(checkedStages);
        if (dynamicOffset && type != BindingType.UNIFORM_BUFFER
                && type != BindingType.READ_ONLY_STORAGE_BUFFER
                && type != BindingType.READ_WRITE_STORAGE_BUFFER) {
            throw new IllegalArgumentException("dynamic offsets are valid only for buffer bindings");
        }
    }
}
