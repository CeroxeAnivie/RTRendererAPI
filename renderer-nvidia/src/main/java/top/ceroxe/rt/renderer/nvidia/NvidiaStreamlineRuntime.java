package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanQueueRequirements;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns Streamline's pre-device lease, typed requirements, and Vulkan device handoff. */
final class NvidiaStreamlineRuntime {
    private NvidiaStreamlineRuntime() {
    }

    static Preflight preflight(Set<Feature> features) {
        Objects.requireNonNull(features, "features");
        // Pre-device Streamline negotiation can be the process's first NVIDIA bridge call, so it
        // must trigger the packaged runtime loader before resolving any direct JNI entry point.
        NvidiaNativeBridge.Probe bridge = NvidiaNativeBridge.probe();
        if (!bridge.loaded()) {
            return new Preflight(
                    false,
                    "Streamline preflight unavailable: " + bridge.reason(),
                    Map.of()
            );
        }
        try {
            return Preflight.parse(NvidiaNativeBridge.nativeStreamlinePreflight(Feature.mask(features)));
        } catch (LinkageError failure) {
            // Streamline is optional: loader failures become a structured unavailable result so
            // a preferred feature cannot abort Vulkan planning before fallback is considered.
            String message = failure.getMessage();
            String reason = "Streamline preflight unavailable: " + failure.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            return new Preflight(false, reason, Map.of());
        }
    }

    static void closePreflight() {
        try {
            NvidiaNativeBridge.nativeCloseStreamlinePreflight();
        } catch (LinkageError ignored) {
            // No lease exists when the optional native bridge could not be loaded.
        }
    }

    static Set<Feature> bindDevice(
            long instance,
            long physicalDevice,
            long device,
            QueueRanges queues,
            Set<Feature> requiredFeatures
    ) {
        QueueRanges checked = Objects.requireNonNull(queues, "queues");
        Set<Feature> required = Set.copyOf(Objects.requireNonNull(requiredFeatures, "requiredFeatures"));
        if (instance == 0L || physicalDevice == 0L || device == 0L) {
            throw new IllegalArgumentException("Streamline Vulkan handles must not be zero");
        }
        int result = NvidiaNativeBridge.nativeStreamlineSetVulkanInfo(
                instance,
                physicalDevice,
                device,
                checked.computeQueueIndex(),
                checked.computeQueueFamily(),
                checked.graphicsQueueIndex(),
                checked.graphicsQueueFamily(),
                checked.opticalFlowQueueIndex(),
                checked.opticalFlowQueueFamily(),
                Feature.mask(required),
                checked.useNativeOpticalFlowMode()
        );
        if (result != 0) {
            throw handoffFailure(result, NvidiaNativeBridge.nativeStreamlineDiagnostic());
        }
        return Feature.fromMask(NvidiaNativeBridge.nativeStreamlineExecutionFeatureMask());
    }

    static IllegalStateException handoffFailure(int result, String diagnostic) {
        if (result == 0) throw new IllegalArgumentException("successful Streamline handoff has no failure");
        String checkedDiagnostic = Objects.requireNonNullElse(diagnostic, "no diagnostic").trim();
        if (checkedDiagnostic.isEmpty()) checkedDiagnostic = "no diagnostic";
        return new IllegalStateException(
                "slSetVulkanInfo failed with result=" + result
                        + "; Streamline diagnostic: " + checkedDiagnostic
        );
    }

    enum Feature {
        DLSS(1),
        NIS(1 << 1),
        DLSS_FRAME_GENERATION(1 << 2),
        DLSS_RAY_RECONSTRUCTION(1 << 3),
        REFLEX(1 << 4),
        PCL(1 << 5);

        private final int bit;

        Feature(int bit) {
            this.bit = bit;
        }

        int bit() {
            return bit;
        }

        static int mask(Set<Feature> features) {
            int result = 0;
            for (Feature feature : features) result |= Objects.requireNonNull(feature, "feature").bit;
            return result;
        }

        static Set<Feature> fromMask(int mask) {
            EnumSet<Feature> result = EnumSet.noneOf(Feature.class);
            int remaining = mask;
            for (Feature feature : values()) {
                if ((remaining & feature.bit) != 0) {
                    result.add(feature);
                    remaining &= ~feature.bit;
                }
            }
            if (remaining != 0) throw new IllegalStateException("unknown Streamline execution mask: " + mask);
            return result.isEmpty() ? Set.of() : Collections.unmodifiableSet(result);
        }
    }

