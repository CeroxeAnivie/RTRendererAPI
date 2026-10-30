package top.ceroxe.rt.renderer.api;

/**
 * Optional per-frame photon map for specular reflection and refraction caustics on surfaces
 * and inside a participating medium.
 * The emission sphere bounds the supported caustic region and must enclose medium boundaries.
 * A fixed-radius normalized density estimate introduces spatial bias and sampling noise;
 * increasing photons and reducing the gather radius must be evaluated together.
 *
 * @param photons emitted photon paths per native frame; zero disables the stage
 * @param maxEvents maximum geometric events per photon, from one to 32
 * @param gatherRadius density-estimation radius in world units
 * @param centerX emission sphere center X
 * @param centerY emission sphere center Y
 * @param centerZ emission sphere center Z
 * @param radius emission sphere radius, at most one million world units
 * @param normalThreshold minimum photon/receiver geometric-normal agreement, from zero to one
 */
public record Caustics(int photons, int maxEvents, float gatherRadius,
        double centerX, double centerY, double centerZ, float radius, float normalThreshold) {
    public static final int MAX_PHOTONS = 1_048_576;
    private static final Caustics DISABLED = new Caustics(0, 12, .05f, 0, 0, 0, 1, .9f);

    public Caustics {
        if (photons < 0 || photons > MAX_PHOTONS)
            throw new IllegalArgumentException("photons must be within [0, 1048576]");
        if (maxEvents < 1 || maxEvents > 32)
            throw new IllegalArgumentException("maxEvents must be within [1, 32]");
        if (!Float.isFinite(radius) || radius <= 0 || radius > 1_000_000)
            throw new IllegalArgumentException("radius must be within (0, 1000000]");
        if (!Float.isFinite(gatherRadius) || gatherRadius <= 0 || gatherRadius > radius
                || radius / (double) gatherRadius > 1_000_000)
            throw new IllegalArgumentException("gatherRadius must be positive, at most radius, and resolve at most 1000000 cells per radius");
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || !Float.isFinite((float) centerX) || !Float.isFinite((float) centerY) || !Float.isFinite((float) centerZ))
            throw new IllegalArgumentException("caustic sphere center must fit finite GPU coordinates");
        if (!Float.isFinite(normalThreshold) || normalThreshold < 0 || normalThreshold > 1)
            throw new IllegalArgumentException("normalThreshold must be within [0, 1]");
    }

    /** Returns the shared policy that allocates no photon map and dispatches no photon stage. */
    public static Caustics disabled() { return DISABLED; }
    /** Creates a photon map in a world-space sphere, with 12 events and 0.9 surface-normal agreement. */
    public static Caustics photonMap(double x, double y, double z, float radius, int photons, float gatherRadius) {
        return new Caustics(photons, 12, gatherRadius, x, y, z, radius, .9f);
    }
    public boolean enabled() { return photons != 0; }
    public Caustics withPhotons(int value) {
        return new Caustics(value, maxEvents, gatherRadius, centerX, centerY, centerZ, radius, normalThreshold);
    }
    public Caustics withMaxEvents(int value) {
        return new Caustics(photons, value, gatherRadius, centerX, centerY, centerZ, radius, normalThreshold);
    }
    public Caustics withGatherRadius(float value) {
        return new Caustics(photons, maxEvents, value, centerX, centerY, centerZ, radius, normalThreshold);
    }
    public Caustics withNormalThreshold(float value) {
        return new Caustics(photons, maxEvents, gatherRadius, centerX, centerY, centerZ, radius, value);
    }
}
