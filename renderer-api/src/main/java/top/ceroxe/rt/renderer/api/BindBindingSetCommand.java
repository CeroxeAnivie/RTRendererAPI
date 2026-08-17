package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Binds one complete resource set and ordered command-time buffer offsets.
 *
 * <p>Dynamic offsets are listed in layout declaration order and then array-element order. Their count
 * must exactly match the layout entries that declare dynamic offsets.</p>
 */
public record BindBindingSetCommand(
        BindingSet bindingSet,
        List<Long> dynamicOffsets
) implements RenderCommand {
    /** Validates offset count and non-negative offsets. */
    public BindBindingSetCommand {
        Objects.requireNonNull(bindingSet, "bindingSet");
        Objects.requireNonNull(dynamicOffsets, "dynamicOffsets");
        int expectedOffsets = 0;
        for (BindingLayoutEntry entry : bindingSet.layout().entries()) {
            if (entry.dynamicOffset()) {
                try {
                    expectedOffsets = Math.addExact(expectedOffsets, entry.arrayCount());
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("dynamic offset count overflows int", overflow);
                }
            }
        }
        if (dynamicOffsets.size() != expectedOffsets) {
            throw new IllegalArgumentException("dynamic offset count does not match the binding layout");
        }
        ArrayList<Long> checked = new ArrayList<>(dynamicOffsets.size());
        for (Long offset : dynamicOffsets) {
            long value = Objects.requireNonNull(offset, "dynamic offset");
            if (value < 0L) throw new IllegalArgumentException("dynamic offsets must not be negative");
            checked.add(value);
        }
        int offsetIndex = 0;
        for (BindingLayoutEntry entry : bindingSet.layout().entries()) {
            if (!entry.dynamicOffset()) continue;
            for (BindingSet.Value value : bindingSet.values().get(entry.key())) {
                BindingSet.BufferValue bufferValue = (BindingSet.BufferValue) value;
                long dynamicOffset = checked.get(offsetIndex++);
                long finalOffset;
                try {
                    finalOffset = Math.addExact(bufferValue.range().offsetBytes(), dynamicOffset);
                } catch (ArithmeticException overflow) {
                    throw new IllegalArgumentException("dynamic buffer offset overflows long", overflow);
                }
                bufferValue.buffer().requireContained(new ByteRange(finalOffset, bufferValue.range().lengthBytes()));
            }
        }
        dynamicOffsets = List.copyOf(checked);
    }

    /** Creates a binding command for a set without dynamic offsets. */
    public static BindBindingSetCommand fixed(BindingSet bindingSet) {
        return new BindBindingSetCommand(bindingSet, List.of());
    }
}
