package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, order-stable collection of unique shader binding declarations. */
public final class BindingLayout {
    private final List<BindingLayoutEntry> entries;
    private final Map<BindingKey, BindingLayoutEntry> entriesByKey;

    /**
     * Creates a layout and rejects duplicate binding coordinates.
     *
     * @param entries possibly empty binding declarations
     */
    public BindingLayout(List<BindingLayoutEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        ArrayList<BindingLayoutEntry> checked = new ArrayList<>(entries.size());
        LinkedHashMap<BindingKey, BindingLayoutEntry> indexed = new LinkedHashMap<>();
        for (BindingLayoutEntry entry : entries) {
            BindingLayoutEntry value = Objects.requireNonNull(entry, "binding layout entry");
            if (indexed.putIfAbsent(value.key(), value) != null) {
                throw new IllegalArgumentException("duplicate binding layout key: " + value.key());
            }
            checked.add(value);
        }
        this.entries = List.copyOf(checked);
        this.entriesByKey = Collections.unmodifiableMap(indexed);
    }

    /** @return immutable declaration order */
    public List<BindingLayoutEntry> entries() { return entries; }

    /** @return immutable lookup by exact binding key */
    public Map<BindingKey, BindingLayoutEntry> entriesByKey() { return entriesByKey; }

    /**
     * Returns a required declaration.
     *
     * @param key non-null exact key
     * @return matching declaration
     * @throws IllegalArgumentException when the key is not declared
     */
    public BindingLayoutEntry require(BindingKey key) {
        BindingLayoutEntry entry = entriesByKey.get(Objects.requireNonNull(key, "key"));
        if (entry == null) throw new IllegalArgumentException("binding key is not declared: " + key);
        return entry;
    }
}
