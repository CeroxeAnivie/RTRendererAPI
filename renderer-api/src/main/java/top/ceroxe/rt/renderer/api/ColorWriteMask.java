package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable set of color attachment components that fragment output may update. */
public final class ColorWriteMask {
    public enum Component { RED, GREEN, BLUE, ALPHA }

    private static final ColorWriteMask ALL = new ColorWriteMask(EnumSet.allOf(Component.class));
    private static final ColorWriteMask NONE = new ColorWriteMask(EnumSet.noneOf(Component.class));

    private final Set<Component> components;

    /** Creates a defensive immutable copy of any component subset. */
    public ColorWriteMask(Set<Component> components) {
        Objects.requireNonNull(components, "components");
        EnumSet<Component> checked = EnumSet.noneOf(Component.class);
        for (Component component : components) {
            checked.add(Objects.requireNonNull(component, "color write component"));
        }
        this.components = Collections.unmodifiableSet(checked);
    }

    /** @return mask that writes all four color components */
    public static ColorWriteMask all() { return ALL; }

    /** @return mask that suppresses every color component */
    public static ColorWriteMask none() { return NONE; }

    /** @return immutable component subset */
    public Set<Component> components() { return components; }

    /** Returns whether one component may be written. */
    public boolean contains(Component component) {
        return components.contains(Objects.requireNonNull(component, "component"));
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof ColorWriteMask that && components.equals(that.components);
    }

    @Override public int hashCode() { return components.hashCode(); }

    @Override public String toString() { return "ColorWriteMask" + components; }
}
