package top.ceroxe.rt.renderer.api;

/**
 * Frame-local work and error budgets for optical transport. Light samples are per scattering
 * interval: zero enumerates every light; positive values bound importance-sampled light work.
 * A zero transmittance cutoff disables early termination. Distances use scene world units.
 *
 * @param maxEvents maximum optical boundary or surface events per path, in [1, 32]
 * @param opticalSamples optical paths per primary sample, in [1, 8]
 * @param scatteringSteps integration intervals per segment, in [1, 64]
 * @param lightSamples sampled lights per interval, in [0, 8]; zero enumerates all lights
 * @param maxDistance maximum transport distance, in (0, 1000000] world units
 * @param transmittanceCutoff termination threshold for the largest throughput channel, in [0, 1)
 * @param scatteringBounces additional volume scattering events beyond direct single scattering,
 *                          in [0, 8]; zero selects direct single scattering
 * @param firstInterfaceSampling deterministic reflection/refraction split by default, or an
 *                               explicit stochastic single branch with probability compensation
 */
public record MediumTransport(int maxEvents, int opticalSamples, int scatteringSteps,
        int lightSamples, float maxDistance, float transmittanceCutoff, int scatteringBounces,
        FirstInterfaceSampling firstInterfaceSampling) {
    /** Quality/cost choice for the first material-shaded dielectric interface. */
    public enum FirstInterfaceSampling { DETERMINISTIC_SPLIT, STOCHASTIC_SINGLE_BRANCH }
    public static final int MAX_EVENTS = 32;
    public static final int MAX_OPTICAL_SAMPLES = 8;
    public static final int MAX_SCATTERING_STEPS = 64;
    public static final int MAX_LIGHT_SAMPLES = 8;
    public static final int MAX_SCATTERING_BOUNCES = 8;
    public static final int MAX_NESTED_BOUNDARIES = 32;
    private static final MediumTransport BALANCED = new MediumTransport(12, 1, 8, 4, 512, 1e-4f);

    public MediumTransport {
        java.util.Objects.requireNonNull(firstInterfaceSampling, "firstInterfaceSampling");
        if (maxEvents < 1 || maxEvents > MAX_EVENTS)
            throw new IllegalArgumentException("maxEvents must be within [1, 32]");
        if (opticalSamples < 1 || opticalSamples > MAX_OPTICAL_SAMPLES)
            throw new IllegalArgumentException("opticalSamples must be within [1, 8]");
        if (scatteringSteps < 1 || scatteringSteps > MAX_SCATTERING_STEPS)
            throw new IllegalArgumentException("scatteringSteps must be within [1, 64]");
        if (lightSamples < 0 || lightSamples > MAX_LIGHT_SAMPLES)
            throw new IllegalArgumentException("lightSamples must be within [0, 8]");
        if (!Float.isFinite(maxDistance) || maxDistance <= 0 || maxDistance > 1_000_000)
            throw new IllegalArgumentException("maxDistance must be within (0, 1000000]");
        if (!Float.isFinite(transmittanceCutoff) || transmittanceCutoff < 0 || transmittanceCutoff >= 1)
            throw new IllegalArgumentException("transmittanceCutoff must be within [0, 1)");
        if (scatteringBounces < 0 || scatteringBounces > MAX_SCATTERING_BOUNCES)
            throw new IllegalArgumentException("scatteringBounces must be within [0, 8]");
        transmittanceCutoff = transmittanceCutoff == 0 ? 0 : transmittanceCutoff;
    }

    /** Preserves the original deterministic first-interface split and seven-argument ABI. */
    public MediumTransport(int maxEvents, int opticalSamples, int scatteringSteps,
            int lightSamples, float maxDistance, float transmittanceCutoff, int scatteringBounces) {
        this(maxEvents, opticalSamples, scatteringSteps, lightSamples, maxDistance, transmittanceCutoff,
                scatteringBounces, FirstInterfaceSampling.DETERMINISTIC_SPLIT);
    }

    /** Creates budgets for direct single scattering without additional volume bounces. */
    public MediumTransport(int maxEvents, int opticalSamples, int scatteringSteps,
            int lightSamples, float maxDistance, float transmittanceCutoff) {
        this(maxEvents, opticalSamples, scatteringSteps, lightSamples, maxDistance, transmittanceCutoff, 0);
    }

    /** Returns 12 events, one optical path, eight intervals and four light samples with direct single scattering. */
    public static MediumTransport balanced() { return BALANCED; }
    /**
     * Explicit lower-cost scattering preset: two density/integration intervals and one local
     * light sample per interval. This changes quadrature error and variance; it adds no temporal
     * reconstruction and preserves deterministic dielectric splitting.
     */
    public static MediumTransport realtime() { return BALANCED.withScatteringSteps(2).withLightSamples(1); }
    /** Explicit higher sampling budget: 32 intervals, all local lights and four optical samples. */
    public static MediumTransport highQuality() { return BALANCED.withScatteringSteps(32).withLightSamples(0).withOpticalSamples(4); }
    public MediumTransport withMaxEvents(int value) {
        return new MediumTransport(value, opticalSamples, scatteringSteps, lightSamples, maxDistance, transmittanceCutoff, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withOpticalSamples(int value) {
        return new MediumTransport(maxEvents, value, scatteringSteps, lightSamples, maxDistance, transmittanceCutoff, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withScatteringSteps(int value) {
        return new MediumTransport(maxEvents, opticalSamples, value, lightSamples, maxDistance, transmittanceCutoff, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withLightSamples(int value) {
        return new MediumTransport(maxEvents, opticalSamples, scatteringSteps, value, maxDistance, transmittanceCutoff, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withMaxDistance(float value) {
        return new MediumTransport(maxEvents, opticalSamples, scatteringSteps, lightSamples, value, transmittanceCutoff, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withTransmittanceCutoff(float value) {
        return new MediumTransport(maxEvents, opticalSamples, scatteringSteps, lightSamples, maxDistance, value, scatteringBounces, firstInterfaceSampling);
    }
    public MediumTransport withScatteringBounces(int value) {
        return new MediumTransport(maxEvents, opticalSamples, scatteringSteps, lightSamples, maxDistance, transmittanceCutoff, value, firstInterfaceSampling);
    }
    /** Selects an explicit first-interface estimator without changing any work budget. */
    public MediumTransport withFirstInterfaceSampling(FirstInterfaceSampling value) {
        return new MediumTransport(maxEvents, opticalSamples, scatteringSteps, lightSamples, maxDistance,
                transmittanceCutoff, scatteringBounces, value);
    }
}
