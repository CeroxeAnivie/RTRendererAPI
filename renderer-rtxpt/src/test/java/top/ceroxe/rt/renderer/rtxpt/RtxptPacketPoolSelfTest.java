package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Real scope invalidation and synchronous JNI ownership, without GPU or GC. */
public final class RtxptPacketPoolSelfTest {
    private static void rejects(Runnable action){
        try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Expected rejected packet operation");
    }
    public static void main(String[] args)throws Exception{
        var pool=new RtxptMemoryResources.PacketPool();pool.diagnostics(false);
        try{
            require(RtxptMemoryResources.PacketPool.storageCapacity((257<<20)+1)==(257<<20)+65536,"Large packet slack bounded by 64 KiB");
            require(RtxptMemoryResources.PacketPool.storageCapacity(512<<20)==512<<20,"Maximum accepted without overflow");
            rejects(()->pool.acquire(Integer.MAX_VALUE));
            var view=pool.acquire(16).putLong(0,0x123456789abcdefL).slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            try(var borrow=pool.borrow(view)){
                require(borrow.buffer().getLong(0)==0x123456789abcdefL,"Borrowed bytes preserved");
                rejects(pool::clear);rejects(()->pool.acquire(8192));
                require(view.getLong(0)==0x123456789abcdefL,"Rejected close cannot invalidate native borrower");
            }
            try(var retry=pool.borrow(view)){require(retry.buffer().equals(view),"Pending retry borrows exact immutable bytes");}
            var grown=pool.acquire((2<<20)+1).slice().asReadOnlyBuffer();
            require(pool.capacity()==(2<<20)+65536,"Large allocation uses exact aligned capacity");
            rejects(()->view.get(0));rejects(()->pool.borrow(view));
            try(var borrow=pool.borrow(grown)){throw new IllegalArgumentException("Simulated synchronous JNI failure");}
            catch(IllegalArgumentException expected){}
            pool.clear();require(pool.capacity()==0,"Failed native call releases borrow before cleanup");rejects(()->grown.get(0));
            var fresh=pool.acquire(32).slice();pool.close();pool.close();rejects(()->fresh.get(0));
        }finally{pool.close();}
        Path file=Files.createTempFile("rtxpt-packet-pool-",".jfr");
        try(var recording=new Recording()){
            recording.enable("top.ceroxe.rt.renderer.rtxpt.ResourcePacketMemory");recording.start();
            try(var observed=new RtxptMemoryResources.PacketPool()){observed.acquire(16);}
            try(var off=new RtxptMemoryResources.PacketPool()){off.diagnostics(false);off.acquire(16);}
            recording.stop();recording.dump(file);
            var events=RecordingFile.readAllEvents(file).stream().filter(e->e.getEventType().getName().equals("top.ceroxe.rt.renderer.rtxpt.ResourcePacketMemory")).toList();
            require(events.size()==2,"OFF emits no allocation or release event");
            require(events.get(0).getLong("usedBytes")==16&&events.get(0).getLong("capacityBytes")==4096
                    &&events.get(1).getLong("releasedBytes")==4096,"Recorded used/capacity/released bytes are exact");
        }finally{Files.deleteIfExists(file);}
        System.out.println("RTXPT_PACKET_POOL PASS nativeScopeReleased=true growthInvalidatesOld=true borrowerProtected=true retryExact=true exceptionCleanup=true largeSlack64KiB=true offEvents=0");
    }
}
