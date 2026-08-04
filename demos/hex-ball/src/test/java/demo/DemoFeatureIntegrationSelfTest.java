package demo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;

public final class DemoFeatureIntegrationSelfTest {
    private DemoFeatureIntegrationSelfTest() {
    }

    public static void main(String[] arguments) {
        requestsFgOnlyAndExactProjection();
        acceptsExplicitUncappedTargetFps();
        acceptsBoundedDurationAndRejectsAmbiguousRunLimits();
        requestsMfgCadenceFromMultiplier();
        requestsAllExceptMfgAndRejectsContradictoryProperties();
        disablesFgAndForwardsTheBaselineProperty();
        startsPresentationMeasurementAtFirstPresent();
        mapsTechnologyStatusesWithoutHardwareInference();
        countsActiveMfgFramesInVisibleFps();
        boundsBlockedReasonsAndHandlesMissingCapabilityExtension();
        System.out.println("DemoFeatureIntegrationSelfTest passed");
    }

    private static void requestsFgOnlyAndExactProjection() {
        DemoConfig config = new DemoConfig(
                640, 360, 1, true, false, false, 0, 1, Duration.ZERO
        );
        RayTracingRendererConfig renderer = DemoRendererProfile.interactive(config);
        require(renderer.temporalRendering().equals(TemporalRenderingOptions.disabled()),
                "built-in temporal rendering must be disabled in the FG-only profile");
        require(renderer.frameReconstruction().equals(FrameReconstructionOptions.disabled()),
                "reconstruction must be disabled in the FG-only profile");
        require(renderer.denoising().equals(DenoisingOptions.disabled()),
                "denoising must be disabled in the FG-only profile");
        require(renderer.lowLatency().equals(LowLatencyOptions.disabled()),
                "standalone low latency must be disabled in the FG-only profile");
        require(renderer.rayTracingOptimizations().equals(RayTracingOptimizationOptions.disabled()),
                "RT optimizations must be disabled in the FG-only profile");
        require(renderer.frameGeneration().preference() == RendererFeaturePreference.PREFERRED,
                "FG must use preferred activation with native presentation fallback");
        require(renderer.frameGeneration().mode() == FrameGenerationOptions.Mode.FRAME_GENERATION,
                "the Demo must request FG rather than MFG or adaptive generation");
        require(renderer.frameGeneration().multiplier() == FrameGenerationOptions.Multiplier.TWO_X,
                "FG must request the exact 2x cadence");
        require(renderer.frameGeneration().fallback()
                        == FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES,
                "FG must preserve native presentation when activation is unavailable");

        RenderFrameRequest frame = DemoRendererProfile.frame(
                0, 640, 360, DemoRendererProfile.camera(640, 360, 15.4)
        ).build();
        require(frame.depthProjection().equals(DemoRendererProfile.depthProjection()),
                "every demo frame must carry the exact depth projection");
        require(frame.depthProjection().known(), "the depth projection must not be unknown");
    }

    private static void acceptsExplicitUncappedTargetFps() {
        DemoConfig config = DemoConfig.parse(new String[]{
                "--width=640", "--height=360", "--target-fps=0"
        });
        require(config.targetFps() == 0, "explicit uncapped target FPS was rejected or changed");
        try {
            DemoConfig.parse(new String[]{"--width=640", "--height=360", "--target-fps=-1"});
            throw new AssertionError("negative target FPS was accepted");
        } catch (IllegalArgumentException expected) {
            // The CLI parser and record invariant reject negative pacing values.
        }
    }

    private static void acceptsBoundedDurationAndRejectsAmbiguousRunLimits() {
        DemoConfig duration = DemoConfig.parse(new String[]{
                "--width=640", "--height=360", "--duration-seconds=40"
        });
        require(duration.duration().equals(Duration.ofSeconds(40)),
                "bounded duration was rejected or changed");
        try {
            DemoConfig.parse(new String[]{
                    "--width=640", "--height=360", "--frames=1", "--duration-seconds=1"
            });
            throw new AssertionError("ambiguous frame and duration limits were accepted");
        } catch (IllegalArgumentException expected) {
            // A finite run must have exactly one termination source so evidence remains auditable.
        }
    }

