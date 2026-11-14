package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;
import org.lwjgl.vulkan.NVRayTracingInvocationReorder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Deterministic ServiceLoader negotiation and composite feature-session owner.
 *
 * <p>This class is the only place that knows how optional providers compete and how preferred
 * requests degrade. Device bootstrap consumes only the resulting extension sets; frame code sees
 * only one composite session.</p>
 */
public final class VulkanFeatureRegistry {
    private VulkanFeatureRegistry() {
    }

    /**
     * Resolves feature providers and pre-device extension requirements.
     *
     * @param configuration immutable renderer configuration
     * @return deterministic pre-device plan
     */
    public static VulkanFeaturePlan plan(RendererConfig configuration) {
        RendererConfig checked = Objects.requireNonNull(configuration, "configuration");
        EnumMap<Feature, RendererFeaturePreference> preferences = preferences(checked);
        RenderingFeatureCapabilities.Builder capabilities = RenderingFeatureCapabilities.builder();
        LinkedHashSet<String> requiredInstanceExtensions = new LinkedHashSet<>();
        LinkedHashSet<String> preferredInstanceExtensions = new LinkedHashSet<>();
        LinkedHashSet<String> requiredExtensions = new LinkedHashSet<>();
        LinkedHashSet<String> preferredExtensions = new LinkedHashSet<>();
        EnumSet<Vulkan12Feature> requiredVulkan12Features = EnumSet.noneOf(Vulkan12Feature.class);
        EnumSet<Vulkan12Feature> preferredVulkan12Features = EnumSet.noneOf(Vulkan12Feature.class);
        EnumSet<Vulkan13Feature> requiredVulkan13Features = EnumSet.noneOf(Vulkan13Feature.class);
        EnumSet<Vulkan13Feature> preferredVulkan13Features = EnumSet.noneOf(Vulkan13Feature.class);
        // Generic render-command transactions use Vulkan dynamic rendering. Keep this optional so
        // the retained 1.0.x scene path still opens on Vulkan 1.2 devices, while the generic lane
        // can report itself unsupported instead of issuing illegal VK13 commands.
        preferredVulkan13Features.add(Vulkan13Feature.DYNAMIC_RENDERING);
        VulkanQueueRequirements requiredQueues = VulkanQueueRequirements.NONE;
        VulkanQueueRequirements preferredQueues = VulkanQueueRequirements.NONE;
        addCoreVulkanRequirements(
                checked.rayTracingOptimizations(), capabilities, requiredExtensions, preferredExtensions
        );

        List<VulkanFeatureProvider> discovered = discoverProviders();
        try {
        LinkedHashMap<String, VulkanFeatureRequirements> declarations = new LinkedHashMap<>();
        for (VulkanFeatureProvider provider : discovered) {
            declarations.put(
                    provider.id(),
                    Objects.requireNonNull(
                            provider.requirements(checked),
                            "feature provider " + provider.id() + " requirements"
                    )
            );
        }

        LinkedHashMap<String, Set<Technology>> technologyOwnership = new LinkedHashMap<>();
        EnumSet<Technology> claimedTechnologies = EnumSet.noneOf(Technology.class);
        for (VulkanFeatureProvider provider : discovered) {
            for (Map.Entry<Technology, Entry> declaration
                    : declarations.get(provider.id()).technologies().entrySet()) {
                if (declaration.getValue().status() == Status.DISABLED
                        || !claimedTechnologies.add(declaration.getKey())) continue;
                capabilities.technology(declaration.getKey(), declaration.getValue());
                technologyOwnership.computeIfAbsent(
                        provider.id(), ignored -> EnumSet.noneOf(Technology.class)
                ).add(declaration.getKey());
            }
        }

        EnumSet<Feature> providerFeatures = EnumSet.of(
                Feature.FRAME_RECONSTRUCTION,
                Feature.FRAME_GENERATION,
                Feature.LOW_LATENCY,
                Feature.DENOISING,
                Feature.MEMORY_OPTIMIZATION
        );
        LinkedHashMap<String, Set<Feature>> ownership = new LinkedHashMap<>();
        List<VulkanFeatureProvider> selectedProviders = new ArrayList<>();
        for (Feature feature : providerFeatures) {
            RendererFeaturePreference preference = preferences.get(feature);
            if (!preference.requested()) continue;
            VulkanFeatureProvider selected = null;
            Entry selectedEntry = null;
            for (VulkanFeatureProvider provider : discovered) {
                Entry candidate = declarations.get(provider.id()).support().get(feature);
                if (candidate != null && supportsActivation(candidate.status())) {
                    selected = provider;
                    selectedEntry = candidate;
                    break;
                }
            }
            if (selected == null) {
                Entry fallback = fallback(feature, checked);
                if (preference == RendererFeaturePreference.REQUIRED) {
                    throw new IllegalStateException("required rendering feature is unavailable: " + feature);
                }
                capabilities.feature(feature, fallback);
                continue;
            }
            capabilities.feature(feature, selectedEntry);
            ownership.computeIfAbsent(selected.id(), ignored -> EnumSet.noneOf(Feature.class)).add(feature);
            if (!selectedProviders.contains(selected)) selectedProviders.add(selected);
        }

        for (VulkanFeatureProvider provider : selectedProviders) {
            VulkanFeatureRequirements requirements = declarations.get(provider.id());
            mergeExtensions(
                    requiredInstanceExtensions, preferredInstanceExtensions,
                    requirements.requiredInstanceExtensions(), requirements.preferredInstanceExtensions()
            );
            mergeExtensions(requiredExtensions, preferredExtensions, requirements);
            mergeFeatures(
                    requiredVulkan12Features, preferredVulkan12Features,
                    requirements.requiredVulkan12Features(), requirements.preferredVulkan12Features()
            );
            mergeFeatures(
                    requiredVulkan13Features, preferredVulkan13Features,
                    requirements.requiredVulkan13Features(), requirements.preferredVulkan13Features()
            );
            requiredQueues = requiredQueues.plus(requirements.requiredQueues());
            preferredQueues = preferredQueues.plus(requirements.preferredQueues());
        }
        LinkedHashMap<String, VulkanQueueRequirements> providerRequiredQueues = new LinkedHashMap<>();
        LinkedHashMap<String, VulkanQueueRequirements> providerPreferredQueues = new LinkedHashMap<>();
        for (VulkanFeatureProvider provider : selectedProviders) {
            VulkanFeatureRequirements requirements = declarations.get(provider.id());
            providerRequiredQueues.put(provider.id(), requirements.requiredQueues());
            providerPreferredQueues.put(provider.id(), requirements.preferredQueues());
        }
        VulkanFeaturePlan plan = new VulkanFeaturePlan(
                requiredInstanceExtensions,
                preferredInstanceExtensions,
                requiredExtensions,
                preferredExtensions,
                requiredVulkan12Features,
                preferredVulkan12Features,
                requiredVulkan13Features,
                preferredVulkan13Features,
                requiredQueues,
                preferredQueues,
                selectedProviders,
                capabilities.build(),
                ownership,
                technologyOwnership,
                providerRequiredQueues,
                providerPreferredQueues,
                preferences
        );
        // Providers are instantiated per plan. A provider that lost every ownership tie must not
        // retain native preflight state until an unrelated later plan happens to run.
        for (VulkanFeatureProvider provider : discovered) {
            if (!selectedProviders.contains(provider)) provider.discardPlan();
        }
        return plan;
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            discardProvidersSuppressing(discovered, failure);
            throw failure;
        }
    }

