package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable result of pre-device feature negotiation. */
public final class VulkanFeaturePlan implements AutoCloseable {
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
    private final List<VulkanFeatureProvider> providers;
    private final RenderingFeatureCapabilities capabilities;
    private final Map<String, Set<RenderingFeatureCapabilities.Feature>> providerFeatures;
    private final Map<String, Set<RenderingFeatureCapabilities.Technology>> providerTechnologies;
    private final Map<String, VulkanQueueRequirements> providerRequiredQueueRequirements;
    private final Map<String, VulkanQueueRequirements> providerPreferredQueueRequirements;
    private final Map<RenderingFeatureCapabilities.Feature, RendererFeaturePreference> preferences;
    private Lifecycle lifecycle = Lifecycle.PLANNED;

    VulkanFeaturePlan(
            Set<String> requiredInstanceExtensions,
            Set<String> preferredInstanceExtensions,
            Set<String> requiredDeviceExtensions,
            Set<String> preferredDeviceExtensions,
            Set<Vulkan12Feature> requiredVulkan12Features,
            Set<Vulkan12Feature> preferredVulkan12Features,
            Set<Vulkan13Feature> requiredVulkan13Features,
            Set<Vulkan13Feature> preferredVulkan13Features,
            VulkanQueueRequirements requiredQueues,
            VulkanQueueRequirements preferredQueues,
            List<VulkanFeatureProvider> providers,
            RenderingFeatureCapabilities capabilities,
            Map<String, Set<RenderingFeatureCapabilities.Feature>> providerFeatures,
            Map<String, Set<RenderingFeatureCapabilities.Technology>> providerTechnologies,
            Map<String, VulkanQueueRequirements> providerRequiredQueueRequirements,
            Map<String, VulkanQueueRequirements> providerPreferredQueueRequirements,
            Map<RenderingFeatureCapabilities.Feature, RendererFeaturePreference> preferences
    ) {
        this.requiredInstanceExtensions = Set.copyOf(requiredInstanceExtensions);
        this.preferredInstanceExtensions = Set.copyOf(preferredInstanceExtensions);
        this.requiredDeviceExtensions = Set.copyOf(requiredDeviceExtensions);
        this.preferredDeviceExtensions = Set.copyOf(preferredDeviceExtensions);
        this.requiredVulkan12Features = Set.copyOf(requiredVulkan12Features);
        this.preferredVulkan12Features = Set.copyOf(preferredVulkan12Features);
        this.requiredVulkan13Features = Set.copyOf(requiredVulkan13Features);
        this.preferredVulkan13Features = Set.copyOf(preferredVulkan13Features);
        this.requiredQueues = Objects.requireNonNull(requiredQueues, "requiredQueues");
        this.preferredQueues = Objects.requireNonNull(preferredQueues, "preferredQueues");
        this.providers = List.copyOf(providers);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        LinkedHashMap<String, Set<RenderingFeatureCapabilities.Feature>> ownership = new LinkedHashMap<>();
        providerFeatures.forEach((provider, features) -> ownership.put(provider, Set.copyOf(features)));
        this.providerFeatures = Collections.unmodifiableMap(ownership);
        LinkedHashMap<String, Set<RenderingFeatureCapabilities.Technology>> technologyOwnership =
                new LinkedHashMap<>();
        providerTechnologies.forEach((provider, technologies) ->
                technologyOwnership.put(provider, Set.copyOf(technologies))
        );
        this.providerTechnologies = Collections.unmodifiableMap(technologyOwnership);
        LinkedHashMap<String, VulkanQueueRequirements> requiredQueuesByProvider = new LinkedHashMap<>();
        providerRequiredQueueRequirements.forEach((provider, requirements) -> requiredQueuesByProvider.put(
                Objects.requireNonNull(provider, "provider queue owner"),
                Objects.requireNonNull(requirements, "provider queue requirements")
        ));
        this.providerRequiredQueueRequirements = Collections.unmodifiableMap(requiredQueuesByProvider);
        LinkedHashMap<String, VulkanQueueRequirements> preferredQueuesByProvider = new LinkedHashMap<>();
        providerPreferredQueueRequirements.forEach((provider, requirements) -> preferredQueuesByProvider.put(
                Objects.requireNonNull(provider, "provider queue owner"),
                Objects.requireNonNull(requirements, "provider queue requirements")
        ));
        this.providerPreferredQueueRequirements = Collections.unmodifiableMap(preferredQueuesByProvider);
        EnumMap<RenderingFeatureCapabilities.Feature, RendererFeaturePreference> requested =
                new EnumMap<>(RenderingFeatureCapabilities.Feature.class);
        requested.putAll(preferences);
        this.preferences = Collections.unmodifiableMap(requested);
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
    public Set<String> requiredInstanceExtensions() { return requiredInstanceExtensions; }

    /**
     * Returns preferred Vulkan instance extensions.
     * @return immutable extension set
     */
    public Set<String> preferredInstanceExtensions() { return preferredInstanceExtensions; }

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
     * Returns selected providers in deterministic priority order.
     * @return immutable provider list
     */
    public List<VulkanFeatureProvider> providers() {
        return Collections.unmodifiableList(providers);
    }

    /**
     * Releases provider preflight state when this plan is abandoned before device opening.
     *
     * <p>Once {@link VulkanFeatureRegistry#openSession(VulkanFeaturePlan,
     * top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime,
     * top.ceroxe.rt.renderer.api.RendererConfig)} claims the plan, provider ownership is
     * transferred to the session-opening transaction and this method becomes an idempotent no-op.</p>
     */
    @Override
    public void close() {
        List<VulkanFeatureProvider> abandoned;
        synchronized (this) {
            if (lifecycle == Lifecycle.CLOSED) return;
            abandoned = lifecycle == Lifecycle.PLANNED ? providers : List.of();
            lifecycle = Lifecycle.CLOSED;
        }
        Throwable failure = null;
        for (int index = abandoned.size() - 1; index >= 0; index--) {
            try {
                abandoned.get(index).discardPlan();
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof LinkageError linkageFailure) throw linkageFailure;
    }

    synchronized List<VulkanFeatureProvider> claimProviders() {
        if (lifecycle != Lifecycle.PLANNED) {
            throw new IllegalStateException("Vulkan feature plan has already been claimed or closed");
        }
        lifecycle = Lifecycle.CLAIMED;
        return providers;
    }

    /**
     * Returns the pre-device capability result.
     * @return immutable capability result
     */
    public RenderingFeatureCapabilities capabilities() {
        return capabilities;
    }

    Set<RenderingFeatureCapabilities.Feature> featuresFor(VulkanFeatureProvider provider) {
        return providerFeatures.getOrDefault(provider.id(), Set.of());
    }

    Set<RenderingFeatureCapabilities.Technology> technologiesFor(VulkanFeatureProvider provider) {
        return providerTechnologies.getOrDefault(provider.id(), Set.of());
    }

    /**
     * Returns all additional queues requested by a selected provider.
     *
     * @param provider selected non-null provider
     * @return required plus preferred provider-scoped requests
     */
    public VulkanQueueRequirements queueRequirementsFor(VulkanFeatureProvider provider) {
        String providerId = Objects.requireNonNull(provider, "provider").id();
        return providerRequiredQueueRequirements.getOrDefault(providerId, VulkanQueueRequirements.NONE)
                .plus(providerPreferredQueueRequirements.getOrDefault(
                        providerId, VulkanQueueRequirements.NONE
                ));
    }

    /**
     * Returns immutable required queue requests keyed by selected provider id.
     *
     * @return provider-id to queue-requirement mapping
     */
    public Map<String, VulkanQueueRequirements> providerRequiredQueueRequirements() {
        return providerRequiredQueueRequirements;
    }

    /**
     * Returns immutable preferred queue requests keyed by selected provider id.
     *
     * @return provider-id to preferred queue-requirement mapping
     */
    public Map<String, VulkanQueueRequirements> providerPreferredQueueRequirements() {
        return providerPreferredQueueRequirements;
    }

    RendererFeaturePreference preference(RenderingFeatureCapabilities.Feature feature) {
        return preferences.getOrDefault(feature, RendererFeaturePreference.DISABLED);
    }

    private enum Lifecycle {
        PLANNED,
        CLAIMED,
        CLOSED
    }
}
