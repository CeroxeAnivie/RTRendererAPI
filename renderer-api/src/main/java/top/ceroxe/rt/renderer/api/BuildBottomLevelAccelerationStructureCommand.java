package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/** Builds or updates one bottom-level hardware acceleration structure from explicit triangles. */
public record BuildBottomLevelAccelerationStructureCommand(
        AccelerationStructureResource destination,
        AccelerationStructureBuildMode mode,
        List<AccelerationStructureTriangleGeometry> geometries
) implements RenderCommand {
    /** Validates target kind, update declaration, and immutable non-empty geometry input. */
    public BuildBottomLevelAccelerationStructureCommand {
        destination = Objects.requireNonNull(destination, "destination");
        mode = Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(geometries, "geometries");
        geometries = geometries.stream().map(geometry -> Objects.requireNonNull(geometry, "geometry")).toList();
        if (destination.kind() != AccelerationStructureKind.BOTTOM_LEVEL) {
            throw new IllegalArgumentException("bottom-level build requires a BOTTOM_LEVEL destination");
        }
        if (mode == AccelerationStructureBuildMode.UPDATE && !destination.allowUpdate()) {
            throw new IllegalArgumentException("AS UPDATE requires destination allowUpdate=true");
        }
        if (geometries.isEmpty()) throw new IllegalArgumentException("bottom-level AS build requires triangle geometry");
    }
}
