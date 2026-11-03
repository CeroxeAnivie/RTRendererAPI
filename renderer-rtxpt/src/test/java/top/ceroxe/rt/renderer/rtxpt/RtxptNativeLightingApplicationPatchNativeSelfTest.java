package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Actual GPU extension consumption and emitted application writeBuffer byte counts. */
public final class RtxptNativeLightingApplicationPatchNativeSelfTest {
    private static NativeLightingApplicationPatch patch(long generation,int offset,float... values){
        var data=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:values)data.putFloat(value);data.flip();
        return new NativeLightingApplicationPatch(generation,offset,data);
    }
    public static void main(String[] arguments)throws Exception {
        java.nio.file.Files.createDirectories(RtxptNativeLightingNativeSelfTest.OUT);
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(NativeLightingPlan.defaults(48,32));renderer.apply(RtxptNativeLightingNativeSelfTest.scene());
            var initial=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);initial.putFloat(1).putFloat(.5f).putFloat(.25f).putFloat(1);initial.position(0);
            renderer.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(),null,initial));
            renderer.activateLightingExtension(RtxptNativeLightingExtensionNativeSelfTest.MANIFEST);
            renderer.submit(RtxptNativeLightingExtensionNativeSelfTest.frame(0,0));
            require(Math.abs(RtxptNativeLightingExtensionNativeSelfTest.center(renderer,"patch-initial")-.625)<.0005,"Initial extension publication reaches GPU");
            var before=renderer.nativeLightingApplicationTransfers();
            renderer.patchLightingApplicationData(patch(2,12,0));renderer.submit(RtxptNativeLightingExtensionNativeSelfTest.frame(1,0));
            require(RtxptNativeLightingExtensionNativeSelfTest.center(renderer,"patch-four-bytes")==1,"Four-byte direct-strength delta changes actual GPU output");
            var after=renderer.nativeLightingApplicationTransfers();
            require(after[0]==4096&&after[1]-before[1]==4&&after[2]-before[2]==1,"Four-byte patch emits exactly one four-byte upload, keeps capacity");
            RtxptNativeLightingExtensionNativeSelfTest.rejected(()->renderer.patchLightingApplicationData(patch(2,12,1)),"Duplicate generation rejected atomically");
            RtxptNativeLightingExtensionNativeSelfTest.rejected(()->renderer.patchLightingApplicationData(patch(3,4096,1)),"Out-of-capacity patch rejected atomically");
            renderer.patchLightingApplicationData(patch(3,0,.3f));renderer.patchLightingApplicationData(patch(4,4,.6f));renderer.patchLightingApplicationData(patch(5,12,1));
            renderer.submit(RtxptNativeLightingExtensionNativeSelfTest.frame(2,0));var merged=renderer.nativeLightingApplicationTransfers();
            require(merged[1]-after[1]==12&&merged[2]-after[2]==2,"Adjacent updates merge, unchanged gap is not uploaded");
            require(Math.abs(RtxptNativeLightingExtensionNativeSelfTest.center(renderer,"patch-restored")-.625)<.0005,"Later patch wins and invalid delta did not corrupt state");
            try(var lease=renderer.acquireLightingFrame().orElseThrow()){require(lease.frame().attributeGeneration()==5,"Patch generation identifies actual output packet");}
            before=merged;renderer.patchLightingApplicationData(patch(6,0,.2f,.4f));renderer.patchLightingApplicationData(patch(7,4,.8f));
            renderer.publishLightingAttributes(new NativeLightingPublication(8,List.of(),List.of(),null,null));
            renderer.submit(RtxptNativeLightingExtensionNativeSelfTest.frame(3,0));after=renderer.nativeLightingApplicationTransfers();
            require(after[1]-before[1]==8&&after[2]-before[2]==1&&renderer.nativeLightingExecution().attributeGeneration()==8,"Overlaps merge and share the metadata generation timeline");
            System.out.println("RTXPT_710_APPLICATION_PATCH_GPU PASS capacity=4096 singlePatchUploaded=4 fullEquivalent=4096 uploadReduction=99.90234375% adjacentAndGapUploaded=12 overlapUploaded=8");
        }
    }
}
