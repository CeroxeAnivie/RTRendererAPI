package top.ceroxe.rt.renderer.api;

/** Frame-local bounded optical transport quality, independent of temporal history or output size. */
public record MediumTransportOptions(int maxInterfaceEvents, int opticalSamples,
        int scatteringSteps, float maximumDistance) {
    public static final int MAX_INTERFACE_EVENTS = 32;
    public static final int MAX_OPTICAL_SAMPLES = 8;
    public static final int MAX_SCATTERING_STEPS = 64;
    public static final int MAX_NESTED_BOUNDARIES = 32;
    private static final MediumTransportOptions BALANCED = new MediumTransportOptions(12, 1, 8, 512);

    public MediumTransportOptions {
        if (maxInterfaceEvents < 1 || maxInterfaceEvents > MAX_INTERFACE_EVENTS
                || opticalSamples < 1 || opticalSamples > MAX_OPTICAL_SAMPLES
                || scatteringSteps < 1 || scatteringSteps > MAX_SCATTERING_STEPS
                || !Float.isFinite(maximumDistance) || maximumDistance <= 0 || maximumDistance > 1_000_000)
            throw new IllegalArgumentException("invalid bounded medium transport options");
    }

    /** Balanced default: 12 interface events, one optical sample, eight scattering intervals. */
    public static MediumTransportOptions balanced() { return BALANCED; }
    public static Builder builder() { return new Builder(BALANCED); }
    public Builder toBuilder() { return new Builder(this); }
    public MediumTransportOptions withMaxInterfaceEvents(int value) {
        return new MediumTransportOptions(value, opticalSamples, scatteringSteps, maximumDistance);
    }
    public MediumTransportOptions withOpticalSamples(int value) {
        return new MediumTransportOptions(maxInterfaceEvents, value, scatteringSteps, maximumDistance);
    }
    public MediumTransportOptions withScatteringSteps(int value) {
        return new MediumTransportOptions(maxInterfaceEvents, opticalSamples, value, maximumDistance);
    }
    public MediumTransportOptions withMaximumDistance(float value) {
        return new MediumTransportOptions(maxInterfaceEvents, opticalSamples, scatteringSteps, value);
    }

    public static final class Builder {
        private int maxInterfaceEvents, opticalSamples, scatteringSteps;
        private float maximumDistance;
        private Builder(MediumTransportOptions source) {
            maxInterfaceEvents=source.maxInterfaceEvents; opticalSamples=source.opticalSamples;
            scatteringSteps=source.scatteringSteps; maximumDistance=source.maximumDistance;
        }
        public Builder maxInterfaceEvents(int value) { maxInterfaceEvents=value; return this; }
        public Builder opticalSamples(int value) { opticalSamples=value; return this; }
        public Builder scatteringSteps(int value) { scatteringSteps=value; return this; }
        public Builder maximumDistance(float value) { maximumDistance=value; return this; }
        public MediumTransportOptions build() {
            return new MediumTransportOptions(maxInterfaceEvents,opticalSamples,scatteringSteps,maximumDistance);
        }
    }
}
