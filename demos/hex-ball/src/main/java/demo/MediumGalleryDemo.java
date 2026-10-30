package demo;

import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.interop.vulkan.*;

/** Native-resolution medium gallery; launching this application performs GPU work. */
public final class MediumGalleryDemo {
    private MediumGalleryDemo() {}

    record Options(int width,int height,int spp,int frames,int seconds,int photons,int bounces,
            boolean fg,boolean caustics,boolean absorption,boolean scattering,boolean heterogeneous,
            boolean emission,boolean refraction,boolean nested,boolean transientVolumes,boolean inward,
            boolean inside,boolean animate,boolean validation,Path capture,MediumGalleryScene.Preset scene,
            boolean controls,int cycleSeconds) {
        static Options parse(String[] args) {
            var values=new HashMap<String,String>();
            var allowed=Set.of("width","height","spp","frames","seconds","photons","bounces","fg","caustics",
                    "absorption","scattering","heterogeneous","emission","refraction","nested","transient",
                    "inward","inside","animate","validation","capture","scene","controls","cycle-seconds");
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
            MediumGalleryScene.Preset scene=MediumGalleryScene.Preset.parse(values.getOrDefault("scene","pbr-direct-uniform"));
            boolean caustics=bool(values,"caustics",scene.caustic());
            if(caustics&&!scene.caustic())
                throw new IllegalArgumentException("--caustics=true requires a caustic scene preset");
            boolean fg=bool(values,"fg",false);
            if (capture!=null && fg) throw new IllegalArgumentException("capture uses CPU readback; specify --fg=false");
            return new Options(width,height,spp,frames,seconds,photons,bounces,fg,caustics,
                    bool(values,"absorption",true),bool(values,"scattering",true),bool(values,"heterogeneous",true),
                    bool(values,"emission",true),bool(values,"refraction",true),bool(values,"nested",true),
                    bool(values,"transient",true),bool(values,"inward",false),bool(values,"inside",false),
                    bool(values,"animate",true),bool(values,"validation",false),capture,
                    scene,
                    bool(values,"controls",true),integer(values,"cycle-seconds",0,0,3600));
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
            System.out.println("介质验收画廊：2560×1440、1 SPP、FG默认关闭；控制台切换场景，窗口显示真实完成FPS与帧时。");
            System.out.println("--scene=pbr-direct-uniform|pbr-direct-density|pbr-multiple-uniform|pbr-multiple-density|boundary-optical-scatter|absorption-emission|glass-scatter-caustics|glass-density-caustics|boundary-density-caustics|gallery");
            System.out.println("--width=2560 --height=1440 --spp=1 --controls=true --cycle-seconds=0 --frames=0 --seconds=0 --bounces=2");
            System.out.println("--validation=true --fg=false --animate=false --capture=path.png；glass-scatter-caustics 场景验收玻璃、均匀散射和焦散交叉。");
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
                .validationEnabled(options.validation()).temporalRendering(TemporalRenderingOptions.disabled())
                .denoising(DenoisingOptions.disabled()).frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(fg).lowLatency(options.fg()?LowLatencyOptions.recommended():LowLatencyOptions.disabled()).build();
    }

    private static void present(Renderer renderer,MediumGalleryScene initialScene,Options options) {
        var selected=new AtomicInteger(options.scene().ordinal());
        Controls controls=options.controls()?Controls.open(selected):null;
        try (var presenter=VulkanFramePresenter.open(renderer,VulkanFramePresenterConfig.builder()
                .title("RTRendererAPI 7 · 介质验收画廊").initialExtent(options.width(),options.height())
                .windowMode(VulkanFramePresenterConfig.WindowMode.WINDOWED)
                .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED).maximumFramesQueuedAhead(2).build())) {
            long submitted=0,retired=0,presented=0,start=System.nanoTime(),lastReport=start;
            long previousCompleted=0,previousGenerated=0,previousTotal=0,previousEpoch=-1;
            RenderFrameRequest pending=null;
            double previousAdmissionSeconds=0,pendingSeconds=0;
            boolean stopping=false,firstAdmission=true,firstPresentation=true;
            MediumGalleryScene scene=initialScene;
            long revision=1,lastCycle=start;
            String lastMetrics="等待首个 GPU 完成样本";
            presenter.setOverlayText("MEDIUM GALLERY | "+scene.preset().id+"\nWaiting for completed GPU samples | Caustics: "+causticStatus(scene));
            while (true) {
                presenter.pollEvents();var window=presenter.windowState();long now=System.nanoTime();
                if (window.closeRequested()) break;
                if (options.frames()>0 && submitted>=options.frames() || options.seconds()>0 && now-start>=options.seconds()*1_000_000_000L)
                    stopping=true;
                if (stopping && retired==submitted) break;
                if(options.cycleSeconds()>0&&now-lastCycle>=options.cycleSeconds()*1_000_000_000L) {
                    selected.updateAndGet(value->(value+1)%(MediumGalleryScene.Preset.values().length-1));lastCycle=now;
                }
                if(selected.get()!=scene.preset().ordinal()&&retired==submitted) {
                    scene=new MediumGalleryScene(options,MediumGalleryScene.Preset.values()[selected.get()],++revision);
                    renderer.apply(scene.transaction());pending=null;
                    previousCompleted=renderer.diagnostics().frameGpuTiming().completedSamples();
                    lastReport=now;
                    if(controls!=null)controls.update(scene.preset(),"切换中，等待新场景完成样本");
                }
                if (!window.drawable()) {LockSupport.parkNanos(1_000_000);continue;}
                if (!stopping && selected.get()==scene.preset().ordinal() && submitted-retired<2) {
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
                    var gpuTiming=diagnostics.frameGpuTiming();
                    double gpuMillis=gpuTiming.averageTotalNanos()/1e6;
                    String gpuFrame=gpuTiming.enabled()&&gpuTiming.completedSamples()>0
                            ?String.format(Locale.ROOT,"GPU输出阶段 %.2f ms",gpuMillis):"GPU输出阶段等待计时样本";
                    lastMetrics=String.format(Locale.ROOT,"%dx%d | %d SPP | GPU完成 %.1f FPS | 完成帧间隔 %.2f ms | %s",
                            window.framebufferWidth(),window.framebufferHeight(),options.spp(),nativeRate,nativeRate>0?1000/nativeRate:Double.NaN,gpuFrame);
                    String text=String.format(Locale.ROOT,"MEDIUM GALLERY | %s | %dx%d | %d SPP%nNative completed %.1f FPS | completion interval %.2f ms | %s%n%s%nCaustics: %s | submitted %d / presented %d",
                            scene.preset().id,window.framebufferWidth(),window.framebufferHeight(),options.spp(),nativeRate,
                            nativeRate>0?1000/nativeRate:Double.NaN,gpuFrame,generated,causticStatus(scene),submitted,presented);
                    presenter.setOverlayText(text);
                    presenter.setTitle("介质验收 · "+scene.preset().label+" | "+lastMetrics);
                    if(controls!=null)controls.update(scene.preset(),lastMetrics);
                    System.out.println(scene.preset().label+" | "+lastMetrics+" | 焦散："+causticStatus(scene));
                    previousCompleted=completed;previousGenerated=fg.generatedFramesActuallyPresented();
                    previousTotal=fg.totalFramesActuallyPresented();previousEpoch=fg.resetEpoch();lastReport=now;
                }
            }
            System.out.println("MediumGallery presentationTimings="+presenter.performanceSnapshot());
        } finally {if(controls!=null)controls.close();}
    }

