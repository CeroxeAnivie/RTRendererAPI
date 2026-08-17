package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Exact stable identity plus published generation of one generic resource. */
public record ResourceGenerationKey(RenderResourceId id, ResourceVersion version) {
    public ResourceGenerationKey {
        id = Objects.requireNonNull(id, "id");
        version = Objects.requireNonNull(version, "version");
    }

    /** Creates a key from an exact resource descriptor without guessing its kind. */
    public static ResourceGenerationKey of(RenderResource resource) {
        Objects.requireNonNull(resource, "resource");
        return new ResourceGenerationKey(resource.id(), resource.version());
    }
}