    /**
     * Opens selected providers and returns their single ordered lifetime/frame boundary.
     *
     * @param plan pre-device plan used to create the logical device
     * @param device borrowed open device runtime
     * @param configuration immutable renderer configuration
     * @return composite feature session
     */
    public static VulkanFeatureSession openSession(
            VulkanFeaturePlan plan,
            VulkanDeviceRuntime device,
            RendererConfig configuration
    ) {
        VulkanFeaturePlan checkedPlan = Objects.requireNonNull(plan, "plan");
        List<VulkanFeatureProvider> providers = checkedPlan.claimProviders();
        VulkanFeatureOpenContext context = new VulkanFeatureOpenContext(device, configuration);
        ArrayList<OwnedSession> sessions = new ArrayList<>();
        RenderingFeatureCapabilities.Builder resolved = copyCapabilities(checkedPlan.capabilities());
        try {
            for (VulkanFeatureProvider provider : providers) {
                Set<Feature> features = checkedPlan.featuresFor(provider);
                Set<Technology> technologies = checkedPlan.technologiesFor(provider);
                VulkanFeatureSession openedSession = null;
                try {
                    openedSession = Objects.requireNonNull(
                            provider.open(context), "feature provider " + provider.id() + " session"
                    );
                    validateProviderSession(provider, features, openedSession.capabilities());
                    for (Feature feature : features) {
                        resolved.feature(feature, openedSession.capabilities().feature(feature));
                    }
                    for (Technology technology : technologies) {
                        resolved.technology(technology, openedSession.capabilities().technology(technology));
                    }
                    sessions.add(new OwnedSession(provider.id(), features, technologies, openedSession));
                    openedSession = null;
                } catch (RuntimeException | LinkageError openFailure) {
                    closeSessionSuppressing(openedSession, openFailure);
                    if (containsRequired(checkedPlan, features)) throw openFailure;
                    discardProviderSuppressing(provider, openFailure);
                    for (Feature feature : features) {
                        Entry fallback = fallback(feature, configuration);
                        resolved.feature(feature, Entry.of(
                                fallback.status(),
                                fallback.implementation(),
                                fallback.reason() + "; provider " + provider.id() + " failed to open: "
                                        + openFailure.getClass().getSimpleName()
                                        + (openFailure.getMessage() == null ? "" : ": " + openFailure.getMessage())
                        ));
                    }
                    for (Technology technology : technologies) {
                        Entry planned = resolved.build().technology(technology);
                        resolved.technology(technology, Entry.of(
                                Status.BLOCKED,
                                planned.implementation(),
                                "provider " + provider.id() + " failed to initialize " + technology + ": "
                                        + openFailure.getClass().getSimpleName()
                                        + (openFailure.getMessage() == null ? "" : ": " + openFailure.getMessage())
                        ));
                    }
                }
            }
            return new CompositeSession(resolved.build(), sessions);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(sessions, failure);
            discardProvidersSuppressing(providers, failure);
            throw failure;
        }
    }

