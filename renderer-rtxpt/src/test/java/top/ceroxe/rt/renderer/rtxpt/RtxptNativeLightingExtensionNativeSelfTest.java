package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Offline DXIL activation, immutable data, clock and failure preservation on the actual GPU. */
public final class RtxptNativeLightingExtensionNativeSelfTest {
    static final Path MANIFEST=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.extensionManifest","build/native-lighting-extension-example/manifest.json")).toAbsolutePath();
    static NativeLightingPublication parameters(long generation,float direct) {
        var bytes=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);bytes.putFloat(1).putFloat(.5f).putFloat(.25f).putFloat(direct).flip();
        return new NativeLightingPublication(generation,List.of(),List.of(),null,bytes);
    }
    static RenderFrameRequest frame(int sequence,long time) {return RtxptNativeLightingNativeSelfTest.frame(sequence,1).toBuilder().clock(FrameClock.of(time,0)).build();}
    static double center(RtxptRenderer r,String name)throws Exception {
        var path=RtxptNativeLightingNativeSelfTest.OUT.resolve(name+".pfm");r.captureNativeLighting(path,0);
        return RtxptLightTreeNativeSelfTest.linear(path)[(16*48+24)*3];
    }
    static void rejected(Runnable work,String reason){boolean failed=false;try{work.run();}catch(RuntimeException expected){failed=true;}require(failed,reason);}
    public static void main(String[] args)throws Exception {
        Files.createDirectories(RtxptNativeLightingNativeSelfTest.OUT);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)) {
            r.nativeLightingPlan(NativeLightingPlan.defaults(48,32));r.apply(RtxptNativeLightingNativeSelfTest.scene());r.publishLightingAttributes(parameters(1,1));
            if(args.length>0&&args[0].equals("reject-old")){
                rejected(()->r.activateLightingExtension(Path.of(args[1])),"Prior shader ABI rejected by final runtime");
                r.submit(frame(0,0));require(Double.isFinite(center(r,"extension-old-rejected")),"Default rendering survives old ABI rejection");
                System.out.println("NATIVE_EXTENSION_OLD_ABI PASS rejected=true defaultRendering=true");return;
            }
            r.activateLightingExtension(MANIFEST);r.submit(frame(0,0));require(Math.abs(center(r,"extension-full")-.625)<.0005,"External DXIL reads host baseline and application bytes");
            var before=r.retainedUpdateTimings();r.publishLightingAttributes(parameters(2,0));r.submit(frame(1,0));
            require(center(r,"extension-neutral")==1,"Application publication changes GPU receiver evaluation without pipeline rebuild");
            r.publishLightingAttributes(parameters(3,1));r.submit(frame(2,1L<<31));
            require(Math.abs(center(r,"extension-clock")-.6625)<.001,"GPU extension consumes submitted clock");
            try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().clock().equals(FrameClock.of(1L<<31,0))&&lease.frame().attributeGeneration()==3,"Packet clock and publication match actual work");}
            var invalid=MANIFEST.resolveSibling("invalid-abi.json");String text=Files.readString(MANIFEST);Files.writeString(invalid,text.replace("\"abiVersion\": 1","\"abiVersion\": 999"));
            rejected(()->r.activateLightingExtension(invalid),"Wrong ABI rejected before activation");
            rejected(()->r.publishLightingAttributes(new NativeLightingPublication(4,List.of(),List.of(),null,ByteBuffer.allocate(4))),"Undersized extension data rejected before mutation");
            r.submit(frame(3,1L<<31));require(Math.abs(center(r,"extension-after-rejection")-.6625)<.001,"Rejected activation and data preserve active output");
            r.resetLightingExtension();r.submit(frame(4,1L<<31));require(Math.abs(center(r,"extension-reset")-.625)<.0005,"Default pipeline can be restored without scene reload");
            var after=r.retainedUpdateTimings();require(before.builtBlases()==after.builtBlases()&&before.exportedMeshVariants()==after.exportedMeshVariants(),"Shader/data switching retains scene geometry");
            require(r.nativeLightingExecution().fullBeautyFrames()==0,"Extensions never dispatch full beauty");
            Files.deleteIfExists(invalid);System.out.println("RTXPT_NATIVE_EXTENSION_ALL PASS baseline data clock reject restore");
        }
    }
}
