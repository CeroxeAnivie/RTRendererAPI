package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Immutable point-in-time evidence for presentation-time frame generation.
 *
 * <p>This value reports observed runtime facts rather than capability prose. Counters are
 * cumulative only within the current renderer device generation and may restart after device
 * recovery. A caller that needs fresh values must obtain a new {@link RendererDiagnostics}
 * snapshot; retaining this object never retains native resources.</p>
 */
public final class FrameGenerationEvidence {
    private static final FrameGenerationEvidence UNAVAILABLE = new Builder().build();

    private final boolean reported;
    private final int requestedGeneratedFramesPerNativeFrame;
    private final int lastSubmittedGeneratedFramesPerNativeFrame;
    private final int configuredGeneratedFramesPerNativeFrame;
    private final long proxyPresentCalls;
    private final long stateSamples;
    private final long stateQueryCalls;
    private final long totalFramesActuallyPresented;
    private final long generatedFramesActuallyPresented;
    private final int lastFramesActuallyPresented;
    private final int maximumSupportedGeneratedFramesPerNativeFrame;
    private final long stateQueryFailures;
    private final long generationRequestMisses;
    private final int maximumGeneratedFramesObservedPerSample;
    private final OptionalInt latestNativeStatus;
    private final OptionalLong firstProxyPresentSequence;
    private final OptionalLong lastProxyPresentSequence;
    private final OptionalLong lastGeneratedObservationSequence;
    private final long resetEpoch;

    private FrameGenerationEvidence(Builder builder) {
        reported = builder.reported;
        requestedGeneratedFramesPerNativeFrame = builder.requestedGeneratedFramesPerNativeFrame;
        lastSubmittedGeneratedFramesPerNativeFrame =
                builder.lastSubmittedGeneratedFramesPerNativeFrame;
        configuredGeneratedFramesPerNativeFrame = builder.configuredGeneratedFramesPerNativeFrame;
        proxyPresentCalls = builder.proxyPresentCalls;
        stateSamples = builder.stateSamples;
        stateQueryCalls = builder.stateQueryCalls;
        totalFramesActuallyPresented = builder.totalFramesActuallyPresented;
        generatedFramesActuallyPresented = builder.generatedFramesActuallyPresented;
        lastFramesActuallyPresented = builder.lastFramesActuallyPresented;
        maximumSupportedGeneratedFramesPerNativeFrame =
                builder.maximumSupportedGeneratedFramesPerNativeFrame;
        stateQueryFailures = builder.stateQueryFailures;
        generationRequestMisses = builder.generationRequestMisses;
        maximumGeneratedFramesObservedPerSample =
                builder.maximumGeneratedFramesObservedPerSample;
        latestNativeStatus = Objects.requireNonNull(builder.latestNativeStatus, "latestNativeStatus");
        firstProxyPresentSequence = optionalSequence(
                builder.firstProxyPresentSequence, "firstProxyPresentSequence"
        );
        lastProxyPresentSequence = optionalSequence(
                builder.lastProxyPresentSequence, "lastProxyPresentSequence"
        );
        lastGeneratedObservationSequence = optionalSequence(
                builder.lastGeneratedObservationSequence, "lastGeneratedObservationSequence"
        );
        resetEpoch = builder.resetEpoch;
        validate();
    }

    /**
     * Returns the canonical snapshot when no frame-generation provider reports evidence.
     *
     * @return the immutable unreported snapshot
     */
    public static FrameGenerationEvidence unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Starts a single-thread-confined semantic builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder initialized from this snapshot.
     *
     * @return a builder containing this snapshot's values
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns whether a provider supplied this evidence for the current device generation.
     *
     * @return {@code true} when the snapshot is provider-owned
     */
    public boolean reported() {
        return reported;
    }

    /**
     * Returns the policy-requested number of generated frames per native frame interval.
     *
     * @return the requested generated-frame count
     */
    public int requestedGeneratedFramesPerNativeFrame() {
        return requestedGeneratedFramesPerNativeFrame;
    }

    /**
     * Returns the generated-frame count carried by the latest proxy-present request.
     *
     * @return the latest submitted generated-frame count
     */
    public int lastSubmittedGeneratedFramesPerNativeFrame() {
        return lastSubmittedGeneratedFramesPerNativeFrame;
    }

