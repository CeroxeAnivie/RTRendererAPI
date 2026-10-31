package top.ceroxe.rt.renderer.rt.diagnostics;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.TreeMap;
import top.ceroxe.rt.renderer.rt.device.RtGpuTimestampPool;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.OpticalFrameTimings;

/** 显式开启的分轮诊断；生产默认路径不分配记录、不读时钟、不增加 GPU 工作。 */
public final class OpticalDetailedProfile {
    public static final boolean ENABLED=Boolean.getBoolean("top.ceroxe.rt.medium.opticalFrameProfile")&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalDetailedProfile");
    public static final boolean SHADERS=ENABLED&&Boolean.getBoolean("top.ceroxe.rt.medium.opticalShaderProfile");
    public static final boolean CAMERA_SCATTERING_SHADERS=Boolean.getBoolean("top.ceroxe.rt.medium.cameraScatteringShaderProfile");
    public static final int GROUP=Integer.getInteger("top.ceroxe.rt.medium.opticalShaderProfileGroup",0);
    public static final int STAGES=184,STRIDE=8192,CAMERA_SCATTERING_STAGES=16,CAMERA_SCATTERING_STRIDE=65536;
    private static final String[] CAMERA_SCATTERING_STAGE_NAMES={"primaryRayAndHitOrCacheLoad","lightRangeAndWeight",
            "candidateReservoirUpdate","segmentSamplingAndPosition","sunPhaseEvaluation","opaqueShadowQueryResidual",
            "mediumBeerAttenuation","localLightGeometryAndAttenuation","localPhaseAndContribution",
            "volumeCaustics","sourcePacketAddress","sourcePacketWrite","transmissionShadowFallback",
            "opaqueShadowAnyHit","opaqueShadowClosestHit"};
    private OpticalDetailedProfile(){}
    private static final class Records {
        static final ThreadLocal<String> scope=ThreadLocal.withInitial(()->"unscoped");
        static final Map<String,Long> totals=new TreeMap<>();
        static final ThreadLocal<java.util.List<Pending>> pending=ThreadLocal.withInitial(java.util.ArrayList::new);
        static final ThreadLocal<long[]> shaderAggregation=ThreadLocal.withInitial(()->new long[STAGES*2]);
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
    public static boolean opticalProfileFrame(long frameSequence){return SHADERS&&Math.floorMod(frameSequence,8L)==0L;}
    public static int cameraScatteringShaderBytes(int paths){return CAMERA_SCATTERING_SHADERS
            ?Math.toIntExact(((long)paths+CAMERA_SCATTERING_STRIDE-1)/CAMERA_SCATTERING_STRIDE*CAMERA_SCATTERING_STAGES*16):0;}
    public static boolean cameraScatteringProfileFrame(long frameSequence){
        // Keep all eight bucket phases together, then use production shaders for
        // three groups. A single full-image tile must not be instrumented every frame.
        return CAMERA_SCATTERING_SHADERS&&Math.floorMod(Math.floorDiv(frameSequence,8L),4L)==0L;
    }
    public static void shaderSamples(MemorySegment values,int paths){
        if(!SHADERS)return;
        int first=switch(GROUP){case 0->0;case 1->24;case 2->48;case 3->64;case 4->96;case 5->124;case 6->152;case 7->160;
            default->throw new IllegalArgumentException("optical shader group must be between 0 and 7");};
        int end=switch(GROUP){case 0->24;case 1->48;case 2->64;case 3->96;case 4->124;case 5->152;case 6->160;default->STAGES;};
        long[] totals=Records.shaderAggregation.get();java.util.Arrays.fill(totals,first*2,end*2,0L);
        for(int sample=0;sample<Math.ceilDiv((long)paths,STRIDE);sample++)for(int stage=first;stage<end;stage++){
            long at=((long)sample*STAGES+stage)*16,ticks=values.get(ValueLayout.JAVA_LONG,at),calls=values.get(ValueLayout.JAVA_LONG,at+8);
            if(calls!=0){totals[stage*2]=Math.addExact(totals[stage*2],ticks);totals[stage*2+1]=Math.addExact(totals[stage*2+1],calls);}
        }
        for(int stage=first;stage<end;stage++)if(totals[stage*2+1]!=0){
            add("shader/"+stage+"/ticks",totals[stage*2]);add("shader/"+stage+"/calls",totals[stage*2+1]);}
    }
    public static void cameraScatteringShaderSamples(MemorySegment values,int paths,long frameSequence){
        long records=(paths+(long)CAMERA_SCATTERING_STRIDE-1)/CAMERA_SCATTERING_STRIDE;
        long firstSelected=Math.floorMod(-frameSequence,8L);
        long selected=firstSelected>=records?0:1+(records-1-firstSelected)/8;
        // The last bucket can be partial; a rotated sample beyond the image is not a dropped record.
        long offset=(frameSequence*7919L)&65535L;
        if(selected>0 && (firstSelected+(selected-1)*8L)*CAMERA_SCATTERING_STRIDE+offset>=paths)selected--;
        long observed=0;
        for(int sample=0;sample<records;sample++)for(int stage=0;stage<CAMERA_SCATTERING_STAGES;stage++){
            long at=((long)sample*CAMERA_SCATTERING_STAGES+stage)*16,ticks=values.get(ValueLayout.JAVA_LONG,at),calls=values.get(ValueLayout.JAVA_LONG,at+8);
            if(stage==15){addGlobal("cameraScattering/shadowParityMismatches",ticks);
                addGlobal("cameraScattering/shadowParityQueries",calls);continue;}
            if(calls!=0){if(stage==0)observed++;String prefix="cameraScattering/"+CAMERA_SCATTERING_STAGE_NAMES[stage]+"/";
                try{addGlobal(prefix+"ticks",ticks);addGlobal(prefix+"calls",calls);}
                catch(ArithmeticException overflow){throw new IllegalStateException("camera-scattering diagnostic overflow stage="
                        +CAMERA_SCATTERING_STAGE_NAMES[stage]+" bucket="+sample+" ticks="
                        +Long.toUnsignedString(ticks)+" calls="+Long.toUnsignedString(calls),overflow);}}
        }
        addGlobal("cameraScattering/sampledPaths",selected);
        addGlobal("cameraScattering/recordedPaths",observed);
    }
    private static void addGlobal(String name,long value){
        if((ENABLED||CAMERA_SCATTERING_SHADERS)&&value!=0)synchronized(Records.totals){Records.totals.merge(name,value,Math::addExact);}
    }
    public static void defer(String name,RtGpuTimestampPool.Capture capture){
        if(capture!=null)Records.pending.get().add(new Pending(Records.scope.get(),name,capture));
    }
    public static void resolveDeferred(){
        if(!ENABLED&&!OpticalFrameTimings.ENABLED)return;
        Throwable failure=null;
        try{for(var pending:Records.pending.get())try(var capture=pending.capture()){
            if(capture.resolve())for(int i=0;i<capture.segmentCount();i++) {
                long nanos=capture.segmentNanos(i);
                if(ENABLED)synchronized(Records.totals){Records.totals.merge(pending.scope()+"/"+pending.name()+"/"+capture.segmentName(i)+"Nanos",nanos,Math::addExact);}
                OpticalFrameTimings.recordNamedGpuStage("vulkan."+pending.name()+"/"+capture.segmentName(i),nanos);
            }else if(ENABLED)synchronized(Records.totals){Records.totals.merge(pending.scope()+"/timestampUnavailable",1L,Math::addExact);}
        }catch(RuntimeException|Error exception){if(failure==null)failure=exception;else failure.addSuppressed(exception);}}
        finally{Records.pending.get().clear();}
        if(failure instanceof RuntimeException exception)throw exception;
        if(failure instanceof Error error)throw error;
    }
    /** The caller must retire submitted GPU work before abandoning its query ranges. */
    public static void discardDeferred(){
        if(!ENABLED&&!OpticalFrameTimings.ENABLED)return;
        try{for(var pending:Records.pending.get())pending.capture().close();}
        finally{Records.pending.get().clear();}
    }
    public static Map<String,Long> snapshot(){
        if(!ENABLED&&!CAMERA_SCATTERING_SHADERS)return Map.of();synchronized(Records.totals){return new TreeMap<>(Records.totals);}
    }
    public static String deltaJson(Map<String,Long> before){
        StringBuilder json=new StringBuilder("{\n\"scope\":\"diagnostic-only; wall and GPU time overlap; shader device-clock ticks are sampled latency, not frame percentages\",\n\"shaderGroup\":")
                .append(SHADERS?GROUP:-1).append(",\n\"sampleStride\":").append(STRIDE).append(",\n\"sampleFramePeriod\":8,\n\"values\":{");
        boolean first=true;for(var entry:snapshot().entrySet()){long value=entry.getValue()-before.getOrDefault(entry.getKey(),0L);
            if(value==0)continue;if(!first)json.append(',');first=false;json.append("\n\"").append(entry.getKey()).append("\":").append(value);}
        return json.append("\n}}\n").toString();
    }
    public static String cameraScatteringSummaryJson(Map<String,Long> before,int frames){
        if(frames<1)throw new IllegalArgumentException("frames must be positive");
        var values=snapshot();long sampled=values.getOrDefault("cameraScattering/sampledPaths",0L)
                -before.getOrDefault("cameraScattering/sampledPaths",0L);
        long recorded=values.getOrDefault("cameraScattering/recordedPaths",0L)
                -before.getOrDefault("cameraScattering/recordedPaths",0L);
        long denominator=recorded==0?sampled:recorded;
        long anyHitTicks=values.getOrDefault("cameraScattering/opaqueShadowAnyHit/ticks",0L)
                -before.getOrDefault("cameraScattering/opaqueShadowAnyHit/ticks",0L);
        long closestHitTicks=values.getOrDefault("cameraScattering/opaqueShadowClosestHit/ticks",0L)
                -before.getOrDefault("cameraScattering/opaqueShadowClosestHit/ticks",0L);
        long parityQueries=values.getOrDefault("cameraScattering/shadowParityQueries",0L)
                -before.getOrDefault("cameraScattering/shadowParityQueries",0L);
        long parityMismatches=values.getOrDefault("cameraScattering/shadowParityMismatches",0L)
                -before.getOrDefault("cameraScattering/shadowParityMismatches",0L);
        var stages=new java.util.ArrayList<CameraStage>();long sumTicks=0;
        for(String name:CAMERA_SCATTERING_STAGE_NAMES){String prefix="cameraScattering/"+name+"/";
            long ticks=values.getOrDefault(prefix+"ticks",0L)-before.getOrDefault(prefix+"ticks",0L);
            long calls=values.getOrDefault(prefix+"calls",0L)-before.getOrDefault(prefix+"calls",0L);
            if(name.equals("opaqueShadowQueryResidual"))ticks=Math.max(0L,ticks-anyHitTicks-closestHitTicks);
            long perPath=denominator==0?0:Math.round(ticks/(double)denominator);sumTicks=Math.addExact(sumTicks,ticks);
            stages.add(new CameraStage(name,ticks,calls,perPath,calls==0?0:Math.round(ticks/(double)calls),
                    calls==0?0:ticks/(double)calls));
        }
        stages.sort(java.util.Comparator.comparingLong(CameraStage::nsPerSampledPath).reversed());
        StringBuilder json=new StringBuilder("{\n\"frames\":").append(frames).append(",\n\"sampledPaths\":").append(sampled)
                .append(",\n\"recordedPaths\":").append(recorded).append(",\n\"missingSamples\":").append(sampled-recorded)
                .append(",\n\"shadowParityQueries\":").append(parityQueries)
                .append(",\n\"shadowParityMismatches\":").append(parityMismatches)
                .append(",\n\"clockUnit\":\"nanoseconds\",\n\"stages\":[");
        for(int i=0;i<stages.size();i++){CameraStage stage=stages.get(i);if(i!=0)json.append(',');
            json.append("\n{\"rank\":").append(i+1).append(",\"stage\":\"").append(stage.name())
                    .append("\",\"ticks\":").append(stage.ticks()).append(",\"calls\":").append(stage.calls())
                    .append(",\"nsPerSampledPath\":").append(stage.nsPerSampledPath())
                    .append(",\"sharePct\":").append(sumTicks==0?0:String.format(java.util.Locale.ROOT,"%.2f",stage.ticks()*100.0/sumTicks))
                    .append(",\"nsPerCall\":").append(stage.nsPerCall()).append('}');}
        return json.append("\n],\n\"sumNsPerRecordedPath\":").append(denominator==0?0:Math.round(sumTicks/(double)denominator))
                .append("\n}\n").toString();
    }
    private record CameraStage(String name,long ticks,long calls,long nsPerSampledPath,long nsPerCall,double nsPerCallExact){}
}