    private static void disablesFgAndForwardsTheBaselineProperty() {
        String property = DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            DemoConfig config = new DemoConfig(
                    640, 360, 1, true, false, false, 0, 1, Duration.ZERO
            );
            RayTracingRendererConfig renderer = DemoRendererProfile.interactive(config);
            require(renderer.frameGeneration().equals(FrameGenerationOptions.disabled()),
                    "the native baseline property must disable frame generation");

            List<String> command = DemoLauncher.childCommand(
                    Path.of("RTRendererAPI-HexBallDemo-0.5.0.jar"),
                    new String[]{"--width=640", "--height=360"}
            );
            String forwarded = "-D" + property + "=true";
            int propertyIndex = command.indexOf(forwarded);
            int jarIndex = command.indexOf("-jar");
            require(propertyIndex >= 0, "the executable-JAR child must receive the baseline property");
            require(propertyIndex < jarIndex, "the baseline property must be a child JVM argument");
            require(command.subList(command.size() - 2, command.size())
                            .equals(List.of("--width=640", "--height=360")),
                    "application arguments must remain after the executable JAR path");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static void requestsMfgCadenceFromMultiplier() {
        String property = DemoRendererProfile.FRAME_GENERATION_MULTIPLIER_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "4");
            RayTracingRendererConfig renderer = DemoRendererProfile.interactive(
                    new DemoConfig(
                            640, 360, 1, true, false, false, 0, 1, Duration.ZERO
                    )
            );
            require(renderer.frameGeneration().mode() == FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION,
                    "3x/4x cadence must use mutually exclusive MFG mode");
            require(renderer.frameGeneration().multiplier() == FrameGenerationOptions.Multiplier.FOUR_X,
                    "4x multiplier was not projected");
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void requestsAllExceptMfgAndRejectsContradictoryProperties() {
        String profileProperty = DemoFeatureProfile.PROPERTY;
        String disableProperty = DemoRendererProfile.DISABLE_FRAME_GENERATION_PROPERTY;
        String multiplierProperty = DemoRendererProfile.FRAME_GENERATION_MULTIPLIER_PROPERTY;
        String previousProfile = System.getProperty(profileProperty);
        String previousDisable = System.getProperty(disableProperty);
        String previousMultiplier = System.getProperty(multiplierProperty);
        try {
            System.setProperty(profileProperty, "all-except-mfg");
            System.clearProperty(disableProperty);
            System.setProperty(multiplierProperty, "2");
            RayTracingRendererConfig renderer = DemoRendererProfile.interactive(
                    new DemoConfig(
                            640, 360, 1, true, false, false, 0, 1, Duration.ZERO
                    )
            );
            require(renderer.temporalRendering().equals(TemporalRenderingOptions.balanced()),
                    "all-except-MFG must enable the built-in temporal fallback resources");
            require(renderer.frameReconstruction().equals(
                            FrameReconstructionOptions.productionDefault()),
                    "all-except-MFG must request SR with its explicit NIS fallback");
            require(renderer.denoising().equals(DenoisingOptions.productionDefault()),
                    "all-except-MFG must request production NRD policy");
            require(renderer.frameGeneration().mode()
                            == FrameGenerationOptions.Mode.FRAME_GENERATION
                            && renderer.frameGeneration().multiplier()
                            == FrameGenerationOptions.Multiplier.TWO_X,
                    "all-except-MFG must select FG 2x and exclude MFG");
            require(renderer.lowLatency().equals(LowLatencyOptions.productionDefault()),
                    "all-except-MFG must explicitly request Reflex/PCL");
            require(renderer.rayTracingOptimizations().equals(
                            RayTracingOptimizationOptions.productionDefault()),
                    "all-except-MFG must request SER and RTXMU policy");

            List<String> command = DemoLauncher.childCommand(
                    Path.of("RTRendererAPI-HexBallDemo-0.5.0.jar"), new String[0]
            );
            int propertyIndex = command.indexOf("-D" + profileProperty + "=all-except-mfg");
            require(propertyIndex >= 0 && propertyIndex < command.indexOf("-jar"),
                    "the feature profile must be forwarded as a child JVM argument");

            System.setProperty(disableProperty, "true");
            expectIllegalArgument(
                    () -> DemoRendererProfile.interactive(new DemoConfig(
                            640, 360, 1, true, false, false, 0, 1, Duration.ZERO
                    )),
                    "all-except-MFG accepted disabled frame generation"
            );
            System.clearProperty(disableProperty);
            System.setProperty(multiplierProperty, "3");
            expectIllegalArgument(
                    () -> DemoRendererProfile.interactive(new DemoConfig(
                            640, 360, 1, true, false, false, 0, 1, Duration.ZERO
                    )),
                    "all-except-MFG accepted an MFG multiplier"
            );
        } finally {
            restoreProperty(profileProperty, previousProfile);
            restoreProperty(disableProperty, previousDisable);
            restoreProperty(multiplierProperty, previousMultiplier);
        }
    }

    private static void startsPresentationMeasurementAtFirstPresent() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        RenderStats stats = new RenderStats(clock::get);
        clock.addAndGet(9_000_000_000L); // renderer/window startup must not enter the FPS denominator
        stats.framePresented();
        clock.addAndGet(100_000_000L);
        stats.framePresented();
        requireNear(stats.framesPerSecond(), 10.0, 0.0001,
                "rolling FPS must use the first-present epoch");
        requireNear(stats.totalPresentationFramesPerSecond(), 10.0, 0.0001,
                "total FPS must share the first-present epoch");
    }

