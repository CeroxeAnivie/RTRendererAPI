package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable bounded diagnostics with no internal cache or native-resource references.
 *
 * <p>A snapshot may be read from an observation thread and never blocks for GPU completion.
 * Semantic builders are used because adjacent sequence numbers and counters are otherwise easy
 * to exchange without a compiler error.</p>
 */
public final class RendererDiagnostics {
    private final RayTracingRenderer.Status status;
    private final long latestAcceptedSceneRevision;
    private final long latestSubmittedFrameSequence;
    private final long latestCompletedFrameSequence;
    private final long residentMeshes;
    private final long residentInstances;
    private final DeviceRecovery deviceRecovery;
    private final FrameGpuTiming frameGpuTiming;
    private final FrameGenerationEvidence frameGenerationEvidence;
    private final TechnologyExecutionEvidence technologyExecutionEvidence;

    private RendererDiagnostics(Builder builder) {
        status = requireSelected(builder.status, "status");
        latestAcceptedSceneRevision = builder.latestAcceptedSceneRevision;
        latestSubmittedFrameSequence = builder.latestSubmittedFrameSequence;
        latestCompletedFrameSequence = builder.latestCompletedFrameSequence;
        residentMeshes = builder.residentMeshes;
        residentInstances = builder.residentInstances;
        deviceRecovery = requireSelected(builder.deviceRecovery, "deviceRecovery");
        frameGpuTiming = requireSelected(builder.frameGpuTiming, "frameGpuTiming");
        frameGenerationEvidence = requireSelected(
                builder.frameGenerationEvidence, "frameGenerationEvidence"
        );
        technologyExecutionEvidence = requireSelected(
                builder.technologyExecutionEvidence, "technologyExecutionEvidence"
        );
        if (latestAcceptedSceneRevision < 0L || latestSubmittedFrameSequence < -1L
                || latestCompletedFrameSequence < -1L || residentMeshes < 0L || residentInstances < 0L) {
            throw new IllegalArgumentException("renderer diagnostics counters are out of range");
        }
        if (latestCompletedFrameSequence > latestSubmittedFrameSequence) {
            throw new IllegalArgumentException("completed frame sequence must not exceed submitted sequence");
        }
    }

    /**
     * Starts a diagnostics snapshot with empty scene and frame counters.
     *
     * @return new single-thread-confined builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static <T> T requireSelected(T value, String name) {
        if (value == null) throw new IllegalStateException(name + " must be selected before build");
        return value;
    }

    /**
     * Starts an independent builder initialized from this snapshot.
     *
     * @return builder containing every current diagnostics property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns renderer lifecycle state at snapshot time.
     *
     * @return non-null renderer state
     */
    public RayTracingRenderer.Status status() {
        return status;
    }

    /**
     * Returns the latest atomically accepted scene revision.
     *
     * @return non-negative scene revision
     */
    public long latestAcceptedSceneRevision() {
        return latestAcceptedSceneRevision;
    }

    /**
     * Returns the latest accepted frame sequence.
     *
     * @return frame sequence, or {@code -1} before the first submission
     */
    public long latestSubmittedFrameSequence() {
        return latestSubmittedFrameSequence;
    }

    /**
     * Returns the latest GPU-completed frame sequence.
     *
     * @return frame sequence, or {@code -1} before the first completion
     */
    public long latestCompletedFrameSequence() {
        return latestCompletedFrameSequence;
    }

    /**
     * Returns the number of resident logical meshes.
     *
     * @return non-negative mesh count
     */
    public long residentMeshes() {
        return residentMeshes;
    }

    /**
     * Returns the number of resident logical instances.
     *
     * @return non-negative instance count
     */
    public long residentInstances() {
        return residentInstances;
    }

    /**
     * Returns bounded device-recovery evidence.
     *
     * @return immutable recovery counters
     */
    public DeviceRecovery deviceRecovery() {
        return deviceRecovery;
    }

