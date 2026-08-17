package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/** Builds or updates one top-level AS from an exact ordered instance list. */
public record BuildTopLevelAccelerationStructureCommand(
        AccelerationStructureResource destination,
        AccelerationStructureBuildMode mode,
        List<AccelerationStructureInstance> instances
) implements RenderCommand {
    /** Validates target kind, update declaration, and immutable non-empty instance input. */
    public BuildTopLevelAccelerationStructureCommand {
        destination = Objects.requireNonNull(destination, "destination");
        mode = Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(instances, "instances");
        instances = instances.stream().map(instance -> Objects.requireNonNull(instance, "instance")).toList();
        if (destination.kind() != AccelerationStructureKind.TOP_LEVEL) {
            throw new IllegalArgumentException("top-level build requires a TOP_LEVEL destination");
        }
        if (mode == AccelerationStructureBuildMode.UPDATE && !destination.allowUpdate()) {
            throw new IllegalArgumentException("AS UPDATE requires destination allowUpdate=true");
        }
        if (instances.isEmpty()) throw new IllegalArgumentException("top-level AS build requires instances");
    }
}
