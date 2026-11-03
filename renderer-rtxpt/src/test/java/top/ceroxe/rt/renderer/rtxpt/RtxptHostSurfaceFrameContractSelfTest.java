package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.HostSurfaceFrameLease;

/** Isolated synthetic descriptors only; no JNI loading, GPU allocation or native release. */
public final class RtxptHostSurfaceFrameContractSelfTest {
    private static int checks;
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void reject(Runnable work,String message){checks++;try{work.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static long[] descriptor(){return new long[]{1,0,101,7,13,0xfedcba9876543210L,17,19,1,1000,3,4,0,-100,16,1,0,1,17*19*8,1001,201,301,2,0};}
    private static void rejectWord(int index,long value){var data=descriptor();data[index]=value;reject(()->new RtxptHostSurfaceFrameLease(0,data),"Accepted invalid descriptor word "+index);}
    public static void main(String[] arguments)throws InterruptedException {
        var lease=new RtxptHostSurfaceFrameLease(0,descriptor());var frame=lease.frame();
        require(frame.generation()==101&&frame.sequence()==7&&frame.sceneRevision()==13,"Output frame identity");
        require(frame.inputGeneration()==201&&frame.resourceGeneration()==301,"Input/output generation correlation");
        require(frame.reconstruction()==HostSurfaceFrameLease.Reconstruction.RR,"Actual RR processing metadata");
        require(frame.clock().timeNanos()==-100&&frame.adapterLuid()==0xfedcba9876543210L,"Signed clock and opaque 64-bit LUID");
        require(frame.planes().getFirst().signal()==HostSurfaceFrameLease.Signal.COMBINED_SURFACE,"Independent combined-surface signal namespace");
        reject(()->frame.planes().clear(),"Mutable output-plane list");
        reject(lease::completionSignalEnqueued,"Acknowledged completion before import");
        lease.markImported();reject(lease::markImported,"Duplicate import");
        reject(lease::close,"Imported output returned before enqueueing consumer completion");
        var rejected=new boolean[1];var foreign=new Thread(()->{try{lease.frame();}catch(IllegalStateException expected){rejected[0]=true;}});
        foreign.start();foreign.join();require(rejected[0],"Cross-thread lease access");
        rejectWord(0,2);rejectWord(1,3);rejectWord(2,0);rejectWord(6,Integer.MAX_VALUE);rejectWord(8,2);
        rejectWord(9,0);rejectWord(10,Long.MAX_VALUE);rejectWord(11,5);rejectWord(14,-1);rejectWord(15,2);
        rejectWord(16,1);rejectWord(17,2);rejectWord(18,1);rejectWord(19,0);rejectWord(20,0);rejectWord(21,-1);rejectWord(22,3);
        System.out.println("HOST_SURFACE_OUTPUT_CONTRACT_SELFTEST PASS checks="+checks+" actualReconstruction=RR generationCorrelation=retained importedPrematureReturn=rejected");
    }
}