    private static void mapsTechnologyStatusesWithoutHardwareInference() {
        RenderingFeatureCapabilities.Builder capabilities = RenderingFeatureCapabilities.builder();
        Technology[] technologies = Technology.values();
        Status[] statuses = {
                Status.ACTIVE,
                Status.AVAILABLE,
                Status.FALLBACK,
                Status.BLOCKED,
                Status.NOT_SUPPORTED,
                Status.ACTIVE,
                Status.ACTIVE,
                Status.AVAILABLE,
                Status.FALLBACK
        };
        require(statuses.length == technologies.length,
                "the HUD self-test must cover every public technology exactly once");
        for (int index = 0; index < technologies.length; index++) {
            capabilities.technology(
                    technologies[index],
                    Entry.of(statuses[index], "provider." + index, "capability evidence " + index)
            );
        }
        String text = DemoTechnologyHud.snapshot(
                Optional.of(capabilities.build()), "PERFORMANCE"
        ).text();
        require(text.contains("DLSS SR: ACTIVE"), "DLSS SR status was not projected");
        require(text.contains("DLAA: AVAILABLE"), "DLAA status was not projected");
        require(text.contains("NIS: FALLBACK"), "NIS status was not projected");
        require(text.contains("DLSS FG: BLOCKED (capability evidence 3)"),
                "FG blocked status and reason were not projected");
        require(text.contains("DLSS MFG: NOT_SUPPORTED"), "MFG status was not projected");
        require(text.contains("REFLEX/PCL: ACTIVE"), "Reflex/PCL status was not projected");
        require(text.contains("NRD: ACTIVE"), "NRD status was not projected");
        require(text.contains("SER: AVAILABLE"), "SER status was not projected");
        require(text.contains("RTXMU: FALLBACK"), "RTXMU status was not projected");
        require(!text.contains("RTX 5080") && !text.contains("NVIDIA GeForce"),
                "HUD must not infer capability from a GPU model name");
    }

    private static void countsActiveMfgFramesInVisibleFps() {
        String reason = "human-readable frame-generation explanation";
        RenderingFeatureCapabilities capabilities = RenderingFeatureCapabilities.builder()
                .feature(
                        Feature.FRAME_GENERATION,
                        Entry.of(Status.ACTIVE, "nvidia.streamline.dlss-g", reason)
                )
                .technology(
                        Technology.FRAME_GENERATION,
                        Entry.of(
                                Status.AVAILABLE,
                                "nvidia.streamline.dlss-g",
                                "MFG selected; standard DLSS FG is mutually exclusive"
                        )
                )
                .technology(
                        Technology.MULTI_FRAME_GENERATION,
                        Entry.of(Status.ACTIVE, "nvidia.streamline.dlss-g.mfg", reason)
                )
                .build();
        FrameGenerationEvidence evidence = FrameGenerationEvidence.builder()
                .reported(true)
                .requestedGeneratedFramesPerNativeFrame(2)
                .lastSubmittedGeneratedFramesPerNativeFrame(2)
                .configuredGeneratedFramesPerNativeFrame(2)
                .proxyPresentCalls(100L)
                .stateSamples(100L)
                .stateQueryCalls(101L)
                .totalFramesActuallyPresented(300L)
                .generatedFramesActuallyPresented(200L)
                .lastFramesActuallyPresented(3)
                .maximumSupportedGeneratedFramesPerNativeFrame(3)
                .maximumGeneratedFramesObservedPerSample(2)
                .latestNativeStatus(OptionalInt.of(0))
                .proxyPresentSequenceRange(1L, 100L)
                .lastGeneratedObservationSequence(100L)
                .resetEpoch(1L)
                .build();

        double totalFps = DemoTechnologyHud.presentTotalFps(
                evidence, 60.0
        );
        requireNear(totalFps, 180.0, 0.0001,
                "visible FPS must include both generated MFG frames per native frame");
        String hud = DemoTechnologyHud.snapshot(
                Optional.of(capabilities), "PERFORMANCE", evidence
        ).text();
        require(hud.contains("DLSS MFG: ACTIVE | 3x | generated N/A FPS | effective 3.00x"),
                "HUD did not project typed MFG cadence evidence");
        require(!hud.contains("DLSS FG: AVAILABLE | 3x"),
                "HUD attached MFG evidence to the mutually exclusive FG mode");
    }

    private static void boundsBlockedReasonsAndHandlesMissingCapabilityExtension() {
        String longReason = "x".repeat(200) + "\nignored line";
        RenderingFeatureCapabilities capabilities = RenderingFeatureCapabilities.builder()
                .technology(
                        Technology.FRAME_GENERATION,
                        Entry.of(Status.BLOCKED, "provider", longReason)
                )
                .build();
        String blocked = DemoTechnologyHud.snapshot(Optional.of(capabilities), "PERFORMANCE").text();
        String blockedLine = blocked.lines()
                .filter(line -> line.startsWith("DLSS FG:"))
                .findFirst()
                .orElseThrow();
        require(blockedLine.length() <= 128, "blocked reason was not bounded");
        require(blockedLine.endsWith("...)"), "bounded blocked reason must remain explicit");

        String missing = DemoTechnologyHud.snapshot(Optional.empty(), "PERFORMANCE").text();
        require(missing.lines().skip(1).allMatch(line -> line.endsWith("NOT_SUPPORTED")),
                "missing capability extension must not fabricate ACTIVE or BLOCKED states");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireNear(double actual, double expected, double tolerance, String message) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Contradictory process-lifetime feature controls must fail before renderer startup.
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }
}