    record Requirements(
            Set<String> instanceExtensions,
            Set<String> deviceExtensions,
            Set<String> vulkan12Features,
            Set<String> vulkan13Features,
            VulkanQueueRequirements queues,
            Version streamlineVersion
    ) {
        Requirements {
            instanceExtensions = immutableTokens(instanceExtensions, "instanceExtensions");
            deviceExtensions = immutableTokens(deviceExtensions, "deviceExtensions");
            vulkan12Features = immutableTokens(vulkan12Features, "vulkan12Features");
            vulkan13Features = immutableTokens(vulkan13Features, "vulkan13Features");
            queues = Objects.requireNonNull(queues, "queues");
            streamlineVersion = Objects.requireNonNull(streamlineVersion, "streamlineVersion");
        }
    }

    record Version(int major, int minor, int patch) implements Comparable<Version> {
        Version {
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("Streamline version components must not be negative");
            }
        }

        @Override
        public int compareTo(Version other) {
            Version checked = Objects.requireNonNull(other, "other");
            int result = Integer.compare(major, checked.major);
            if (result == 0) result = Integer.compare(minor, checked.minor);
            return result == 0 ? Integer.compare(patch, checked.patch) : result;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    record QueueRanges(
            int computeQueueIndex,
            int computeQueueFamily,
            int graphicsQueueIndex,
            int graphicsQueueFamily,
            int opticalFlowQueueIndex,
            int opticalFlowQueueFamily,
            boolean useNativeOpticalFlowMode
    ) {
        QueueRanges {
            if (computeQueueIndex < 0 || computeQueueFamily < 0
                    || graphicsQueueIndex < 0 || graphicsQueueFamily < 0
                    || opticalFlowQueueIndex < 0 || opticalFlowQueueFamily < 0) {
                throw new IllegalArgumentException("Streamline queue indices and families must not be negative");
            }
        }
    }

    record Preflight(boolean ready, String reason, Map<Feature, Requirements> requirements) {
        Preflight {
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("reason must not be blank");
            EnumMap<Feature, Requirements> copied = new EnumMap<>(Feature.class);
            copied.putAll(Objects.requireNonNull(requirements, "requirements"));
            requirements = Collections.unmodifiableMap(copied);
            if (!ready && !requirements.isEmpty()) {
                throw new IllegalArgumentException("failed Streamline preflight cannot return requirements");
            }
        }

        static Preflight parse(String payload) {
            String[] lines = Objects.requireNonNull(payload, "native Streamline payload").split("\\n", -1);
            if (lines.length < 2 || !("ready".equals(lines[0]) || "failed".equals(lines[0]))) {
                throw new IllegalStateException("invalid native Streamline preflight payload");
            }
            boolean ready = "ready".equals(lines[0]);
            EnumMap<Feature, Requirements> requirements = new EnumMap<>(Feature.class);
            for (int index = 2; index < lines.length; index++) {
                if (lines[index].isEmpty()) continue;
                String[] fields = lines[index].split("\\t", -1);
                if (fields.length != 12) throw new IllegalStateException("invalid Streamline requirement record");
                Feature feature;
                try {
                    feature = Feature.valueOf(fields[0]);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalStateException(
                            "unknown Streamline feature requirement: " + fields[0], failure
                    );
                }
                if (!"0".equals(fields[1])) {
                    throw new IllegalStateException(
                            "Streamline feature requirement query failed: " + feature + ", result=" + fields[1]
                    );
                }
                Requirements parsed;
                try {
                    parsed = new Requirements(
                            tokens(fields[5]), tokens(fields[6]), tokens(fields[7]), tokens(fields[8]),
                            new VulkanQueueRequirements(
                                    parseCount(fields[2]), parseCount(fields[3]), parseCount(fields[4])
                            ),
                            new Version(
                                parseCount(fields[9]), parseCount(fields[10]), parseCount(fields[11])
                            )
                    );
                } catch (IllegalArgumentException failure) {
                    throw new IllegalStateException(
                            "invalid Streamline requirement values for " + feature, failure
                    );
                }
                Requirements prior = requirements.put(feature, parsed);
                if (prior != null) throw new IllegalStateException("duplicate Streamline feature requirement: " + feature);
            }
            return new Preflight(ready, lines[1], requirements);
        }
    }

    private static Set<String> tokens(String value) {
        if (value.isEmpty()) return Set.of();
        return Set.of(value.split(",", -1));
    }

    private static int parseCount(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("invalid native Streamline queue count: " + value, failure);
        }
    }

    private static Set<String> immutableTokens(Set<String> values, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : Objects.requireNonNull(values, label)) {
            String checked = Objects.requireNonNull(value, label + " value").trim();
            if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not contain blank values");
            result.add(checked);
        }
        return Collections.unmodifiableSet(result);
    }
}
