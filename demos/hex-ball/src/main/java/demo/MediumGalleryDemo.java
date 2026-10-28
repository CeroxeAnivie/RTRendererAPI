package demo;

import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.*;

/** Native-resolution medium gallery; launching this application performs GPU work. */
public final class MediumGalleryDemo {
    private MediumGalleryDemo() {}

    record Options(int width,int height,int spp,int frames,int seconds,int photons,int bounces,
            boolean fg,boolean caustics,boolean absorption,boolean scattering,boolean heterogeneous,
            boolean emission,boolean refraction,boolean nested,boolean transientVolumes,boolean inward,
            boolean inside,boolean animate,boolean validation,Path capture) {
        static Options parse(String[] args) {
            var values=new HashMap<String,String>();
            var allowed=Set.of("width","height","spp","frames","seconds","photons","bounces","fg","caustics",
                    "absorption","scattering","heterogeneous","emission","refraction","nested","transient",
                    "inward","inside","animate","validation","capture");
            for (String arg:args) {
                int equal=arg.indexOf('=');
                if (!arg.startsWith("--") || equal<3 || !allowed.contains(arg.substring(2,equal)))
                    throw new IllegalArgumentException("unknown gallery argument: "+arg+"; use --help");
                if (values.put(arg.substring(2,equal),arg.substring(equal+1))!=null)
                    throw new IllegalArgumentException("duplicate gallery argument: "+arg);
            }
            int width=integer(values,"width",2560,1,16384),height=integer(values,"height",1440,1,16384);
            int spp=integer(values,"spp",1,1,8),frames=integer(values,"frames",0,0,1_000_000);
            int seconds=integer(values,"seconds",0,0,86400),photons=integer(values,"photons",131072,1,Caustics.MAX_PHOTONS);
            int bounces=integer(values,"bounces",2,0,8);
            Path capture=values.containsKey("capture")?Path.of(values.get("capture")).toAbsolutePath():null;
            boolean fg=bool(values,"fg",true);
            if (capture!=null && fg) throw new IllegalArgumentException("capture uses CPU readback; specify --fg=false");
            return new Options(width,height,spp,frames,seconds,photons,bounces,fg,bool(values,"caustics",true),
                    bool(values,"absorption",true),bool(values,"scattering",true),bool(values,"heterogeneous",true),
                    bool(values,"emission",true),bool(values,"refraction",true),bool(values,"nested",true),
                    bool(values,"transient",true),bool(values,"inward",false),bool(values,"inside",false),
                    bool(values,"animate",true),bool(values,"validation",false),capture);
        }
        private static int integer(Map<String,String> values,String key,int fallback,int low,int high) {
            int value=Integer.parseInt(values.getOrDefault(key,Integer.toString(fallback)));
            if (value<low || value>high) throw new IllegalArgumentException(key+" must be within ["+low+", "+high+"]");
            return value;
        }
        private static boolean bool(Map<String,String> values,String key,boolean fallback) {
            String value=values.getOrDefault(key,Boolean.toString(fallback));
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key+" must be true or false");
            return Boolean.parseBoolean(value);
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (Arrays.asList(arguments).contains("--help")) {
            System.out.println("Medium gallery: default native 2560x1440, 1 spp, preferred 2x FG, every medium feature enabled.");
            System.out.println("--width=2560 --height=1440 --spp=1 --fg=true --frames=0 --seconds=0 --photons=131072 --bounces=2");
            System.out.println("Feature switches: --caustics=true --absorption=true --scattering=true --heterogeneous=true "
                    +"--emission=true --refraction=true --nested=true --transient=true --inward=false --inside=false --animate=true");
            System.out.println("--validation=true; --fg=false --animate=false --capture=path.png uses CPU readback and no presenter.");
            return;
        }
        Options options=Options.parse(arguments);
        long start=System.nanoTime();
        var scene=new MediumGalleryScene(options);
        System.out.printf(Locale.ROOT,"MediumGallery sceneBuildMs=%.3f options=%s%n",(System.nanoTime()-start)/1e6,options);
        start=System.nanoTime();
        try (var renderer=Renderer.open(configuration(options))) {
            System.out.printf(Locale.ROOT,"MediumGallery rendererOpenMs=%.3f%n",(System.nanoTime()-start)/1e6);
            start=System.nanoTime();renderer.apply(scene.transaction());
            System.out.printf(Locale.ROOT,"MediumGallery sceneAdmissionMs=%.3f (GPU upload convergence is deferred)%n",(System.nanoTime()-start)/1e6);
            if (options.capture()!=null) capture(renderer,scene,options);
            else present(renderer,scene,options);
            System.out.println("MediumGallery finalDiagnostics="+renderer.diagnostics());
        }
    }

