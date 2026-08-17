package top.ceroxe.rt.renderer.api;

/**
 * Stable shader resource location.
 *
 * @param group non-negative binding-group index
 * @param binding non-negative binding index within the group
 */
public record BindingKey(int group, int binding) implements Comparable<BindingKey> {
    /** Validates non-negative coordinates. */
    public BindingKey {
        if (group < 0 || binding < 0) {
            throw new IllegalArgumentException("binding group and binding index must not be negative");
        }
    }

    @Override
    public int compareTo(BindingKey other) {
        int groupOrder = Integer.compare(group, other.group);
        return groupOrder != 0 ? groupOrder : Integer.compare(binding, other.binding);
    }
}
