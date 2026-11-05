package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Diagnostic ABI and statistics tests. Accepts a fixture emitted by the native selftest. */
public final class RtxptDiagnosticsSelfTest {
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejected(long[] values){
        try{RtxptDiagnostics.decode(values);throw new AssertionError("Malformed snapshot accepted");}
        catch(IllegalStateException expected){ }
    }
    private static long[] data(long... values){return values;}
    private static void compatibility(){
        var empty=RtxptDiagnostics.decode(data(2,12,0,0,0,0,0));
        require(empty.events().isEmpty()&&empty.activeScopes().isEmpty(),"Legacy empty snapshot");
        var legacy=RtxptDiagnostics.decode(data(2,12,0,1,0,0,0,7,2,1,3,1,100,90,70,2,0,0,0));
        require(legacy.frames().samples()==1&&legacy.frames().p50Nanos()==90,"Legacy FRAME percentile");
        require(legacy.events().getFirst().scopeId()==0,"Legacy ABI invented scope identity");
        require(legacy.recordedLevel().isEmpty(),"Legacy snapshot invented level");
        rejected(data(3,16,0,0,0,0,0,0,0,0,0,1,3));
        rejected(data(3,16,0,0,0,0,0,513,0,0,0,1,1));
        rejected(data(3,16,0,0,0,0,0,0,0,Long.MAX_VALUE,0,1,1));
        rejected(data(2,12,0,Long.MAX_VALUE,0,0,0));
        System.out.println("RTXPT_DIAGNOSTICS_JAVA_COMPAT PASS v2 malformedCount level duration");
    }
    private static void fixture(Path path)throws Exception {
        byte[] bytes=Files.readAllBytes(path);require(bytes.length%8==0,"Native fixture alignment");
        var words=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();long[] values=new long[words.remaining()];words.get(values);
        var snapshot=RtxptDiagnostics.decode(values);
        require(snapshot.recordedLevel().orElseThrow()==RtxptDiagnostics.Level.DEEP,"Native level mapping");
        require(snapshot.submissions().samples()==6,"All native submit samples retained");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.INITIAL).samples()==1,"Initial distribution");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.UPDATE).samples()==2,"Update distribution");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.RESIZE).samples()==1,"Resize distribution");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.WAIT).samples()==1,"Wait distribution");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.STEADY).samples()==1,"Steady distribution");
        require(snapshot.failedSubmissions()==1&&snapshot.submissionsWithoutNativeFrame()==3,"Failure and pre-frame accounting");
        require(snapshot.submissions().over50Millis()==1,"Slow submit threshold");
        require(snapshot.lastSlowFrame().stream().anyMatch(e->e.stage()==RtxptDiagnostics.Stage.LIGHTING),"Frame-before lighting retained");
        require(snapshot.events().stream().allMatch(e->e.scopeId()>0&&e.selfNanos()<=e.inclusiveNanos()),"Unique scope and self mapping");
        try{snapshot.events().clear();throw new AssertionError("Mutable event snapshot");}catch(UnsupportedOperationException expected){ }
        System.out.println("RTXPT_DIAGNOSTICS_JAVA_NATIVE PASS submits=6 failed=1 preFrame=3 categories=5");
    }
    private static void incomplete(){
        long flags=RtxptDiagnostics.FLAG_TRUNCATED|RtxptDiagnostics.FLAG_FAILED;
        long[] words={3,16,0,0,0,0,0,1,0,2,0,3,0,
            7,4,1,2,26,100,50,0,0,0,0,RtxptDiagnostics.FLAG_ACTIVE,512,0,11,12,
            5,4,100,flags,1,1024,
            6,4,200,RtxptDiagnostics.FLAG_ENTERED_NATIVE_FRAME,2,1536};
        var snapshot=RtxptDiagnostics.decode(words);
        require(snapshot.activeScopes().getFirst().active()&&snapshot.activeScopes().getFirst().inputGeneration()==11,"In-progress generation mapping");
        require(snapshot.submissions().samples()==1&&snapshot.submissions().maximumNanos()==200,"Truncated time entered completed percentiles");
        require(snapshot.truncatedSubmissions()==1&&snapshot.failedSubmissions()==1,"Truncated failure count");
        require(snapshot.submissions(RtxptDiagnostics.SubmissionKind.UPDATE).samples()==0,"Truncated update entered percentile");
        words[13+11]=0;rejected(words);
        System.out.println("RTXPT_DIAGNOSTICS_JAVA_INCOMPLETE PASS truncatedExcluded activeGenerations");
    }
    private static void resources(){
        long[] data=new long[40];
        long[] header={5,25,900,1,0,0,0,0,0,0,0,3,2,901,902};System.arraycopy(header,0,data,0,15);
        data[15+4]=RtxptDiagnostics.Stage.BLAS_ALLOCATE.ordinal();data[15+18]=0xaabbccddL;
        data[15+19]=42;data[15+20]=1025;data[15+21]=4096;data[15+22]=7;data[15+23]=2;data[15+24]=901;
        var snapshot=RtxptDiagnostics.decode(data);var event=snapshot.events().getFirst();
        require(event.resourceIdentity()==0xaabbccddL&&event.resourceGeneration()==42&&event.bytes()==4096&&event.attempt()==7&&event.diagnosticLevel()==2,"Resource metadata ABI");
        require(snapshot.firstEventOrdinal()==901&&snapshot.nextEventOrdinal()==902,"Retained ordinal window excludes pre-registration loss");
        data[15+23]=3;rejected(data);data[15+23]=2;data[14]=903;rejected(data);
        var gpu=RtxptDiagnostics.decodeGpu(new String[]{"0,0,0,1,1","1,graphics,0,-1,ResourceBlasBuild,1.5,10,25,10000,7,42,2,1234,42,1"});
        require(gpu.scopes().getFirst().diagnosticLevel()==2&&gpu.scopes().getFirst().resourceIdentity()==1234,"GPU records own mode and resource");
        for(String identity:new String[]{"9223372036854775808","14358104993958142841","18446744073709551615"}){
            var highBit=RtxptDiagnostics.decodeGpu(new String[]{"0,0,0,1,1","1,graphics,0,-1,ResourceBlasBuild,1.5,10,25,10000,7,42,2,"+identity+",42,1"});
            require(Long.toUnsignedString(highBit.scopes().getFirst().resourceIdentity()).equals(identity),"Unsigned GPU resource identity must retain every bit");
        }
        var legacyGpu=RtxptDiagnostics.decodeGpu(new String[]{"0,0,0,1,1","1,graphics,0,-1,Legacy,1.5,10,25,10000,7,42"});
        require(legacyGpu.scopes().getFirst().diagnosticLevel()==-1,"Legacy GPU mode stays unknown");
        System.out.println("RTXPT_DIAGNOSTICS_RESOURCE_ABI PASS format=5 legacyGpu=true metadata=true ordinals=true");
    }
    public static void main(String[] args)throws Exception {
        compatibility();incomplete();resources();if(args.length==1)fixture(Path.of(args[0]));
        System.out.println("RTXPT_DIAGNOSTICS_JAVA_ALL PASS");
    }
}