    /**
     * Returns the generated-frame count accepted by the latest successful runtime configuration.
     *
     * @return the configured generated-frame count
     */
    public int configuredGeneratedFramesPerNativeFrame() {
        return configuredGeneratedFramesPerNativeFrame;
    }

    /**
     * Returns application-rendered presents routed through the frame-generation proxy.
     *
     * @return the cumulative proxy-present count
     */
    public long proxyPresentCalls() {
        return proxyPresentCalls;
    }

    /**
     * Returns non-empty authoritative runtime delivery samples.
     *
     * @return the cumulative non-empty sample count
     */
    public long stateSamples() {
        return stateSamples;
    }

    /**
     * Returns all authoritative runtime state-query attempts, including failed attempts.
     *
     * @return the cumulative state-query attempt count
     */
    public long stateQueryCalls() {
        return stateQueryCalls;
    }

    /**
     * Returns all frames the runtime authoritatively reports as actually presented.
     *
     * @return the cumulative total presented-frame count
     */
    public long totalFramesActuallyPresented() {
        return totalFramesActuallyPresented;
    }

    /**
     * Returns actually presented frames beyond the application-rendered frames.
     *
     * @return the cumulative generated presented-frame count
     */
    public long generatedFramesActuallyPresented() {
        return generatedFramesActuallyPresented;
    }

    /**
     * Returns total frames reported by the latest non-empty delivery sample.
     *
     * @return the latest sample's total frame count
     */
    public int lastFramesActuallyPresented() {
        return lastFramesActuallyPresented;
    }

    /**
     * Returns the runtime-advertised upper bound on generated frames per native interval.
     *
     * @return the maximum supported generated-frame count
     */
    public int maximumSupportedGeneratedFramesPerNativeFrame() {
        return maximumSupportedGeneratedFramesPerNativeFrame;
    }

    /**
     * Returns failed authoritative state-query attempts.
     *
     * @return the cumulative state-query failure count
     */
    public long stateQueryFailures() {
        return stateQueryFailures;
    }

    /**
     * Returns proxy presents that could not match a valid generation request.
     *
     * @return the cumulative generation-request miss count
     */
    public long generationRequestMisses() {
        return generationRequestMisses;
    }

    /**
     * Returns the largest generated-frame batch observed in one authoritative sample.
     *
     * @return the largest observed generated-frame batch
     */
    public int maximumGeneratedFramesObservedPerSample() {
        return maximumGeneratedFramesObservedPerSample;
    }

    /**
     * Returns the provider-native runtime status from the latest state observation.
     *
     * @return the latest status, or empty before a successful query
     */
    public OptionalInt latestNativeStatus() {
        return latestNativeStatus;
    }

    /**
     * Returns the first renderer frame sequence routed through the proxy.
     *
     * @return first proxy-present sequence, or empty before the first proxy present
     */
    public OptionalLong firstProxyPresentSequence() {
        return firstProxyPresentSequence;
    }

    /**
     * Returns the latest renderer frame sequence routed through the proxy.
     *
     * @return latest proxy-present sequence, or empty before the first proxy present
     */
    public OptionalLong lastProxyPresentSequence() {
        return lastProxyPresentSequence;
    }

    /**
     * Returns the renderer sequence whose state query most recently observed generated output.
     * The asynchronous pacer may report output from an earlier present, so this is an observation
     * identity and deliberately does not claim producer-frame ownership.
     *
     * @return latest generated-output observation sequence, or empty before generated output
     */
    public OptionalLong lastGeneratedObservationSequence() {
        return lastGeneratedObservationSequence;
    }

    /**
     * Returns the provider evidence reset epoch.
     *
     * @return monotonically increasing provider reset generation
     */
    public long resetEpoch() {
        return resetEpoch;
    }

    /**
     * Returns the requested total presentation cadence, where native-only is {@code 1x}.
     *
     * @return the requested presentation multiplier
     */
    public int requestedPresentationMultiplier() {
        return Math.addExact(requestedGeneratedFramesPerNativeFrame, 1);
    }

    /**
     * Returns the configured total presentation cadence, where native-only is {@code 1x}.
     *
     * @return the configured presentation multiplier
     */
    public int configuredPresentationMultiplier() {
        return Math.addExact(configuredGeneratedFramesPerNativeFrame, 1);
    }

    /**
     * Returns the cumulative effective cadence proven by authoritative generated-frame delivery.
     * The value is {@code 1.0} before the first proxy present.
     *
     * @return the observed presentation multiplier
     */
    public double effectivePresentationMultiplier() {
        return proxyPresentCalls == 0L
                ? 1.0
                : 1.0 + (double) generatedFramesActuallyPresented / proxyPresentCalls;
    }

