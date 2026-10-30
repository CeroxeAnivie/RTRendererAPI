package consumer;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.Set;

import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import top.ceroxe.rt.renderer.api.Medium;
import top.ceroxe.rt.renderer.api.MediumBoundary;
import top.ceroxe.rt.renderer.api.MediumTransport;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.Rgb;
import top.ceroxe.rt.renderer.api.SubmissionDeferralReason;
import top.ceroxe.rt.renderer.api.WorkloadExecutionEvidence;
import top.ceroxe.rt.renderer.spi.RendererBackendProvider;

/** Executes published API/linkage and archive contracts without opening a native device. */
public final class PublishedApiRuntimeSelfTest {
    private PublishedApiRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "expected renderer version and staged repository URI are required");
        String version = args[0];
        Path repository = Path.of(URI.create(args[1]));
        requireArtifact(Renderer.class, "renderer-api", version, repository);
        verifyBackendProvider(version, repository);
        verifyFeatureProvider(version, repository);
        verifyNativeManifest("META-INF/native/cuda/windows-x86_64/", Set.of(
                "nvrtc64_120_0.dll", "nvrtc-builtins64_129.dll", "LICENSE.txt"));
        verifyNativeManifest("META-INF/native/windows-x86_64/", Set.of(
                "rtrenderer_nvidia.dll", "sl.interposer.dll", "sl.common.dll",
                "sl.dlss.dll", "sl.dlss_g.dll", "sl.nis.dll", "sl.reflex.dll", "sl.pcl.dll"));
        verifyPublicValues();
        System.out.println("PublishedApiRuntimeSelfTest passed: version=" + version
                + ", provider linkage, native resource hashes and public contracts; no GPU initialized");
    }

    private static void verifyBackendProvider(String version, Path repository) throws Exception {
        List<RendererBackendProvider> providers = ServiceLoader.load(RendererBackendProvider.class)
                .stream().map(ServiceLoader.Provider::get).toList();
        require(providers.size() == 1, "expected exactly one published renderer backend");
        RendererBackendProvider provider = providers.getFirst();
        requireArtifact(provider.getClass(), "renderer-core", version, repository);
        RendererBackendProvider.Descriptor descriptor = provider.descriptor();
        require(descriptor.id().equals("vulkan-rt")
                        && descriptor.apiMajor() == RendererBackendProvider.API_MAJOR
                        && descriptor.apiMinor() <= RendererBackendProvider.API_MINOR,
                "published renderer backend does not match the public SPI");
    }

    private static void verifyFeatureProvider(String version, Path repository) throws Exception {
        Class<?> featureSpi = Class.forName("top.ceroxe.rt.renderer.feature.VulkanFeatureProvider", false,
                PublishedApiRuntimeSelfTest.class.getClassLoader());
        List<?> providers = ServiceLoader.load(featureSpi).stream().map(ServiceLoader.Provider::get).toList();
        require(providers.size() == 1, "expected exactly one published feature provider");
        Object provider = providers.getFirst();
        requireArtifact(provider.getClass(), "renderer-nvidia", version, repository);
        require("nvidia".equals(featureSpi.getMethod("id").invoke(provider)),
                "published feature provider identity is incorrect");
    }

    private static void requireArtifact(Class<?> type, String module, String version, Path repository) throws Exception {
        URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        require("file".equals(location.getScheme())
                        && Path.of(location).getFileName().toString().equals(module + "-" + version + ".jar"),
                "consumer loaded a project class directory or a mismatched artifact for " + type.getName());
        Path staged = repository.resolve("top/ceroxe/rt/" + module + "/" + version
                + "/" + module + "-" + version + ".jar");
        require(MessageDigest.isEqual(fileHash(Path.of(location)), fileHash(staged)),
                "consumer loaded stale bytes instead of the staged artifact for " + module);
    }

    private static byte[] fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static void verifyNativeManifest(String root, Set<String> required) throws Exception {
        ClassLoader loader = PublishedApiRuntimeSelfTest.class.getClassLoader();
        var manifests = loader.getResources(root + "runtime-files.sha256");
        require(manifests.hasMoreElements(), "published native manifest missing: " + root);
        var manifest = manifests.nextElement();
        require(!manifests.hasMoreElements(), "duplicate published native manifest: " + root);
        List<String> lines;
        try (InputStream input = manifest.openStream()) {
            lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank()).toList();
        }
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            require(fields.length == 2 && fields[0].matches("[0-9a-f]{64}")
                            && fields[1].matches("[A-Za-z0-9._-]+") && names.add(fields[1]),
                    "invalid or duplicate native manifest entry: " + root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = loader.getResourceAsStream(root + fields[1])) {
                require(input != null, "native resource missing: " + root + fields[1]);
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            require(fields[0].equals(HexFormat.of().formatHex(digest.digest())),
                    "native resource digest mismatch: " + root + fields[1]);
        }
        require(names.containsAll(required), "native manifest omits required resources: " + root);
    }

    private static void verifyPublicValues() {
        Medium medium = new Medium(new Rgb(.02f, .01f, .005f), Rgb.ZERO, Rgb.ZERO, 1f, 1f, 0f);
        require(medium.participating() && !medium.isVacuum() && Medium.vacuum().isVacuum(),
                "published medium value contract failed");
        require(MediumBoundary.volume(medium).medium().equals(medium),
                "published medium boundary lost its medium");
        MediumTransport quality = MediumTransport.balanced().withOpticalSamples(2)
                .withFirstInterfaceSampling(MediumTransport.FirstInterfaceSampling.STOCHASTIC_SINGLE_BRANCH);
        require(quality.opticalSamples() == 2 && quality.maxEvents() == MediumTransport.balanced().maxEvents(),
                "published medium budget copy lost an independent field");
        WorkloadExecutionEvidence deferred = WorkloadExecutionEvidence.sceneDeferred(1,
                SubmissionDeferralReason.DEVICE_RECOVERING, "device recovery");
        require(deferred.reason() == WorkloadExecutionEvidence.Reason.DEVICE_NOT_READY
                        && deferred.submissionDeferralReason().orElseThrow() == SubmissionDeferralReason.DEVICE_RECOVERING,
                "published workload lost typed recovery classification");
        CommandExecutionEvidence blocked = new CommandExecutionEvidence(1,
                CommandExecutionEvidence.Outcome.BLOCKED, CommandExecutionEvidence.Reason.BOUNDED_BACKPRESSURE,
                OptionalLong.empty(), Optional.empty(), 0, "command lane busy");
        WorkloadExecutionEvidence combined = WorkloadExecutionEvidence.combined(
                Renderer.FrameSubmissionResult.accepted(1, 0, Set.of()), blocked);
        require(combined.outcome() == WorkloadExecutionEvidence.Outcome.PARTIALLY_ACCEPTED
                        && combined.sceneSubmission().isPresent()
                        && combined.graphicsExecution().orElseThrow() == blocked,
                "published combined workload hid an already accepted scene");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
