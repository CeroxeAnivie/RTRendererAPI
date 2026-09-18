package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/** Builds or updates a procedural BLAS; hit groups must provide an intersection shader. */
public record BuildProceduralBottomLevelAccelerationStructureCommand(
        AccelerationStructureResource destination,
        AccelerationStructureBuildMode mode,
        List<AccelerationStructureAabbGeometry> geometries
) implements RenderCommand {
    /** Validates the destination and snapshots the ordered, non-empty geometry list. */
    public BuildProceduralBottomLevelAccelerationStructureCommand {
        destination = Objects.requireNonNull(destination, "destination");
        mode = Objects.requireNonNull(mode, "mode");
        geometries = List.copyOf(Objects.requireNonNull(geometries, "geometries"));
        if (destination.kind() != AccelerationStructureKind.BOTTOM_LEVEL) {
            throw new IllegalArgumentException("procedural build requires a BOTTOM_LEVEL destination");
        }
        if (mode == AccelerationStructureBuildMode.UPDATE && !destination.allowUpdate()) {
            throw new IllegalArgumentException("AS UPDATE requires destination allowUpdate=true");
        }
        if (geometries.isEmpty()) throw new IllegalArgumentException("procedural BLAS requires AABB geometry");
    }
}
