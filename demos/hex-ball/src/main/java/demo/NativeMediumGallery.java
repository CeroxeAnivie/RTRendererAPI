package demo;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import javax.swing.*;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.luxcore.LuxCoreAccess;

/** CPU display of upstream Film; presentation is separate from native sampling throughput. */
final class NativeMediumGallery {
    static void run(MediumGalleryDemo.Options options) throws Exception {
        run(options,-1);
    }
    static void run(MediumGalleryDemo.Options options,int certificationPreset) throws Exception {
        boolean certification=certificationPreset>=0;
        if(options.fg())throw new IllegalArgumentException("LuxCore gallery uses native Film; --fg must be false");
        var selection=new AtomicInteger(certification?certificationPreset:options.scene().ordinal());var running=new AtomicBoolean(true);
        var labels=new JLabel[2];var window=new JFrame[1];
        if(options.capture()==null)SwingUtilities.invokeAndWait(()->{
            window[0]=new JFrame("LuxCore · 介质画廊");window[0].setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window[0].addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosed(java.awt.event.WindowEvent e){running.set(false);}});
            labels[0]=new JLabel("等待 LuxCore 完成首个采样",SwingConstants.CENTER);labels[0].setPreferredSize(new Dimension(Math.min(1280,options.width()),Math.min(720,options.height())));
            labels[1]=new JLabel("原生分辨率采样；显示刷新不计作渲染 FPS");
            String[] names=certification?Arrays.stream(MediumCertificationDemo.Preset.values()).map(MediumCertificationDemo.Preset::label).toArray(String[]::new):Arrays.stream(MediumGalleryScene.Preset.values()).map(p->p.label).toArray(String[]::new);
            var select=new JComboBox<String>(names);
            select.setSelectedIndex(selection.get());select.addActionListener(e->selection.set(select.getSelectedIndex()));
            window[0].add(select,BorderLayout.NORTH);window[0].add(labels[0],BorderLayout.CENTER);window[0].add(labels[1],BorderLayout.SOUTH);
            window[0].pack();window[0].setLocationRelativeTo(null);window[0].setVisible(true);
        });
        try(var renderer=Renderer.open("luxcore",RendererConfig.recommended())) {
            var access=renderer.extension(LuxCoreAccess.class).orElseThrow();
            MediumGalleryScene scene=null;MediumCertificationDemo.Fixture fixture=null;int active=-1;long revision=0,sequence=0,shown=0,start=System.nanoTime(),lastReport=start,lastCycle=start;double priorSamples=0;
            while(running.get()) {
                long now=System.nanoTime();double elapsed=(now-start)/1e9;
                if(options.seconds()>0&&elapsed>=options.seconds()||options.frames()>0&&shown>=options.frames())break;
                if(options.cycleSeconds()>0&&now-lastCycle>=options.cycleSeconds()*1_000_000_000L){selection.updateAndGet(v->(v+1)%MediumGalleryScene.Preset.values().length);lastCycle=now;}
                if(active!=selection.get()) {
                    active=selection.get();revision++;
                    if(certification){fixture=MediumCertificationDemo.Preset.fixture(MediumCertificationDemo.Preset.values()[active],revision,sequence,options.width(),options.height(),0,0);renderer.apply(NativeMediumScenes.scene(fixture.scene()));}
                    else {scene=new MediumGalleryScene(options,MediumGalleryScene.Preset.values()[active],revision);renderer.apply(NativeMediumScenes.scene(scene.transaction()));}
                    priorSamples=0;lastReport=System.nanoTime();
                    System.out.println("LuxCoreGallery selected="+active+" nativeMechanisms="+NativeMediumScenes.JSON_CHANGES);
                }
                RenderFrameRequest request=certification?fixture.frame(sequence++,options.width(),options.height(),0,0,fixture.preset().rig()):scene.frame(sequence++,options.width(),options.height(),options.animate()?elapsed:0,0);
                renderer.submit(NativeMediumScenes.frame(request));
                if(options.capture()!=null&&access.totalSamples()<options.width()*(double)options.height()*options.spp()){LockSupport.parkNanos(1_000_000);continue;}
                var frame=renderer.awaitFrame(Duration.ofMillis(options.capture()!=null?30_000:20));
                if(frame.isPresent()) {
                    var f=frame.orElseThrow();var pixels=f.pixels();var image=new BufferedImage(f.width(),f.height(),BufferedImage.TYPE_INT_RGB);var rgb=new int[f.width()*f.height()];
                    for(int i=0;i<rgb.length;i++)rgb[i]=(pixels.get(i*4)&255)<<16|(pixels.get(i*4+1)&255)<<8|(pixels.get(i*4+2)&255);
                    image.setRGB(0,0,f.width(),f.height(),rgb,0,f.width());shown++;
                    if(options.capture()!=null){var parent=options.capture().getParent();if(parent!=null)Files.createDirectories(parent);ImageIO.write(image,"png",options.capture().toFile());System.out.println("LuxCoreGallery capture="+options.capture());break;}
                    SwingUtilities.invokeLater(()->labels[0].setIcon(new ImageIcon(image)));
                }
                now=System.nanoTime();if(now-lastReport>=1_000_000_000L) {
                    double samples=access.totalSamples(),rate=Math.max(0,samples-priorSamples)/(options.width()*(double)options.height())/((now-lastReport)/1e9);
                    String text=String.format(Locale.ROOT,"%dx%d · LuxCore %.2f SPP/秒 · 上游渐进 Film · 已显示 %d 张",options.width(),options.height(),rate,shown);
                    System.out.println(text);SwingUtilities.invokeLater(()->labels[1].setText(text));priorSamples=samples;lastReport=now;
                }
                LockSupport.parkNanos(16_000_000);
            }
        } finally {if(window[0]!=null)SwingUtilities.invokeLater(window[0]::dispose);}
    }
    private NativeMediumGallery() { }
}