    private static void discardProvidersSuppressing(
            List<VulkanFeatureProvider> providers,
            Throwable failure
    ) {
        for (int index = providers.size() - 1; index >= 0; index--) {
            discardProviderSuppressing(providers.get(index), failure);
        }
    }

    private static void closeSessionSuppressing(VulkanFeatureSession session, Throwable failure) {
        if (session == null) return;
        try {
            session.close();
        } catch (RuntimeException | LinkageError closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void discardProviderSuppressing(
            VulkanFeatureProvider provider,
            Throwable failure
    ) {
        try {
            provider.discardPlan();
        } catch (RuntimeException | LinkageError discardFailure) {
            failure.addSuppressed(discardFailure);
        }
    }

    private static List<VulkanFeatureProvider> discoverProviders() {
        ArrayList<VulkanFeatureProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(VulkanFeatureProvider.class).forEach(providers::add);
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("failed to discover Vulkan feature providers", failure);
        }
        providers.sort(Comparator.comparingInt(VulkanFeatureProvider::priority).reversed()
                .thenComparing(VulkanFeatureProvider::id));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (VulkanFeatureProvider provider : providers) {
            String id = requireProviderId(provider.id());
            if (!ids.add(id)) throw new IllegalStateException("duplicate Vulkan feature provider id: " + id);
        }
        return List.copyOf(providers);
    }

    private static void addCoreVulkanRequirements(
            RayTracingOptimizationOptions options,
            RenderingFeatureCapabilities.Builder capabilities,
            Set<String> required,
            Set<String> preferred
    ) {
        addCoreDeviceFeature(
                Feature.SHADER_EXECUTION_REORDERING,
                options.shaderExecutionReordering(),
                NVRayTracingInvocationReorder.VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME,
                capabilities,
                required,
                preferred
        );
    }

    private static void addCoreDeviceFeature(
            Feature feature,
            RendererFeaturePreference preference,
            String extension,
            RenderingFeatureCapabilities.Builder capabilities,
            Set<String> required,
            Set<String> preferred
    ) {
        if (!preference.requested()) return;
        if (preference == RendererFeaturePreference.REQUIRED) required.add(extension);
        else preferred.add(extension);
        capabilities.feature(
                feature,
                Entry.of(Status.BLOCKED, "none", "pending physical-device feature negotiation")
        );
        Technology technology = Technology.SHADER_EXECUTION_REORDERING;
        capabilities.technology(
                technology,
                Entry.of(Status.BLOCKED, "none", "pending physical-device feature negotiation")
        );
    }

    private static EnumMap<Feature, RendererFeaturePreference> preferences(
            RendererConfig configuration
    ) {
        EnumMap<Feature, RendererFeaturePreference> result = new EnumMap<>(Feature.class);
        result.put(Feature.FRAME_RECONSTRUCTION, configuration.frameReconstruction().preference());
        result.put(Feature.FRAME_GENERATION, configuration.frameGeneration().preference());
        result.put(Feature.LOW_LATENCY, configuration.lowLatency().preference());
        result.put(Feature.DENOISING, configuration.denoising().preference());
        result.put(
                Feature.SHADER_EXECUTION_REORDERING,
                configuration.rayTracingOptimizations().shaderExecutionReordering()
        );
        result.put(Feature.MEMORY_OPTIMIZATION, configuration.rayTracingOptimizations().memoryOptimization());
        return result;
    }

    private static Entry fallback(Feature feature, RendererConfig configuration) {
        if (feature == Feature.FRAME_RECONSTRUCTION) {
            FrameReconstructionOptions options = configuration.frameReconstruction();
            if (options.fallback() == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL
                    && configuration.temporalRendering().enabled()) {
                return Entry.of(Status.FALLBACK_PENDING, "renderer.temporal", "preferred reconstruction is unavailable");
            }
        } else if (feature == Feature.FRAME_GENERATION) {
            FrameGenerationOptions options = configuration.frameGeneration();
            if (options.fallback() == FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES) {
                return Entry.of(
                        Status.FALLBACK_PENDING,
                        "renderer.native-presentation",
                        "preferred frame generation is unavailable; presenting native renderer frames"
                );
            }
        } else if (feature == Feature.DENOISING) {
            DenoisingOptions options = configuration.denoising();
            if (options.builtInTemporalFallback() && configuration.temporalRendering().enabled()) {
                return Entry.of(Status.FALLBACK_PENDING, "renderer.temporal", "preferred denoiser is unavailable");
            }
        }
        return Entry.of(Status.NOT_SUPPORTED, "none", "no compatible feature provider is available");
    }

    private static void mergeExtensions(
            Set<String> required,
            Set<String> preferred,
            VulkanFeatureRequirements requirements
    ) {
        mergeExtensions(
                required, preferred, requirements.requiredDeviceExtensions(), requirements.preferredDeviceExtensions()
        );
    }

    private static <E extends Enum<E>> void mergeFeatures(
            Set<E> required,
            Set<E> preferred,
            Set<E> requiredFeatures,
            Set<E> preferredFeatures
    ) {
        for (E feature : requiredFeatures) {
            preferred.remove(feature);
            required.add(feature);
        }
        for (E feature : preferredFeatures) {
            if (!required.contains(feature)) preferred.add(feature);
        }
    }

    private static void mergeExtensions(
            Set<String> required,
            Set<String> preferred,
            Set<String> requiredExtensions,
            Set<String> preferredExtensions
    ) {
        for (String extension : requiredExtensions) {
            preferred.remove(extension);
            required.add(extension);
        }
        for (String extension : preferredExtensions) {
            if (!required.contains(extension)) preferred.add(extension);
        }
    }

    private static RenderingFeatureCapabilities.Builder copyCapabilities(
            RenderingFeatureCapabilities source
    ) {
        RenderingFeatureCapabilities.Builder result = RenderingFeatureCapabilities.builder();
        source.features().forEach(result::feature);
        source.technologies().forEach(result::technology);
        return result;
    }

    private static boolean supportsActivation(Status status) {
        return status == Status.AVAILABLE || status == Status.ACTIVE
                || status == Status.FALLBACK_PENDING || status == Status.FALLBACK;
    }

    private static boolean containsRequired(VulkanFeaturePlan plan, Set<Feature> features) {
        return features.stream().anyMatch(feature -> plan.preference(feature) == RendererFeaturePreference.REQUIRED);
    }

    private static void validateProviderSession(
            VulkanFeatureProvider provider,
            Set<Feature> owned,
            RenderingFeatureCapabilities capabilities
    ) {
        Objects.requireNonNull(capabilities, "feature session capabilities");
        for (Feature feature : owned) {
            Status status = capabilities.feature(feature).status();
            if (status != Status.ACTIVE && status != Status.AVAILABLE
                    && status != Status.FALLBACK_PENDING && status != Status.FALLBACK) {
                throw new IllegalStateException(
                        "feature provider " + provider.id() + " opened without activating " + feature
                );
            }
        }
    }

    private static String requireProviderId(String id) {
        String checked = Objects.requireNonNull(id, "feature provider id").trim();
        if (checked.isEmpty()) throw new IllegalStateException("feature provider id must not be blank");
        return checked;
    }

    private static void closeSuppressing(List<OwnedSession> sessions, Throwable failure) {
        for (int index = sessions.size() - 1; index >= 0; index--) {
            try {
                sessions.get(index).session().close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private record OwnedSession(
            String providerId,
            Set<Feature> features,
            Set<Technology> technologies,
            VulkanFeatureSession session
    ) {
        private OwnedSession {
            providerId = requireProviderId(providerId);
            features = Set.copyOf(features);
            technologies = Set.copyOf(technologies);
            session = Objects.requireNonNull(session, "session");
        }
    }

    private static final class CompositeSession implements VulkanFeatureSession {
        private final RenderingFeatureCapabilities capabilities;
        private final List<OwnedSession> sessions;
        private boolean closed;

        private CompositeSession(RenderingFeatureCapabilities capabilities, List<OwnedSession> sessions) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            this.sessions = List.copyOf(sessions);
        }

        @Override
        public RenderingFeatureCapabilities capabilities() {
            // The disabled configuration owns no provider sessions. Its resolved capabilities
            // are already immutable, so rebuilding an EnumMap on every admission attempt would
            // add allocation and lock pressure to the native baseline for no semantic benefit.
            if (sessions.isEmpty()) return capabilities;
            RenderingFeatureCapabilities.Builder resolved = copyCapabilities(capabilities);
            for (OwnedSession session : sessions) {
                RenderingFeatureCapabilities providerCapabilities = session.session().capabilities();
                for (Feature feature : session.features()) {
                    resolved.feature(feature, providerCapabilities.feature(feature));
                }
                for (Technology technology : session.technologies()) {
                    resolved.technology(technology, providerCapabilities.technology(technology));
                }
            }
            return resolved.build();
        }

        @Override
        public ReconfigurationAssessment assessReconfiguration(
                RendererFeatureProfile source,
                RendererFeatureProfile target
        ) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RendererFeatureProfile checkedSource = Objects.requireNonNull(source, "source");
            RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
            Set<Feature> changed = changedFeatures(checkedSource, checkedTarget);
            if (changed.isEmpty()) {
                return ReconfigurationAssessment.frameDrain("provider-owned profile is unchanged");
            }
            List<OwnedSession> affected = sessions.stream()
                    .filter(session -> !java.util.Collections.disjoint(session.features(), changed))
                    .toList();
            Set<Feature> covered = EnumSet.noneOf(Feature.class);
            affected.forEach(session -> covered.addAll(session.features()));
            if (!covered.containsAll(changed)) {
                return ReconfigurationAssessment.rendererRebuild(
                        "target enables or changes a feature with no reserved provider session: "
                                + difference(changed, covered)
                );
            }
            if (affected.size() != 1) {
                return ReconfigurationAssessment.rendererRebuild(
                        "atomic runtime transition spans multiple provider sessions"
                );
            }
            return affected.get(0).session().assessReconfiguration(
                    checkedSource, checkedTarget
            );
        }

        @Override
        public void applyReconfiguration(
                RendererFeatureProfile source,
                RendererFeatureProfile target
        ) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            Set<Feature> changed = changedFeatures(
                    Objects.requireNonNull(source, "source"),
                    Objects.requireNonNull(target, "target")
            );
            List<OwnedSession> affected = sessions.stream()
                    .filter(session -> !java.util.Collections.disjoint(session.features(), changed))
                    .toList();
            if (affected.size() != 1) {
                throw new IllegalStateException(
                        "feature transition no longer has one atomic provider owner"
                );
            }
            affected.get(0).session().applyReconfiguration(source, target);
        }

        private static Set<Feature> changedFeatures(
                RendererFeatureProfile source,
                RendererFeatureProfile target
        ) {
            EnumSet<Feature> changed = EnumSet.noneOf(Feature.class);
            if (!source.frameReconstruction().equals(target.frameReconstruction())) {
                changed.add(Feature.FRAME_RECONSTRUCTION);
            }
            if (!source.frameGeneration().equals(target.frameGeneration())) {
                changed.add(Feature.FRAME_GENERATION);
            }
            if (!source.lowLatency().equals(target.lowLatency())) {
                changed.add(Feature.LOW_LATENCY);
            }
            if (!source.denoising().equals(target.denoising())) {
                changed.add(Feature.DENOISING);
            }
            if (source.rayTracingOptimizations().shaderExecutionReordering()
                    != target.rayTracingOptimizations().shaderExecutionReordering()) {
                changed.add(Feature.SHADER_EXECUTION_REORDERING);
            }
            if (source.rayTracingOptimizations().memoryOptimization()
                    != target.rayTracingOptimizations().memoryOptimization()) {
                changed.add(Feature.MEMORY_OPTIMIZATION);
            }
            return changed;
        }

        private static Set<Feature> difference(Set<Feature> left, Set<Feature> right) {
            EnumSet<Feature> result = EnumSet.copyOf(left);
            result.removeAll(right);
            return Set.copyOf(result);
        }

        @Override
        public FrameGenerationEvidence frameGenerationEvidence() {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            FrameGenerationEvidence selected = FrameGenerationEvidence.unavailable();
            String selectedProvider = null;
            for (OwnedSession owned : sessions) {
                if (!owned.features().contains(Feature.FRAME_GENERATION)) continue;
                FrameGenerationEvidence candidate = Objects.requireNonNull(
                        owned.session().frameGenerationEvidence(),
                        "feature provider frame-generation evidence"
                );
                if (!candidate.reported()) continue;
                if (selectedProvider != null) {
                    throw new IllegalStateException(
                            "multiple providers reported frame-generation evidence: "
                                    + selectedProvider + ", " + owned.providerId()
                    );
                }
                selected = candidate;
                selectedProvider = owned.providerId();
            }
            return selected;
        }

        @Override
        public TechnologyExecutionEvidence technologyExecutionEvidence() {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            TechnologyExecutionEvidence.Builder merged = TechnologyExecutionEvidence.builder();
            for (OwnedSession owned : sessions) {
                TechnologyExecutionEvidence providerEvidence = Objects.requireNonNull(
                        owned.session().technologyExecutionEvidence(),
                        "feature provider technology execution evidence"
                );
                for (Technology technology : owned.technologies()) {
                    merged.technology(technology, providerEvidence.technology(technology));
                }
            }
            return merged.build();
        }

        @Override
        public void recordPostTrace(VulkanFeatureFrameContext context) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
            recordDenoising(checked);
            recordReconstruction(checked);
        }

        @Override
        public void recordDenoising(VulkanFeatureFrameContext context) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
            for (OwnedSession session : sessions) session.session().recordDenoising(checked);
        }

