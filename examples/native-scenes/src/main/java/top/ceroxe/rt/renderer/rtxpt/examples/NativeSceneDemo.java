package top.ceroxe.rt.renderer.rtxpt.examples;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.EnvironmentMap;
import top.ceroxe.rt.renderer.api.FrameClock;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.PathTracingOptions;
import top.ceroxe.rt.renderer.api.PathTracingQualityOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;
import top.ceroxe.rt.renderer.rtxpt.RtxptDiagnostics;

/** Consumer scenes rendered by the ordinary RTXPT beauty provider. */
public final class NativeSceneDemo {
    private static final int SAMPLE_LIMIT = 262_144;
    private static final int BOUNDARY_SAMPLE_LIMIT = 8_192;
    private NativeSceneDemo() { }

    /** Runs one window; a second scene/process is never launched automatically. */
    public static void main(String[] args) throws IOException {
        Config config = Config.parse(args);
        if (config.help()) {
            System.out.println("NativeSceneDemo --scene balls|mixed|glass|nested|absorption|rough|ior"
                    + " [--material glass|opaque|metal] [--width 1600] [--height 1000] [--frames 0]"
                    + " [--seconds 0] [--native false] [--fg off|2|3|4] [--warmup-frames 120]"
                    + " [--denoising scene|off|relax|rr]"
                    + " [--cycle-frames 0]"
                    + " [--light-sampler auto|uniform|power|adaptive] [--reconstruction-quality scene|performance|balanced|quality]"
                    + " [--diagnostics off|summary|deep] [--output result.json] [--capture image.png]");
            System.out.println("No software frame cap. Window keys: balls 1-3, optics 1-6, Esc quits. Console: names or quit.");
            System.out.println("Optics demonstrate absorption/refraction and nested dielectrics, not volume scattering.");
            return;
        }
        run(config, List.of(args));
    }

