package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

final class RtxptLightingFrameLease implements LightingFrameLease {
    private final Thread owner=Thread.currentThread();
    private final long handle;
    private final int slot;
    private final Frame frame;
    private boolean imported,returned,closed;
    RtxptLightingFrameLease(long handle,long[] data){
        if(data.length<16||(data[0]!=1&&data[0]!=2)||data[15]<1||data[15]>4||data.length!=(data[0]==2?18:16)+data[15]*4)
            throw new IllegalStateException("Invalid native HDR signal-packet ABI");
        this.handle=handle;slot=Math.toIntExact(data[1]);var planes=new ArrayList<Plane>();
        int tail=16+Math.toIntExact(data[15])*4;
        for(int i=16;i<tail;i+=4){
            var signal=NativeLightingPlan.Signal.values()[Math.toIntExact(data[i])];
            var format=switch(Math.toIntExact(data[i+1])){case 1->Format.RGBA16_FLOAT;case 2->Format.RGBA32_UINT;case 3->Format.RGBA32_FLOAT;case 4->Format.RGBA8_UNORM;default->throw new IllegalStateException("Unknown signal format");};
            planes.add(new Plane(signal,format,data[i+2],data[i+3]));
        }
        int encoding=data[0]==2?Math.toIntExact(data[tail]):0;
        if(encoding<0||encoding>=NativeLightingPlan.Encoding.values().length)throw new IllegalStateException("Unknown lighting output encoding");
        float maximumGi=data[0]==2?Float.intBitsToFloat((int)data[tail+1]):1;
        frame=new Frame(data[2],data[3],data[4],data[5],Math.toIntExact(data[6]),Math.toIntExact(data[7]),data[9],data[10],data[11],data[12],FrameClock.of(data[13],data[14]),planes,
            NativeLightingPlan.Encoding.values()[encoding],maximumGi);
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Return lighting leases on the renderer owner thread");}
    private void active(){owner();if(closed||returned)throw new IllegalStateException("Lighting lease is no longer active");}
    @Override public Frame frame(){owner();if(closed)throw new IllegalStateException("Lighting lease closed");return frame;}
    @Override public void markImported(){active();if(imported)throw new IllegalStateException("Lighting lease already imported");imported=true;}
    @Override public void completionSignalEnqueued(){active();if(!imported)throw new IllegalStateException("Import the packet before publishing consumer completion");
        RtxptNative.nativeLightingRelease(handle,slot,frame.generation(),frame.sequence(),true);returned=true;}
    @Override public void close(){owner();if(closed)return;if(!returned){if(imported)throw new IllegalStateException("Enqueue GPU consumer completion before closing an imported lighting lease");
        RtxptNative.nativeLightingRelease(handle,slot,frame.generation(),frame.sequence(),false);returned=true;}closed=true;}
}
