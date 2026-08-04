package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Immutable point-in-time execution evidence for every concrete rendering technology.
 *
 * <p>This type complements capability negotiation with proof of work. In particular,
 * {@link Health#ACTIVE} means that work has completed on the GPU; selection, configuration, and
 * command recording alone produce earlier health states. Consumers can therefore distinguish a
 * technology that is genuinely participating in rendering from one that was merely enabled in a
 * configuration.</p>
 *
 * <p>The map is total: every {@link RenderingFeatureCapabilities.Technology} has an entry, even
 * when it was not requested. This prevents callers from interpreting a missing key as either
 * disabled or unavailable and keeps diagnostics consumers independent from provider-specific log
 * text. Instances and all collections returned by them are immutable.</p>
 */
public final class TechnologyExecutionEvidence {
    private static final TechnologyExecutionEvidence DISABLED = new Builder().build();

    private final Map<RenderingFeatureCapabilities.Technology, Entry> technologies;

    private TechnologyExecutionEvidence(Builder builder) {
        EnumMap<RenderingFeatureCapabilities.Technology, Entry> complete =
                new EnumMap<>(RenderingFeatureCapabilities.Technology.class);
        for (RenderingFeatureCapabilities.Technology technology
                : RenderingFeatureCapabilities.Technology.values()) {
            complete.put(technology, builder.technologies.getOrDefault(technology, Entry.disabled()));
        }
        technologies = Collections.unmodifiableMap(complete);
    }

    /**
     * Returns the canonical snapshot in which no technology was requested.
     *
     * @return shared immutable snapshot containing a disabled entry for every technology
     */
    public static TechnologyExecutionEvidence disabled() {
        return DISABLED;
    }

    /**
     * Starts a builder whose omitted technologies remain explicitly disabled.
     *
     * @return new single-thread-confined snapshot builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder containing every entry in this snapshot.
     *
     * <p>The entries themselves are immutable, so copying the complete map is sufficient to let a
     * caller replace selected technologies without sharing mutable builder state.</p>
     *
     * @return new builder initialized from this snapshot
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the evidence for one concrete technology.
     *
     * @param technology non-null technology identity
     * @return non-null immutable evidence entry, including a disabled entry when unrequested
     * @throws NullPointerException if {@code technology} is {@code null}
     */
    public Entry technology(RenderingFeatureCapabilities.Technology technology) {
        return technologies.get(Objects.requireNonNull(technology, "technology"));
    }

    /**
     * Returns every technology entry in stable enum order.
     *
     * <p>The returned map is complete and unmodifiable, which lets monitoring code iterate without
     * inventing defaults or racing mutations.</p>
     *
     * @return immutable total map ordered by technology enum declaration order
     */
    public Map<RenderingFeatureCapabilities.Technology, Entry> technologies() {
        return technologies;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TechnologyExecutionEvidence evidence
                && technologies.equals(evidence.technologies);
    }

    @Override
    public int hashCode() {
        return technologies.hashCode();
    }

    @Override
    public String toString() {
        return "TechnologyExecutionEvidence[technologies=" + technologies + ']';
    }

    /**
     * Exhaustive execution health derived from structured evidence rather than reason text.
     *
     * <p>The states intentionally separate negotiation, configuration, submission, GPU completion,
     * fallback, and terminal failure. This ordering prevents dashboards from reporting a feature as
     * active merely because its provider was discovered or its resources were configured.</p>
     */
    public enum Health {
        /** The application did not request this technology. */
        DISABLED,
        /** The request could not negotiate an executable implementation and owns no activity. */
        UNAVAILABLE,
        /** An implementation is supported and negotiated but is not selected for execution. */
        NEGOTIATED,
        /** The selected implementation is configured but has not recorded work yet. */
        READY,
        /** Work was recorded or queue-accepted but no work has completed on the GPU. */
        SUBMITTED,
        /** The configured implementation has at least one unit of GPU-completed work. */
        ACTIVE,
        /** A fallback is selected but no replacement GPU work has completed yet. */
        FALLBACK_PENDING,
        /** A fallback implementation owns the technology and has GPU-completed replacement work. */
        DEGRADED,
        /** Execution stopped at a terminal technology-specific error. */
        FAILED
    }

    /**
     * Identifies the address space of execution and output sequence numbers.
     *
     * <p>A range is meaningful only within its declared domain and reset epoch. Consumers must not
     * compare renderer frame sequences with provider work ordinals or infer an event count from a
     * numeric range, because valid observations need not be contiguous.</p>
     */
    public enum SequenceDomain {
        /** No execution work has been observed, so no sequence range exists. */
        NONE,
        /** Sequences identify application renderer frames in the renderer's frame timeline. */
        RENDERER_FRAME,
        /** Sequences identify provider-local work ordinals, such as completed BLAS builds. */
        PROVIDER_WORK
    }

    /**
     * Immutable, validated execution evidence for one technology.
     *
     * <p>Implementation identifiers and codes are stable machine-readable tokens. Human-readable
     * explanations remain in {@link RenderingFeatureCapabilities.Entry#reason()} and must not be
     * reconstructed from these fields. Counts describe distinct execution milestones and are not
     * interchangeable; sequence values identify where evidence occurred, while {@link #resetEpoch()}
     * separates generations when a native counter source is reset.</p>
     */
    public static final class Entry {
        private static final String NONE = "none";
        private static final Entry DISABLED = new Builder().build();

        private final RendererFeaturePreference requestPreference;
        private final String requestedImplementation;
        private final String negotiatedImplementation;
        private final String configuredImplementation;
        private final Map<String, String> configuredParameters;
        private final long recordedCount;
        private final long queueAcceptedCount;
        private final long gpuCompletedCount;
        private final long outputCount;
        private final Optional<String> fallbackCode;
        private final Optional<String> errorCode;
        private final OptionalLong firstSequence;
        private final OptionalLong lastSequence;
        private final OptionalLong lastOutputSequence;
        private final SequenceDomain sequenceDomain;
        private final long resetEpoch;
        private final Health health;

        private Entry(Builder builder) {
            requestPreference = Objects.requireNonNull(builder.requestPreference, "requestPreference");
            requestedImplementation = requireToken(builder.requestedImplementation, "requestedImplementation");
            negotiatedImplementation = requireToken(builder.negotiatedImplementation, "negotiatedImplementation");
            configuredImplementation = requireToken(builder.configuredImplementation, "configuredImplementation");
            configuredParameters = immutableParameters(builder.configuredParameters);
            recordedCount = builder.recordedCount;
            queueAcceptedCount = builder.queueAcceptedCount;
            gpuCompletedCount = builder.gpuCompletedCount;
            outputCount = builder.outputCount;
            fallbackCode = optionalCode(builder.fallbackCode, "fallbackCode");
            errorCode = optionalCode(builder.errorCode, "errorCode");
            firstSequence = optionalSequence(builder.firstSequence, "firstSequence");
            lastSequence = optionalSequence(builder.lastSequence, "lastSequence");
            lastOutputSequence = optionalSequence(builder.lastOutputSequence, "lastOutputSequence");
            sequenceDomain = Objects.requireNonNull(builder.sequenceDomain, "sequenceDomain");
            resetEpoch = builder.resetEpoch;
            health = Objects.requireNonNull(builder.health, "health");
            validate();
        }

        /**
         * Returns the canonical unrequested, zero-evidence entry.
         *
         * @return shared immutable entry with disabled preference and health
         */
        public static Entry disabled() {
            return DISABLED;
        }

        /**
         * Creates a zero-activity entry for a request that could not negotiate an implementation.
         *
         * @param preference non-disabled request preference
         * @param requestedImplementation stable requested implementation identifier
         * @return immutable unavailable evidence
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if the preference is disabled or the identifier is not
         *         a valid stable token
         */
        public static Entry unavailable(
                RendererFeaturePreference preference,
                String requestedImplementation
        ) {
            return builder()
                    .requestPreference(preference)
                    .requestedImplementation(requestedImplementation)
                    .health(Health.UNAVAILABLE)
                    .build();
        }

        /**
         * Starts an entry builder with the canonical disabled defaults.
         *
         * <p>Setting a requested preference normally requires the remaining negotiation and activity
         * fields to be made consistent before {@link Builder#build()}.</p>
         *
         * @return new single-thread-confined entry builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Starts an independent builder containing this complete entry.
         *
         * @return new builder initialized from every field in this entry
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Returns the renderer-lifetime request policy that led to this evidence.
         *
         * @return request preference; disabled exactly for a canonical disabled entry
         */
        public RendererFeaturePreference requestPreference() {
            return requestPreference;
        }

        /**
         * Returns the implementation identity requested by policy or configuration.
         *
         * @return stable token, or {@code "none"} when the technology was disabled
         */
        public String requestedImplementation() {
            return requestedImplementation;
        }

        /**
         * Returns the implementation selected by capability negotiation.
         *
         * <p>This may differ from the requested identity when a documented fallback is negotiated.
         * Negotiation does not by itself prove that the implementation was configured or executed.</p>
         *
         * @return stable implementation token, or {@code "none"} when negotiation did not succeed
         */
        public String negotiatedImplementation() {
            return negotiatedImplementation;
        }

        /**
         * Returns the negotiated implementation currently configured to execute the technology.
         *
         * @return stable implementation token, or {@code "none"} before configuration
         */
        public String configuredImplementation() {
            return configuredImplementation;
        }

        /**
         * Returns stable provider-defined parameters for the configured implementation.
         *
         * <p>The sorted, unmodifiable map is diagnostic evidence of the effective configuration,
         * not a mutable control surface.</p>
         *
         * @return immutable parameter map ordered by key; empty when nothing is configured
         */
        public Map<String, String> configuredParameters() {
            return configuredParameters;
        }

        /**
         * Returns the number of technology work units recorded for execution.
         *
         * <p>Recording is the earliest activity milestone and does not prove queue acceptance or GPU
         * execution. The value is non-negative and bounds {@link #queueAcceptedCount()} from above.</p>
         *
         * @return recorded work-unit count in the current reset epoch
         */
        public long recordedCount() {
            return recordedCount;
        }

        /**
         * Returns the number of recorded work units accepted by an execution queue or provider.
         *
         * <p>Acceptance proves that work progressed beyond recording but not that the GPU completed
         * it. The value is between {@link #gpuCompletedCount()} and {@link #recordedCount()}.</p>
         *
         * @return queue-accepted work-unit count in the current reset epoch
         */
        public long queueAcceptedCount() {
            return queueAcceptedCount;
        }

        /**
         * Returns the number of accepted work units known to have completed on the GPU.
         *
         * <p>A positive value is required for {@link Health#ACTIVE} and {@link Health#DEGRADED}; it
         * is the evidence that distinguishes real execution from a merely configured feature.</p>
         *
         * @return GPU-completed work-unit count in the current reset epoch
         */
        public long gpuCompletedCount() {
            return gpuCompletedCount;
        }

        /**
         * Returns the number of externally observable outputs produced by completed work.
         *
         * <p>Output cardinality is intentionally independent of work-unit cardinality: one completed
         * native interval may produce multiple presented frames, as with multi-frame generation.
         * A positive value nevertheless requires at least one GPU-completed work unit.</p>
         *
         * @return output count in the current reset epoch
         */
        public long outputCount() {
            return outputCount;
        }

        /**
         * Returns the stable reason code for selecting a replacement implementation.
         *
         * <p>The code is present only for pending or active fallback health. It is intended for
         * machine correlation; display text should come from capability diagnostics.</p>
         *
         * @return uppercase fallback code, or an empty optional when no fallback is selected
         */
        public Optional<String> fallbackCode() {
            return fallbackCode;
        }

        /**
         * Returns the stable code for the failure that affected negotiation or execution.
         *
         * <p>An error may accompany unavailable, fallback, degraded, or failed health. Its presence
         * does not by itself mean execution is terminal because a fallback may continue to operate.</p>
         *
         * @return uppercase error code, or an empty optional when no error is reported
         */
        public Optional<String> errorCode() {
            return errorCode;
        }

        /**
         * Returns the earliest observed sequence containing recorded work.
         *
         * <p>The value is present exactly when {@link #recordedCount()} is positive and belongs to
         * {@link #sequenceDomain()}. It is a boundary, not proof that every intermediate sequence
         * contains work.</p>
         *
         * @return first recorded sequence, or empty when no work has been recorded
         */
        public OptionalLong firstSequence() {
            return firstSequence;
        }

        /**
         * Returns the latest observed sequence containing recorded work.
         *
         * @return last recorded sequence, or empty when no work has been recorded
         */
        public OptionalLong lastSequence() {
            return lastSequence;
        }

        /**
         * Returns the latest sequence for which an output was observed.
         *
         * <p>The value is present exactly when {@link #outputCount()} is positive and lies within the
         * recorded sequence range in the same sequence domain.</p>
         *
         * @return last output sequence, or empty when no output has been observed
         */
        public OptionalLong lastOutputSequence() {
            return lastOutputSequence;
        }

        /**
         * Returns the address space used by the execution and output sequence values.
         *
         * @return non-empty domain when activity exists, otherwise {@link SequenceDomain#NONE}
         */
        public SequenceDomain sequenceDomain() {
            return sequenceDomain;
        }

        /**
         * Returns the generation of the underlying activity counters.
         *
         * <p>A provider increments this non-negative epoch when its native evidence counters reset.
         * Consumers should calculate counter deltas only between observations with the same epoch;
         * a changed epoch establishes a new baseline rather than negative progress. Disabled entries
         * always use epoch zero.</p>
         *
         * @return non-negative counter reset generation
         */
        public long resetEpoch() {
            return resetEpoch;
        }

        /**
         * Returns the validated high-level state derived from negotiation and execution evidence.
         *
         * @return execution health; active and degraded states are backed by GPU completion
         */
        public Health health() {
            return health;
        }

        private void validate() {
            if (recordedCount < 0L || queueAcceptedCount < 0L || gpuCompletedCount < 0L
                    || outputCount < 0L || resetEpoch < 0L) {
                throw new IllegalArgumentException("technology evidence counters must not be negative");
            }
            if (queueAcceptedCount > recordedCount) {
                throw new IllegalArgumentException("queue-accepted count must not exceed recorded count");
            }
            if (gpuCompletedCount > queueAcceptedCount) {
                throw new IllegalArgumentException("GPU-completed count must not exceed queue-accepted count");
            }

            boolean hasActivity = recordedCount != 0L;
            if (firstSequence.isPresent() != lastSequence.isPresent() || hasActivity != firstSequence.isPresent()) {
                throw new IllegalArgumentException("recorded evidence requires a complete first/last sequence range");
            }
            if (hasActivity == (sequenceDomain == SequenceDomain.NONE)) {
                throw new IllegalArgumentException(
                        "recorded evidence requires an explicit non-empty sequence domain"
                );
            }
            if (firstSequence.isPresent() && firstSequence.getAsLong() > lastSequence.getAsLong()) {
                throw new IllegalArgumentException("first sequence must not exceed last sequence");
            }
            if ((outputCount != 0L) != lastOutputSequence.isPresent()) {
                throw new IllegalArgumentException("output evidence requires exactly one last-output sequence");
            }
            if (outputCount != 0L && gpuCompletedCount == 0L) {
                throw new IllegalArgumentException("output evidence requires GPU-completed work");
            }
            // One completed native interval may produce multiple outputs (for example MFG), so
            // outputCount intentionally is not capped by gpuCompletedCount.
            if (lastOutputSequence.isPresent()
                    && (lastOutputSequence.getAsLong() < firstSequence.getAsLong()
                    || lastOutputSequence.getAsLong() > lastSequence.getAsLong())) {
                throw new IllegalArgumentException("last-output sequence must lie within the execution range");
            }

            if (requestPreference == RendererFeaturePreference.DISABLED) {
                validateDisabled();
                return;
            }
            if (NONE.equals(requestedImplementation)) {
                throw new IllegalArgumentException("requested technology requires an implementation identifier");
            }
            if (!NONE.equals(configuredImplementation) && NONE.equals(negotiatedImplementation)) {
                throw new IllegalArgumentException("configured implementation requires successful negotiation");
            }
            if (NONE.equals(configuredImplementation) && !configuredParameters.isEmpty()) {
                throw new IllegalArgumentException(
                        "configured parameters require a configured implementation"
                );
            }

            switch (health) {
                case DISABLED -> throw new IllegalArgumentException("requested technology cannot be disabled");
                case UNAVAILABLE -> {
                    if (!NONE.equals(negotiatedImplementation) || !NONE.equals(configuredImplementation)
                            || !configuredParameters.isEmpty() || hasActivity) {
                        throw new IllegalArgumentException("unavailable technology cannot own implementation or activity");
                    }
                }
                case NEGOTIATED -> {
                    if (NONE.equals(negotiatedImplementation)
                            || !NONE.equals(configuredImplementation) || hasActivity) {
                        throw new IllegalArgumentException(
                                "negotiated technology cannot be configured or contain activity"
                        );
                    }
                }
                case READY -> {
                    requireConfigured();
                    if (hasActivity) throw new IllegalArgumentException("ready technology cannot contain activity");
                }
                case SUBMITTED -> {
                    requireConfigured();
                    if (!hasActivity || gpuCompletedCount != 0L) {
                        throw new IllegalArgumentException(
                                "submitted technology requires pending recorded work only"
                        );
                    }
                }
                case ACTIVE -> {
                    requireConfigured();
                    if (gpuCompletedCount == 0L) {
                        throw new IllegalArgumentException("active technology requires GPU-completed work");
                    }
                }
                case FALLBACK_PENDING -> {
                    requireConfigured();
                    if (fallbackCode.isEmpty()) {
                        throw new IllegalArgumentException("pending fallback requires a fallback code");
                    }
                    if (gpuCompletedCount != 0L || outputCount != 0L) {
                        throw new IllegalArgumentException(
                                "pending fallback cannot contain completed replacement output"
                        );
                    }
                }
                case DEGRADED -> {
                    requireConfigured();
                    if (fallbackCode.isEmpty()) {
                        throw new IllegalArgumentException("degraded technology requires a fallback code");
                    }
                    if (gpuCompletedCount == 0L) {
                        throw new IllegalArgumentException("degraded technology requires completed fallback work");
                    }
                }
                case FAILED -> {
                    if (errorCode.isEmpty()) {
                        throw new IllegalArgumentException("failed technology requires an error code");
                    }
                }
            }
            if (fallbackCode.isPresent() && health != Health.FALLBACK_PENDING
                    && health != Health.DEGRADED) {
                throw new IllegalArgumentException(
                        "fallback code requires pending or completed degraded health"
                );
            }
            if (errorCode.isPresent() && health != Health.UNAVAILABLE
                    && health != Health.FALLBACK_PENDING
                    && health != Health.DEGRADED && health != Health.FAILED) {
                throw new IllegalArgumentException(
                        "error code requires unavailable, degraded, or failed health"
                );
            }
        }

        private void validateDisabled() {
            if (health != Health.DISABLED || !NONE.equals(requestedImplementation)
                    || !NONE.equals(negotiatedImplementation) || !NONE.equals(configuredImplementation)
                    || !configuredParameters.isEmpty()
                    || recordedCount != 0L || queueAcceptedCount != 0L || gpuCompletedCount != 0L
                    || outputCount != 0L || fallbackCode.isPresent() || errorCode.isPresent()
                    || firstSequence.isPresent() || lastSequence.isPresent()
                    || lastOutputSequence.isPresent() || sequenceDomain != SequenceDomain.NONE
                    || resetEpoch != 0L) {
                throw new IllegalArgumentException("disabled technology must use the canonical zero-evidence state");
            }
        }

        private void requireConfigured() {
            if (NONE.equals(negotiatedImplementation) || NONE.equals(configuredImplementation)) {
                throw new IllegalArgumentException("technology health requires a configured implementation");
            }
        }

        private static Map<String, String> immutableParameters(Map<String, String> values) {
            Objects.requireNonNull(values, "configuredParameters");
            TreeMap<String, String> checked = new TreeMap<>();
            values.forEach((key, value) -> {
                String checkedKey = requireToken(key, "configured parameter key");
                String checkedValue = Objects.requireNonNull(value, "configured parameter value").trim();
                if (checkedValue.isEmpty() || checkedValue.length() > 256
                        || checkedValue.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(
                            "configured parameter value must be non-blank, bounded text"
                    );
                }
                checked.put(checkedKey, checkedValue);
            });
            return Collections.unmodifiableMap(checked);
        }

        private static String requireToken(String value, String name) {
            String checked = Objects.requireNonNull(value, name);
            if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
                throw new IllegalArgumentException(name + " must be a stable implementation token");
            }
            return checked;
        }

        private static Optional<String> optionalCode(String value, String name) {
            if (value == null) return Optional.empty();
            String checked = requireToken(value, name);
            if (!checked.matches("[A-Z][A-Z0-9_.-]*")) {
                throw new IllegalArgumentException(name + " must be a stable uppercase code");
            }
            return Optional.of(checked);
        }

        private static OptionalLong optionalSequence(Long value, String name) {
            if (value == null) return OptionalLong.empty();
            if (value < 0L) throw new IllegalArgumentException(name + " must not be negative");
            return OptionalLong.of(value);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Entry entry)) return false;
            return recordedCount == entry.recordedCount
                    && queueAcceptedCount == entry.queueAcceptedCount
                    && gpuCompletedCount == entry.gpuCompletedCount
                    && outputCount == entry.outputCount
                    && resetEpoch == entry.resetEpoch
                    && requestPreference == entry.requestPreference
                    && requestedImplementation.equals(entry.requestedImplementation)
                    && negotiatedImplementation.equals(entry.negotiatedImplementation)
                    && configuredImplementation.equals(entry.configuredImplementation)
                    && configuredParameters.equals(entry.configuredParameters)
                    && fallbackCode.equals(entry.fallbackCode)
                    && errorCode.equals(entry.errorCode)
                    && firstSequence.equals(entry.firstSequence)
                    && lastSequence.equals(entry.lastSequence)
                    && lastOutputSequence.equals(entry.lastOutputSequence)
                    && sequenceDomain == entry.sequenceDomain
                    && health == entry.health;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    requestPreference, requestedImplementation, negotiatedImplementation,
                    configuredImplementation, configuredParameters,
                    recordedCount, queueAcceptedCount, gpuCompletedCount,
                    outputCount, fallbackCode, errorCode, firstSequence, lastSequence,
                    lastOutputSequence, sequenceDomain, resetEpoch, health
            );
        }

        @Override
        public String toString() {
            return "Entry[requestPreference=" + requestPreference
                    + ", requestedImplementation=" + requestedImplementation
                    + ", negotiatedImplementation=" + negotiatedImplementation
                    + ", configuredImplementation=" + configuredImplementation
                    + ", configuredParameters=" + configuredParameters
                    + ", recordedCount=" + recordedCount
                    + ", queueAcceptedCount=" + queueAcceptedCount
                    + ", gpuCompletedCount=" + gpuCompletedCount
                    + ", outputCount=" + outputCount
                    + ", fallbackCode=" + fallbackCode
                    + ", errorCode=" + errorCode
                    + ", firstSequence=" + firstSequence
                    + ", lastSequence=" + lastSequence
                    + ", lastOutputSequence=" + lastOutputSequence
                    + ", sequenceDomain=" + sequenceDomain
                    + ", resetEpoch=" + resetEpoch
                    + ", health=" + health + ']';
        }

        /**
         * Single-thread-confined semantic builder for one technology entry.
         *
         * <p>Setters collect a candidate snapshot; {@link #build()} performs cross-field validation.
         * This delayed validation permits transitions such as replacing counts and their sequence
         * range in either order while still preventing publication of internally inconsistent
         * evidence.</p>
         */
        public static final class Builder {
            private RendererFeaturePreference requestPreference = RendererFeaturePreference.DISABLED;
            private String requestedImplementation = NONE;
            private String negotiatedImplementation = NONE;
            private String configuredImplementation = NONE;
            private final Map<String, String> configuredParameters = new TreeMap<>();
            private long recordedCount;
            private long queueAcceptedCount;
            private long gpuCompletedCount;
            private long outputCount;
            private String fallbackCode;
            private String errorCode;
            private Long firstSequence;
            private Long lastSequence;
            private Long lastOutputSequence;
            private SequenceDomain sequenceDomain = SequenceDomain.NONE;
            private long resetEpoch;
            private Health health = Health.DISABLED;

            private Builder() {
            }

            private Builder(Entry source) {
                requestPreference = source.requestPreference;
                requestedImplementation = source.requestedImplementation;
                negotiatedImplementation = source.negotiatedImplementation;
                configuredImplementation = source.configuredImplementation;
                configuredParameters.putAll(source.configuredParameters);
                recordedCount = source.recordedCount;
                queueAcceptedCount = source.queueAcceptedCount;
                gpuCompletedCount = source.gpuCompletedCount;
                outputCount = source.outputCount;
                fallbackCode = source.fallbackCode.orElse(null);
                errorCode = source.errorCode.orElse(null);
                firstSequence = source.firstSequence.isPresent() ? source.firstSequence.getAsLong() : null;
                lastSequence = source.lastSequence.isPresent() ? source.lastSequence.getAsLong() : null;
                lastOutputSequence = source.lastOutputSequence.isPresent()
                        ? source.lastOutputSequence.getAsLong() : null;
                sequenceDomain = source.sequenceDomain;
                resetEpoch = source.resetEpoch;
                health = source.health;
            }

            /**
             * Sets the request policy that caused negotiation of this technology.
             *
             * @param value non-null renderer feature preference
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder requestPreference(RendererFeaturePreference value) {
                requestPreference = Objects.requireNonNull(value, "requestPreference");
                return this;
            }

            /**
             * Sets the stable identity requested by policy or configuration.
             *
             * @param value implementation token; use {@code "none"} only for disabled evidence
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder requestedImplementation(String value) {
                requestedImplementation = Objects.requireNonNull(value, "requestedImplementation");
                return this;
            }

            /**
             * Sets the stable identity selected during capability negotiation.
             *
             * @param value negotiated implementation token, possibly a fallback identity
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder negotiatedImplementation(String value) {
                negotiatedImplementation = Objects.requireNonNull(value, "negotiatedImplementation");
                return this;
            }

            /**
             * Sets the negotiated implementation currently configured for execution.
             *
             * @param value configured implementation token
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder configuredImplementation(String value) {
                configuredImplementation = Objects.requireNonNull(value, "configuredImplementation");
                return this;
            }

            /**
             * Adds or replaces one stable parameter of the configured implementation.
             *
             * @param key non-null stable parameter token
             * @param value non-null, non-blank bounded diagnostic value
             * @return this builder
             * @throws NullPointerException if either argument is {@code null}
             */
            public Builder configuredParameter(String key, String value) {
                configuredParameters.put(
                        Objects.requireNonNull(key, "key"),
                        Objects.requireNonNull(value, "value")
                );
                return this;
            }

            /**
             * Replaces every configured parameter with entries from the supplied map.
             *
             * <p>The entries are validated and copied when the snapshot is built, ensuring callers
             * cannot mutate the resulting evidence through the source map.</p>
             *
             * @param values non-null map of stable parameter keys to bounded values
             * @return this builder
             * @throws NullPointerException if {@code values} is {@code null}
             */
            public Builder configuredParameters(Map<String, String> values) {
                configuredParameters.clear();
                configuredParameters.putAll(Objects.requireNonNull(values, "values"));
                return this;
            }

            /**
             * Sets the number of work units recorded for execution.
             *
             * @param value non-negative count, not less than the queue-accepted count
             * @return this builder
             */
            public Builder recordedCount(long value) {
                recordedCount = value;
                return this;
            }

            /**
             * Sets the number of recorded work units accepted for execution.
             *
             * @param value non-negative count not exceeding the recorded count and not less than
             *         the GPU-completed count
             * @return this builder
             */
            public Builder queueAcceptedCount(long value) {
                queueAcceptedCount = value;
                return this;
            }

            /**
             * Sets the number of accepted work units known to have completed on the GPU.
             *
             * @param value non-negative count not exceeding the queue-accepted count
             * @return this builder
             */
            public Builder gpuCompletedCount(long value) {
                gpuCompletedCount = value;
                return this;
            }

            /**
             * Sets the number of externally observable outputs from completed work.
             *
             * <p>The value may exceed the GPU-completed count when one work unit emits multiple
             * outputs, but any positive value requires GPU-completed work.</p>
             *
             * @param value non-negative output count
             * @return this builder
             */
            public Builder outputCount(long value) {
                outputCount = value;
                return this;
            }

            /**
             * Sets the stable reason code for selecting a fallback implementation.
             *
             * @param value non-null uppercase machine-readable code
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder fallbackCode(String value) {
                fallbackCode = Objects.requireNonNull(value, "fallbackCode");
                return this;
            }

            /**
             * Removes any fallback reason code from the candidate entry.
             *
             * @return this builder
             */
            public Builder clearFallbackCode() {
                fallbackCode = null;
                return this;
            }

            /**
             * Sets the stable error code associated with negotiation or execution.
             *
             * @param value non-null uppercase machine-readable code
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder errorCode(String value) {
                errorCode = Objects.requireNonNull(value, "errorCode");
                return this;
            }

            /**
             * Removes any error code from the candidate entry.
             *
             * @return this builder
             */
            public Builder clearErrorCode() {
                errorCode = null;
                return this;
            }

            /**
             * Sets the inclusive observed sequence range containing recorded work.
             *
             * <p>The range identifies boundaries rather than a count and must be paired with a
             * positive recorded count and a non-empty sequence domain.</p>
             *
             * @param first non-negative first observed sequence
             * @param last non-negative last observed sequence, not less than {@code first}
             * @return this builder
             */
            public Builder sequenceRange(long first, long last) {
                firstSequence = first;
                lastSequence = last;
                return this;
            }

            /**
             * Removes both boundaries of the recorded sequence range.
             *
             * @return this builder
             */
            public Builder clearSequenceRange() {
                firstSequence = null;
                lastSequence = null;
                return this;
            }

            /**
             * Sets the latest sequence for which an output was observed.
             *
             * @param value non-negative sequence within the recorded sequence range
             * @return this builder
             */
            public Builder lastOutputSequence(long value) {
                lastOutputSequence = value;
                return this;
            }

            /**
             * Removes the latest output sequence from the candidate entry.
             *
             * @return this builder
             */
            public Builder clearLastOutputSequence() {
                lastOutputSequence = null;
                return this;
            }

            /**
             * Sets the shared address space of execution and output sequence values.
             *
             * @param value non-null sequence domain; activity requires a domain other than
             *         {@link SequenceDomain#NONE}
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder sequenceDomain(SequenceDomain value) {
                sequenceDomain = Objects.requireNonNull(value, "sequenceDomain");
                return this;
            }

            /**
             * Sets the generation of the underlying activity counters.
             *
             * <p>Increment the epoch when the counter source resets so consumers do not calculate a
             * delta across unrelated generations.</p>
             *
             * @param value non-negative reset generation; disabled evidence requires zero
             * @return this builder
             */
            public Builder resetEpoch(long value) {
                resetEpoch = value;
                return this;
            }

            /**
             * Sets the high-level health represented by the candidate evidence.
             *
             * @param value non-null health consistent with implementation, activity, and codes
             * @return this builder
             * @throws NullPointerException if {@code value} is {@code null}
             */
            public Builder health(Health value) {
                health = Objects.requireNonNull(value, "health");
                return this;
            }

            /**
             * Validates and creates the immutable technology entry.
             *
             * <p>Validation ties health to negotiation, configuration, milestone counts, sequence
             * metadata, and structured codes so an impossible or falsely active snapshot cannot be
             * published.</p>
             *
             * @return new immutable entry
             * @throws NullPointerException if a required field or parameter component is null
             * @throws IllegalArgumentException if any token, count, range, health transition, code,
             *         or cross-field invariant is invalid
             */
            public Entry build() {
                return new Entry(this);
            }
        }
    }

    /**
     * Single-thread-confined builder for one complete technology evidence snapshot.
     *
     * <p>Technologies omitted from the builder are materialized as explicit disabled entries at
     * build time, preserving the total-map contract.</p>
     */
    public static final class Builder {
        private final EnumMap<RenderingFeatureCapabilities.Technology, Entry> technologies =
                new EnumMap<>(RenderingFeatureCapabilities.Technology.class);

        private Builder() {
        }

        private Builder(TechnologyExecutionEvidence source) {
            technologies.putAll(source.technologies);
        }

        /**
         * Replaces the evidence for one concrete technology.
         *
         * @param technology non-null technology identity
         * @param entry non-null immutable evidence entry
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        public Builder technology(RenderingFeatureCapabilities.Technology technology, Entry entry) {
            technologies.put(
                    Objects.requireNonNull(technology, "technology"),
                    Objects.requireNonNull(entry, "entry")
            );
            return this;
        }

        /**
         * Creates a complete immutable snapshot.
         *
         * @return immutable snapshot containing one entry for every technology
         */
        public TechnologyExecutionEvidence build() {
            return new TechnologyExecutionEvidence(this);
        }
    }
}