    /**
     * Returns aggregate completed-frame GPU timing.
     *
     * @return immutable timing snapshot
     */
    public FrameGpuTiming frameGpuTiming() {
        return frameGpuTiming;
    }

    /**
     * Returns authoritative presentation-time frame-generation evidence.
     *
     * @return immutable evidence, or {@link FrameGenerationEvidence#unavailable()} when no
     * provider reports frame-generation runtime facts
     */
    public FrameGenerationEvidence frameGenerationEvidence() {
        return frameGenerationEvidence;
    }

    /**
     * Returns structured execution evidence for every concrete rendering technology.
     *
     * @return immutable complete technology evidence snapshot
     */
    public TechnologyExecutionEvidence technologyExecutionEvidence() {
        return technologyExecutionEvidence;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RendererDiagnostics diagnostics)) return false;
        return latestAcceptedSceneRevision == diagnostics.latestAcceptedSceneRevision
                && latestSubmittedFrameSequence == diagnostics.latestSubmittedFrameSequence
                && latestCompletedFrameSequence == diagnostics.latestCompletedFrameSequence
                && residentMeshes == diagnostics.residentMeshes
                && residentInstances == diagnostics.residentInstances
                && status == diagnostics.status
                && deviceRecovery.equals(diagnostics.deviceRecovery)
                && frameGpuTiming.equals(diagnostics.frameGpuTiming)
                && frameGenerationEvidence.equals(diagnostics.frameGenerationEvidence)
                && technologyExecutionEvidence.equals(diagnostics.technologyExecutionEvidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, latestAcceptedSceneRevision, latestSubmittedFrameSequence,
                latestCompletedFrameSequence, residentMeshes, residentInstances, deviceRecovery,
                frameGpuTiming, frameGenerationEvidence, technologyExecutionEvidence);
    }

    @Override
    public String toString() {
        return "RendererDiagnostics[status=" + status
                + ", latestAcceptedSceneRevision=" + latestAcceptedSceneRevision
                + ", latestSubmittedFrameSequence=" + latestSubmittedFrameSequence
                + ", latestCompletedFrameSequence=" + latestCompletedFrameSequence
                + ", residentMeshes=" + residentMeshes
                + ", residentInstances=" + residentInstances
                + ", deviceRecovery=" + deviceRecovery
                + ", frameGpuTiming=" + frameGpuTiming
                + ", frameGenerationEvidence=" + frameGenerationEvidence
                + ", technologyExecutionEvidence=" + technologyExecutionEvidence + ']';
    }

    /**
     * Single-thread-confined semantic builder for one diagnostics snapshot.
     */
    public static final class Builder {
        private RayTracingRenderer.Status status;
        private long latestAcceptedSceneRevision;
        private long latestSubmittedFrameSequence = -1L;
        private long latestCompletedFrameSequence = -1L;
        private long residentMeshes;
        private long residentInstances;
        private DeviceRecovery deviceRecovery = DeviceRecovery.initial();
        private FrameGpuTiming frameGpuTiming = FrameGpuTiming.unavailable();
        private FrameGenerationEvidence frameGenerationEvidence = FrameGenerationEvidence.unavailable();
        private TechnologyExecutionEvidence technologyExecutionEvidence =
                TechnologyExecutionEvidence.disabled();

        private Builder() {
        }

        private Builder(RendererDiagnostics source) {
            status = source.status;
            latestAcceptedSceneRevision = source.latestAcceptedSceneRevision;
            latestSubmittedFrameSequence = source.latestSubmittedFrameSequence;
            latestCompletedFrameSequence = source.latestCompletedFrameSequence;
            residentMeshes = source.residentMeshes;
            residentInstances = source.residentInstances;
            deviceRecovery = source.deviceRecovery;
            frameGpuTiming = source.frameGpuTiming;
            frameGenerationEvidence = source.frameGenerationEvidence;
            technologyExecutionEvidence = source.technologyExecutionEvidence;
        }

        /**
         * Selects renderer lifecycle state.
         *
         * @param value non-null renderer state
         * @return this builder
         */
        public Builder status(RayTracingRenderer.Status value) {
            status = Objects.requireNonNull(value, "status");
            return this;
        }

        /**
         * Selects the latest accepted scene revision.
         *
         * @param value non-negative revision
         * @return this builder
         */
        public Builder latestAcceptedSceneRevision(long value) {
            latestAcceptedSceneRevision = value;
            return this;
        }

        /**
         * Selects the latest accepted frame sequence.
         *
         * @param value sequence, or {@code -1} before the first submission
         * @return this builder
         */
        public Builder latestSubmittedFrameSequence(long value) {
            latestSubmittedFrameSequence = value;
            return this;
        }

        /**
         * Selects the latest GPU-completed frame sequence.
         *
         * @param value sequence, or {@code -1} before the first completion
         * @return this builder
         */
        public Builder latestCompletedFrameSequence(long value) {
            latestCompletedFrameSequence = value;
            return this;
        }

        /**
         * Selects the resident logical mesh count.
         *
         * @param value non-negative mesh count
         * @return this builder
         */
        public Builder residentMeshes(long value) {
            residentMeshes = value;
            return this;
        }

        /**
         * Selects the resident logical instance count.
         *
         * @param value non-negative instance count
         * @return this builder
         */
        public Builder residentInstances(long value) {
            residentInstances = value;
            return this;
        }

        /**
         * Selects device-recovery evidence.
         *
         * @param value non-null immutable counters
         * @return this builder
         */
        public Builder deviceRecovery(DeviceRecovery value) {
            deviceRecovery = Objects.requireNonNull(value, "deviceRecovery");
            return this;
        }

        /**
         * Selects completed-frame GPU timing evidence.
         *
         * @param value non-null immutable timing snapshot
         * @return this builder
         */
        public Builder frameGpuTiming(FrameGpuTiming value) {
            frameGpuTiming = Objects.requireNonNull(value, "frameGpuTiming");
            return this;
        }

        /**
         * Selects authoritative frame-generation runtime evidence.
         *
         * @param value non-null immutable evidence
         * @return this builder
         */
        public Builder frameGenerationEvidence(FrameGenerationEvidence value) {
            frameGenerationEvidence = Objects.requireNonNull(value, "frameGenerationEvidence");
            return this;
        }

        /**
         * Selects the complete structured execution evidence snapshot.
         *
         * @param value non-null immutable technology evidence
         * @return this builder
         */
        public Builder technologyExecutionEvidence(TechnologyExecutionEvidence value) {
            technologyExecutionEvidence = Objects.requireNonNull(value, "technologyExecutionEvidence");
            return this;
        }

        /**
         * Validates and returns an independent immutable diagnostics snapshot.
         *
         * @return validated diagnostics snapshot
         */
        public RendererDiagnostics build() {
            return new RendererDiagnostics(this);
        }
    }

    /**
     * Bounded device recreation evidence. Generation {@code 0} is the initial logical device;
     * each successful recreation advances it exactly once.
     *
     * @param generation number of successful device recreations
     * @param attempts   total bounded recreation attempts
     * @param failures   attempts that failed before a replacement became ready
     */
    public record DeviceRecovery(long generation, long attempts, long failures) {
        /**
         * Validates internally consistent monotonic recovery counters.
         */
        public DeviceRecovery {
            if (generation < 0L || attempts < 0L || failures < 0L) {
                throw new IllegalArgumentException("device recovery counters must not be negative");
            }
            if (attempts != generation + failures) {
                throw new IllegalArgumentException(
                        "device recovery attempts must equal successful generations plus failures"
                );
            }
        }

        /**
         * Returns the initial-device state before any recreation attempt.
         *
         * @return immutable zero-valued recovery evidence
         */
        public static DeviceRecovery initial() {
            return new DeviceRecovery(0L, 0L, 0L);
        }
    }

    /**
     * Bounded aggregate of completed GPU timing samples.
     */
    public static final class FrameGpuTiming {
        private static final FrameGpuTiming UNAVAILABLE = new Builder().build();

        private final boolean enabled;
        private final long completedSamples;
        private final long droppedSamples;
        private final long failedSamples;
        private final long averageTraceNanos;
        private final long averagePostTraceNanos;
        private final long averageTotalNanos;
        private final long maxTotalNanos;

        private FrameGpuTiming(Builder builder) {
            enabled = builder.enabled;
            completedSamples = builder.completedSamples;
            droppedSamples = builder.droppedSamples;
            failedSamples = builder.failedSamples;
            averageTraceNanos = builder.averageTraceNanos;
            averagePostTraceNanos = builder.averagePostTraceNanos;
            averageTotalNanos = builder.averageTotalNanos;
            maxTotalNanos = builder.maxTotalNanos;
            validate();
        }

        /**
         * Starts an empty timing aggregate.
         *
         * @return new single-thread-confined builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns the canonical snapshot for disabled timing collection.
         *
         * @return immutable zero-valued unavailable snapshot
         */
        public static FrameGpuTiming unavailable() {
            return UNAVAILABLE;
        }

        /**
         * Starts an independent builder initialized from this timing snapshot.
         *
         * @return builder containing every current timing property
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Returns whether timing collection is enabled.
         *
         * @return timing collection state
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * Returns the number of incorporated samples.
         *
         * @return non-negative sample count
         */
        public long completedSamples() {
            return completedSamples;
        }

        /**
         * Returns the number of intentionally omitted samples.
         *
         * @return non-negative sample count
         */
        public long droppedSamples() {
            return droppedSamples;
        }

        /**
         * Returns the number of samples lost to collection failure.
         *
         * @return non-negative sample count
         */
        public long failedSamples() {
            return failedSamples;
        }

        /**
         * Returns average ray-tracing duration.
         *
         * @return non-negative nanoseconds
         */
        public long averageTraceNanos() {
            return averageTraceNanos;
        }

        /**
         * Returns average post-trace duration.
         *
         * @return non-negative nanoseconds
         */
        public long averagePostTraceNanos() {
            return averagePostTraceNanos;
        }

        /**
         * Returns average total GPU duration.
         *
         * @return non-negative nanoseconds
         */
        public long averageTotalNanos() {
            return averageTotalNanos;
        }

        /**
         * Returns maximum observed total GPU duration.
         *
         * @return non-negative nanoseconds
         */
        public long maxTotalNanos() {
            return maxTotalNanos;
        }

        private void validate() {
            if (completedSamples < 0L || droppedSamples < 0L || failedSamples < 0L
                    || averageTraceNanos < 0L || averagePostTraceNanos < 0L
                    || averageTotalNanos < 0L || maxTotalNanos < 0L) {
                throw new IllegalArgumentException("GPU timing values must not be negative");
            }
            long stageSum;
            try {
                stageSum = Math.addExact(averageTraceNanos, averagePostTraceNanos);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("GPU timing stage sum overflowed", overflow);
            }
            if (Math.abs(averageTotalNanos - stageSum) > 2L) {
                throw new IllegalArgumentException("total GPU time must equal trace plus post-trace time");
            }
            if (!enabled && (completedSamples != 0L || droppedSamples != 0L || failedSamples != 0L
                    || averageTraceNanos != 0L || averagePostTraceNanos != 0L
                    || averageTotalNanos != 0L || maxTotalNanos != 0L)) {
                throw new IllegalArgumentException("disabled GPU timing must not publish samples or durations");
            }
            if (completedSamples == 0L && (averageTraceNanos != 0L || averagePostTraceNanos != 0L
                    || averageTotalNanos != 0L || maxTotalNanos != 0L)) {
                throw new IllegalArgumentException("GPU timing without completed samples must have zero durations");
            }
            if (completedSamples > 0L && maxTotalNanos < averageTotalNanos) {
                throw new IllegalArgumentException("maximum GPU duration must not be below the average");
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FrameGpuTiming timing)) return false;
            return enabled == timing.enabled
                    && completedSamples == timing.completedSamples
                    && droppedSamples == timing.droppedSamples
                    && failedSamples == timing.failedSamples
                    && averageTraceNanos == timing.averageTraceNanos
                    && averagePostTraceNanos == timing.averagePostTraceNanos
                    && averageTotalNanos == timing.averageTotalNanos
                    && maxTotalNanos == timing.maxTotalNanos;
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, completedSamples, droppedSamples, failedSamples,
                    averageTraceNanos, averagePostTraceNanos, averageTotalNanos, maxTotalNanos);
        }

        @Override
        public String toString() {
            return "FrameGpuTiming[enabled=" + enabled
                    + ", completedSamples=" + completedSamples
                    + ", droppedSamples=" + droppedSamples
                    + ", failedSamples=" + failedSamples
                    + ", averageTraceNanos=" + averageTraceNanos
                    + ", averagePostTraceNanos=" + averagePostTraceNanos
                    + ", averageTotalNanos=" + averageTotalNanos
                    + ", maxTotalNanos=" + maxTotalNanos + ']';
        }

        /**
         * Single-thread-confined semantic builder for one GPU timing aggregate.
         */
        public static final class Builder {
            private boolean enabled;
            private long completedSamples;
            private long droppedSamples;
            private long failedSamples;
            private long averageTraceNanos;
            private long averagePostTraceNanos;
            private long averageTotalNanos;
            private long maxTotalNanos;

            private Builder() {
            }

            private Builder(FrameGpuTiming source) {
                enabled = source.enabled;
                completedSamples = source.completedSamples;
                droppedSamples = source.droppedSamples;
                failedSamples = source.failedSamples;
                averageTraceNanos = source.averageTraceNanos;
                averagePostTraceNanos = source.averagePostTraceNanos;
                averageTotalNanos = source.averageTotalNanos;
                maxTotalNanos = source.maxTotalNanos;
            }

            /**
             * Selects whether timing collection is enabled.
             *
             * @param value collection state
             * @return this builder
             */
            public Builder enabled(boolean value) {
                enabled = value;
                return this;
            }

            /**
             * Selects the incorporated sample count.
             *
             * @param value non-negative count
             * @return this builder
             */
            public Builder completedSamples(long value) {
                completedSamples = value;
                return this;
            }

            /**
             * Selects the intentionally omitted sample count.
             *
             * @param value non-negative count
             * @return this builder
             */
            public Builder droppedSamples(long value) {
                droppedSamples = value;
                return this;
            }

            /**
             * Selects the failed collection sample count.
             *
             * @param value non-negative count
             * @return this builder
             */
            public Builder failedSamples(long value) {
                failedSamples = value;
                return this;
            }

            /**
             * Selects average ray-tracing duration.
             *
             * @param value non-negative nanoseconds
             * @return this builder
             */
            public Builder averageTraceNanos(long value) {
                averageTraceNanos = value;
                return this;
            }

            /**
             * Selects average post-trace duration.
             *
             * @param value non-negative nanoseconds
             * @return this builder
             */
            public Builder averagePostTraceNanos(long value) {
                averagePostTraceNanos = value;
                return this;
            }

            /**
             * Selects average total GPU duration.
             *
             * @param value non-negative nanoseconds
             * @return this builder
             */
            public Builder averageTotalNanos(long value) {
                averageTotalNanos = value;
                return this;
            }

            /**
             * Selects maximum observed total GPU duration.
             *
             * @param value non-negative nanoseconds
             * @return this builder
             */
            public Builder maxTotalNanos(long value) {
                maxTotalNanos = value;
                return this;
            }

            /**
             * Validates and returns an independent immutable timing aggregate.
             *
             * @return validated timing snapshot
             */
            public FrameGpuTiming build() {
                return new FrameGpuTiming(this);
            }
        }
    }
}
