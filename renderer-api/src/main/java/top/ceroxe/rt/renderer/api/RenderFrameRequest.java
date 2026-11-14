package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable render request assembled through a stable semantic builder.
 */
public final class RenderFrameRequest {
    private final long sequence;
    private final long minimumSceneRevision;
    private final FrameClock clock;
    private final int width;
    private final int height;
    private final CameraState camera;
    private final EnvironmentState environment;
    private final DistanceFogState fog;
    private final FramePrimitiveBatch primitiveBatch;
    private final Set<HistoryResetReason> temporalHistoryResets;

    private RenderFrameRequest(Builder builder) {
        if (builder.sequence < 0L || builder.minimumSceneRevision < 0L) {
            throw new IllegalArgumentException("frame and scene revisions must not be negative");
        }
        if (builder.width <= 0 || builder.height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        long pixels = (long) builder.width * builder.height;
        if (pixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame extent exceeds supported pixel address space");
        }
        sequence = builder.sequence;
        minimumSceneRevision = builder.minimumSceneRevision;
        clock = Objects.requireNonNull(builder.clock, "clock");
        width = builder.width;
        height = builder.height;
        camera = Objects.requireNonNull(builder.camera, "camera");
        environment = Objects.requireNonNull(builder.environment, "environment");
        fog = Objects.requireNonNull(builder.fog, "fog");
        primitiveBatch = Objects.requireNonNull(builder.primitiveBatch, "primitiveBatch");
        temporalHistoryResets = immutableEnumSet(builder.temporalHistoryResets);
    }

    /**
     * Starts a frame builder with safe production defaults for all optional policies.
     *
     * @param sequence non-negative monotonically increasing frame sequence
     * @param width    positive output width in pixels
     * @param height   positive output height in pixels
     * @param camera   non-null immutable camera state
     * @return new single-thread-confined frame builder
     */
    public static Builder builder(long sequence, int width, int height, CameraState camera) {
        return new Builder(sequence, width, height, camera);
    }

    private static Set<HistoryResetReason> immutableEnumSet(Set<HistoryResetReason> source) {
        if (source.isEmpty()) return Set.of();
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }

    /**
     * Starts an independent builder initialized from this complete request.
     *
     * @return new builder containing every current request policy
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the exact frame sequence.
     *
     * @return non-negative frame sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns the minimum accepted scene revision allowed for this frame.
     *
     * @return non-negative minimum scene revision
     */
    public long minimumSceneRevision() {
        return minimumSceneRevision;
    }

    /**
     * Returns the application timeline copied into the standard GPU frame ABI.
     * @return immutable timeline, zero unless explicitly supplied
     */
    public FrameClock clock() {
        return clock;
    }

    /**
     * Returns the output width.
     *
     * @return positive width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the output height.
     *
     * @return positive height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the immutable camera state.
     *
     * @return non-null camera state
     */
    public CameraState camera() {
        return camera;
    }

    /**
     * Returns the immutable environment state.
     *
     * @return non-null environment state
     */
    public EnvironmentState environment() {
        return environment;
    }





    /**
     * Returns the distance-fog policy.
     *
     * @return non-null fog state
     */
    public DistanceFogState fog() {
        return fog;
    }







    /**
     * Returns frame-replaced primitive instances referencing persistent mesh assets.
     *
     * @return immutable, possibly empty primitive batch
     */
    public FramePrimitiveBatch primitiveBatch() {
        return primitiveBatch;
    }

    /**
     * Returns an immutable, deduplicated set of caller-known temporal discontinuities.
     *
     * @return immutable, possibly empty reset-reason set
     */
    public Set<HistoryResetReason> temporalHistoryResets() {
        return temporalHistoryResets;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RenderFrameRequest request)) return false;
        return sequence == request.sequence
                && minimumSceneRevision == request.minimumSceneRevision
                && width == request.width
                && height == request.height
                && clock.equals(request.clock)
                && camera.equals(request.camera)
                && environment.equals(request.environment)
                && fog.equals(request.fog)
                && primitiveBatch.equals(request.primitiveBatch)
                && temporalHistoryResets.equals(request.temporalHistoryResets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sequence, minimumSceneRevision, width, height, clock, camera, environment,
                fog, primitiveBatch,
                temporalHistoryResets
        );
    }

    @Override
    public String toString() {
        return "RenderFrameRequest[sequence=" + sequence
                + ", minimumSceneRevision=" + minimumSceneRevision
                + ", clock=" + clock
                + ", width=" + width
                + ", height=" + height
                + ", camera=" + camera
                + ", environment=" + environment
                + ", fog=" + fog
                + ", primitiveBatch=" + primitiveBatch
                + ", temporalHistoryResets=" + temporalHistoryResets + ']';
    }

    /**
     * Single-thread-confined builder for one immutable frame request.
     */
    public static final class Builder {
        private final long sequence;
        private final int width;
        private final int height;
        private final CameraState camera;
        private final EnumSet<HistoryResetReason> temporalHistoryResets =
                EnumSet.noneOf(HistoryResetReason.class);
        private long minimumSceneRevision;
        private FrameClock clock = FrameClock.of(0, 0);
        private EnvironmentState environment = EnvironmentState.neutral();
        private DistanceFogState fog = DistanceFogState.disabled();
        private FramePrimitiveBatch primitiveBatch = FramePrimitiveBatch.empty();

        private Builder(long sequence, int width, int height, CameraState camera) {
            this.sequence = sequence;
            this.width = width;
            this.height = height;
            this.camera = Objects.requireNonNull(camera, "camera");
        }

        private Builder(RenderFrameRequest source) {
            sequence = source.sequence;
            width = source.width;
            height = source.height;
            camera = source.camera;
            minimumSceneRevision = source.minimumSceneRevision;
            clock = source.clock;
            environment = source.environment;
            fog = source.fog;
            primitiveBatch = source.primitiveBatch;
            temporalHistoryResets.addAll(source.temporalHistoryResets);
        }

        /**
         * Selects the minimum accepted scene revision allowed for this frame.
         *
         * @param value non-negative minimum scene revision
         * @return this builder
         */
        public Builder minimumSceneRevision(long value) {
            minimumSceneRevision = value;
            return this;
        }

        /** Sets the monotonic application timeline copied into the GPU frame ABI. */
        public Builder clock(FrameClock value) {
            clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        /**
         * Selects the immutable environment state.
         *
         * @param value non-null environment state
         * @return this builder
         */
        public Builder environment(EnvironmentState value) {
            environment = Objects.requireNonNull(value, "environment");
            return this;
        }



        /**
         * Selects the distance-fog policy.
         *
         * @param value non-null fog state
         * @return this builder
         */
        public Builder fog(DistanceFogState value) {
            fog = Objects.requireNonNull(value, "fog");
            return this;
        }







        /**
         * Replaces the complete frame-scoped primitive batch.
         *
         * <p>The batch does not mutate persistent scene revision state. Mesh dependencies must
         * already be resident at {@link #minimumSceneRevision(long)}.</p>
         *
         * @param value non-null immutable batch
         * @return this builder
         */
        public Builder primitiveBatch(FramePrimitiveBatch value) {
            primitiveBatch = Objects.requireNonNull(value, "primitiveBatch");
            return this;
        }

        /**
         * Adds one caller-known discontinuity; repeated reasons are deduplicated.
         *
         * @param reason non-null semantic reset reason
         * @return this builder
         */
        public Builder resetTemporalHistory(HistoryResetReason reason) {
            temporalHistoryResets.add(Objects.requireNonNull(reason, "reason"));
            return this;
        }

        /**
         * Removes all caller-requested resets from this builder.
         *
         * @return this builder
         */
        public Builder continueTemporalHistory() {
            temporalHistoryResets.clear();
            return this;
        }

        /**
         * Validates and returns an independent immutable request.
         *
         * @return immutable validated frame request
         */
        public RenderFrameRequest build() {
            return new RenderFrameRequest(this);
        }
    }
}
