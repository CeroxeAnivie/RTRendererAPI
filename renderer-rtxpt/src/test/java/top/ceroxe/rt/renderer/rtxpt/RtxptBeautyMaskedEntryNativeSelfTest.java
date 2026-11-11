package top.ceroxe.rt.renderer.rtxpt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Focused first-masked beauty evidence; run each mode in a fresh native session. */
public final class RtxptBeautyMaskedEntryNativeSelfTest {
    private static final int EXTENT=320;
    private static final long ACTIVE=256;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();

    public static void main(String[] args)throws Exception {
        boolean dormant=Arrays.asList(args).contains("--dormant-return");
        if(dormant&&Arrays.asList(args).contains("--masked-live"))
            throw new IllegalArgumentException("Select one masked entry mode");
        for(String arg:args)if(arg.startsWith("--")&&!arg.equals("--dormant-return")&&!arg.equals("--masked-live"))
            throw new IllegalArgumentException("Unknown argument: "+arg);
        String mode=dormant?"dormant-return":"masked-live";
        Path output=Path.of(Arrays.stream(args).filter(arg->!arg.startsWith("--")).findFirst()
                .orElse("build/reports/beauty-masked-entry-"+mode)).toAbsolutePath();
        Files.createDirectories(output);
        try(var evidence=new Evidence(output,mode)){
            evidence.mark("renderer-open-enter",0,"");
            try(var renderer=RtxptRenderers.openHiddenWindow(RtxptRenderers.nativeLightingConfiguration())){
                evidence.mark("renderer-open-exit",0,"");
                var access=renderer.extension(RtxptAccess.class).orElseThrow();
                access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
                var javaMonitor=access.diagnosticMonitor();
                var instance=SceneInstance.builder(11,1).build();
                apply(renderer,SceneTransaction.builder(1).resetScene().upsert(opaque()).upsert(quad()).upsert(instance).build(),1,evidence);
                submit(renderer,access,javaMonitor,1,evidence);
                // Creating the native session requires its first submit. Polling
                // the acquired monitor goes directly to Snapshot, never Invoke.
                evidence.mark("monitor-acquire-enter",1,"");
                var monitor=access.nativeDiagnosticMonitor();
                evidence.mark("monitor-acquire-exit",1,"");
                try(var watchdog=new Watchdog(monitor,evidence,output,mode)){
                    capture(access,output,"opaque-initial",1,1,evidence);
                    var masked=MaterialAsset.builder(1).baseColorRgba8(0xff000000).emissive(new Rgb(4,0,0))
                            .blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(8)
                            .alphaCutoff(.5f).doubleSided(true).build();
                    var texture=TextureAsset.builder(8,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                            .pixelsRgba8(new byte[]{-1,-1,-1,-1}).build();
                    var mutation=SceneTransaction.builder(2).upsert(texture).upsert(masked);
                    if(dormant)mutation.removeInstance(11);
                    apply(renderer,mutation.build(),2,evidence);
                    submit(renderer,access,javaMonitor,2,evidence);
                    capture(access,output,dormant?"masked-domain-empty":"masked-live",2,dormant?-1:0,evidence);
                    if(dormant){
                        apply(renderer,SceneTransaction.builder(3).upsert(instance).build(),3,evidence);
                        submit(renderer,access,javaMonitor,3,evidence);
                        capture(access,output,"masked-dormant-return",3,0,evidence);
                    }
                    long cutoutSequence=dormant?4:3;
                    var cutout=TextureAsset.builder(8,2,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                            .pixelsRgba8(new byte[]{-1,-1,-1,0,-1,-1,-1,-1}).build();
                    var background=MeshAsset.builder(2,new float[]{-2,-2,-.25f,2,-2,-.25f,2,2,-.25f,-2,2,-.25f},
                            new int[]{0,1,2,0,2,3},new long[]{2,2})
                            .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();
                    apply(renderer,SceneTransaction.builder(cutoutSequence).upsert(cutout)
                            .upsert(MaterialAsset.builder(2).baseColorRgba8(0xff000000)
                                    .emissive(new Rgb(0,4,0)).doubleSided(true).build())
                            .upsert(background).upsert(SceneInstance.builder(12,2).build()).build(),cutoutSequence,evidence);
                    submit(renderer,access,javaMonitor,cutoutSequence,evidence);
                    captureCutout(access,output,cutoutSequence,evidence);
                    evidence.mark("rendering-state-enter",cutoutSequence,"");
                    require(!access.renderingState().externalLighting(),"Fixture unexpectedly selected independent lighting");
                    evidence.mark("rendering-state-exit",cutoutSequence,"");
                }
                evidence.mark("renderer-close-enter",dormant?4:3,"");
            }
            evidence.mark("renderer-close-exit",dormant?4:3,"");
            evidence.mark("pass",dormant?4:3,"Fresh-frame and alpha-hole pixel assertions passed; not a performance result");
        }
        System.out.println("BEAUTY_MASKED_ENTRY_NATIVE PASS mode="+mode+" freshFrames="+(dormant?4:3)
                +" defaultSampler=true opaqueWhiteTexture=true realAlphaHole=true emissivePixels=true performanceClaim=false");
    }

    private static void apply(Renderer renderer,SceneTransaction transaction,long sequence,Evidence evidence)throws IOException {
        evidence.mark("apply-enter",sequence,"");renderer.apply(transaction);evidence.mark("apply-exit",sequence,"");
    }
    private static void submit(Renderer renderer,RtxptAccess access,RtxptDiagnostics.Monitor monitor,long sequence,Evidence evidence)throws IOException {
        var request=RenderFrameRequest.builder(sequence,EXTENT,EXTENT,CAMERA).minimumSceneRevision(sequence).build();
        evidence.mark("submit-enter",sequence,"");renderer.submit(request);evidence.mark("submit-exit",sequence,"");
        evidence.mark("await-idle-enter",sequence,"");access.awaitIdle();evidence.mark("await-idle-exit",sequence,"");
        var snapshot=monitor.poll();
        require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(RtxptDiagnostics.Event::failed),
                "Failed or lost API diagnostic events at sequence="+sequence);
    }
    private static void capture(RtxptAccess access,Path output,String name,long sequence,int expected,Evidence evidence)throws Exception {
        Path path=output.resolve(name+".png");
        evidence.mark("capture-enter",sequence,name);access.capture(path);evidence.mark("capture-exit",sequence,name);
        var image=ImageIO.read(path.toFile());
        require(image!=null&&image.getWidth()==EXTENT&&image.getHeight()==EXTENT,"Missing fresh beauty image: "+name);
        int rgb=image.getRGB(EXTENT/2,EXTENT/2);int[] values={(rgb>>>16)&255,(rgb>>>8)&255,rgb&255};
        if(expected<0){
            require(!(values[0]>32&&values[0]>values[1]+20&&values[0]>values[2]+20)
                            &&!(values[1]>32&&values[1]>values[0]+20&&values[1]>values[2]+20),
                    "Dormant emissive geometry remained in beauty output: "+name);
        }else{
            require(values[expected]>32,"Emissive material disappeared from beauty output: "+name);
            for(int channel=0;channel<3;channel++)if(channel!=expected)
                require(values[expected]>values[channel]+20,"Wrong fresh beauty channel: "+name+Arrays.toString(values));
        }
        evidence.mark("pixel-assertion-pass",sequence,name+Arrays.toString(values));
    }
    private static MaterialAsset opaque(){
        return MaterialAsset.builder(1).baseColorRgba8(0xff000000).emissive(new Rgb(0,4,0)).doubleSided(true).build();
    }
    private static void captureCutout(RtxptAccess access,Path output,long sequence,Evidence evidence)throws Exception {
        Path path=output.resolve("masked-alpha-hole.png");
        evidence.mark("capture-enter",sequence,"masked-alpha-hole");access.capture(path);
        var image=ImageIO.read(path.toFile());
        require(image!=null&&image.getWidth()==EXTENT&&image.getHeight()==EXTENT,"Missing fresh alpha-hole beauty image");
        int left=image.getRGB(EXTENT/4,EXTENT/2),right=image.getRGB(3*EXTENT/4,EXTENT/2);
        int lr=(left>>>16)&255,lg=(left>>>8)&255,rr=(right>>>16)&255,rg=(right>>>8)&255;
        require((lr>lg+20&&rg>rr+20)||(lg>lr+20&&rr>rg+20),
                "Masked alpha hole did not reveal green background beside red surface: "
                        +Integer.toHexString(left)+" / "+Integer.toHexString(right));
        evidence.mark("alpha-hole-pixel-assertion-pass",sequence,Integer.toHexString(left)+" / "+Integer.toHexString(right));
    }
    private static MeshAsset quad(){
        return MeshAsset.builder(1,new float[]{-2,-2,0,2,-2,0,2,2,0,-2,2,0},new int[]{0,1,2,0,2,3},new long[]{1,1})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String quote(String value){
        var result=new StringBuilder("\"");
        for(char c:value.toCharArray())switch(c){
            case '\\'->result.append("\\\\");case '"'->result.append("\\\"");case '\n'->result.append("\\n");
            case '\r'->result.append("\\r");case '\t'->result.append("\\t");
            default->{if(c<32)result.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else result.append(c);}
        }
        return result.append('"').toString();
    }
    private static String shortError(Throwable error){String value=error.toString();return value.substring(0,Math.min(512,value.length()));}

    private static final class Evidence implements AutoCloseable {
        final String mode;final BufferedWriter writer;private int marks;
        Evidence(Path output,String mode)throws IOException {
            this.mode=mode;writer=Files.newBufferedWriter(output.resolve("lifecycle.jsonl"),StandardCharsets.UTF_8);
            mark("start",0,"Lifecycle clocks use Java monotonic time; native timestamps have their own clock domain");
        }
        synchronized void mark(String phase,long sequence,String detail)throws IOException {
            if(++marks>64)throw new IOException("Bounded lifecycle record limit exceeded");
            writer.write("{\"mode\":"+quote(mode)+",\"phase\":"+quote(phase)+",\"sequence\":"+sequence
                    +",\"unixMillis\":"+System.currentTimeMillis()+",\"javaNanoTime\":"+System.nanoTime()+",\"detail\":"+quote(detail)+"}\n");
            writer.flush();
        }
        @Override public void close()throws IOException {writer.close();}
    }

    private static final class Watchdog implements AutoCloseable {
        private final RtxptDiagnostics.NativeMonitor monitor;private final Evidence evidence;
        private final Path output;private final String mode;private final Thread thread;
        private volatile boolean running=true;private volatile Throwable failure;
        private long polls,lost;private RtxptDiagnostics.NativeEvent lastCompleted;
        Watchdog(RtxptDiagnostics.NativeMonitor monitor,Evidence evidence,Path output,String mode)throws IOException {
            this.monitor=monitor;this.evidence=evidence;this.output=output;this.mode=mode;
            evidence.mark("watchdog-start",1,"Direct native Snapshot, no owner Invoke; bounded latest JSON every second");
            thread=new Thread(this::run,"beauty-masked-native-watchdog");thread.setDaemon(true);thread.start();
        }
        private void run(){
            try{
                while(running){
                    long begin=System.nanoTime();write("poll-enter",0,List.of(),List.of(),0,"");
                    var snapshot=monitor.poll();polls++;lost+=snapshot.lostEvents();
                    var completed=new ArrayList<RtxptDiagnostics.NativeEvent>();
                    var active=new ArrayList<RtxptDiagnostics.NativeEvent>();
                    for(var event:snapshot.events()){
                        if((event.flags()&ACTIVE)!=0)active.add(event);
                        else if(!event.stage().startsWith("Diagnostic")){
                            completed.add(event);
                            if(lastCompleted==null||end(event)>end(lastCompleted))lastCompleted=event;
                        }
                    }
                    completed.sort(Comparator.comparingLong(Watchdog::end).reversed());
                    active.sort(Comparator.comparingLong(RtxptDiagnostics.NativeEvent::inclusiveNanos).reversed());
                    write("poll-exit",System.nanoTime()-begin,completed.subList(0,Math.min(8,completed.size())),
                            active.subList(0,Math.min(16,active.size())),Math.max(0,active.size()-16),"");
                    Thread.sleep(1000);
                }
            }catch(InterruptedException interrupted){
                if(running){failure=interrupted;Thread.currentThread().interrupt();}
            }catch(Throwable error){
                failure=error;try{write("poll-failed",0,List.of(),List.of(),0,shortError(error));}catch(IOException ignored){ }
            }
        }
        private static long end(RtxptDiagnostics.NativeEvent event){return event.beginNanos()+event.inclusiveNanos();}
        private static String event(RtxptDiagnostics.NativeEvent value){
            if(value==null)return "null";
            return "{\"stage\":"+quote(value.stage())+",\"sequence\":"+value.sequence()+",\"revision\":"+value.sceneRevision()
                    +",\"thread\":"+value.thread()+",\"scope\":"+value.scopeId()+",\"parentScope\":"+value.parentScopeId()
                    +",\"beginNanos\":"+value.beginNanos()+",\"inclusiveNanos\":"+value.inclusiveNanos()+",\"selfNanos\":"+value.selfNanos()
                    +",\"threadCpuNanos\":"+value.threadCpuNanos()+",\"flags\":"+value.flags()+",\"items\":"+value.items()+",\"tasks\":"+value.tasks()+"}";
        }
        private void write(String state,long wall,List<RtxptDiagnostics.NativeEvent> completed,List<RtxptDiagnostics.NativeEvent> active,int truncated,String error)throws IOException {
            String json="{\"mode\":"+quote(mode)+",\"pollState\":"+quote(state)+",\"unixMillis\":"+System.currentTimeMillis()
                    +",\"javaNanoTime\":"+System.nanoTime()+",\"polls\":"+polls+",\"pollWallNanos\":"+wall+",\"lostEventsTotal\":"+lost
                    +",\"lastCompleted\":"+event(lastCompleted)+",\"recentCompleted\":"+completed.stream().map(Watchdog::event).toList()
                    +",\"activeScopes\":"+active.stream().map(Watchdog::event).toList()+",\"activeTruncated\":"+truncated
                    +",\"publicNativeEventOmitsIdentity\":true,\"error\":"+quote(error)+"}\n";
            Path temporary=output.resolve("native-watchdog.json.tmp"),target=output.resolve("native-watchdog.json");
            Files.writeString(temporary,json,StandardCharsets.UTF_8);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }
        @Override public void close()throws Exception {
            // The polling sleep is at most one second. Let it finish rather
            // than interrupting a concurrent JSON write on normal shutdown.
            running=false;thread.join(3000);
            evidence.mark("watchdog-stop",0,thread.isAlive()?"Poll did not return within stop grace":"");
            require(!thread.isAlive(),"Native diagnostic poll remained blocked");
            require(failure==null,"Native watchdog failed: "+failure);
            require(lost==0,"Lost native diagnostic events: "+lost);
        }
    }
}
