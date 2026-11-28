package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/**
 * A one-sided Lambertian surface for transmitted-light transport. The world-space
 * triangle starts at {@code (x,y,z)} and its other vertices are formed by adding
 * each edge. {@code edgeU × edgeV} points into the emitting hemisphere.
 * {@code radiance} is linear RGB radiance; {@code reach} is a trace distance in
 * world units, not an attenuation curve. This source does not add an unbent direct light.
 *
 * <p>{@code mediumInstanceIds} names the closed retained volumes on the emitting
 * side of the whole triangle, or is empty for air. Split a triangle when its
 * emitting side crosses a boundary. The IDs belong to the frame's scene revision.</p>
 */
public record OpticalTriangleEmitter(double x, double y, double z,
        float edgeUx, float edgeUy, float edgeUz, float edgeVx, float edgeVy, float edgeVz,
        Rgb radiance, float reach, List<Long> mediumInstanceIds) {
    public OpticalTriangleEmitter {
        if (!Float.isFinite((float) x) || !Float.isFinite((float) y) || !Float.isFinite((float) z))
            throw new IllegalArgumentException("Optical emitter origin exceeds finite scene coordinates");
        if (!Float.isFinite(edgeUx) || !Float.isFinite(edgeUy) || !Float.isFinite(edgeUz)
                || !Float.isFinite(edgeVx) || !Float.isFinite(edgeVy) || !Float.isFinite(edgeVz))
            throw new IllegalArgumentException("Optical emitter edges must be finite");
        double nx = (double) edgeUy * edgeVz - (double) edgeUz * edgeVy;
        double ny = (double) edgeUz * edgeVx - (double) edgeUx * edgeVz;
        double nz = (double) edgeUx * edgeVy - (double) edgeUy * edgeVx;
        double area = .5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!((float) area > 0) || !Float.isFinite((float) area))
            throw new IllegalArgumentException("Optical emitter must have a finite positive triangle area");
        if (!(reach > 0) || !Float.isFinite(reach))
            throw new IllegalArgumentException("Optical emitter reach must be finite and positive");
        radiance = Objects.requireNonNull(radiance, "radiance");
        if (!Float.isFinite((float) (Math.PI * area * radiance.maxComponent())))
            throw new IllegalArgumentException("Optical emitter flux exceeds the float range");
        if (mediumInstanceIds.size() > 4)
            throw new IllegalArgumentException("Optical emitter containment exceeds four volumes");
        mediumInstanceIds = mediumInstanceIds.isEmpty() ? List.of() : mediumInstanceIds.stream().sorted().toList();
        for (int i = 0; i < mediumInstanceIds.size(); ++i) {
            var id = mediumInstanceIds.get(i);
            if (id < 0 || (i > 0 && id.equals(mediumInstanceIds.get(i - 1))))
                throw new IllegalArgumentException("Invalid or duplicate optical emitter medium instance ID");
        }
    }
}