    static final class Controls {
        private final JFrame frame=new JFrame("介质验收 · 场景选择");
        private final JComboBox<MediumGalleryScene.Preset> scenes=new JComboBox<>(MediumGalleryScene.Preset.values());
        private final JLabel active=new JLabel("初始化中");
        private final JTextArea metrics=new JTextArea("等待 GPU 完成样本");
        static Controls open(AtomicInteger selected) {
            var result=new Controls[1];
            try {SwingUtilities.invokeAndWait(()->result[0]=new Controls(selected));}
            catch(Exception failure){throw new IllegalStateException("无法创建场景控制台；无桌面环境请用--controls=false",failure);}
            return result[0];
        }
        private Controls(AtomicInteger selected) {
            scenes.setSelectedIndex(selected.get());scenes.addActionListener(event->selected.set(scenes.getSelectedIndex()));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);frame.setLayout(new BorderLayout(8,8));
            var top=new JPanel(new BorderLayout(4,4));top.add(scenes,BorderLayout.NORTH);top.add(active,BorderLayout.SOUTH);
            var buttons=new JPanel(new GridLayout(1,2));
            var previous=new JButton("← 上一场");var next=new JButton("下一场 →");
            previous.addActionListener(event->scenes.setSelectedIndex(Math.floorMod(selected.get()-1,scenes.getItemCount())));
            next.addActionListener(event->scenes.setSelectedIndex((selected.get()+1)%scenes.getItemCount()));
            buttons.add(previous);buttons.add(next);top.add(buttons,BorderLayout.CENTER);frame.add(top,BorderLayout.NORTH);
            var coverage=new JTextArea("覆盖：PBR 均匀/密度直接散射、PBR 均匀/密度多次散射、显式边界折射与散射、吸收与自发光。\n\n棱镜焦散池、云海光井、水晶云盒分别展示均匀介质、密度介质、折射边界中的玻璃与体积焦散。\n\nFPS 来自真实 GPU 完成计数；FG 输出单列。数字 1—9 选择正式场景，0 选择组合实验馆；左右键或按钮切换。\n切换先排空已提交帧，统计每秒刷新。");
            coverage.setEditable(false);coverage.setLineWrap(true);coverage.setWrapStyleWord(true);frame.add(coverage,BorderLayout.CENTER);
            metrics.setEditable(false);metrics.setLineWrap(true);metrics.setRows(3);frame.add(metrics,BorderLayout.SOUTH);
            for(int index=0;index<scenes.getItemCount();index++) {
                final int choice=index;String name="scene-"+index;
                int key=index<9?KeyEvent.VK_1+index:KeyEvent.VK_0;
                frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key,0),name);
                frame.getRootPane().getActionMap().put(name,new AbstractAction(){public void actionPerformed(ActionEvent event){scenes.setSelectedIndex(choice);}});
            }
            frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,0),"previous");
            frame.getRootPane().getActionMap().put("previous",new AbstractAction(){public void actionPerformed(ActionEvent event){previous.doClick();}});
            frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT,0),"next");
            frame.getRootPane().getActionMap().put("next",new AbstractAction(){public void actionPerformed(ActionEvent event){next.doClick();}});
            frame.setPreferredSize(new Dimension(600,330));frame.pack();frame.setLocationByPlatform(true);frame.setVisible(true);
        }
        void update(MediumGalleryScene.Preset preset,String text){SwingUtilities.invokeLater(()->{active.setText("当前："+preset.label);metrics.setText(text);});}
        void close(){SwingUtilities.invokeLater(frame::dispose);}
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
    private static String causticStatus(MediumGalleryScene scene) {
        return scene.causticsEnabled()?"已开启":"未启用";
    }
    private static int display(float value) {
        double x=Math.max(0,value),mapped=(x*(2.51*x+.03))/(x*(2.43*x+.59)+.14);
        return (int)Math.round(Math.pow(Math.clamp(mapped,0,1),1/2.2)*255);
    }
}