    static RendererConfig configuration(Options options) {
        var fg=options.fg()?FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.FRAME_GENERATION).multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build():FrameGenerationOptions.disabled();
        return RendererConfig.presentation().copyBuilder().frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                .maxFramesInFlight(3).cpuFrameReadbackEnabled(options.capture()!=null).gpuTimingsEnabled(true)
                .validationEnabled(options.validation()).temporalRendering(TemporalRenderingOptions.balanced())
                .denoising(DenoisingOptions.disabled()).frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(fg).lowLatency(options.fg()?LowLatencyOptions.recommended():LowLatencyOptions.disabled()).build();
    }

    private static void present(Renderer renderer,MediumGalleryScene scene,Options options) {
        try (var presenter=VulkanFramePresenter.open(renderer,VulkanFramePresenterConfig.builder()
                .title("RTRendererAPI 7 - Medium Gallery").initialExtent(options.width(),options.height())
                .windowMode(VulkanFramePresenterConfig.WindowMode.WINDOWED)
                .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED).maximumFramesQueuedAhead(2).build())) {
            long submitted=0,retired=0,presented=0,start=System.nanoTime(),lastReport=start;
            long previousCompleted=0,previousGenerated=0,previousTotal=0,previousEpoch=-1;
            RenderFrameRequest pending=null;
            double previousAdmissionSeconds=0,pendingSeconds=0;
            boolean stopping=false,firstAdmission=true,firstPresentation=true;
            while (true) {
                presenter.pollEvents();var window=presenter.windowState();long now=System.nanoTime();
                if (window.closeRequested()) break;
                if (options.frames()>0 && submitted>=options.frames() || options.seconds()>0 && now-start>=options.seconds()*1_000_000_000L)
                    stopping=true;
                if (stopping && retired==submitted) break;
                if (!window.drawable()) {LockSupport.parkNanos(1_000_000);continue;}
                if (!stopping && submitted-retired<2) {
                    if (pending==null) {
                        pendingSeconds=(now-start)/1e9;
                        pending=scene.frame(submitted,window.framebufferWidth(),window.framebufferHeight(),pendingSeconds,
                                submitted==0?0:pendingSeconds-previousAdmissionSeconds);
                    }
                    if (renderer.trySubmit(pending) instanceof Renderer.FrameSubmitted) {
                        submitted++;pending=null;previousAdmissionSeconds=pendingSeconds;
                        if (firstAdmission) {System.out.printf(Locale.ROOT,"MediumGallery firstAdmissionMs=%.3f%n",(System.nanoTime()-start)/1e6);firstAdmission=false;}
                    }
                }
                var result=presenter.presentLatestFrame();
                if (result.isPresent()) {
                    retired++;
                    if (result.orElseThrow().outcome()==VulkanFramePresenter.Outcome.PRESENTED) {
                        presented++;
                        if (firstPresentation) {System.out.printf(Locale.ROOT,"MediumGallery firstProxyPresentMs=%.3f%n",(System.nanoTime()-start)/1e6);firstPresentation=false;}
                    }
                } else LockSupport.parkNanos(100_000);
                now=System.nanoTime();
                if (now-lastReport>=1_000_000_000L) {
                    var diagnostics=renderer.diagnostics();var fg=diagnostics.frameGenerationEvidence();
                    long completed=diagnostics.frameGpuTiming().completedSamples();double seconds=(now-lastReport)/1e9;
                    double nativeRate=(completed-previousCompleted)/seconds;
                    String generated=fg.reported() && fg.resetEpoch()==previousEpoch
                            ?String.format(Locale.ROOT,"FG %.1f/s | observed output %.1f/s",(fg.generatedFramesActuallyPresented()-previousGenerated)/seconds,
                                    (fg.totalFramesActuallyPresented()-previousTotal)/seconds):"FG awaiting runtime evidence";
                    String text=String.format(Locale.ROOT,"Medium Gallery | %dx%d | %d spp | native completed %.1f/s | %s%n"
                                    +"Glass + water / amber absorption / density-grid fog / multiple scattering / emission / surface photon caustics%n"
                                    +"GPU whole-frame lifetime mean %.2f ms | FG requested %s | submitted %d / proxy presented %d",
                            window.framebufferWidth(),window.framebufferHeight(),options.spp(),nativeRate,generated,
                            diagnostics.frameGpuTiming().averageTotalNanos()/1e6,options.fg(),submitted,presented);
                    presenter.setOverlayText(text);System.out.println(text);
                    previousCompleted=completed;previousGenerated=fg.generatedFramesActuallyPresented();
                    previousTotal=fg.totalFramesActuallyPresented();previousEpoch=fg.resetEpoch();lastReport=now;
                }
            }
            System.out.println("MediumGallery presentationTimings="+presenter.performanceSnapshot());
        }
    }

    private static void capture(Renderer renderer,MediumGalleryScene scene,Options options) throws Exception {
        int frames=options.frames()==0?64:options.frames();FrameSnapshot last=null;
        for (int i=0;i<frames;i++) {
            var request=scene.frame(i,options.width(),options.height(),0,0);
            long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
            while (!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
                if (System.nanoTime()>deadline) throw new IllegalStateException("gallery capture admission timed out");
                LockSupport.parkNanos(1_000_000);
            }
            last=renderer.awaitFrame(Duration.ofSeconds(120)).orElseThrow(()->new IllegalStateException("gallery frame timed out"));
        }
        var image=new BufferedImage(last.width(),last.height(),BufferedImage.TYPE_INT_RGB);
        var bytes=last.pixels().order(ByteOrder.LITTLE_ENDIAN);
        for (int y=0;y<last.height();y++) for (int x=0;x<last.width();x++) {
            int at=y*last.rowBytes()+x*8;
            float r=Float.float16ToFloat(bytes.getShort(at)),g=Float.float16ToFloat(bytes.getShort(at+2)),b=Float.float16ToFloat(bytes.getShort(at+4));
            if (!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)) throw new IllegalStateException("non-finite HDR output at "+x+","+y);
            image.setRGB(x,y,display(r)<<16|display(g)<<8|display(b));
        }
        Files.createDirectories(options.capture().getParent());ImageIO.write(image,"PNG",options.capture().toFile());
        System.out.println("MediumGallery capture="+options.capture()+" nativeFrames="+frames+" displayTransform=ACES-fit+gamma2.2");
    }
    private static int display(float value) {
        double x=Math.max(0,value),mapped=(x*(2.51*x+.03))/(x*(2.43*x+.59)+.14);
        return (int)Math.round(Math.pow(Math.clamp(mapped,0,1),1/2.2)*255);
    }
}