        @Override
        public void recordReconstruction(VulkanFeatureFrameContext context) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
            for (OwnedSession session : sessions) session.session().recordReconstruction(checked);
        }

        @Override
        public void recordFrameGeneration(VulkanFeatureFrameContext context) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
            for (OwnedSession session : sessions) session.session().recordFrameGeneration(checked);
        }

        @Override
        public InputCompletion awaitFrameInputReuse(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            InputCompletion completion = InputCompletion.none();
            for (OwnedSession session : sessions) {
                InputCompletion candidate = Objects.requireNonNull(
                        session.session().awaitFrameInputReuse(frameSequence),
                        "feature input completion"
                );
                if (!candidate.enabled()) continue;
                if (completion.enabled() && !completion.equals(candidate)) {
                    throw new IllegalStateException(
                            "multiple feature providers published incompatible input-completion waits"
                    );
                }
                completion = candidate;
            }
            return completion;
        }

        @Override
        public void commitFrameInputReuse(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            for (OwnedSession session : sessions) {
                session.session().commitFrameInputReuse(frameSequence);
            }
        }

        @Override
        public void beginFramePreparation(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            int opened = 0;
            try {
                for (; opened < sessions.size(); opened++) {
                    sessions.get(opened).session().beginFramePreparation(frameSequence);
                }
            } catch (RuntimeException | Error failure) {
                for (int index = opened - 1; index >= 0; index--) {
                    try {
                        sessions.get(index).session().cancelFramePreparation(frameSequence);
                    } catch (RuntimeException | Error closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void cancelFramePreparation(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                try {
                    sessions.get(index).session().cancelFramePreparation(frameSequence);
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null && errorFailure == null) runtimeFailure = failure;
                    else if (errorFailure != null) errorFailure.addSuppressed(failure);
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (runtimeFailure == null && errorFailure == null) errorFailure = failure;
                    else if (errorFailure != null) errorFailure.addSuppressed(failure);
                    else runtimeFailure.addSuppressed(failure);
                }
            }
            if (runtimeFailure != null) throw runtimeFailure;
            if (errorFailure != null) throw errorFailure;
        }

        @Override
        public void beginFrameSubmission(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            int opened = 0;
            try {
                for (; opened < sessions.size(); opened++) {
                    sessions.get(opened).session().beginFrameSubmission(frameSequence);
                }
            } catch (RuntimeException | Error failure) {
                for (int index = opened - 1; index >= 0; index--) {
                    try {
                        sessions.get(index).session().endFrameSubmission(frameSequence);
                    } catch (RuntimeException | Error closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            }
        }

        @Override
        public void endFrameSubmission(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                try {
                    sessions.get(index).session().endFrameSubmission(frameSequence);
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null && errorFailure == null) runtimeFailure = failure;
                    else if (errorFailure != null) errorFailure.addSuppressed(failure);
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (runtimeFailure == null && errorFailure == null) errorFailure = failure;
                    else if (errorFailure != null) errorFailure.addSuppressed(failure);
                    else runtimeFailure.addSuppressed(failure);
                }
            }
            if (runtimeFailure != null) throw runtimeFailure;
            if (errorFailure != null) throw errorFailure;
        }

        @Override
        public void commitFrameSubmission(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            for (OwnedSession session : sessions) {
                session.session().commitFrameSubmission(frameSequence);
            }
        }

        @Override
        public void observeFrameCompletion(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            for (OwnedSession session : sessions) {
                try {
                    session.session().observeFrameCompletion(frameSequence);
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null) runtimeFailure = failure;
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (errorFailure == null) errorFailure = failure;
                    else errorFailure.addSuppressed(failure);
                }
            }
            if (runtimeFailure != null) {
                if (errorFailure != null) runtimeFailure.addSuppressed(errorFailure);
                throw runtimeFailure;
            }
            if (errorFailure != null) throw errorFailure;
        }

        @Override
        public void discardFrameSubmission(long frameSequence) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RuntimeException failure = null;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                try {
                    sessions.get(index).session().discardFrameSubmission(frameSequence);
                } catch (RuntimeException discardFailure) {
                    if (failure == null) failure = discardFailure;
                    else failure.addSuppressed(discardFailure);
                }
            }
            if (failure != null) throw failure;
        }

        @Override
        public void observePresentation(long frameSequence, boolean succeeded) {
            if (closed) throw new IllegalStateException("Vulkan feature session is closed");
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            for (OwnedSession session : sessions) {
                try {
                    session.session().observePresentation(frameSequence, succeeded);
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null) runtimeFailure = failure;
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (errorFailure == null) errorFailure = failure;
                    else errorFailure.addSuppressed(failure);
                }
            }
            if (runtimeFailure != null) {
                if (errorFailure != null) runtimeFailure.addSuppressed(errorFailure);
                throw runtimeFailure;
            }
            if (errorFailure != null) throw errorFailure;
        }

        @Override
        public VulkanFrameExtents negotiateFrameExtents(RenderFrameRequest request, VulkanFrameExtents requested) {
            VulkanFrameExtents negotiated = Objects.requireNonNull(requested, "requested");
            for (OwnedSession session : sessions) {
                negotiated = session.session().negotiateFrameExtents(request, negotiated);
            }
            return negotiated;
        }

        @Override
        public boolean extentNegotiationMayChangeCapabilities() {
            for (OwnedSession session : sessions) {
                if (session.session().extentNegotiationMayChangeCapabilities()) return true;
            }
            return false;
        }

        @Override
        public Optional<VulkanSwapchainInterceptor> swapchainInterceptor() {
            VulkanSwapchainInterceptor selected = null;
            for (OwnedSession session : sessions) {
                Optional<VulkanSwapchainInterceptor> candidate = session.session().swapchainInterceptor();
                if (candidate.isEmpty()) continue;
                if (selected != null) {
                    throw new IllegalStateException("multiple feature providers attempted to intercept one swapchain");
                }
                selected = candidate.orElseThrow();
            }
            return Optional.ofNullable(selected);
        }

        @Override
        public Optional<VulkanAccelerationStructureMemoryOptimizer> accelerationStructureMemoryOptimizer() {
            VulkanAccelerationStructureMemoryOptimizer selected = null;
            for (OwnedSession session : sessions) {
                Optional<VulkanAccelerationStructureMemoryOptimizer> candidate =
                        session.session().accelerationStructureMemoryOptimizer();
                if (candidate.isEmpty()) continue;
                if (selected != null) {
                    throw new IllegalStateException(
                            "multiple providers claimed acceleration-structure memory ownership"
                    );
                }
                selected = candidate.orElseThrow();
            }
            return Optional.ofNullable(selected);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            for (int index = sessions.size() - 1; index >= 0; index--) {
                try {
                    sessions.get(index).session().close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
