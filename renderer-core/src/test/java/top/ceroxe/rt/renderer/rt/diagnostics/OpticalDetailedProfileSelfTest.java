package top.ceroxe.rt.renderer.rt.diagnostics;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;

/** Checks the sampled-record ABI, group boundaries and reusable aggregation storage. */
public final class OpticalDetailedProfileSelfTest {
    public static void main(String[] args) {
        System.setProperty("top.ceroxe.rt.medium.opticalFrameProfile","true");
        System.setProperty("top.ceroxe.rt.medium.opticalDetailedProfile","true");
        System.setProperty("top.ceroxe.rt.medium.opticalShaderProfile","true");
        System.setProperty("top.ceroxe.rt.medium.opticalShaderProfileGroup","0");
        try(var arena=Arena.ofConfined()) {
            int paths=OpticalDetailedProfile.STRIDE+1;
            var records=arena.allocate(OpticalDetailedProfile.shaderBytes(paths),16);
            put(records,0,0,100,1);put(records,1,0,300,2);
            put(records,0,23,50,1);put(records,0,24,999,1);
            put(records,0,1,999,0);
            var before=OpticalDetailedProfile.snapshot();
            OpticalDetailedProfile.shaderSamples(records,paths);
            var after=OpticalDetailedProfile.snapshot();
            require(after.get("unscoped/shader/0/ticks")==400,"both sample records retained");
            require(after.get("unscoped/shader/0/calls")==3,"call counts retained");
            require(after.get("unscoped/shader/23/ticks")==50,"last group stage retained");
            require(!after.containsKey("unscoped/shader/24/ticks"),"next group excluded");
            require(!after.containsKey("unscoped/shader/1/ticks"),"empty records ignored");
            records.fill((byte)0);put(records,1,0,7,1);
            OpticalDetailedProfile.shaderSamples(records,paths);
            after=OpticalDetailedProfile.snapshot();
            require(after.get("unscoped/shader/0/ticks")==407,"scratch aggregation resets");
            require(after.get("unscoped/shader/23/ticks")==50,"empty next batch adds nothing");
            require(OpticalDetailedProfile.deltaJson(before).contains("\"shaderGroup\":0"),"report identifies group");
        }
        System.out.println("OpticalDetailedProfileSelfTest passed: partial sample bucket, group bounds, counters and reusable storage");
    }
    private static void put(java.lang.foreign.MemorySegment records,int sample,int stage,long ticks,long calls) {
        long offset=((long)sample*OpticalDetailedProfile.STAGES+stage)*16;
        records.set(ValueLayout.JAVA_LONG,offset,ticks);records.set(ValueLayout.JAVA_LONG,offset+8,calls);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