    /**
     * Returns whether authoritative evidence proves at least one generated frame was presented and
     * the latest runtime state query succeeded.
     *
     * @return {@code true} only after successful runtime status and generated output are observed
     */
    public boolean active() {
        return reported && latestNativeStatus.isPresent() && latestNativeStatus.orElseThrow() == 0
                && generatedFramesActuallyPresented > 0L;
    }

    private void validate() {
        if (requestedGeneratedFramesPerNativeFrame < 0
                || lastSubmittedGeneratedFramesPerNativeFrame < 0
                || configuredGeneratedFramesPerNativeFrame < 0
                || proxyPresentCalls < 0L || stateSamples < 0L || stateQueryCalls < 0L
                || totalFramesActuallyPresented < 0L || generatedFramesActuallyPresented < 0L
                || lastFramesActuallyPresented < 0
                || maximumSupportedGeneratedFramesPerNativeFrame < 0
                || stateQueryFailures < 0L || generationRequestMisses < 0L
                || maximumGeneratedFramesObservedPerSample < 0
                || resetEpoch < 0L
                || latestNativeStatus.isPresent() && latestNativeStatus.orElseThrow() < 0) {
            throw new IllegalArgumentException("frame-generation evidence values must not be negative");
        }
        if (configuredGeneratedFramesPerNativeFrame > requestedGeneratedFramesPerNativeFrame) {
            throw new IllegalArgumentException("configured generation cadence exceeds the requested cadence");
        }
        if (generatedFramesActuallyPresented > totalFramesActuallyPresented) {
            throw new IllegalArgumentException("generated frames must not exceed total actually presented frames");
        }
        if (stateSamples > stateQueryCalls || stateQueryFailures > stateQueryCalls) {
            throw new IllegalArgumentException("frame-generation state-query counters are inconsistent");
        }
        if (generationRequestMisses > proxyPresentCalls) {
            throw new IllegalArgumentException("generation request misses must not exceed proxy presents");
        }
        if (stateSamples == 0L && (totalFramesActuallyPresented != 0L
                || generatedFramesActuallyPresented != 0L
                || lastFramesActuallyPresented != 0
                || maximumGeneratedFramesObservedPerSample != 0)) {
            throw new IllegalArgumentException("actual delivery evidence requires a state sample");
        }
        if (maximumGeneratedFramesObservedPerSample > generatedFramesActuallyPresented) {
            throw new IllegalArgumentException("sample maximum exceeds cumulative generated frames");
        }
        if (stateQueryCalls == stateQueryFailures && latestNativeStatus.isPresent()) {
            throw new IllegalArgumentException("native status requires a successful state query");
        }
        if ((proxyPresentCalls > 0L) != firstProxyPresentSequence.isPresent()
                || firstProxyPresentSequence.isPresent() != lastProxyPresentSequence.isPresent()) {
            throw new IllegalArgumentException(
                    "proxy-present count requires a complete frame-sequence range"
            );
        }
        if (firstProxyPresentSequence.isPresent()
                && firstProxyPresentSequence.getAsLong() > lastProxyPresentSequence.getAsLong()) {
            throw new IllegalArgumentException("proxy-present sequence range is inverted");
        }
        if ((generatedFramesActuallyPresented > 0L) != lastGeneratedObservationSequence.isPresent()) {
            throw new IllegalArgumentException(
                    "generated output count requires its latest frame sequence"
            );
        }
        if (lastGeneratedObservationSequence.isPresent() && firstProxyPresentSequence.isEmpty()) {
            throw new IllegalArgumentException(
                    "generated output observation requires a proxy-present sequence range"
            );
        }
        if (lastGeneratedObservationSequence.isPresent()
                && (lastGeneratedObservationSequence.getAsLong() < firstProxyPresentSequence.orElseThrow()
                || lastGeneratedObservationSequence.getAsLong() > lastProxyPresentSequence.orElseThrow())) {
            throw new IllegalArgumentException(
                    "generated output sequence lies outside the proxy-present range"
            );
        }
        if (!reported && (requestedGeneratedFramesPerNativeFrame != 0
                || lastSubmittedGeneratedFramesPerNativeFrame != 0
                || configuredGeneratedFramesPerNativeFrame != 0
                || proxyPresentCalls != 0L || stateSamples != 0L || stateQueryCalls != 0L
                || totalFramesActuallyPresented != 0L || generatedFramesActuallyPresented != 0L
                || lastFramesActuallyPresented != 0
                || maximumSupportedGeneratedFramesPerNativeFrame != 0
                || stateQueryFailures != 0L || generationRequestMisses != 0L
                || maximumGeneratedFramesObservedPerSample != 0 || latestNativeStatus.isPresent()
                || firstProxyPresentSequence.isPresent() || lastProxyPresentSequence.isPresent()
                || lastGeneratedObservationSequence.isPresent() || resetEpoch != 0L)) {
            throw new IllegalArgumentException("unreported frame-generation evidence must be empty");
        }
    }

