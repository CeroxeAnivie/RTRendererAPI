package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import top.ceroxe.rt.renderer.api.Caustics;

/** Independent wire/budget checks for bounded optical surface and volume photon paths. */
public final class CudaOpticalCausticAbiSelfTest {
    public static void main(String[] args) {
        var c=Caustics.photonMap(1,2,3,8,17,.25f).withMaxEvents(12);
        var p=CudaOpticalCausticAbi.plan(17,12,3,1024*1024);
        require(p.capacity()==255&&p.stateOffset()==128,"complete per-path deposition capacity");
        require(p.hitOffset()==128+17*272&&p.photonOffset()==p.hitOffset()+17*208,"state/hit layout");
        require(p.headsOffset()==p.photonOffset()+255*64&&p.bytes()==p.headsOffset()+512*4,"hash layout");
        var b=ByteBuffer.allocate((int)p.bytes()).order(ByteOrder.nativeOrder());
        b.put(CudaOpticalCausticAbi.header(p,c,3,29));
        CudaOpticalCausticAbi.initialize(b,p,16,1,2,3,0,0,-2,4,5,6,30,new int[]{7,9});
        int at=p.stateOffset()+16*272;
        require(b.getInt(at+112)==-1&&b.getInt(at+244)==2,"pinned PathVolumeInfo wire offsets");
        require(b.getInt(at+116)==7&&b.getInt(at+120)==9,"compact initial medium entries");
        require(b.getFloat(at+24)==-1&&b.getFloat(at+48)==4&&b.getFloat(at+60)==30,"normalized direction and photon flux");
        require(b.getInt(at+256)==0&&b.getInt(at+260)==0,"empty deposit/specular metadata");
        // Arena.asByteBuffer() and a newly allocated buffer default to big endian.
        // Native pointers and states must use the wire order regardless of the caller.
        var foreign=ByteBuffer.allocate((int)p.bytes());
        var wire=foreign.duplicate().order(ByteOrder.nativeOrder());
        long scene=0x123456789abcde00L,queries=0x23456789abcdef00L;
        CudaOpticalCausticAbi.bindCudaScene(foreign,scene);
        CudaOpticalCausticAbi.bindCudaGather(foreign,queries);
        CudaOpticalCausticAbi.initialize(foreign,p,0,1,2,3,0,0,-2,4,5,6,30,new int[]{7,9});
        require(wire.getLong(96)==scene&&wire.getLong(104)==queries,"native pointer byte order");
        require(wire.getInt(p.stateOffset()+32)==CudaOpticalCausticAbi.ACTIVE
                &&wire.getFloat(p.stateOffset()+24)==-1,"native state byte order");
        require(foreign.order()==ByteOrder.BIG_ENDIAN,"caller byte order is preserved");
        expectIllegal(()->CudaOpticalCausticAbi.plan(17,12,3,p.bytes()+65535));
        expectIllegal(()->CudaOpticalCausticAbi.plan(0,12,3,1024));
        expectIllegal(()->CudaOpticalCausticAbi.initialize(b,p,0,0,0,0,0,0,0,1,1,1,10,new int[0]));
        System.out.println("CudaOpticalCausticAbiSelfTest passed: bounded full deposits, pinned stack offsets, normalized flux paths");
    }
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    private static void expectIllegal(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
}
