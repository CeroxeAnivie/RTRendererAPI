package top.ceroxe.rt.renderer.api;
import java.util.Objects;
/** Closed, outward-wound material surface with a homogeneous absorbing interior.
 * Higher priorities win in overlaps. RTXPT stores four nested interior slots.
 * No invisible boundary, inward-winding override or independent camera medium is exposed.
 * @param medium optical interior
 * @param priority native nested priority in [0,14]
 */
public record MediumBoundary(Medium medium, int priority) {
    public static final int MAX_PRIORITY = 14;
    public static final int MAX_NESTED_INTERIORS = 4;
    public MediumBoundary {
        Objects.requireNonNull(medium, "medium");
        if(priority < 0 || priority > MAX_PRIORITY)
            throw new IllegalArgumentException("priority must be within [0,14]");
    }
    public static MediumBoundary surface(Medium medium) { return new MediumBoundary(medium, 0); }
    public MediumBoundary withPriority(int value) { return new MediumBoundary(medium, value); }
    public MediumBoundary withMedium(Medium value) { return new MediumBoundary(value, priority); }
}
