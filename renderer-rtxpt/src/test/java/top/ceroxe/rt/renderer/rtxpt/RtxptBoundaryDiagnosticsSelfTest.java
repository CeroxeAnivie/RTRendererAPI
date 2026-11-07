package top.ceroxe.rt.renderer.rtxpt;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests the external recorder without initializing a GPU or loading JNI. */
public final class RtxptBoundaryDiagnosticsSelfTest {
    public static void main(String[] args) throws Exception {
        var recorder=new RtxptDiagnostics.Recorder();
        var first=recorder.monitor();var second=recorder.monitor();
        var done=new AtomicBoolean();
        try(var readers=Executors.newFixedThreadPool(2)){
            Future<Long> a=readers.submit(()->read(first,done));
            Future<Long> b=readers.submit(()->read(second,done));
            for(long i=1;i<=100_000;i++)recorder.add(i,3*i,RtxptDiagnostics.Stage.RESOURCES,recorder.clock(),i+7,i+8,false);
            done.set(true);
            if(a.get(10,TimeUnit.SECONDS)==0||b.get(10,TimeUnit.SECONDS)==0)throw new AssertionError("No concurrent events observed");
        }
        var retained=recorder.snapshot(-1);
        if(retained.events().size()!=1024||retained.lostEvents()!=100_000-1024)throw new AssertionError("Incorrect ring coverage");
        long next=retained.nextOrdinal();
        recorder.level(RtxptDiagnostics.Level.OFF);
        if(recorder.clock()!=0)throw new AssertionError("OFF reads a clock");
        recorder.add(200_000,600_000,RtxptDiagnostics.Stage.RESOURCES,1,0,0,false);
        if(recorder.snapshot(next-1).nextOrdinal()!=next)throw new AssertionError("OFF recorded an event");
        recorder.level(RtxptDiagnostics.Level.DEEP);recorder.close();
        recorder.add(200_001,600_003,RtxptDiagnostics.Stage.RESOURCES,1,0,0,false);
        if(!first.closed()||!second.closed()||recorder.snapshot(next-1).nextOrdinal()!=next)throw new AssertionError("Closed recorder changed");
        System.out.println("BOUNDARY_DIAGNOSTICS PASS concurrentReaders=2 writes=100000 bounded=1024 independentCursors=true off=true closed=true");
    }
    private static long read(RtxptDiagnostics.Monitor monitor,AtomicBoolean done){
        long seen=0,previous=-1;
        do{
            for(var event:monitor.poll().events()){
                if(event.ordinal()<=previous||event.sceneRevision()!=3*event.sequence()
                    ||event.items()!=event.sequence()+7||event.bytes()!=event.sequence()+8)
                    throw new AssertionError("Concurrent record was torn or replayed");
                previous=event.ordinal();seen++;
            }
            Thread.yield();
        }while(!done.get());
        return seen;
    }
}
