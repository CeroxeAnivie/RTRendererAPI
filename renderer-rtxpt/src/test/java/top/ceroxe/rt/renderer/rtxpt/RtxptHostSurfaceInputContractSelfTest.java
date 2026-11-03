package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.HostSurfacePlan;

/** Isolated synthetic wire descriptors; no JNI calls, GPU work or native release. */
public final class RtxptHostSurfaceInputContractSelfTest {
    private static int checks;
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reject(Runnable operation,String message){checks++;try{operation.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static long[] descriptor(int count){
        var result=new long[18+count*4];
        long[] header={1,2,101,5,7,11,13,0,0xfedcba9876543210L,17,19,17,2,3,4,-100,16,count};
        System.arraycopy(header,0,result,0,header.length);
        for(int i=0;i<count;i++){
            var plane=HostSurfacePlan.Plane.values()[i];int offset=18+i*4;
            result[offset]=i;result[offset+1]=plane.format().ordinal();
            result[offset+2]=17L*19*plane.format().bytesPerPixel();result[offset+3]=1000+i;
        }
        return result;
    }
    private static RtxptHostSurfaceInputLease decode(long[] descriptor){return new RtxptHostSurfaceInputLease(0,descriptor,()->{});}
    private static void rejectWord(int count,int word,long value){
        var data=descriptor(count);data[word]=value;reject(()->decode(data),"Accepted invalid input descriptor word "+word+" in "+count+"-plane packet");
    }
    public static void main(String[] arguments){
        for(int count:new int[]{8,10}){
            var frame=decode(descriptor(count)).frame();
            require(frame.planes().size()==count,"Decoded input plane count");
            require(frame.composition()==(count==8?HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING:HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION),"Composition-specific input shape");
            require(frame.generation()==101&&frame.sequence()==7&&frame.sceneRevision()==11&&frame.resourceGeneration()==13,"Complete host frame identity");
            require(frame.adapterLuid()==0xfedcba9876543210L&&frame.clock().timeNanos()==-100,"Opaque LUID and signed clock");
            require(frame.previousDoneValue()==2&&frame.readyValue()==3&&frame.doneValue()==4,"Exact GPU fence ordering");
            for(int i=0;i<count;i++)require(frame.planes().get(i).kind().ordinal()==i,"Exact input ABI plane order");
            rejectWord(count,0,2);rejectWord(count,1,-1);rejectWord(count,1,3);rejectWord(count,3,0);
            rejectWord(count,9,0);rejectWord(count,13,2);rejectWord(count,16,-1);rejectWord(count,17,9);
            rejectWord(count,18,-1);rejectWord(count,18,10);rejectWord(count,19,-1);rejectWord(count,19,4);
            rejectWord(count,20,1);rejectWord(count,21,0);rejectWord(count,22,0);
            var truncated=java.util.Arrays.copyOf(descriptor(count),18+count*4-1);
            reject(()->decode(truncated),"Truncated input packet accepted");
            var extended=java.util.Arrays.copyOf(descriptor(count),18+count*4+1);
            reject(()->decode(extended),"Trailing input descriptor word accepted");
        }
        reject(()->decode(null),"Null input descriptor accepted");
        reject(()->decode(new long[17]),"Truncated input header accepted");
        rejectWord(10,18+8*4,9);rejectWord(10,18+9*4,8);
        System.out.println("HOST_SURFACE_INPUT_CONTRACT_SELFTEST PASS checks="+checks+" wireLongs=50/58 planes=8/10 composition=inferred");
    }
}
