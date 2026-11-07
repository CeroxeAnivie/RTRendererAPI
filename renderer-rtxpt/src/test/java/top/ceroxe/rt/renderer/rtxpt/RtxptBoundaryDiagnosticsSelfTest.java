package top.ceroxe.rt.renderer.rtxpt;

import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Public boundary evidence contracts, without loading JNI or inspecting upstream internals. */
public final class RtxptBoundaryDiagnosticsSelfTest {
    public static void main(String[] args) throws InterruptedException {
        var recorder=new RtxptDiagnostics.Recorder();
        var empty=recorder.snapshot(-1);
        require(empty.events().isEmpty()&&empty.nextOrdinal()==0&&empty.lostEvents()==0,"Empty initial cursor");
        recorder.level(RtxptDiagnostics.Level.DEEP);
        long begin=recorder.clock();
        Thread.sleep(5);
        recorder.add(17,9,RtxptDiagnostics.Stage.RTXPT_FRAME,begin,3,64,true);
        var captured=recorder.snapshot(-1);var failed=captured.events().getFirst();
        require(failed.sequence()==17&&failed.sceneRevision()==9&&failed.failed(),"Failure retains frame identity");
        require(failed.beginNanos()==begin&&failed.durationNanos()>=1_000_000,"Boundary wall duration includes blocked time");
        require(!captured.upstreamInternalsExpanded(),"Boundary evidence never claims expanded upstream internals");
        try {captured.events().clear();throw new AssertionError("Mutable diagnostic snapshot");}
        catch(UnsupportedOperationException expected) { }

        long cursor=captured.nextOrdinal()-1;
        recorder.level(RtxptDiagnostics.Level.OFF);
        require(recorder.clock()==0,"OFF does not start a timer");
        recorder.add(18,9,RtxptDiagnostics.Stage.RESOURCES,begin,1,0,false);
        var off=recorder.snapshot(cursor);
        require(off.level()==RtxptDiagnostics.Level.OFF&&off.events().isEmpty()
                &&off.nextOrdinal()==captured.nextOrdinal(),"OFF freezes ordinal and event collection");

        recorder.level(RtxptDiagnostics.Level.SUMMARY);
        for(int index=0;index<1050;index++)
            recorder.add(100+index,10,RtxptDiagnostics.Stage.TRANSLATION,recorder.clock(),1,0,false);
        var retained=recorder.snapshot(-1);
        require(retained.events().size()==1024&&retained.lostEvents()==27&&retained.nextOrdinal()==1051,"Bounded retention reports overwritten events");
        require(retained.events().getFirst().ordinal()==27&&retained.events().getLast().ordinal()==1050,"Retained ordering is continuous");
        require(!retained.events().getLast().failed(),"Successful boundaries remain distinct from failures");
        var consumed=recorder.snapshot(retained.nextOrdinal()-1);
        require(consumed.events().isEmpty()&&consumed.lostEvents()==0,"Last consumed ordinal does not replay events");
        var missed=recorder.snapshot(23);
        require(missed.lostEvents()==3&&missed.events().getFirst().ordinal()==27,"Loss is relative to the consumer cursor");
        reject(()->recorder.snapshot(-2));reject(()->recorder.snapshot(retained.nextOrdinal()));
        System.out.println("RTXPT_BOUNDARY_DIAGNOSTICS PASS offFrozen=true retention=1024 loss=27 cursor=true failedIdentity=true wallIncludesWait=true upstreamExpanded=false");
    }
    private static void reject(Runnable action) {
        try {action.run();} catch(IllegalArgumentException expected) {return;}
        throw new AssertionError("Invalid diagnostic cursor accepted");
    }
}
