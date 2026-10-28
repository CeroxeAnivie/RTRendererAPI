package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * A closed, consistently wound mesh's interior. Higher priority replaces lower priority in an
 * overlap; equal priorities select the most recently entered boundary and restore it on exit.
 * Surface interaction enables the mesh material; a volume-only boundary has no visible surface.
 *
 * @param medium homogeneous optical properties of the interior
 * @param priority signed overlap priority; higher values win
 * @param orientation triangle winding relative to the mesh interior
 * @param surfaceInteraction whether the boundary also shades using the mesh material
 */
public record MediumBoundary(Medium medium, int priority, Orientation orientation, boolean surfaceInteraction) {
    /** Direction of the winding-derived geometric normals on a closed mesh. */
    public enum Orientation { OUTWARD, INWARD }

    public MediumBoundary {
        Objects.requireNonNull(medium, "medium");
        Objects.requireNonNull(orientation, "orientation");
    }

    /** Creates a volume-only boundary with priority zero and outward winding. */
    public static MediumBoundary volume(Medium medium) {
        return new MediumBoundary(medium, 0, Orientation.OUTWARD, false);
    }
    /** Creates a material-shaded boundary with priority zero and outward winding. */
    public static MediumBoundary surface(Medium medium) {
        return new MediumBoundary(medium, 0, Orientation.OUTWARD, true);
    }
    public MediumBoundary withPriority(int value) {
        return new MediumBoundary(medium, value, orientation, surfaceInteraction);
    }
    public MediumBoundary withOrientation(Orientation value) {
        return new MediumBoundary(medium, priority, value, surfaceInteraction);
    }
    public MediumBoundary withMedium(Medium value) {
        return new MediumBoundary(value, priority, orientation, surfaceInteraction);
    }
    public MediumBoundary withSurfaceInteraction(boolean value) {
        return new MediumBoundary(medium, priority, orientation, value);
    }
}
