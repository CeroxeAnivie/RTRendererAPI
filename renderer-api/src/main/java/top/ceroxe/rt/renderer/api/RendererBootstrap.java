package top.ceroxe.rt.renderer.api;

import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

import java.util.*;

/**
 * Deterministic provider discovery without a compile-time dependency on renderer-core.
 */
public final class RendererBootstrap {
    private RendererBootstrap() {
    }

    /**
     * Opens the highest-priority compatible provider using default policy.
     *
     * @param preset simple-mode renderer preset
     * @return newly owned renderer instance
     */
    public static Renderer open(RendererPreset preset) {
        RendererPreset checkedPreset = Objects.requireNonNull(preset, "preset");
        return openCandidates(
                null,
                checkedPreset.configuration(),
                discover(Thread.currentThread().getContextClassLoader())
        );
    }

    /**
     * Opens the highest-priority provider using one complete expert configuration.
     *
     * @param configuration immutable expert renderer policy
     * @return newly owned renderer instance
     */
    public static Renderer open(RendererConfig configuration) {
        return openCandidates(
                null,
                Objects.requireNonNull(configuration, "configuration"),
                discover(Thread.currentThread().getContextClassLoader())
        );
    }

    /**
     * Opens a renderer from one explicitly required provider.
     *
     * @param requiredProviderId required non-blank provider id
     * @param configuration      immutable renderer policy
     * @return newly owned renderer instance
     */
    public static Renderer open(
            String requiredProviderId,
            RendererConfig configuration
    ) {
        Objects.requireNonNull(requiredProviderId, "requiredProviderId");
        Objects.requireNonNull(configuration, "configuration");
        if (requiredProviderId.isBlank()) {
            throw new IllegalArgumentException("requiredProviderId must be non-blank");
        }
        return openCandidates(
                requiredProviderId,
                configuration,
                discover(Thread.currentThread().getContextClassLoader())
        );
    }

    /**
     * Enumerates immutable capability snapshots for every currently available hardware RT GPU.
     * The returned devices are safe to retain, but opening a stale or removed device fails closed.
     *
     * @return immutable device list in deterministic provider order
     */
    public static List<RendererGpuDevice> availableGpuDevices() {
        List<RendererGpuDevice> devices = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Candidate candidate : discover(Thread.currentThread().getContextClassLoader())) {
            List<RendererGpuDevice> reported;
            try {
                reported = List.copyOf(Objects.requireNonNull(
                        candidate.provider().availableGpuDevices(), "provider GPU device list"
                ));
            } catch (RuntimeException failure) {
                throw new RendererInitializationException(
                        "GPU device enumeration failed: " + candidate.descriptor().id(),
                        candidate.descriptor().id(), failure
                );
            }
            for (RendererGpuDevice device : reported) {
                RendererGpuDevice checked = Objects.requireNonNull(device, "provider GPU device");
                if (!candidate.descriptor().id().equals(checked.backendId())) {
                    throw new RendererInitializationException(
                            "provider returned a GPU owned by another backend: " + checked.backendId(),
                            candidate.descriptor().id(), null
                    );
                }
                String identity = checked.backendId() + '\0' + checked.stableId();
                if (!identities.add(identity)) {
                    throw new RendererInitializationException(
                            "duplicate GPU identity: " + checked.backendId() + "/" + checked.stableId(),
                            candidate.descriptor().id(), null
                    );
                }
                devices.add(checked);
            }
        }
        return List.copyOf(devices);
    }

    static Renderer openProviders(
            String requiredProviderId,
            RendererConfig configuration,
            List<RendererBackendProvider> providers
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(providers, "providers");
        return openCandidates(requiredProviderId, configuration, candidates(providers));
    }

    private static Renderer openCandidates(
            String requiredProviderId,
            RendererConfig configuration,
            List<Candidate> candidates
    ) {
        RendererGpuDevice selectedGpu = configuration.gpuDevice().orElse(null);
        if (selectedGpu != null && requiredProviderId != null
                && !requiredProviderId.equals(selectedGpu.backendId())) {
            throw new IllegalArgumentException(
                    "requiredProviderId does not own the selected GPU: " + selectedGpu.backendId()
            );
        }
        List<RendererUnavailableException.BackendAttempt> attempts = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (requiredProviderId != null && !requiredProviderId.equals(candidate.descriptor().id())) {
                continue;
            }
            if (selectedGpu != null && !selectedGpu.backendId().equals(candidate.descriptor().id())) {
                continue;
            }
            RendererBackendProvider.ProbeResult probe;
            try {
                probe = Objects.requireNonNull(candidate.provider().probe(configuration), "provider probe result");
            } catch (RuntimeException failure) {
                throw new RendererInitializationException(
                        "backend probe failed: " + candidate.descriptor().id(),
                        candidate.descriptor().id(),
                        failure
                );
            }
            attempts.add(RendererUnavailableException.BackendAttempt.of(
                    candidate.descriptor().id(), probe.compatibility(), probe.reason()
            ));
            if (probe.compatibility() != RendererBackendProvider.Compatibility.COMPATIBLE) {
                continue;
            }
            try {
                Renderer opened = Objects.requireNonNull(
                        candidate.provider().open(configuration),
                        () -> "backend provider returned null: " + candidate.descriptor().id()
                );
                return opened;
            } catch (RuntimeException failure) {
                if (failure instanceof RendererInitializationException initializationFailure) {
                    throw initializationFailure;
                }
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
        List<RendererBackendProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(RendererBackendProvider.class, loader).forEach(providers::add);
        } catch (ServiceConfigurationError error) {
            throw new RendererInitializationException("renderer provider discovery failed", "service-loader", error);
        }
        return candidates(providers);
    }

    private static List<Candidate> candidates(List<RendererBackendProvider> providers) {
        Set<String> ids = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>(providers.size());
        for (RendererBackendProvider provider : providers) {
            RendererBackendProvider.Descriptor descriptor = Objects.requireNonNull(
                    provider.descriptor(), "provider descriptor"
            );
            if (descriptor.apiMajor() != RendererBackendProvider.API_MAJOR
                    || descriptor.apiMinor() > RendererBackendProvider.API_MINOR) {
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

    record Candidate(RendererBackendProvider provider, RendererBackendProvider.Descriptor descriptor) {
        Candidate {
            provider = Objects.requireNonNull(provider, "provider");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
