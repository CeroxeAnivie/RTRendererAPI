package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

final class RtxptLightingFrameLease implements LightingFrameLease {
    private static final NativeLightingPlan.Signal[] SIGNALS=NativeLightingPlan.Signal.values();
    private volatile Thread owner=Thread.currentThread();
    private final long handle;
    private final int slot;
    private final Frame frame;
    private boolean imported,returned,closed;
    RtxptLightingFrameLease(long handle,long[] data){
        if(data.length<16||(data[0]!=1&&data[0]!=2)||data[15]<1||data[15]>SIGNALS.length||data.length!=(data[0]==2?18:16)+data[15]*4)
            throw new IllegalStateException("Invalid native HDR signal-packet ABI");
        this.handle=handle;slot=Math.toIntExact(data[1]);var planes=new ArrayList<Plane>(Math.toIntExact(data[15]));
        int tail=16+Math.toIntExact(data[15])*4;
        int planeMask=0;
        for(int i=16;i<tail;i+=4){
            int ordinal=Math.toIntExact(data[i]);
            if(ordinal<0||ordinal>=SIGNALS.length||(planeMask&(1<<ordinal))!=0)
                throw new IllegalStateException("Unknown or duplicate native lighting signal");
            planeMask|=1<<ordinal;
            var signal=SIGNALS[ordinal];
            var format=switch(Math.toIntExact(data[i+1])){case 1->Format.RGBA16_FLOAT;case 2->Format.RGBA32_UINT;case 3->Format.RGBA32_FLOAT;case 4->Format.RGBA8_UNORM;default->throw new IllegalStateException("Unknown signal format");};
            if((signal==NativeLightingPlan.Signal.DIELECTRIC_RADIANCE||signal==NativeLightingPlan.Signal.DIELECTRIC_REFINEMENT)&&format!=Format.RGBA32_FLOAT)
                throw new IllegalStateException("Dielectric radiance requires an RGBA32_FLOAT plane");
            planes.add(new Plane(signal,format,data[i+2],data[i+3]));
        }
        if(planeMask!=data[8])throw new IllegalStateException("Native lighting planes do not match the packet mask");
        int encoding=data[0]==2?Math.toIntExact(data[tail]):0;
        if(encoding<0||encoding>=NativeLightingPlan.Encoding.values().length)throw new IllegalStateException("Unknown lighting output encoding");
        float maximumGi=data[0]==2?Float.intBitsToFloat((int)data[tail+1]):1;
        frame=new Frame(data[2],data[3],data[4],data[5],Math.toIntExact(data[6]),Math.toIntExact(data[7]),data[9],data[10],data[11],data[12],FrameClock.of(data[13],data[14]),planes,
            NativeLightingPlan.Encoding.values()[encoding],maximumGi);
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Access lighting leases on their current owner thread");}
    private void active(){owner();if(closed||returned)throw new IllegalStateException("Lighting lease is no longer active");}
    @Override public Frame frame(){owner();if(closed)throw new IllegalStateException("Lighting lease closed");return frame;}
    @Override public void transferTo(Thread nextOwner){
        active();Objects.requireNonNull(nextOwner,"nextOwner");
        if(imported)throw new IllegalStateException("Transfer lighting ownership before importing the packet");
        // The volatile owner publication also publishes all prior lease state.
        owner=nextOwner;
    }
    @Override public void markImported(){active();if(imported)throw new IllegalStateException("Lighting lease already imported");imported=true;}
    @Override public void completionSignalEnqueued(){active();if(!imported)throw new IllegalStateException("Import the packet before publishing consumer completion");
        RtxptNative.nativeLightingRelease(handle,slot,frame.generation(),frame.sequence(),true);returned=true;}
    @Override public void close(){owner();if(closed)return;if(!returned){if(imported)throw new IllegalStateException("Enqueue GPU consumer completion before closing an imported lighting lease");
        RtxptNative.nativeLightingRelease(handle,slot,frame.generation(),frame.sequence(),false);returned=true;}closed=true;}
}
