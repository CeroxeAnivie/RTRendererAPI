package demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** RTXPT-only GPU presentation; the upstream UI draws the HUD without a Vulkan compositor. */
public final class RtxptMediumDemo {
    private RtxptMediumDemo() { }

    public static void main(String[] args) throws Exception {
        Map<String,String> values = new HashMap<>();
        for (String argument : DemoApiControls.parse(args)) {
            if (argument.equals("--help")) {
                System.out.println("--width=1920 --height=1080 --scene=" + String.join("|",RtxptMediumScene.PRESETS)
                    + " --native=false --seconds=0 --capture=file.png\n"
                    + "Default: RTXPT ONLY, windowed, 1 SPP, DLSS-RR + SHaRC, 2x FG. HUD selector or keys 1-6; Esc quits.\n"
                    + "--native selects internal resolution only; the backend is always RTXPT.\n"
                    + "Console accepts scene names and quit. -Ddemo.disable-fg=true disables FG.");
                return;
            }
            String[] pair = argument.replaceFirst("^--", "").split("=", 2);
            if (pair.length != 2 || !java.util.Set.of("width","height","scene","native","seconds","capture").contains(pair[0]))
                throw new IllegalArgumentException("Unknown option: " + argument);
            values.put(pair[0], pair[1]);
        }
        int width = Integer.parseInt(values.getOrDefault("width", "1920"));
        int height = Integer.parseInt(values.getOrDefault("height", "1080"));
        new DemoConfig(width,height,1,true,false,false,0,0,Duration.ZERO);
        double seconds = Double.parseDouble(values.getOrDefault("seconds", "0"));
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("seconds must be finite and nonnegative");
        String scene = values.getOrDefault("scene", "mixed");
        RtxptMediumScene.description(scene);
        Path capture = values.containsKey("capture") ? Path.of(values.get("capture")).toAbsolutePath() : null;
        if (capture != null) Files.createDirectories(capture.getParent());
        var commands = new ConcurrentLinkedQueue<String>();
        Thread.ofVirtual().name("rtxpt-demo-console").start(() -> {
            var input = new Scanner(System.in);
            while (input.hasNextLine()) commands.add(input.nextLine().strip());
        });
        var config = DemoRendererProfile.rtxptMediumInteractive(Boolean.parseBoolean(values.getOrDefault("native", "false")));
        var stats = new RenderStats();
        try (var renderer = Renderer.open("rtxpt", config)) {
            renderer.apply(RtxptMediumScene.create(1,scene));
            renderer.submit(frameRequest(0,1,width,height));
            var host = renderer.extension(RtxptAccess.class).orElseThrow();
            host.setWindowTitle("RTRendererAPI 7.0.0 - Medium Demo | RTXPT Only");
            long revision = 1, sequence = 1;
            recordPresented(host,stats,0);
            var keys = new DemoWindowKeys(host.windowHandle());
            host.setOverlayText(overlay(renderer,host,stats,scene));
            host.setOverlayChoices(RtxptMediumScene.LABELS,RtxptMediumScene.PRESETS.indexOf(scene));
            System.out.println("RTXPT MEDIUM READY: backend=RTXPT_ONLY; composition=disabled; scene=" + scene + "; HUD selector / keys 1-6, Esc; " + host.runtimeStatus());
            long start = System.nanoTime(), report = start, hudUpdate = start;
            long reportFrames = host.bridgeTimings().frames();
            boolean captured = false;
            while (true) {
                String key = keys.pollCommand();
                String command = key != null ? key : commands.poll();
                int selected=host.overlaySelection();
                if(command==null && selected>=0 && !scene.equals(RtxptMediumScene.PRESETS.get(selected)))
                    command=RtxptMediumScene.PRESETS.get(selected);
                if ("quit".equals(command)) break;
                long before = host.bridgeTimings().frames();
                if (command != null && RtxptMediumScene.PRESETS.contains(command) && !scene.equals(command)) {
                    scene = command;
                    renderer.apply(RtxptMediumScene.create(++revision,scene));
                    submit(renderer,sequence++,revision,width,height);
                    host.setOverlayChoices(RtxptMediumScene.LABELS,RtxptMediumScene.PRESETS.indexOf(scene));
                    hudUpdate = 0;
                    System.out.println("RTXPT MEDIUM SCENE: " + scene);
                } else if (!host.renderFrame()) break;
                recordPresented(host,stats,before);
                long now = System.nanoTime();
                if (now-hudUpdate >= 500_000_000L) {
                    host.setOverlayText(overlay(renderer,host,stats,scene));
                    hudUpdate = now;
                }
                if (now-report >= 1_000_000_000L) {
                    long frames = host.bridgeTimings().frames();
                    var generation = renderer.diagnostics().frameGenerationEvidence();
                    double nativeFps = (frames-reportFrames)*1e9/(now-report);
                    System.out.printf(Locale.ROOT,"MEDIUM scene=%s native=%.2f FPS output=%.2f FPS | %.3f ms/native | SDK presented=%d generated=%d failures=%d%n",
                        scene,nativeFps,DemoTechnologyHud.presentTotalFps(generation,nativeFps),
                        nativeFps>0?1000/nativeFps:0,generation.totalFramesActuallyPresented(),
                        generation.generatedFramesActuallyPresented(),generation.stateQueryFailures());
                    report = now;
                    reportFrames = frames;
                }
                if (capture != null && !captured && now-start >= 2_000_000_000L && stats.presentedFrames.get() >= 120) {
                    host.capture(capture);
                    captured = true;
                    System.out.println("RTXPT MEDIUM CAPTURE: " + capture);
                }
                if (seconds > 0 && (now-start)/1e9 >= seconds) break;
            }
            host.awaitIdle();
            System.out.println("RTXPT MEDIUM FINAL: " + host.runtimeStatus() + "; " + renderer.diagnostics().frameGenerationEvidence());
        } catch (IllegalStateException failure) {
            if (!"RTXPT window closed".equals(failure.getMessage())) throw failure;
        }
    }