    private static void run(Config config, List<String> arguments) throws IOException {
        boolean animated = config.scene().equals("balls");
        BouncingBallPhysics physics;
        BouncingBallScene.BallMaterial material = config.material();
        BouncingBallScene balls = animated ? new BouncingBallScene(material) : null;
        String preset = config.scene();
        CameraState camera = animated ? ballCamera(config.width(), config.height())
                : OpticalMediumScene.camera(config.width(), config.height());
        EnvironmentState environment = animated ? ballEnvironment() : OpticalMediumScene.environment();
        RendererConfig rendererConfig = rendererConfig(config);
        ConcurrentLinkedQueue<String> commands = consoleCommands();
        Stats stats = new Stats(config);
        String completion = "limit-reached";
        Throwable problem = null;
        boolean captured = false;
        long started = System.nanoTime();
        long revision = 0;
        System.out.println(description(animated, material, preset));
        System.out.printf(Locale.ROOT, "Requested window: %dx%d; no software frame cap; requested FG=%s.%n",
                config.width(), config.height(), config.generation());
        System.out.println("Requested policies are not proof of native activation. Report: " + config.output());
        long openBegin = System.nanoTime();
        try (Renderer renderer = config.hidden()
                ? top.ceroxe.rt.renderer.rtxpt.RtxptRenderers.openHiddenWindow(rendererConfig)
                : Renderer.open("rtxpt", rendererConfig)) {
            stats.rendererOpenMillis = (System.nanoTime() - openBegin) / 1e6;
            RtxptAccess access = renderer.extension(RtxptAccess.class).orElseThrow(
                    () -> new IllegalStateException("RTXPT provider did not expose native window access"));
            access.diagnosticLevel(config.diagnostics());
            access.setWindowTitle(NativeSceneHud.title(animated));
            access.setOverlayChoices(NativeSceneHud.choices(animated), NativeSceneHud.selection(animated, material, preset));
            access.setOverlayText(NativeSceneHud.text(animated, material, preset, null, null, null, null, null, null, null));
            physics = animated ? new BouncingBallPhysics() : null;
            long previousClock = -1;
            long readyAt = 0;
            long reportAt = 0;
            long reportFrames = 0;
            long hudAt = 0, selectorAt = 0;
            int cycleStart = NativeSceneHud.selection(animated, material, preset);
            int cycleCount = animated ? 3 : OpticalMediumScene.PRESETS.size();
            WindowKeys keys = null;
            for (long sequence = 1; config.frames() == 0 || sequence <= config.frames(); sequence++) {
                if (config.cycleFrames() > 0 && sequence > (long) config.cycleFrames() * cycleCount) break;
                if (Thread.currentThread().isInterrupted()) { completion = "interrupted"; break; }
                long frameBegin = System.nanoTime();
                if (readyAt != 0 && config.seconds() > 0
                        && (frameBegin - readyAt) / 1_000_000_000.0 >= config.seconds()) break;
                boolean changed = sequence == 1;
                String command = keys == null ? null : keys.pollCommand();
                if (command == null) command = commands.poll();
                if (keys != null && frameBegin - selectorAt >= 25_000_000L) {
                    int selected = access.overlaySelection();
                    selectorAt = frameBegin;
                    stats.selectorPolls++;
                    if (command == null && selected != NativeSceneHud.selection(animated, material, preset))
                        command = NativeSceneHud.command(animated, selected);
                }
                if (config.cycleFrames() > 0 && (sequence - 1) % config.cycleFrames() == 0)
                    command = NativeSceneHud.command(animated,
                            (cycleStart + (int) ((sequence - 1) / config.cycleFrames())) % cycleCount);
                if (command != null) {
                    command = command.strip().toLowerCase(Locale.ROOT);
                    if (command.equals("quit") || command.equals("exit")) { completion = "console-quit"; break; }
                    if (animated) {
                        String requested = command.startsWith("material ") ? command.substring(9).strip() : command;
                        var selected = parseMaterialOrNull(requested);
                        if (selected == null) System.out.println("Unknown material command: " + command);
                        else if (selected != material) {
                            access.setExposureOffset(exposure(selected));
                            material = selected;
                            balls = new BouncingBallScene(material);
                            changed = true;
                            hudAt = 0;
                            stats.sceneChanges++;
                            System.out.println("Ball material changed; physics retained: " + material);
                        }
                    } else {
                        String requested = command.startsWith("scene ") ? command.substring(6).strip() : command;
                        if (requested.length() == 1 && requested.charAt(0) >= '1' && requested.charAt(0) <= '6')
                            requested = OpticalMediumScene.PRESETS.get(requested.charAt(0) - '1');
                        if (!OpticalMediumScene.PRESETS.contains(requested)) System.out.println("Unknown scene command: " + command);
                        else if (!requested.equals(preset)) {
                            preset = requested;
                            changed = true;
                            hudAt = 0;
                            stats.sceneChanges++;
                            System.out.println("Optical scene changed: " + preset);
                        }
                    }
                }
                long clock = Math.max(previousClock + 1, frameBegin - started);
                long delta = previousClock < 0 ? 0 : clock - previousClock;
                if (animated) physics.advanceTo(frameBegin);
                if (animated || changed) {
                    SceneTransaction transaction;
                    revision++;
                    if (sequence == 1) transaction = animated ? balls.initialTransaction(revision, physics)
                            : OpticalMediumScene.create(revision, preset);
                    else if (!animated) transaction = OpticalMediumScene.create(revision, preset);
                    else transaction = changed ? balls.materialTransaction(revision, physics)
                            : balls.motionTransaction(revision, physics);
                    if (renderer.apply(transaction).acceptedSceneRevision() != revision)
                        throw new IllegalStateException("Scene publication changed the requested revision");
                    if (changed && sequence != 1)
                        access.setOverlayChoices(NativeSceneHud.choices(animated), NativeSceneHud.selection(animated, material, preset));
                }
                RenderFrameRequest frame = RenderFrameRequest.builder(sequence, config.width(), config.height(), camera)
                        .minimumSceneRevision(revision).clock(FrameClock.of(clock, delta)).environment(environment).build();
                long submitBegin = System.nanoTime();
                Renderer.FrameSubmissionResult result = renderer.submit(frame);
                long finished = System.nanoTime();
                if (result.frameSequence() != sequence || result.scheduledSceneRevision() != revision)
                    throw new IllegalStateException("Frame admission changed sequence or scene revision");
                stats.accept(sequence, frameBegin, submitBegin, finished);
                previousClock = clock;
                if (readyAt == 0) {
                    readyAt = reportAt = finished;
                    reportFrames = stats.acceptedFrames;
                    keys = new WindowKeys(access.windowHandle(), animated);
                    System.out.println("Native scene ready; cold first submit is separate from steady latency samples.");
                }
                if (hudAt == 0 || finished - hudAt >= 500_000_000L) {
                    stats.observeNative(access, sequence);
                    access.setOverlayText(NativeSceneHud.text(animated, material, preset, stats.hudNativeFps(finished),
                            stats.nativeWindow, stats.nativePresentation, stats.nativeExposure, stats.nativeDenoiser,
                            stats.nativeRendering, access.frameStatistics()));
                    stats.hudUpdates++;
                    hudAt = finished;
                }
                if (sequence % 60 == 0) stats.poll(access);
                if (finished - reportAt >= 1_000_000_000L) {
                    System.out.printf(Locale.ROOT, "Native completed submissions: %.2f FPS; total=%d; preset=%s.%n",
                            (stats.acceptedFrames - reportFrames) * 1_000_000_000.0 / (finished - reportAt),
                            stats.acceptedFrames, animated ? material : preset);
                    reportAt = finished;
                    reportFrames = stats.acceptedFrames;
                }
                boolean cycleCapture = config.cycleFrames() > 0 && sequence % config.cycleFrames() == 0;
                if (config.capture() != null && (cycleCapture || config.cycleFrames() == 0
                        && !captured && sequence >= 120
                        && (animated || finished - readyAt >= 2_000_000_000L))) {
                    Path capture = config.capture();
                    if (cycleCapture) {
                        String filename = capture.getFileName().toString();
                        int extension = filename.lastIndexOf('.');
                        String stem = extension < 0 ? filename : filename.substring(0, extension);
                        capture = capture.resolveSibling(stem + "-"
                                + (animated ? material.name().toLowerCase(Locale.ROOT) : preset) + ".png");
                    }
                    Path parent = capture.toAbsolutePath().getParent();
                    if (parent != null) Files.createDirectories(parent);
                    access.capture(capture);
                    captured = true;
                    System.out.println("Explicit final-window capture; this run includes readback: " + capture);
                }
            }
            access.awaitIdle();
            stats.measurementEnd = System.nanoTime();
            stats.finalGpuDrain = true;
            stats.poll(access);
            if (stats.lastSequence > 0) stats.observeNative(access, stats.lastSequence);
            stats.finalBoundaryPoll = config.diagnostics() != RtxptDiagnostics.Level.OFF;
        } catch (IOException | RuntimeException | Error failure) {
            if (failure instanceof IllegalStateException closed && windowClosed(closed)) completion = "window-closed";
            else { problem = failure; completion = "failed"; }
        }
        stats.elapsedNanos = System.nanoTime() - started;
        Map<String, Object> report = stats.report(arguments, rendererConfig, preset, material, revision,
                completion, captured, problem);
        Path parent = config.output().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.writeString(config.output(), json(report) + "\n", StandardCharsets.UTF_8);
        } catch (IOException outputFailure) {
            if (problem != null) problem.addSuppressed(outputFailure);
            else throw outputFailure;
        }
        System.out.println("Native scene ended: " + completion + "; report=" + config.output());
        if (problem instanceof IOException failure) throw failure;
        if (problem instanceof RuntimeException failure) throw failure;
        if (problem instanceof Error failure) throw failure;
    }

    static RendererConfig rendererConfig(Config config) {
        RendererConfig result;
        if (config.scene().equals("balls")) {
            var base = RendererConfig.presentation();
            var quality = PathTracingQualityOptions.defaults().withBeautyTransport(
                    PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT);
            quality = quality.withPostProcessing(quality.postProcessing().withExposureOffset(exposure(config.material())));
            result = base.copyBuilder().pathTracing(PathTracingOptions.defaults().withQuality(quality))
                    .frameReconstruction(base.frameReconstruction()).denoising(DenoisingOptions.disabled())
                    .frameGeneration(config.generation()).build();
        } else {
            var base = config.nativeResolution() ? RendererConfig.builder().build()
                    : RendererConfig.pathTracedPresentation();
            result = base.copyBuilder().frameGeneration(config.generation()).build();
        }
        // Explicit diagnostic comparisons: keep scene defaults until the complete demo is checked.
        // These select upstream denoisers; they do not change SPP, geometry or path budgets.
        if (!config.lightSampler().equals("auto")) {
            var path = result.pathTracing();
            var sampling = path.quality().lighting().withSampler(
                    PathTracingQualityOptions.LightSampler.valueOf(config.lightSampler().toUpperCase(Locale.ROOT)));
            result = result.copyBuilder().pathTracing(path.withQuality(path.quality().withLighting(sampling))).build();
        }
        if (!config.reconstructionQuality().equals("scene"))
            result = result.copyBuilder().frameReconstruction(result.frameReconstruction().toBuilder()
                    .quality(FrameReconstructionOptions.Quality.valueOf(
                            config.reconstructionQuality().toUpperCase(Locale.ROOT))).build()).build();
        return switch (config.denoising()) {
            case "scene" -> result;
            case "off" -> result.copyBuilder().denoising(DenoisingOptions.disabled()).build();
            case "relax" -> result.copyBuilder().denoising(DenoisingOptions.builder()
                    .preference(RendererFeaturePreference.REQUIRED)
                    .strategy(DenoisingOptions.Strategy.RESPONSIVE).build()).build();
            case "rr" -> result.copyBuilder().denoising(DenoisingOptions.builder()
                    .preference(RendererFeaturePreference.REQUIRED)
                    .strategy(DenoisingOptions.Strategy.BALANCED).build()).build();
            default -> throw new IllegalArgumentException("Unknown denoising comparison");
        };
    }

    private static float exposure(BouncingBallScene.BallMaterial material) {
        return material == BouncingBallScene.BallMaterial.ORIGINAL_METAL ? 0 : -1.5f;
    }
    private static String description(boolean animated, BouncingBallScene.BallMaterial material, String preset) {
        return animated ? "Bouncing balls | " + material.label
                : "Absorbing dielectrics | " + OpticalMediumScene.description(preset);
    }
    private static ConcurrentLinkedQueue<String> consoleCommands() {
        var commands = new ConcurrentLinkedQueue<String>();
        Thread.ofVirtual().name("native-scene-console").start(() -> {
            var input = new Scanner(System.in, StandardCharsets.UTF_8);
            while (input.hasNextLine()) commands.add(input.nextLine());
        });
        return commands;
    }

    /** The prior demo's own-window shortcuts; this observes user input and never generates it. */
    @SuppressWarnings("restricted")
    private static final class WindowKeys {
        private static final MethodHandle FOREGROUND, KEY_STATE;
        private static final int[] KEYS = {'1', '2', '3', '4', '5', '6', 0x1b};
        static {
            var library = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            var linker = Linker.nativeLinker();
            FOREGROUND = linker.downcallHandle(library.find("GetForegroundWindow").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            KEY_STATE = linker.downcallHandle(library.find("GetAsyncKeyState").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));
        }
        private final long window;
        private final boolean balls;
        private int held;
        private long lastPoll;
        WindowKeys(long window, boolean balls) { this.window = window; this.balls = balls; }
        String pollCommand() {
            long now = System.nanoTime();
            if (now - lastPoll < 25_000_000L) return null;
            lastPoll = now;
            try {
                if (((MemorySegment) FOREGROUND.invokeExact()).address() != window) { held = 0; return null; }
                int down = 0;
                for (int index = 0; index < KEYS.length; index++)
                    if (((short) KEY_STATE.invokeExact(KEYS[index]) & 0x8000) != 0) down |= 1 << index;
                int pressed = down & ~held;
                held = down;
                if (pressed == 0) return null;
                int index = Integer.numberOfTrailingZeros(pressed);
                if (index == 6) return "quit";
                return balls ? index < 3 ? List.of("glass", "opaque", "metal").get(index) : null
                        : OpticalMediumScene.PRESETS.get(index);
            } catch (Throwable failure) {
                throw new IllegalStateException("Demo window shortcut polling failed", failure);
            }
        }
    }
    private static boolean windowClosed(IllegalStateException failure) {
        return "RTXPT window closed before frame completion".equals(failure.getMessage())
                || "The original RTXPT renderer is closed".equals(failure.getMessage());
    }
    private static CameraState ballCamera(int width, int height) {
        return CameraState.lookAt(0, .25, 15.4, 0, 0, .45).verticalFieldOfViewDegrees(48)
                .aspectRatio(width / (double) height).build();
    }
    private static EnvironmentState ballEnvironment() {
        return EnvironmentState.builder().skyRadiance(.10f, .13f, .19f).ambientIntensity(.70f)
                .environmentMap(ballSky())
                .sunDirection(-.25f, -.45f, -.857321f).sunRadiance(1, .92f, .80f).sunIntensity(1.65f).build();
    }
    private static EnvironmentMap ballSky() {
        // Consumer-authored 7.x sky: the adapter transports this radiance unchanged.
        // Original EnvMapBaker owns projection, importance sampling and GPU storage.
        int width=512,height=256;float[] rgb=new float[width*height*3];
        for(int y=0;y<height;y++){
            double theta=(y+.5)*Math.PI/height,dy=Math.cos(theta),radial=Math.sin(theta);
            float horizon=(float)(.675+.325*dy);
            for(int x=0;x<width;x++){
                double phi=((x+.5)/(double)width-.5)*2*Math.PI;
                double dx=radial*Math.sin(phi),dz=-radial*Math.cos(phi);
                double cosine=dx*.25+dy*.45+dz*.857321;
                double t=Math.clamp((cosine-.9995)/.0004,0,1);float disk=(float)(t*t*(3-2*t)*1.65);
                int offset=(y*width+x)*3;rgb[offset]=.10f*horizon+disk;
                rgb[offset+1]=.13f*horizon+.92f*disk;rgb[offset+2]=.19f*horizon+.80f*disk;
            }
        }
        return new EnvironmentMap(width,height,rgb);
    }
    private static BouncingBallScene.BallMaterial parseMaterialOrNull(String value) {
        return switch (value) {
            case "glass" -> BouncingBallScene.BallMaterial.GLASS;
            case "opaque" -> BouncingBallScene.BallMaterial.OPAQUE_REFLECTIVE;
            case "metal" -> BouncingBallScene.BallMaterial.ORIGINAL_METAL;
            default -> null;
        };
    }
    private static FrameGenerationOptions generation(String value) {
        if (value.equals("off") || value.equals("0")) return FrameGenerationOptions.disabled();
        var multiplier = switch (value) {
            case "2" -> FrameGenerationOptions.Multiplier.TWO_X;
            case "3" -> FrameGenerationOptions.Multiplier.THREE_X;
            case "4" -> FrameGenerationOptions.Multiplier.FOUR_X;
            default -> throw new IllegalArgumentException("FG must be off, 2, 3 or 4");
        };
        return FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                .mode(multiplier == FrameGenerationOptions.Multiplier.TWO_X ? FrameGenerationOptions.Mode.FRAME_GENERATION
                        : FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                .multiplier(multiplier).fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build();
    }

    record Config(String scene, BouncingBallScene.BallMaterial material, int width, int height,
            long frames, double seconds, boolean nativeResolution, FrameGenerationOptions generation,
            String denoising, String lightSampler, String reconstructionQuality, int cycleFrames, int warmupFrames,
            RtxptDiagnostics.Level diagnostics, Path output, Path capture, boolean hidden, boolean help) {
        static Config parse(String[] args) {
            String scene = "balls";
            var material = BouncingBallScene.BallMaterial.GLASS;
            int width = 1920, height = 1080, warmup = 120;
            boolean widthSpecified = false, heightSpecified = false;
            long frames = 0;
            double seconds = 0;
            boolean nativeResolution = false, hidden = false, help = false;
            String denoising = "scene";
            String lightSampler = "auto", reconstructionQuality = "scene";
            int cycleFrames = 0;
            var diagnostics = RtxptDiagnostics.Level.OFF;
            var fg = NativeSceneDemo.generation(Boolean.getBoolean("demo.disable-fg") ? "off" : System.getProperty("demo.fg-multiplier", "2"));
            Path output = null, capture = null;
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if (option.equals("--help") || option.equals("-h")) { help = true; continue; }
                if (option.equals("--windowed")) continue;
                if (option.equals("--original-balls")) { material = BouncingBallScene.BallMaterial.ORIGINAL_METAL; continue; }
                if (option.equals("--opaque-balls")) { material = BouncingBallScene.BallMaterial.OPAQUE_REFLECTIVE; continue; }
                int separator = option.indexOf('=');
                String value;
                if (separator >= 0) { value = option.substring(separator + 1); option = option.substring(0, separator); }
                else { if (++index >= args.length) throw new IllegalArgumentException("Missing value for " + option); value = args[index]; }
                switch (option) {
                    case "--hidden" -> hidden = switch (value.toLowerCase(Locale.ROOT)) {
                        case "true" -> true; case "false" -> false;
                        default -> throw new IllegalArgumentException("hidden must be true or false");
                    };
                    case "--scene" -> scene = value.toLowerCase(Locale.ROOT);
                    case "--material" -> {
                        material = parseMaterialOrNull(value.toLowerCase(Locale.ROOT));
                        if (material == null) throw new IllegalArgumentException("material must be glass, opaque or metal");
                    }
                    case "--width" -> { width = Integer.parseInt(value); widthSpecified = true; }
                    case "--height" -> { height = Integer.parseInt(value); heightSpecified = true; }
                    case "--frames" -> frames = Long.parseLong(value);
                    case "--seconds", "--duration-seconds" -> seconds = Double.parseDouble(value);
                    case "--fg" -> fg = NativeSceneDemo.generation(value.toLowerCase(Locale.ROOT));
                    case "--denoising" -> denoising = value.toLowerCase(Locale.ROOT);
                    case "--cycle-frames" -> cycleFrames = Integer.parseInt(value);
                    case "--light-sampler" -> lightSampler = value.toLowerCase(Locale.ROOT);
                    case "--reconstruction-quality" -> reconstructionQuality = value.toLowerCase(Locale.ROOT);
                    case "--warmup-frames" -> warmup = Integer.parseInt(value);
                    case "--diagnostics" -> diagnostics = RtxptDiagnostics.Level.valueOf(value.toUpperCase(Locale.ROOT));
                    case "--output" -> output = Path.of(value);
                    case "--capture" -> capture = Path.of(value);
                    case "--native" -> nativeResolution = switch (value.toLowerCase(Locale.ROOT)) {
                        case "true" -> true; case "false" -> false;
                        default -> throw new IllegalArgumentException("native must be true or false");
                    };
                    case "--spp" -> { if (!value.equals("1")) throw new IllegalArgumentException("These scenes use one camera sample"); }
                    default -> throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            if (!scene.equals("balls") && !OpticalMediumScene.PRESETS.contains(scene)) throw new IllegalArgumentException("Unknown scene: " + scene);
            if (widthSpecified != heightSpecified) throw new IllegalArgumentException("width and height must be supplied together");
            if (!widthSpecified && scene.equals("balls") && !java.awt.GraphicsEnvironment.isHeadless()) {
                var display = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
                width = Math.min(1920, Math.max(1, display.getWidth() - 160));
                height = Math.min(1080, Math.max(1, display.getHeight() - 160));
            }
            if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException("width and height must be in [1,16384]");
            if (frames < 0 || warmup < 0 || !Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Run limits must be finite and nonnegative");
            if (frames > 0 && seconds > 0) throw new IllegalArgumentException("frames and seconds are mutually exclusive");
            if (cycleFrames != 0 && cycleFrames < 120)
                throw new IllegalArgumentException("cycle-frames must be zero or at least 120");
            if (!List.of("scene", "off", "relax", "rr").contains(denoising))
                throw new IllegalArgumentException("denoising must be scene, off, relax or rr");
            if (!List.of("auto", "uniform", "power", "adaptive").contains(lightSampler))
                throw new IllegalArgumentException("light-sampler must be auto, uniform, power or adaptive");
            if (!List.of("scene", "performance", "balanced", "quality").contains(reconstructionQuality))
                throw new IllegalArgumentException("reconstruction-quality must be scene, performance, balanced or quality");
            if (nativeResolution && denoising.equals("rr"))
                throw new IllegalArgumentException("rr requires frame reconstruction; use relax for a native-resolution comparison");
            if (nativeResolution && scene.equals("balls")) throw new IllegalArgumentException("native is an optical-scene comparison option");
            if (output == null) output = Path.of("build", "reports", "native-scenes",
                    scene + "-" + ProcessHandle.current().pid() + "-" + System.currentTimeMillis() + ".json");
            return new Config(scene, material, width, height, frames, seconds, nativeResolution, fg, denoising,
                    lightSampler, reconstructionQuality, cycleFrames,
                    warmup, diagnostics, output, capture, hidden, help);
        }
    }

    /** Fixed storage; quantiles describe retained observations and disclose truncation. */
    private static final class Samples {
        private final long[] values;
        private int size;
        private long count, max;
        private double sum;
        Samples(int capacity) { values = new long[capacity]; }
        void add(long nanos) { count++; sum += nanos; max = Math.max(max, nanos); if (size < values.length) values[size++] = nanos; }
        Map<String, Object> report() {
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            var result = new LinkedHashMap<String, Object>();
            result.put("observations", count); result.put("retainedSamples", size); result.put("capacity", values.length);
            result.put("droppedSamples", count - size);
            result.put("samplePolicy", "first-N-after-warmup");
            result.put("meanMillisAllObservations", count == 0 ? null : sum / count / 1e6);
            result.put("p50Millis", percentile(sorted, .50)); result.put("p95Millis", percentile(sorted, .95));
            result.put("p99Millis", percentile(sorted, .99)); result.put("maxMillisAllObservations", count == 0 ? null : max / 1e6);
            return result;
        }
        private static Double percentile(long[] sorted, double quantile) {
            return sorted.length == 0 ? null : sorted[Math.max(0, (int)Math.ceil(sorted.length * quantile) - 1)] / 1e6;
        }
    }
    private static final class Boundary {
        private final Samples times = new Samples(BOUNDARY_SAMPLE_LIMIT);
        private long failed, items, bytes;
        void add(RtxptDiagnostics.Event event) { times.add(event.durationNanos()); if (event.failed()) failed++; items += event.items(); bytes += event.bytes(); }
        Map<String, Object> report() {
            var result = new LinkedHashMap<String, Object>(times.report());
            result.put("failedEvents", failed); result.put("items", items); result.put("bytes", bytes);
            return result;
        }
    }
    private static final class Stats {
        private final Config config;
        private final Samples submit = new Samples(SAMPLE_LIMIT), frame = new Samples(SAMPLE_LIMIT);
        private final EnumMap<RtxptDiagnostics.Stage, Boundary> boundaries = new EnumMap<>(RtxptDiagnostics.Stage.class);
        private long acceptedFrames, lastSequence, sceneChanges, elapsedNanos, steadyStart, lastSteadyFinish, steadyFrames, measurementEnd;
        private long cursor = -1, lost, failedEvents, boundaryEvents, lastDiagnosticSequence = -1;
        private Double rendererOpenMillis, coldFirstSubmitMillis;
        private RtxptAccess.PresentationState nativePresentation;
        private RtxptAccess.ExposureState nativeExposure;
        private RtxptAccess.WindowState nativeWindow;
        private RtxptAccess.Denoiser nativeDenoiser;
        private RtxptAccess.RenderingState nativeRendering;
        private long hudRateAt, hudRateFrames, hudUpdates, selectorPolls;
        private Double hudRate;
        private long nativeStateSequence = -1;
        private boolean finalGpuDrain, finalBoundaryPoll, upstreamInternalsExpanded;
        Stats(Config config) { this.config = config; }
        void observeNative(RtxptAccess access, long sequence) {
            var presentation = access.presentationState();
            var exposure = access.exposureState();
            var window = access.windowState();
            var rendering = access.renderingState();
            nativePresentation = presentation;
            nativeExposure = exposure;
            nativeWindow = window;
            nativeRendering = rendering;
            nativeDenoiser = rendering.denoiser();
            nativeStateSequence = sequence;
        }
        Double hudNativeFps(long now) {
            if (hudRateAt != 0 && now > hudRateAt)
                hudRate = (acceptedFrames - hudRateFrames) * 1_000_000_000.0 / (now - hudRateAt);
            hudRateAt = now; hudRateFrames = acceptedFrames;
            return hudRate;
        }
        void accept(long sequence, long begin, long submitBegin, long end) {
            acceptedFrames++; lastSequence = sequence;
            if (acceptedFrames == 1) coldFirstSubmitMillis = (end - submitBegin) / 1e6;
            if (acceptedFrames == config.warmupFrames()) steadyStart = end;
            if (acceptedFrames > config.warmupFrames()) {
                if (steadyStart == 0) steadyStart = begin;
                steadyFrames++; lastSteadyFinish = end;
                submit.add(end - submitBegin); frame.add(end - begin);
            }
        }
        void poll(RtxptAccess access) {
            if (config.diagnostics() == RtxptDiagnostics.Level.OFF) return;
            var snapshot = access.diagnosticSnapshot(cursor);
            lost += snapshot.lostEvents();
            upstreamInternalsExpanded |= snapshot.upstreamInternalsExpanded();
            for (var event : snapshot.events()) {
                boundaryEvents++;
                if (event.failed()) failedEvents++;
                lastDiagnosticSequence = Math.max(lastDiagnosticSequence, event.sequence());
                if (event.sequence() > config.warmupFrames()) boundaries.computeIfAbsent(event.stage(), key -> new Boundary()).add(event);
            }
            cursor = snapshot.nextOrdinal() - 1;
        }
        Map<String, Object> report(List<String> arguments, RendererConfig requested, String preset,
                BouncingBallScene.BallMaterial material, long revision, String completion, boolean captured, Throwable failure) {
            var result = new LinkedHashMap<String, Object>();
            result.put("schema", 1); result.put("entrypoint", NativeSceneDemo.class.getName()); result.put("arguments", arguments);
            result.put("scene", config.scene()); result.put("finalPreset", preset); result.put("finalMaterial", material.name());
            result.put("completion", completion); result.put("acceptedFrames", acceptedFrames); result.put("lastSequence", lastSequence);
            result.put("lastSceneRevision", revision); result.put("sceneChanges", sceneChanges); result.put("elapsedSecondsIncludingStartupAndClose", elapsedNanos / 1e9);
            result.put("requestedOutputWidth", config.width()); result.put("requestedOutputHeight", config.height());
            result.put("requestedWindowed", true); result.put("softwareFrameCap", false); result.put("nativeVsyncObserved", null);
            result.put("nativeInternalWidthObserved", nativeWindow == null ? null : nativeWindow.renderWidth());
            result.put("nativeInternalHeightObserved", nativeWindow == null ? null : nativeWindow.renderHeight());
            result.put("requestedConfiguration", requested.toString()); result.put("requestedPathQuality", requested.pathTracing().toString());
            var path = requested.pathTracing();
            var quality = new LinkedHashMap<String, Object>();
            quality.put("samplesPerPixel", path.samplesPerPixel()); quality.put("maxBounces", path.maxBounces());
            quality.put("diffuseBounces", path.diffuseBounces());
            quality.put("reconstructionMode", requested.frameReconstruction().mode().name());
            quality.put("reconstructionQuality", requested.frameReconstruction().quality().name());
            quality.put("reconstructionPreference", requested.frameReconstruction().preference().name());
            quality.put("denoisingPreference", requested.denoising().preference().name());
            quality.put("radianceCachePreference", path.radianceCache().name());
            quality.put("restirDiPreference", path.directLightResampling().name());
            quality.put("restirGiPreference", path.indirectLightResampling().name());
            quality.put("initialExposureOffsetRequested", path.quality().postProcessing().exposureOffset());
            quality.put("finalExposureOffsetRequested", config.scene().equals("balls") ? exposure(material) : 0);
            result.put("requestedQuality", quality);
            result.put("allNativeQualitySettingsVerified", false);
            result.put("nativeStateObservationSequence", nativeStateSequence < 0 ? null : nativeStateSequence);
            result.put("nativePresentationSettingsObserved", nativePresentation != null);
            result.put("nativeExposureSettingsObserved", nativeExposure != null);
            result.put("nativeWindowStateObserved", nativeWindow != null);
            result.put("nativeDenoiserSelectionObserved", nativeDenoiser != null);
            result.put("observedNativeDenoiserSelection", nativeDenoiser == null ? null : nativeDenoiser.name());
            result.put("nativeStandaloneDenoiserActivationObserved", null);
            result.put("nativeStandaloneDenoiserEnabledObserved", nativeRendering == null ? null : nativeRendering.standaloneDenoiser());
            result.put("nativeRenderingStateObserved", nativeRendering != null);
            result.put("nativeRenderingStateMeaning", "Native modes, shader-path flags and initialized objects; not GPU execution counters.");
            Map<String, Object> observedRendering = null;
            if (nativeRendering != null) {
                observedRendering = new LinkedHashMap<>();
                observedRendering.put("realtime", nativeRendering.realtime());
                observedRendering.put("reconstruction", nativeRendering.reconstruction().name());
                observedRendering.put("dlssQuality", nativeRendering.dlssQuality());
                observedRendering.put("samplesPerPixel", nativeRendering.samplesPerPixel());
                observedRendering.put("standaloneDenoiser", nativeRendering.standaloneDenoiser());
                observedRendering.put("denoiser", nativeRendering.denoiser().name());
                observedRendering.put("denoiserIntegrations", nativeRendering.denoiserIntegrations());
                observedRendering.put("radianceCacheMode", nativeRendering.radianceCacheMode());
                observedRendering.put("restirDi", nativeRendering.restirDi());
                observedRendering.put("restirGi", nativeRendering.restirGi());
                observedRendering.put("dxHitObjectPath", nativeRendering.dxHitObjectPath());
                observedRendering.put("dxReorderThreads", nativeRendering.dxReorderThreads());
                observedRendering.put("nvapiHitObjectPath", nativeRendering.nvapiHitObjectPath());
                observedRendering.put("nvapiReorderThreads", nativeRendering.nvapiReorderThreads());
                observedRendering.put("subSamplingMode", nativeRendering.subSamplingMode());
                observedRendering.put("externalLighting", nativeRendering.externalLighting());
                observedRendering.put("analyticBeauty", nativeRendering.analyticBeauty());
            }
            result.put("observedNativeRenderingState", observedRendering);
            Map<String, Object> observedWindow = null;
            if (nativeWindow != null) {
                observedWindow = new LinkedHashMap<>();
                observedWindow.put("requestedWidth", nativeWindow.requestedWidth());
                observedWindow.put("requestedHeight", nativeWindow.requestedHeight());
                observedWindow.put("windowWidth", nativeWindow.windowWidth());
                observedWindow.put("windowHeight", nativeWindow.windowHeight());
                observedWindow.put("framebufferWidth", nativeWindow.framebufferWidth());
                observedWindow.put("framebufferHeight", nativeWindow.framebufferHeight());
                observedWindow.put("renderWidth", nativeWindow.renderWidth());
                observedWindow.put("renderHeight", nativeWindow.renderHeight());
                observedWindow.put("displayWidth", nativeWindow.displayWidth());
                observedWindow.put("displayHeight", nativeWindow.displayHeight());
                observedWindow.put("maximized", nativeWindow.maximized());
                observedWindow.put("fullscreen", nativeWindow.fullscreen());
            }
            result.put("observedNativeWindowState", observedWindow);
            result.put("consumerHudUpdates", hudUpdates); result.put("consumerSelectorPolls", selectorPolls);
            result.put("nativeUiStateQueryAndUpdateExcludedFromSubmitCallSamples", true);
            result.put("nativeHudStateQueryAndUpdateExcludedFromConsumerFrameSamples", true);
            result.put("nativeSelectorPollIncludedInConsumerFrameSamples", true);
            result.put("nativeUiStateQueryAndUpdateIncludedInFpsWallInterval", true);
            Map<String, Object> observedPresentation = null;
            if (nativePresentation != null) {
                observedPresentation = new LinkedHashMap<>();
                observedPresentation.put("maxBounces", nativePresentation.maxBounces());
                observedPresentation.put("diffuseBounces", nativePresentation.diffuseBounces());
                observedPresentation.put("reflexMode", nativePresentation.reflexMode());
                observedPresentation.put("frameGenerationMode", nativePresentation.frameGenerationMode());
                observedPresentation.put("generatedFrames", nativePresentation.generatedFrames());
                observedPresentation.put("activeReflexMode", nativePresentation.activeReflexMode());
                observedPresentation.put("activeFrameGenerationMode", nativePresentation.activeFrameGenerationMode());
                observedPresentation.put("framesPresentedSinceLastNativeQuery", nativePresentation.framesPresentedSinceLastNativeQuery());
                observedPresentation.put("reflexSupported", nativePresentation.reflexSupported());
                observedPresentation.put("frameGenerationSupported", nativePresentation.frameGenerationSupported());
            }
            result.put("observedNativePresentationState", observedPresentation);
            result.put("observedNativeExposureState", nativeExposure == null ? null : Map.of(
                    "automatic", nativeExposure.automatic(), "value", nativeExposure.value(),
                    "compensation", nativeExposure.compensation()));
            result.put("requestedFrameGeneration", requested.frameGeneration().toString());
            result.put("actualGeneratedOrPresentedFrameCounts", null);
            result.put("rendererOpenMillis", rendererOpenMillis); result.put("coldFirstSubmitMillis", coldFirstSubmitMillis);
            result.put("warmupFramesRequested", config.warmupFrames());
            result.put("steadyFrames", steadyFrames);
            long end = measurementEnd == 0 ? lastSteadyFinish : measurementEnd;
            double seconds = steadyFrames == 0 || end <= steadyStart ? 0 : (end - steadyStart) / 1e9;
            result.put("steadyWallSeconds", seconds); result.put("completedNativeSubmitFps", seconds == 0 ? null : steadyFrames / seconds);
            result.put("finalGpuDrainIncluded", finalGpuDrain); result.put("latencyQuantileMethod", "nearest rank of retained bounded samples");
            result.put("submitCall", submit.report()); result.put("consumerFrameThroughCompletedSubmit", frame.report());
            result.put("captureOccurred", captured); result.put("captureReadbackExcludedFromLatencySamples", true);
            result.put("captureReadbackIncludedInFpsWallInterval", captured);
            var diagnostic = new LinkedHashMap<String, Object>();
            boolean enabled = config.diagnostics() != RtxptDiagnostics.Level.OFF;
            diagnostic.put("level", config.diagnostics().name()); diagnostic.put("observed", enabled);
            diagnostic.put("events", enabled ? boundaryEvents : null); diagnostic.put("lostEvents", enabled ? lost : null);
            diagnostic.put("failedEvents", enabled ? failedEvents : null); diagnostic.put("lastObservedFrameSequence", enabled ? lastDiagnosticSequence : null);
            diagnostic.put("finalPollCompleted", enabled ? finalBoundaryPoll : null);
            diagnostic.put("upstreamInternalsExpanded", upstreamInternalsExpanded);
            diagnostic.put("timingMeaning", "Adapter wall intervals including waits; not GPU busy time. Stages exclude warmup.");
            var stages = new LinkedHashMap<String, Object>();
            boundaries.forEach((stage, boundary) -> stages.put(stage.name(), boundary.report()));
            diagnostic.put("stages", stages); result.put("boundaryDiagnostics", diagnostic);
            result.put("failure", failure == null ? null : failure.getClass().getName() + ": " + failure.getMessage());
            return result;
        }
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var text = new StringBuilder("{");
            for (var entry : map.entrySet()) {
                if (text.length() > 1) text.append(',');
                text.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue()));
            }
            return text.append('}').toString();
        }
        if (value instanceof Iterable<?> items) {
            var text = new StringBuilder("[");
            for (var item : items) { if (text.length() > 1) text.append(','); text.append(json(item)); }
            return text.append(']').toString();
        }
        var text = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\""); case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n"); case '\r' -> text.append("\\r"); case '\t' -> text.append("\\t");
                default -> { if (c < 32) text.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else text.append(c); }
            }
        }
        return text.append('"').toString();
    }
}