    private static OptionalLong optionalSequence(Long value, String name) {
        if (value == null) return OptionalLong.empty();
        if (value < 0L) throw new IllegalArgumentException(name + " must not be negative");
        return OptionalLong.of(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FrameGenerationEvidence evidence)) return false;
        return reported == evidence.reported
                && requestedGeneratedFramesPerNativeFrame
                == evidence.requestedGeneratedFramesPerNativeFrame
                && lastSubmittedGeneratedFramesPerNativeFrame
                == evidence.lastSubmittedGeneratedFramesPerNativeFrame
                && configuredGeneratedFramesPerNativeFrame
                == evidence.configuredGeneratedFramesPerNativeFrame
                && proxyPresentCalls == evidence.proxyPresentCalls
                && stateSamples == evidence.stateSamples
                && stateQueryCalls == evidence.stateQueryCalls
                && totalFramesActuallyPresented == evidence.totalFramesActuallyPresented
                && generatedFramesActuallyPresented == evidence.generatedFramesActuallyPresented
                && lastFramesActuallyPresented == evidence.lastFramesActuallyPresented
                && maximumSupportedGeneratedFramesPerNativeFrame
                == evidence.maximumSupportedGeneratedFramesPerNativeFrame
                && stateQueryFailures == evidence.stateQueryFailures
                && generationRequestMisses == evidence.generationRequestMisses
                && maximumGeneratedFramesObservedPerSample
                == evidence.maximumGeneratedFramesObservedPerSample
                && resetEpoch == evidence.resetEpoch
                && latestNativeStatus.equals(evidence.latestNativeStatus)
                && firstProxyPresentSequence.equals(evidence.firstProxyPresentSequence)
                && lastProxyPresentSequence.equals(evidence.lastProxyPresentSequence)
                && lastGeneratedObservationSequence.equals(evidence.lastGeneratedObservationSequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reported, requestedGeneratedFramesPerNativeFrame,
                lastSubmittedGeneratedFramesPerNativeFrame,
                configuredGeneratedFramesPerNativeFrame, proxyPresentCalls, stateSamples,
                stateQueryCalls,
                totalFramesActuallyPresented,
                generatedFramesActuallyPresented, lastFramesActuallyPresented,
                maximumSupportedGeneratedFramesPerNativeFrame,
                stateQueryFailures, generationRequestMisses,
                maximumGeneratedFramesObservedPerSample, latestNativeStatus,
                firstProxyPresentSequence, lastProxyPresentSequence,
                lastGeneratedObservationSequence, resetEpoch);
    }

    @Override
    public String toString() {
        return "FrameGenerationEvidence[reported=" + reported
                + ", requestedGeneratedFramesPerNativeFrame="
                + requestedGeneratedFramesPerNativeFrame
                + ", lastSubmittedGeneratedFramesPerNativeFrame="
                + lastSubmittedGeneratedFramesPerNativeFrame
                + ", configuredGeneratedFramesPerNativeFrame="
                + configuredGeneratedFramesPerNativeFrame
                + ", proxyPresentCalls=" + proxyPresentCalls
                + ", stateSamples=" + stateSamples
                + ", stateQueryCalls=" + stateQueryCalls
                + ", totalFramesActuallyPresented=" + totalFramesActuallyPresented
                + ", generatedFramesActuallyPresented=" + generatedFramesActuallyPresented
                + ", lastFramesActuallyPresented=" + lastFramesActuallyPresented
                + ", maximumSupportedGeneratedFramesPerNativeFrame="
                + maximumSupportedGeneratedFramesPerNativeFrame
                + ", stateQueryFailures=" + stateQueryFailures
                + ", generationRequestMisses=" + generationRequestMisses
                + ", maximumGeneratedFramesObservedPerSample="
                + maximumGeneratedFramesObservedPerSample
                + ", latestNativeStatus=" + latestNativeStatus
                + ", firstProxyPresentSequence=" + firstProxyPresentSequence
                + ", lastProxyPresentSequence=" + lastProxyPresentSequence
                + ", lastGeneratedObservationSequence=" + lastGeneratedObservationSequence
                + ", resetEpoch=" + resetEpoch + ']';
    }

    /** Single-thread-confined semantic builder for one evidence snapshot. */
    public static final class Builder {
        private boolean reported;
        private int requestedGeneratedFramesPerNativeFrame;
        private int lastSubmittedGeneratedFramesPerNativeFrame;
        private int configuredGeneratedFramesPerNativeFrame;
        private long proxyPresentCalls;
        private long stateSamples;
        private long stateQueryCalls;
        private long totalFramesActuallyPresented;
        private long generatedFramesActuallyPresented;
        private int lastFramesActuallyPresented;
        private int maximumSupportedGeneratedFramesPerNativeFrame;
        private long stateQueryFailures;
        private long generationRequestMisses;
        private int maximumGeneratedFramesObservedPerSample;
        private OptionalInt latestNativeStatus = OptionalInt.empty();
        private Long firstProxyPresentSequence;
        private Long lastProxyPresentSequence;
        private Long lastGeneratedObservationSequence;
        private long resetEpoch;

        private Builder() {
        }

        private Builder(FrameGenerationEvidence source) {
            reported = source.reported;
            requestedGeneratedFramesPerNativeFrame = source.requestedGeneratedFramesPerNativeFrame;
            lastSubmittedGeneratedFramesPerNativeFrame =
                    source.lastSubmittedGeneratedFramesPerNativeFrame;
            configuredGeneratedFramesPerNativeFrame = source.configuredGeneratedFramesPerNativeFrame;
            proxyPresentCalls = source.proxyPresentCalls;
            stateSamples = source.stateSamples;
            stateQueryCalls = source.stateQueryCalls;
            totalFramesActuallyPresented = source.totalFramesActuallyPresented;
            generatedFramesActuallyPresented = source.generatedFramesActuallyPresented;
            lastFramesActuallyPresented = source.lastFramesActuallyPresented;
            maximumSupportedGeneratedFramesPerNativeFrame =
                    source.maximumSupportedGeneratedFramesPerNativeFrame;
            stateQueryFailures = source.stateQueryFailures;
            generationRequestMisses = source.generationRequestMisses;
            maximumGeneratedFramesObservedPerSample =
                    source.maximumGeneratedFramesObservedPerSample;
            latestNativeStatus = source.latestNativeStatus;
            firstProxyPresentSequence = source.firstProxyPresentSequence.isPresent()
                    ? source.firstProxyPresentSequence.getAsLong() : null;
            lastProxyPresentSequence = source.lastProxyPresentSequence.isPresent()
                    ? source.lastProxyPresentSequence.getAsLong() : null;
            lastGeneratedObservationSequence = source.lastGeneratedObservationSequence.isPresent()
                    ? source.lastGeneratedObservationSequence.getAsLong() : null;
            resetEpoch = source.resetEpoch;
        }

        /**
         * Selects whether a provider owns this snapshot.
         *
         * @param value whether a provider reported evidence
         * @return this builder
         */
        public Builder reported(boolean value) {
            reported = value;
            return this;
        }

        /**
         * Selects the policy-requested extra frames per native interval.
         *
         * @param value the requested generated-frame count
         * @return this builder
         */
        public Builder requestedGeneratedFramesPerNativeFrame(int value) {
            requestedGeneratedFramesPerNativeFrame = value;
            return this;
        }

        /**
         * Selects the extra-frame count in the latest proxy-present request.
         *
         * @param value the latest submitted generated-frame count
         * @return this builder
         */
        public Builder lastSubmittedGeneratedFramesPerNativeFrame(int value) {
            lastSubmittedGeneratedFramesPerNativeFrame = value;
            return this;
        }

        /**
         * Selects the currently configured extra frames per native interval.
         *
         * @param value the configured generated-frame count
         * @return this builder
         */
        public Builder configuredGeneratedFramesPerNativeFrame(int value) {
            configuredGeneratedFramesPerNativeFrame = value;
            return this;
        }

        /**
         * Selects the cumulative proxy-present call count.
         *
         * @param value the proxy-present count
         * @return this builder
         */
        public Builder proxyPresentCalls(long value) {
            proxyPresentCalls = value;
            return this;
        }

        /**
         * Selects the count of non-empty authoritative delivery samples.
         *
         * @param value the non-empty sample count
         * @return this builder
         */
        public Builder stateSamples(long value) {
            stateSamples = value;
            return this;
        }

        /**
         * Selects the cumulative authoritative state-query attempt count.
         *
         * @param value the state-query attempt count
         * @return this builder
         */
        public Builder stateQueryCalls(long value) {
            stateQueryCalls = value;
            return this;
        }

        /**
         * Selects the cumulative backend-confirmed total presented-frame count.
         *
         * @param value the total presented-frame count
         * @return this builder
         */
        public Builder totalFramesActuallyPresented(long value) {
            totalFramesActuallyPresented = value;
            return this;
        }

        /**
         * Selects the cumulative backend-confirmed generated-frame count.
         *
         * @param value the generated presented-frame count
         * @return this builder
         */
        public Builder generatedFramesActuallyPresented(long value) {
            generatedFramesActuallyPresented = value;
            return this;
        }

        /**
         * Selects the total-frame count from the latest non-empty delivery sample.
         *
         * @param value the latest sample's total frame count
         * @return this builder
         */
        public Builder lastFramesActuallyPresented(int value) {
            lastFramesActuallyPresented = value;
            return this;
        }

        /**
         * Selects the backend-advertised generated-frame upper bound.
         *
         * @param value the maximum supported generated-frame count
         * @return this builder
         */
        public Builder maximumSupportedGeneratedFramesPerNativeFrame(int value) {
            maximumSupportedGeneratedFramesPerNativeFrame = value;
            return this;
        }

        /**
         * Selects the cumulative failed state-query count.
         *
         * @param value the state-query failure count
         * @return this builder
         */
        public Builder stateQueryFailures(long value) {
            stateQueryFailures = value;
            return this;
        }

        /**
         * Selects generation requests converted to native-only presents by contract misses.
         *
         * @param value the generation-request miss count
         * @return this builder
         */
        public Builder generationRequestMisses(long value) {
            generationRequestMisses = value;
            return this;
        }

        /**
         * Selects the largest generated-frame count in one authoritative sample.
         *
         * @param value the largest observed generated-frame batch
         * @return this builder
         */
        public Builder maximumGeneratedFramesObservedPerSample(int value) {
            maximumGeneratedFramesObservedPerSample = value;
            return this;
        }

        /**
         * Selects an opaque latest provider-native status, or empty before a successful query.
         *
         * @param value the latest native status
         * @return this builder
         */
        public Builder latestNativeStatus(OptionalInt value) {
            latestNativeStatus = Objects.requireNonNull(value, "latestNativeStatus");
            return this;
        }

        /**
         * Selects the inclusive renderer sequence range routed through the proxy.
         *
         * @param first first proxy-present sequence
         * @param last latest proxy-present sequence
         * @return this builder
         */
        public Builder proxyPresentSequenceRange(long first, long last) {
            firstProxyPresentSequence = first;
            lastProxyPresentSequence = last;
            return this;
        }

        /**
         * Clears the proxy-present renderer sequence range.
         *
         * @return this builder
         */
        public Builder clearProxyPresentSequenceRange() {
            firstProxyPresentSequence = null;
            lastProxyPresentSequence = null;
            return this;
        }

        /**
         * Selects the renderer sequence whose state query observed generated output.
         *
         * @param value observation sequence
         * @return this builder
         */
        public Builder lastGeneratedObservationSequence(long value) {
            lastGeneratedObservationSequence = value;
            return this;
        }

        /**
         * Clears the latest generated-output observation sequence.
         *
         * @return this builder
         */
        public Builder clearLastGeneratedObservationSequence() {
            lastGeneratedObservationSequence = null;
            return this;
        }

        /**
         * Selects the provider reset epoch for these cumulative counters.
         *
         * @param value non-negative reset generation
         * @return this builder
         */
        public Builder resetEpoch(long value) {
            resetEpoch = value;
            return this;
        }

        /**
         * Validates and returns an independent immutable snapshot.
         *
         * @return the validated snapshot
         */
        public FrameGenerationEvidence build() {
            return new FrameGenerationEvidence(this);
        }
    }
}