    static void submit(Renderer renderer,long sequence,long revision,int width,int height) {
        renderer.submit(frameRequest(sequence,revision,width,height));
    }

    static RenderFrameRequest frameRequest(long sequence,long revision,int width,int height) {
        return DemoRendererProfile.frame(sequence,width,height,RtxptMediumScene.camera(width,height))
            .minimumSceneRevision(revision).environment(RtxptMediumScene.environment()).build();
    }

    static void recordPresented(RtxptAccess host,RenderStats stats,long previousFrames) {
        for (long i=previousFrames; i<host.bridgeTimings().frames(); i++) {
            stats.submittedFrames.incrementAndGet();
            stats.framePresented();
        }
    }

    static String overlay(Renderer renderer,RtxptAccess host,RenderStats stats,String scene) {
        var actual = host.runtimeStatus();
        var display = host.presentationStatus();
        var diagnostics = renderer.diagnostics();
        var extent = new DemoConfig(display.outputWidth(),display.outputHeight(),1,true,false,false,0,0,Duration.ZERO);
        stats.observeGpuTiming(diagnostics.frameGpuTiming());
        return DemoTechnologyHud.capture(renderer,extent,stats,diagnostics).text()
            + String.format(Locale.ROOT,"%nBACKEND RTXPT ONLY | 1.9.0 | D3D12 | WINDOWED%nINPUT %dx%d -> OUTPUT %dx%d%nSHaRC %s | ReSTIR DI/GI %s/%s%nSCENE %s | %s%n[1] Mixed optics [2] Glass [3] Nested [4] Absorption [5] Rough [6] IOR [Esc] Quit",
                actual.renderWidth(),actual.renderHeight(),display.outputWidth(),display.outputHeight(),
                actual.sharc()?"ACTIVE":"DISABLED",actual.restirDi()?"ACTIVE":"DISABLED",actual.restirGi()?"ACTIVE":"DISABLED",
                scene.toUpperCase(Locale.ROOT),RtxptMediumScene.description(scene));
    }
}
