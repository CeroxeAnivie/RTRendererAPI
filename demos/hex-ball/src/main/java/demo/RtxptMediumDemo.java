package demo;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.*;

/** GPU-native window, no Swing Film copy and no subprocess renderer. */
public final class RtxptMediumDemo {
    private RtxptMediumDemo() { }
    public static void main(String[] args) throws Exception {
        Map<String,String> values=new HashMap<>();for(String a:args){if(a.equals("--help")){System.out.println("--width=1920 --height=1080 --scene=mixed|glass|nested --native=false --seconds=0; console: mixed/glass/nested/quit");return;}String[] kv=a.replaceFirst("^--","").split("=",2);if(kv.length!=2)throw new IllegalArgumentException("Expected --key=value: "+a);values.put(kv[0],kv[1]);}
        int w=Integer.parseInt(values.getOrDefault("width","1920")),h=Integer.parseInt(values.getOrDefault("height","1080"));
        double seconds=Double.parseDouble(values.getOrDefault("seconds","0"));String name=values.getOrDefault("scene","mixed");
        var commands=new ConcurrentLinkedQueue<String>();Thread.ofVirtual().name("rtxpt-demo-console").start(()->{var in=new Scanner(System.in);while(in.hasNextLine())commands.add(in.nextLine().strip());});
        try(Renderer renderer=Renderer.open("rtxpt",RendererConfig.builder().cpuFrameReadbackEnabled(false).build())) {
            var host=renderer.extension(RtxptAccess.class).orElseThrow();host.configure(Boolean.parseBoolean(values.getOrDefault("native","false"))?RtxptOptions.nativeOneSample():RtxptOptions.recommended());
            long revision=1,sequence=0;renderer.apply(RtxptMediumScene.create(revision,name));
            renderer.submit(RenderFrameRequest.builder(sequence++,w,h,RtxptMediumScene.camera(w,h)).minimumSceneRevision(revision).environment(RtxptMediumScene.environment()).build());
            System.out.println("RTXPT native window ready; scene="+name+". Console: mixed / glass / nested / quit. FG=off.");
            long start=System.nanoTime(),report=start,n=0;while(host.renderFrame()) {
                n++;String command=commands.poll();if(command!=null){if(command.equals("quit"))break;if(Set.of("mixed","glass","nested").contains(command)){
                    name=command;renderer.apply(RtxptMediumScene.create(++revision,name));renderer.submit(RenderFrameRequest.builder(sequence++,w,h,RtxptMediumScene.camera(w,h)).minimumSceneRevision(revision).environment(RtxptMediumScene.environment()).build());
                }}
                long now=System.nanoTime();if(now-report>=1_000_000_000L){double fps=n*1e9/(now-report);System.out.printf(Locale.ROOT,"scene=%s, %.2f FPS, %.3f ms/frame, FG=off%n",name,fps,1000/fps);n=0;report=now;}
                if(seconds>0 && (now-start)/1e9>=seconds)break;
            }
        }
    }
}
