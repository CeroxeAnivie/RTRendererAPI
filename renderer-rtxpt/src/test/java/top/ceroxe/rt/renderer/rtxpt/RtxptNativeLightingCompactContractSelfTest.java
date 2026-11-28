package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Isolated synthetic packet descriptors; no JNI, GPU import or native release. */
public final class RtxptNativeLightingCompactContractSelfTest {
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void reject(Runnable operation){try{operation.run();}catch(RuntimeException expected){return;}throw new AssertionError("Invalid compact contract accepted");}
    public static void main(String[] arguments){
        var legacy=NativeLightingPlan.defaults(48,32);require(legacy.encoding()==NativeLightingPlan.Encoding.HDR_FLOAT16,"Existing constructors retain HDR defaults");
        require(legacy.withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8).signals().equals(legacy.signals()),"Encoding does not change requested signal ownership");
        var style=NativeLightingStyle.defaults();var wide=new NativeLightingStyle(style.additionMode(),style.localDimming(),style.localBoost(),style.daylightDimming(),style.moonlightDimming(),
            style.moonRed(),style.moonGreen(),style.moonBlue(),0,20,1);
        reject(()->new NativeLightingPlan(1,1,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,1,0,wide).withEncoding(NativeLightingPlan.Encoding.COMPACT_UNORM8));
        long[] v1={1,0,11,12,13,0xfedcba9876543210L,48,32,1,100,1,2,7,0,0,1,0,1,48*32*8,1000};
        var first=new RtxptLightingFrameLease(0,v1).frame();require(first.encoding()==NativeLightingPlan.Encoding.HDR_FLOAT16&&first.maximumGi()==1,"Version 1 packet remains readable");
        var v2=Arrays.copyOf(v1,22);v2[0]=2;v2[17]=4;v2[18]=48*32*4;v2[20]=1;v2[21]=Integer.toUnsignedLong(Float.floatToRawIntBits(2.5f));
        var compact=new RtxptLightingFrameLease(0,v2).frame();
        require(compact.encoding()==NativeLightingPlan.Encoding.COMPACT_UNORM8&&compact.maximumGi()==2.5f&&compact.planes().getFirst().format()==LightingFrameLease.Format.RGBA8_UNORM,"Encoding, decode scale and exact format are packet facts");
        var response=v2.clone();response[20]=2;
        require(new RtxptLightingFrameLease(0,response).frame().encoding()==NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8,"Response encoding retains its exact packet identity");
        var invalid=v2.clone();invalid[20]=3;reject(()->new RtxptLightingFrameLease(0,invalid));
        reject(()->new RtxptLightingFrameLease(0,Arrays.copyOf(v2,21)));
        checkDielectricPacket(v2);
        var lease=new RtxptLightingFrameLease(0,v2);
        Thread initialOwner=Thread.currentThread();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread consumer=new Thread(()->{try{
            require(lease.frame().sequence()==12,"Transferred packet identity is unchanged");
            lease.transferTo(initialOwner);
            reject(lease::frame);
        }catch(Throwable error){failure.set(error);}},"lighting-lease-consumer");
        reject(()->lease.transferTo(null));
        lease.transferTo(consumer);
        reject(lease::frame);reject(lease::markImported);reject(lease::completionSignalEnqueued);reject(lease::close);
        reject(()->lease.transferTo(initialOwner));
        consumer.start();
        try{consumer.join();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
        if(failure.get()!=null)throw new AssertionError("Transferred consumer failed",failure.get());
        require(lease.frame().sequence()==12,"Ownership can be explicitly returned before import");
        lease.markImported();reject(()->lease.transferTo(consumer));
        // The synthetic handle is intentionally never released through JNI.
        System.out.println("NATIVE_LIGHTING_COMPACT_CONTRACT PASS legacyHDR=true v1=true v2=true maxGi=2.5 dielectric=true");
    }

    private static void checkDielectricPacket(long[] compact){
        var signal=NativeLightingPlan.Signal.DIELECTRIC_RADIANCE;
        var plan=new NativeLightingPlan(48,32,Set.of(signal),0,1,0,NativeLightingStyle.defaults());
        require(signal.ordinal()==6&&plan.signalMask()==64,"Dielectric ordinal and wire bit");
        var dielectricOnly=compact.clone();dielectricOnly[8]=64;
        dielectricOnly[16]=6;dielectricOnly[17]=3;dielectricOnly[18]=48*32*16;
        var only=new RtxptLightingFrameLease(0,dielectricOnly).frame();
        require(only.planes().equals(List.of(new LightingFrameLease.Plane(signal,LightingFrameLease.Format.RGBA32_FLOAT,48*32*16,1000))),
            "Dielectric-only packet retains float radiance, distance and the resource handle under compact encoding");
        int[] formats={4,4,3,2,1,1,3};
        var packet=Arrays.copyOf(compact,18+4*formats.length);packet[8]=127;packet[15]=formats.length;
        for(int ordinal=0;ordinal<formats.length;ordinal++){
            int at=16+ordinal*4;
            packet[at]=ordinal;packet[at+1]=formats[ordinal];packet[at+2]=65536;packet[at+3]=1000+ordinal;
        }
        packet[packet.length-2]=compact[20];packet[packet.length-1]=compact[21];
        var all=new RtxptLightingFrameLease(0,packet).frame();
        require(all.planes().size()==7&&all.planes().getLast().signal()==signal&&all.planes().getLast().memoryHandle()==1006,
            "All seven planes decode in one packet");
        require(all.fenceHandle()==100&&all.readyValue()==1&&all.completionValue()==2,"Dielectric plane uses the atomic packet fence");
        dielectricOnly[17]=1;reject(()->new RtxptLightingFrameLease(0,dielectricOnly));
        packet[40]=5;reject(()->new RtxptLightingFrameLease(0,packet));
    }
}
