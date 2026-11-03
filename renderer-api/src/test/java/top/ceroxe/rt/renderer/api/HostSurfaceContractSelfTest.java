package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;

/** Independent public descriptor validation; physical GPU ordering is tested separately. */
public final class HostSurfaceContractSelfTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reject(Runnable operation,String message){
        checks++;try{operation.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);
    }
    private static List<HostSurfaceInputLease.Plane> planes(int width,int height){
        return planes(width,height,HostSurfacePlan.BASE_PLANE_COUNT);
    }
    private static List<HostSurfaceInputLease.Plane> planes(int width,int height,int count){
        var result=new ArrayList<HostSurfaceInputLease.Plane>();
        for(int i=0;i<count;i++){
            var kind=HostSurfacePlan.Plane.values()[i];result.add(new HostSurfaceInputLease.Plane(kind,kind.format(),
                (long)width*height*kind.format().bytesPerPixel(),100+kind.ordinal()));
        }
        return result;
    }
    private static HostSurfaceInputLease.Frame frame(int width,int height,long previous,long ready,long done,List<HostSurfaceInputLease.Plane> planes){
        return new HostSurfaceInputLease.Frame(1,2,3,4,0xfedcba9876543210L,width,height,7,previous,ready,done,FrameClock.of(-100,16),planes);
    }
    public static void main(String[] arguments){
        var plan=new HostSurfacePlan(17,19);
        check(plan.composition()==HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,"Unexpected default composition");
        check(plan.logicalBytesPerSlot()==17L*19*80,"Incorrect eight-plane stride");
        check(plan.planes().size()==8&&plan.planes().get(7)==HostSurfacePlan.Plane.IDENTITY,"Incorrect fixed plane order");
        check(HostSurfacePlan.augmentLitBase(17,19,31).preservedHostComponents()==31,"Host ownership bits were not retained");
        var augmentation=HostSurfacePlan.augmentLitBase(17,19,31);
        check(augmentation.logicalBytesPerSlot()==17L*19*96,"Incorrect ten-plane augmentation stride");
        check(augmentation.planes().size()==10&&augmentation.planes().get(8)==HostSurfacePlan.Plane.HOST_LIGHTING_BASELINE
            &&augmentation.planes().get(9)==HostSurfacePlan.Plane.HOST_LIT_COLOR,"Missing ordered augmentation input planes");
        check(augmentation.planes().subList(0,8).equals(plan.planes()),"Base ABI order differs between composition modes");
        new HostSurfacePlan(17,19,HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,0,false,17L*19*80*3);
        reject(()->new HostSurfacePlan(17,19,HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION,0,false,17L*19*80*3),"Augmentation ignored its additional allocation budget");
        check(HostSurfacePlan.BACKGROUND==8&&HostSurfacePlan.PRIMARY_FALLBACK==4&&HostSurfacePlan.OPAQUE==2&&HostSurfacePlan.VALID==1,"Pixel flag ABI changed");
        reject(()->new HostSurfacePlan(0,19),"Zero extent accepted");
        reject(()->new HostSurfacePlan(16385,1),"Unsupported extent accepted");
        reject(()->new HostSurfacePlan(4096,4096),"Three-slot budget overflow accepted");
        reject(()->new HostSurfacePlan(1,1,null,0,false,65536),"Null composition accepted");
        reject(()->HostSurfacePlan.augmentLitBase(1,1,32),"Unknown host ownership bit accepted");
        reject(()->new HostSurfacePlan(1,1,HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,0,false,65535),"Too-small budget accepted");
        reject(()->new HostSurfacePlan(1,1,HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,0,false,HostSurfacePlan.DEFAULT_MAXIMUM_RESIDENT_BYTES+1),"Too-large budget accepted");
        var descriptors=planes(17,19);var frame=frame(17,19,2,3,4,descriptors);descriptors.clear();
        check(frame.planes().size()==8,"Frame retained a mutable descriptor list");
        check(frame.composition()==HostSurfacePlan.Composition.SPLIT_SURFACE_LIGHTING,"Split frame composition was not inferred from the complete input shape");
        var augmentedFrame=frame(17,19,2,3,4,planes(17,19,10));
        check(augmentedFrame.composition()==HostSurfacePlan.Composition.HOST_LIT_AUGMENTATION&&augmentedFrame.planes().size()==10,"Ten-plane frame composition was not retained");
        reject(()->frame(17,19,2,3,4,planes(17,19,9)),"Nine-plane augmentation input accepted");
        var unordered=planes(1,1);java.util.Collections.swap(unordered,0,1);
        reject(()->frame(1,1,0,1,2,unordered),"Unordered input plane ABI accepted");
        var missingBase=planes(1,1,10);missingBase.remove(7);missingBase.remove(6);
        reject(()->frame(1,1,0,1,2,missingBase),"Eight-plane shape with augmentation planes replacing required base data accepted");
        check(frame.adapterLuid()==0xfedcba9876543210L&&frame.clock().timeNanos()==-100,"Opaque LUID or signed clock was truncated");
        reject(()->frame.planes().clear(),"Frame descriptors are mutable");
        reject(()->frame(17,19,2,2,4,planes(17,19)),"Ready timeline mismatch accepted");
        reject(()->frame(17,19,2,3,5,planes(17,19)),"Done timeline mismatch accepted");
        reject(()->frame(1,1,Long.MAX_VALUE-1,Long.MAX_VALUE,Long.MIN_VALUE,planes(1,1)),"Fence overflow accepted");
        reject(()->frame(Integer.MAX_VALUE,Integer.MAX_VALUE,0,1,2,planes(1,1)),"Extent multiplication overflow accepted");
        reject(()->frame(1,1,0,1,2,planes(1,1).subList(0,7)),"Incomplete planes accepted");
        var duplicate=planes(1,1);duplicate.set(7,duplicate.get(0));
        reject(()->frame(1,1,0,1,2,duplicate),"Duplicate planes accepted");
        var small=planes(1,1);small.set(0,new HostSurfaceInputLease.Plane(HostSurfacePlan.Plane.WORLD_POSITION_FLAGS,HostSurfacePlan.Format.RGBA32_FLOAT,1,1));
        reject(()->frame(1,1,0,1,2,small),"Undersized memory allocation accepted");
        reject(()->new HostSurfaceInputLease.Plane(HostSurfacePlan.Plane.IDENTITY,HostSurfacePlan.Format.RGBA32_FLOAT,16,1),"Integer identity silently reinterpreted as float");
        reject(()->new HostSurfaceInputLease.Plane(HostSurfacePlan.Plane.MOTION,HostSurfacePlan.Format.RG32_FLOAT,8,0),"Null resource handle accepted");
        System.out.println("HOST_SURFACE_CONTRACT_SELFTEST PASS checks="+checks+" splitPlanes=8 augmentationPlanes=10 bytesPerPixel=80/96 signedClock=retained");
    }
}
