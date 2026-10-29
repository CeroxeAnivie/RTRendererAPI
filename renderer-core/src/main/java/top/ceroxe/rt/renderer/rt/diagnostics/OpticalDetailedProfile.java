package top.ceroxe.rt.renderer.rt.diagnostics;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.TreeMap;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;

/** 显式开启的分轮诊断；生产默认路径不分配记录、不读时钟、不增加 GPU 工作。 */
public final class OpticalDetailedProfile {
    public static final boolean ENABLED=Boolean.getBoolean("top.ceroxe.rt.medium.opticalFrameProfile")&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalDetailedProfile");
    public static final boolean SHADERS=ENABLED&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalShaderProfile");
    public static final int GROUP=Integer.getInteger("top.ceroxe.rt.medium.opticalShaderProfileGroup",0);
    public static final int STAGES=176,STRIDE=8192;
    private OpticalDetailedProfile(){}
    private static final class Records {
        static final ThreadLocal<String> scope=ThreadLocal.withInitial(()->"unscoped");
        static final Map<String,Long> totals=new TreeMap<>();
        static final ThreadLocal<java.util.List<Pending>> pending=ThreadLocal.withInitial(java.util.ArrayList::new);
    }
    private record Pending(String scope,String name,RtGpuTimestampPool.Capture capture){}
    public static void scope(int tile,int sample,int branch,int round){
        if(ENABLED)Records.scope.set("tile"+tile+"/sample"+sample+"/branch"+branch+"/round"+round);
    }
    public static void add(String name,long value){
        if(ENABLED)synchronized(Records.totals){Records.totals.merge(Records.scope.get()+"/"+name,value,Math::addExact);}
    }
    public static long start(){return ENABLED?System.nanoTime():0;}
    public static long elapsed(String name,long start){
        if(!ENABLED)return 0;long now=System.nanoTime();add(name,now-start);return now;
    }
    public static int shaderBytes(int paths){return SHADERS?Math.toIntExact(((long)paths+STRIDE-1)/STRIDE*STAGES*16):0;}
    public static void shaderSamples(MemorySegment values,int paths){
        for(int sample=0;sample<(paths+STRIDE-1)/STRIDE;sample++)for(int stage=0;stage<STAGES;stage++){
            long at=((long)sample*STAGES+stage)*16,ticks=values.get(ValueLayout.JAVA_LONG,at),calls=values.get(ValueLayout.JAVA_LONG,at+8);
            if(calls!=0){add("shader/"+stage+"/ticks",ticks);add("shader/"+stage+"/calls",calls);}
        }
    }
    public static void defer(String name,RtGpuTimestampPool.Capture capture){
        if(capture!=null)Records.pending.get().add(new Pending(Records.scope.get(),name,capture));
    }
    public static void resolveDeferred(){
        if(!ENABLED)return;
        Throwable failure=null;
        try{for(var pending:Records.pending.get())try(var capture=pending.capture()){
            synchronized(Records.totals){
                if(capture.resolve())for(int i=0;i<capture.segmentCount();i++)
                    Records.totals.merge(pending.scope()+"/"+pending.name()+"/"+capture.segmentName(i)+"Nanos",capture.segmentNanos(i),Math::addExact);
                else Records.totals.merge(pending.scope()+"/timestampUnavailable",1L,Math::addExact);
            }
        }catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}}
        finally{Records.pending.get().clear();}
        if(failure instanceof RuntimeException exception)throw exception;
        if(failure instanceof Error error)throw error;
    }
    /** The caller must retire submitted GPU work before abandoning its query ranges. */
    public static void discardDeferred(){
        if(!ENABLED)return;
        try{for(var pending:Records.pending.get())pending.capture().close();}
        finally{Records.pending.get().clear();}
    }
    public static Map<String,Long> snapshot(){
        if(!ENABLED)return Map.of();synchronized(Records.totals){return new TreeMap<>(Records.totals);}
    }
    public static String deltaJson(Map<String,Long> before){
        StringBuilder json=new StringBuilder("{\n\"scope\":\"diagnostic-only; wall and GPU time overlap; shader device-clock ticks are sampled latency, not frame percentages\",\n\"shaderGroup\":")
                .append(SHADERS?GROUP:-1).append(",\n\"sampleStride\":").append(STRIDE).append(",\n\"values\":{");
        boolean first=true;for(var entry:snapshot().entrySet()){long value=entry.getValue()-before.getOrDefault(entry.getKey(),0L);
            if(value==0)continue;if(!first)json.append(',');first=false;json.append("\n\"").append(entry.getKey()).append("\":").append(value);}
        return json.append("\n}}\n").toString();
    }
}
