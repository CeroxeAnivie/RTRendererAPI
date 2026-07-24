package top.ceroxe.mcvulkanrt.renderer.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.HashSet;
import java.util.Set;

import top.ceroxe.mcvulkanrt.renderer.spi.RayTracingBackendProvider;

/** Deterministic provider discovery without a compile-time dependency on renderer-core. */
public final class RendererBootstrap {
    private RendererBootstrap() {
    }

    public static RayTracingRenderer open() {
        return open(RayTracingRendererConfig.defaults());
    }

    public static RayTracingRenderer open(RayTracingRendererConfig configuration) {
        return open(null, configuration);
    }

    public static RayTracingRenderer open(String requiredProviderId, RayTracingRendererConfig configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (requiredProviderId != null && requiredProviderId.isBlank()) {
            throw new IllegalArgumentException("requiredProviderId must be null or non-blank");
        }
        return openCandidates(
                requiredProviderId,
                configuration,
                discover(Thread.currentThread().getContextClassLoader())
        );
    }

    static RayTracingRenderer openProviders(
            String requiredProviderId,
            RayTracingRendererConfig configuration,
            List<RayTracingBackendProvider> providers
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(providers, "providers");
        return openCandidates(requiredProviderId, configuration, candidates(providers));
    }

    private static RayTracingRenderer openCandidates(
            String requiredProviderId,
            RayTracingRendererConfig configuration,
            List<Candidate> candidates
    ) {
        List<RendererUnavailableException.BackendAttempt> attempts = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (requiredProviderId != null && !requiredProviderId.equals(candidate.descriptor().id())) {
                continue;
            }
            RayTracingBackendProvider.ProbeResult probe;
            try {
                probe = Objects.requireNonNull(candidate.provider().probe(configuration), "provider probe result");
            } catch (RuntimeException failure) {
                throw new RendererInitializationException(
                        "backend probe failed: " + candidate.descriptor().id(),
                        candidate.descriptor().id(),
                        failure
                );
            }
            attempts.add(new RendererUnavailableException.BackendAttempt(
                    candidate.descriptor().id(), probe.compatibility().name(), probe.reason()
            ));
            if (probe.compatibility() != RayTracingBackendProvider.Compatibility.COMPATIBLE) {
                continue;
            }
            try {
                return Objects.requireNonNull(
                        candidate.provider().open(configuration),
                        () -> "backend provider returned null: " + candidate.descriptor().id()
                );
            } catch (RuntimeException failure) {
                throw new RendererInitializationException(
                        "backend initialization failed: " + candidate.descriptor().id(),
                        candidate.descriptor().id(),
                        failure
                );
            }
        }
        throw new RendererUnavailableException(
                requiredProviderId == null
                        ? "no installed ray tracing backend is compatible"
                        : "required ray tracing backend is not compatible: " + requiredProviderId,
                attempts
        );
    }

    static List<Candidate> discover(ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? RendererBootstrap.class.getClassLoader() : classLoader;
        List<RayTracingBackendProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(RayTracingBackendProvider.class, loader).forEach(providers::add);
        } catch (ServiceConfigurationError error) {
            throw new RendererInitializationException("renderer provider discovery failed", "service-loader", error);
        }
        return candidates(providers);
    }

    private static List<Candidate> candidates(List<RayTracingBackendProvider> providers) {
        Set<String> ids = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>(providers.size());
        for (RayTracingBackendProvider provider : providers) {
            RayTracingBackendProvider.Descriptor descriptor = Objects.requireNonNull(
                    provider.descriptor(), "provider descriptor"
            );
            if (descriptor.apiMajor() != RayTracingBackendProvider.API_MAJOR) {
                continue;
            }
            if (!ids.add(descriptor.id())) {
                throw new RendererInitializationException(
                        "duplicate renderer provider id: " + descriptor.id(), descriptor.id(), null
                );
            }
            candidates.add(new Candidate(provider, descriptor));
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.descriptor().priority()).reversed()
                .thenComparing(candidate -> candidate.descriptor().id()));
        return List.copyOf(candidates);
    }

    record Candidate(RayTracingBackendProvider provider, RayTracingBackendProvider.Descriptor descriptor) {
        Candidate {
            provider = Objects.requireNonNull(provider, "provider");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
