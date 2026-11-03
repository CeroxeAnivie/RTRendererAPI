package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import top.ceroxe.rt.renderer.api.*;

final class RtxptHostSurfaceInputLease implements HostSurfaceInputLease {
    private final long handle,ticket;private final int slot;private final Frame frame;
    private final Thread owner=Thread.currentThread();private boolean imported,finished,closed;
    private final Runnable cancelled;
    RtxptHostSurfaceInputLease(long handle,long[] p,Runnable cancelled){
        if(p==null||p.length<18||p[0]!=HostSurfacePlan.ABI_VERSION||(p[17]!=8&&p[17]!=10)
            ||p.length!=18+p[17]*4||p[1]<0||p[1]>=HostSurfacePlan.SLOTS||p[3]<=0)
            throw new IllegalArgumentException("Unknown host-surface input descriptor ABI");
        this.handle=handle;this.slot=Math.toIntExact(p[1]);this.ticket=p[3];this.cancelled=cancelled;
        int count=Math.toIntExact(p[17]);var planes=new ArrayList<Plane>(count);
        var kinds=HostSurfacePlan.Plane.values();var formats=HostSurfacePlan.Format.values();
        for(int i=18;i<p.length;i+=4){int kind=Math.toIntExact(p[i]),format=Math.toIntExact(p[i+1]);
            if(kind<0||kind>=kinds.length||format<0||format>=formats.length)throw new IllegalArgumentException("Unknown host-surface input plane kind or format");
            planes.add(new Plane(kinds[kind],formats[format],p[i+2],p[i+3]));}
        frame=new Frame(p[2],p[4],p[5],p[6],p[8],Math.toIntExact(p[9]),Math.toIntExact(p[10]),p[11],p[12],p[13],p[14],new FrameClock(p[15],p[16]),planes);
    }
    private void ready(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the input lease on its renderer owner thread");if(closed||finished)throw new IllegalStateException("Input reservation has finished");}
    @Override public Frame frame(){return frame;}
    @Override public void markImported(){ready();if(imported)throw new IllegalStateException("Input already imported");action(0);imported=true;}
    @Override public void publish(){ready();if(!imported)throw new IllegalStateException("Import and queue the ready signal before publication");action(1);finished=true;}
    @Override public void abandonSignalEnqueued(){ready();if(!imported)throw new IllegalStateException("Input was not imported");action(3);finished=true;cancelled.run();}
    private void action(int action){RtxptNative.hostSurfaceInputAction(handle,frame.generation(),slot,ticket,action);}
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Use the input lease on its renderer owner thread");
        if(closed)return;
        if(!finished){if(imported)throw new IllegalStateException("Queue host readiness and publish or abandon the imported input before closing");action(2);cancelled.run();}
        closed=true;
    }
}
