package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** GPU encoder error sweep plus actual compact output allocation/metadata. */
public final class RtxptNativeLightingCompactNativeSelfTest {
    public static void main(String[] arguments){
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            var compact=NativeLightingPlan.defaults(48,32).withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8);
            renderer.nativeLightingPlan(compact);renderer.apply(RtxptNativeLightingNativeSelfTest.scene());renderer.submit(RtxptNativeLightingNativeSelfTest.frame(0,1));
            try(var lease=renderer.acquireLightingFrame().orElseThrow()){
                require(lease.frame().encoding()==NativeLightingPlan.Encoding.COMPACT_UNORM8&&lease.frame().maximumGi()==1,"Actual compact encoding and GI scale belong to the packet");
                require(lease.frame().planes().size()==2&&lease.frame().planes().stream().allMatch(p->p.format()==LightingFrameLease.Format.RGBA8_UNORM),"Only requested two color signals allocate compact outputs");
            }
            var error=renderer.nativeLightingCompactProbe();
            require(error.length==6&&error[4]==100001&&error[3]==0&&error[5]==8,"Actual GPU UNORM8 output preserves neutral 128, zero GI, alpha and pair size");
            require(error[0]<.003908&&error[1]<.011&&error[2]<.004,"GPU encoder satisfies declared absolute/relative quantization bounds");
            renderer.nativeLightingPlan(compact.withEncoding(NativeLightingPlan.Encoding.HDR_FLOAT16));renderer.submit(RtxptNativeLightingNativeSelfTest.frame(1,1));
            try(var lease=renderer.acquireLightingFrame().orElseThrow()){
                require(lease.frame().encoding()==NativeLightingPlan.Encoding.HDR_FLOAT16&&lease.frame().planes().stream().allMatch(p->p.format()==LightingFrameLease.Format.RGBA16_FLOAT),"HDR switching restores requested half-float output format");
            }
            renderer.nativeLightingPlan(new NativeLightingPlan(49,33,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,.35f,NativeLightingStyle.defaults())
                .withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8));renderer.submit(RtxptNativeLightingNativeSelfTest.frame(2,1));
            try(var lease=renderer.acquireLightingFrame().orElseThrow()){require(lease.frame().planes().size()==1&&lease.frame().width()==49&&lease.frame().height()==33,"Resize/direct-only compact packet does not allocate an unrequested GI signal");}
            System.out.println("RTXPT_710_COMPACT_GPU PASS samples=100001 gainLowAbs="+error[0]+" gainHighRelative="+error[1]+" giAbs="+error[2]+" pairBytes=8 HDRPairBytes=16 encodedOutputReduction=50% conversionPass=1");
        }
    }
}
