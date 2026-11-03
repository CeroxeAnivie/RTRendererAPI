package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import top.ceroxe.rt.renderer.api.*;

/** Separate host-surface output ABI; never reinterpret it as a native-lighting packet. */
final class RtxptHostSurfaceFrameLease implements HostSurfaceFrameLease {
    private final Thread owner=Thread.currentThread();
    private final long handle;
    private final int slot;
    private final Frame frame;
    private boolean imported,returned,closed;
    RtxptHostSurfaceFrameLease(long handle,long[] data){
        if(data==null||data.length<23||data[0]!=1||data[15]<1||data[15]>Signal.values().length
            ||data.length!=20+data[15]*4||data[1]<0||data[1]>=3)
            throw new IllegalArgumentException("Invalid host-surface output packet ABI");
        this.handle=handle;slot=Math.toIntExact(data[1]);int count=Math.toIntExact(data[15]);
        var planes=new ArrayList<Plane>(count);long mask=0;
        for(int i=16;i<16+count*4;i+=4){
            int signal=Math.toIntExact(data[i]);if(signal<0||signal>=Signal.values().length)
                throw new IllegalArgumentException("Unknown host-surface output signal");
            var format=switch(Math.toIntExact(data[i+1])){
                case 1->Format.RGBA16_FLOAT;case 2->Format.RGBA32_UINT;case 3->Format.RGBA32_FLOAT;
                default->throw new IllegalArgumentException("Unknown host-surface output format");};
            planes.add(new Plane(Signal.values()[signal],format,data[i+2],data[i+3]));mask|=1L<<signal;
        }
        if(mask!=data[8])throw new IllegalArgumentException("Host-surface output signal mask differs from its planes");
        int tail=16+count*4;int reconstruction=Math.toIntExact(data[tail+2]);
        if(reconstruction<0||reconstruction>=Reconstruction.values().length)
            throw new IllegalArgumentException("Unknown actual host-surface reconstruction mode");
        int domain=Math.toIntExact(data[tail+3]);if(domain<0||domain>=ColorDomain.values().length)throw new IllegalArgumentException("Unknown host output color domain");
        frame=new Frame(data[2],data[3],data[4],data[5],Math.toIntExact(data[6]),Math.toIntExact(data[7]),
            data[9],data[10],data[11],data[tail],data[tail+1],FrameClock.of(data[13],data[14]),
            Reconstruction.values()[reconstruction],planes,ColorDomain.values()[domain]);
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Return host-surface output leases on their renderer owner thread");}
    private void active(){owner();if(closed||returned)throw new IllegalStateException("Host-surface output lease is no longer active");}
    @Override public Frame frame(){owner();if(closed)throw new IllegalStateException("Host-surface output lease closed");return frame;}
    @Override public void markImported(){active();if(imported)throw new IllegalStateException("Host-surface output lease already imported");imported=true;}
    @Override public void completionSignalEnqueued(){
        active();if(!imported)throw new IllegalStateException("Import the host-surface output before acknowledging GPU completion");
        RtxptNative.hostSurfaceRelease(handle,slot,frame.generation(),frame.sequence(),true);returned=true;
    }
    @Override public void close(){
        owner();if(closed)return;
        if(!returned){if(imported)throw new IllegalStateException("Queue GPU completion before closing imported host-surface output");
            RtxptNative.hostSurfaceRelease(handle,slot,frame.generation(),frame.sequence(),false);returned=true;}
        closed=true;
    }
}
