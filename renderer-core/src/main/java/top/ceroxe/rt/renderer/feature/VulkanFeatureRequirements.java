package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;

import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider declaration consumed before {@code vkCreateDevice}.
 *
 * <p>Required and preferred extension sets are kept separate so missing optional driver support
 * cannot accidentally turn a preferred feature into a hard initialization failure.</p>
 */
public final class VulkanFeatureRequirements {
    private final Set<String> requiredInstanceExtensions;
    private final Set<String> preferredInstanceExtensions;
    private final Set<String> requiredDeviceExtensions;
    private final Set<String> preferredDeviceExtensions;
    private final Set<Vulkan12Feature> requiredVulkan12Features;
    private final Set<Vulkan12Feature> preferredVulkan12Features;
    private final Set<Vulkan13Feature> requiredVulkan13Features;
    private final Set<Vulkan13Feature> preferredVulkan13Features;
    private final VulkanQueueRequirements requiredQueues;
    private final VulkanQueueRequirements preferredQueues;
    private final Map<RenderingFeatureCapabilities.Feature, RenderingFeatureCapabilities.Entry> support;
    private final Map<RenderingFeatureCapabilities.Technology, RenderingFeatureCapabilities.Entry> technologies;

    private VulkanFeatureRequirements(Builder builder) {
        requiredInstanceExtensions = immutableExtensions(builder.requiredInstanceExtensions, "requiredInstanceExtensions");
        preferredInstanceExtensions = immutableExtensions(builder.preferredInstanceExtensions, "preferredInstanceExtensions");
        requiredDeviceExtensions = immutableExtensions(builder.requiredDeviceExtensions, "requiredDeviceExtensions");
        preferredDeviceExtensions = immutableExtensions(builder.preferredDeviceExtensions, "preferredDeviceExtensions");
        requiredVulkan12Features = immutableFeatures(builder.requiredVulkan12Features, Vulkan12Feature.class);
        preferredVulkan12Features = immutableFeatures(builder.preferredVulkan12Features, Vulkan12Feature.class);
        requiredVulkan13Features = immutableFeatures(builder.requiredVulkan13Features, Vulkan13Feature.class);
        preferredVulkan13Features = immutableFeatures(builder.preferredVulkan13Features, Vulkan13Feature.class);
        requiredQueues = builder.requiredQueues;
        preferredQueues = builder.preferredQueues;
        LinkedHashSet<String> overlap = new LinkedHashSet<>(requiredDeviceExtensions);
        overlap.retainAll(preferredDeviceExtensions);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("an extension cannot be both required and preferred: " + overlap);
        }
        overlap = new LinkedHashSet<>(requiredInstanceExtensions);
        overlap.retainAll(preferredInstanceExtensions);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("an instance extension cannot be both required and preferred: " + overlap);
        }
        requireNoOverlap(requiredVulkan12Features, preferredVulkan12Features, "a Vulkan 1.2 feature");
        requireNoOverlap(requiredVulkan13Features, preferredVulkan13Features, "a Vulkan 1.3 feature");
        EnumMap<RenderingFeatureCapabilities.Feature, RenderingFeatureCapabilities.Entry> entries =
                new EnumMap<>(RenderingFeatureCapabilities.Feature.class);
        entries.putAll(builder.support);
        support = Collections.unmodifiableMap(entries);
        EnumMap<RenderingFeatureCapabilities.Technology, RenderingFeatureCapabilities.Entry> technologyEntries =
                new EnumMap<>(RenderingFeatureCapabilities.Technology.class);
        technologyEntries.putAll(builder.technologies);
        technologies = Collections.unmodifiableMap(technologyEntries);
    }

    /**
     * Creates an empty provider requirement builder.
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns required logical-device extensions.
     * @return immutable extension set
     */
    public Set<String> requiredDeviceExtensions() {
        return requiredDeviceExtensions;
    }

    /**
     * Returns required Vulkan instance extensions.
     * @return immutable extension set
     */
    public Set<String> requiredInstanceExtensions() {
        return requiredInstanceExtensions;
    }

    /**
     * Returns preferred Vulkan instance extensions.
     * @return immutable extension set
     */
    public Set<String> preferredInstanceExtensions() {
        return preferredInstanceExtensions;
    }

    /**
     * Returns preferred logical-device extensions.
     * @return immutable extension set
     */
    public Set<String> preferredDeviceExtensions() {
        return preferredDeviceExtensions;
    }

    /**
     * Returns required Vulkan 1.2 feature bits.
     * @return immutable feature set
     */
    public Set<Vulkan12Feature> requiredVulkan12Features() { return requiredVulkan12Features; }

    /**
     * Returns preferred Vulkan 1.2 feature bits.
     * @return immutable feature set
     */
    public Set<Vulkan12Feature> preferredVulkan12Features() { return preferredVulkan12Features; }

    /**
     * Returns required Vulkan 1.3 feature bits.
     * @return immutable feature set
     */
    public Set<Vulkan13Feature> requiredVulkan13Features() { return requiredVulkan13Features; }

    /**
     * Returns preferred Vulkan 1.3 feature bits.
     * @return immutable feature set
     */
    public Set<Vulkan13Feature> preferredVulkan13Features() { return preferredVulkan13Features; }

    /**
     * Returns required additional queue roles.
     * @return required queue counts
     */
    public VulkanQueueRequirements requiredQueues() { return requiredQueues; }

    /**
     * Returns preferred additional queue roles.
     * @return preferred queue counts
     */
    public VulkanQueueRequirements preferredQueues() { return preferredQueues; }

    /**
     * Returns capability declarations owned by this provider.
     * @return immutable feature-to-capability mapping
     */
    public Map<RenderingFeatureCapabilities.Feature, RenderingFeatureCapabilities.Entry> support() {
        return support;
    }

    /**
     * Returns concrete technology declarations owned by this provider.
     * @return immutable technology-to-capability mapping
     */
    public Map<RenderingFeatureCapabilities.Technology, RenderingFeatureCapabilities.Entry> technologies() {
        return technologies;
    }

    /** Single-thread-confined provider declaration builder. */
    public static final class Builder {
        private final LinkedHashSet<String> requiredInstanceExtensions = new LinkedHashSet<>();
        private final LinkedHashSet<String> preferredInstanceExtensions = new LinkedHashSet<>();
        private final LinkedHashSet<String> requiredDeviceExtensions = new LinkedHashSet<>();
        private final LinkedHashSet<String> preferredDeviceExtensions = new LinkedHashSet<>();
        private final EnumSet<Vulkan12Feature> requiredVulkan12Features = EnumSet.noneOf(Vulkan12Feature.class);
        private final EnumSet<Vulkan12Feature> preferredVulkan12Features = EnumSet.noneOf(Vulkan12Feature.class);
        private final EnumSet<Vulkan13Feature> requiredVulkan13Features = EnumSet.noneOf(Vulkan13Feature.class);
        private final EnumSet<Vulkan13Feature> preferredVulkan13Features = EnumSet.noneOf(Vulkan13Feature.class);
        private VulkanQueueRequirements requiredQueues = VulkanQueueRequirements.NONE;
        private VulkanQueueRequirements preferredQueues = VulkanQueueRequirements.NONE;
        private final EnumMap<RenderingFeatureCapabilities.Feature, RenderingFeatureCapabilities.Entry> support =
                new EnumMap<>(RenderingFeatureCapabilities.Feature.class);
        private final EnumMap<RenderingFeatureCapabilities.Technology, RenderingFeatureCapabilities.Entry>
                technologies = new EnumMap<>(RenderingFeatureCapabilities.Technology.class);

        private Builder() {
        }

        /**
         * Adds a required Vulkan instance extension.
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder requireInstanceExtension(String extension) {
            requiredInstanceExtensions.add(requireExtension(extension));
            return this;
        }

        /**
         * Promotes an already preferred instance extension to a hard provider requirement.
         *
         * <p>A composite provider can discover the same extension through multiple selected
         * technologies at different strengths. Promotion preserves the strongest declaration
         * without allowing the final immutable contract to contain contradictory sets.</p>
         *
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder promoteInstanceExtension(String extension) {
            String checked = requireExtension(extension);
            preferredInstanceExtensions.remove(checked);
            requiredInstanceExtensions.add(checked);
            return this;
        }

        /**
         * Merges one instance-extension declaration using strongest-strength-wins semantics.
         *
         * <p>Unlike the strict {@code require}/{@code prefer} pair, this method is intended for a
         * provider that combines requirements reported by multiple selected technologies. Its
         * result is independent of discovery order.</p>
         *
         * @param extension non-blank extension name
         * @param required whether this declaration is mandatory
         * @return this builder
         */
        public Builder mergeInstanceExtension(String extension, boolean required) {
            if (required) return promoteInstanceExtension(extension);
            String checked = requireExtension(extension);
            if (!requiredInstanceExtensions.contains(checked)) preferredInstanceExtensions.add(checked);
            return this;
        }

        /**
         * Adds a preferred Vulkan instance extension.
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder preferInstanceExtension(String extension) {
            preferredInstanceExtensions.add(requireExtension(extension));
            return this;
        }

        /**
         * Adds a required logical-device extension.
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder requireDeviceExtension(String extension) {
            requiredDeviceExtensions.add(requireExtension(extension));
            return this;
        }

        /**
         * Promotes an already preferred logical-device extension to a hard provider requirement.
         *
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder promoteDeviceExtension(String extension) {
            String checked = requireExtension(extension);
            preferredDeviceExtensions.remove(checked);
            requiredDeviceExtensions.add(checked);
            return this;
        }

        /**
         * Merges one logical-device extension using strongest-strength-wins semantics.
         *
         * @param extension non-blank extension name
         * @param required whether this declaration is mandatory
         * @return this builder
         */
        public Builder mergeDeviceExtension(String extension, boolean required) {
            if (required) return promoteDeviceExtension(extension);
            String checked = requireExtension(extension);
            if (!requiredDeviceExtensions.contains(checked)) preferredDeviceExtensions.add(checked);
            return this;
        }

        /**
         * Adds a preferred logical-device extension.
         * @param extension non-blank extension name
         * @return this builder
         */
        public Builder preferDeviceExtension(String extension) {
            preferredDeviceExtensions.add(requireExtension(extension));
            return this;
        }

        /**
         * Adds a required Vulkan 1.2 feature bit.
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder requireVulkan12Feature(Vulkan12Feature feature) {
            requiredVulkan12Features.add(Objects.requireNonNull(feature, "feature"));
            return this;
        }

        /**
         * Promotes an already preferred Vulkan 1.2 feature to a hard provider requirement.
         *
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder promoteVulkan12Feature(Vulkan12Feature feature) {
            Vulkan12Feature checked = Objects.requireNonNull(feature, "feature");
            preferredVulkan12Features.remove(checked);
            requiredVulkan12Features.add(checked);
            return this;
        }

        /**
         * Merges one Vulkan 1.2 feature using strongest-strength-wins semantics.
         *
         * @param feature non-null typed feature
         * @param required whether this declaration is mandatory
         * @return this builder
         */
        public Builder mergeVulkan12Feature(Vulkan12Feature feature, boolean required) {
            if (required) return promoteVulkan12Feature(feature);
            Vulkan12Feature checked = Objects.requireNonNull(feature, "feature");
            if (!requiredVulkan12Features.contains(checked)) preferredVulkan12Features.add(checked);
            return this;
        }

        /**
         * Adds a preferred Vulkan 1.2 feature bit.
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder preferVulkan12Feature(Vulkan12Feature feature) {
            preferredVulkan12Features.add(Objects.requireNonNull(feature, "feature"));
            return this;
        }

        /**
         * Adds a required Vulkan 1.3 feature bit.
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder requireVulkan13Feature(Vulkan13Feature feature) {
            requiredVulkan13Features.add(Objects.requireNonNull(feature, "feature"));
            return this;
        }

        /**
         * Promotes an already preferred Vulkan 1.3 feature to a hard provider requirement.
         *
         * <p>This operation is intentionally distinct from {@link #requireVulkan13Feature}:
         * ordinary duplicate declarations remain contract errors, while a composite provider may
         * discover that one selected capability makes another capability's preferred bit
         * mandatory. Promotion removes the weaker declaration atomically before adding the hard
         * requirement.</p>
         *
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder promoteVulkan13Feature(Vulkan13Feature feature) {
            Vulkan13Feature checked = Objects.requireNonNull(feature, "feature");
            preferredVulkan13Features.remove(checked);
            requiredVulkan13Features.add(checked);
            return this;
        }

        /**
         * Merges one Vulkan 1.3 feature using strongest-strength-wins semantics.
         *
         * @param feature non-null typed feature
         * @param required whether this declaration is mandatory
         * @return this builder
         */
        public Builder mergeVulkan13Feature(Vulkan13Feature feature, boolean required) {
            if (required) return promoteVulkan13Feature(feature);
            Vulkan13Feature checked = Objects.requireNonNull(feature, "feature");
            if (!requiredVulkan13Features.contains(checked)) preferredVulkan13Features.add(checked);
            return this;
        }

        /**
         * Adds a preferred Vulkan 1.3 feature bit.
         * @param feature non-null typed feature
         * @return this builder
         */
        public Builder preferVulkan13Feature(Vulkan13Feature feature) {
            preferredVulkan13Features.add(Objects.requireNonNull(feature, "feature"));
            return this;
        }

        /**
         * Adds queues that must be present or device creation must fail before provider opening.
         * @param queues non-null role-preserving queue counts
         * @return this builder
         */
        public Builder requireQueues(VulkanQueueRequirements queues) {
            requiredQueues = requiredQueues.plus(Objects.requireNonNull(queues, "queues"));
            return this;
        }

        /**
         * Adds queues used only when the physical device can satisfy the complete optional request.
         * @param queues non-null role-preserving queue counts
         * @return this builder
         */
        public Builder preferQueues(VulkanQueueRequirements queues) {
            preferredQueues = preferredQueues.plus(Objects.requireNonNull(queues, "queues"));
            return this;
        }

        /**
         * Declares the provider's current support state for one renderer feature.
         * @param feature non-null renderer feature
         * @param entry non-null capability entry
         * @return this builder
         */
        public Builder support(
                RenderingFeatureCapabilities.Feature feature,
                RenderingFeatureCapabilities.Entry entry
        ) {
            support.put(
                    Objects.requireNonNull(feature, "feature"),
                    Objects.requireNonNull(entry, "entry")
            );
            return this;
        }

        /**
         * Declares the provider's current state for one concrete technology.
         * @param technology non-null technology identity
         * @param entry non-null capability entry
         * @return this builder
         */
        public Builder technology(
                RenderingFeatureCapabilities.Technology technology,
                RenderingFeatureCapabilities.Entry entry
        ) {
            technologies.put(
                    Objects.requireNonNull(technology, "technology"),
                    Objects.requireNonNull(entry, "entry")
            );
            return this;
        }

        /**
         * Validates and creates an immutable requirement declaration.
         * @return immutable requirements
         */
        public VulkanFeatureRequirements build() {
            return new VulkanFeatureRequirements(this);
        }
    }

    private static Set<String> immutableExtensions(Set<String> source, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String extension : source) result.add(requireExtension(extension));
        return Collections.unmodifiableSet(result);
    }

    private static <E extends Enum<E>> Set<E> immutableFeatures(Set<E> source, Class<E> type) {
        return Collections.unmodifiableSet(EnumSet.copyOf(source.isEmpty() ? EnumSet.noneOf(type) : source));
    }

    private static <E> void requireNoOverlap(Set<E> required, Set<E> preferred, String label) {
        LinkedHashSet<E> overlap = new LinkedHashSet<>(required);
        overlap.retainAll(preferred);
        if (!overlap.isEmpty()) throw new IllegalArgumentException(label + " cannot be both required and preferred: " + overlap);
    }

    private static String requireExtension(String extension) {
        String value = Objects.requireNonNull(extension, "extension").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Vulkan extension must not be blank");
        return value;
    }
}
