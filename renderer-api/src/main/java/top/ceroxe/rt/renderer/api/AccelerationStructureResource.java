package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable identity and topology declaration for one hardware acceleration structure. */
public record AccelerationStructureResource(
        RenderResourceId id,
        ResourceVersion version,
        AccelerationStructureKind kind,
        boolean allowUpdate
) {
    /** Validates the complete identity independently of build completion or residency. */
    public AccelerationStructureResource {
        id = Objects.requireNonNull(id, "id");
        version = Objects.requireNonNull(version, "version");
        kind = Objects.requireNonNull(kind, "kind");
    }
}
