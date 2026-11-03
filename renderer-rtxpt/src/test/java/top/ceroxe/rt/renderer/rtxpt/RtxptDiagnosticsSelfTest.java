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
    public static void main(String[] args)throws Exception {
        compatibility();incomplete();if(args.length==1)fixture(Path.of(args[0]));
        System.out.println("RTXPT_DIAGNOSTICS_JAVA_ALL PASS");
    }
}
