package top.ceroxe.rt.renderer.rtxpt;
import top.ceroxe.rt.renderer.api.*;

/** Explicit hardware gate: one JVM, one resident upstream D3D12 renderer. */
public final class RtxptNativeSelfTest {
    public static void main(String[] args) throws Exception {
        try(Renderer r=Renderer.open("rtxpt",RendererConfig.builder().cpuFrameReadbackEnabled(false).build())) {
            var nativeHost=r.extension(RtxptAccess.class).orElseThrow();nativeHost.configure(RtxptOptions.recommended());
            r.apply(RtxptAdapterSelfTest.scene(1));r.submit(RtxptAdapterSelfTest.frame(0));
            RtxptAdapterSelfTest.require(nativeHost.windowHandle()!=0,"Project-controlled native window");
            for(int i=0;i<12;i++)RtxptAdapterSelfTest.require(nativeHost.renderFrame(),"Native render/present");
            var status=nativeHost.runtimeStatus();
            RtxptAdapterSelfTest.require(status.reconstruction()==RtxptOptions.Reconstruction.DLSS_RR,"DLSS-RR must actually be active: "+status);
            RtxptAdapterSelfTest.require(!status.frameGeneration(),"Frame generation disabled");
            System.out.println("RTXPT actual native settings: "+status);
            var t=nativeHost.bridgeTimings();RtxptAdapterSelfTest.require(t.sceneExports()==1,"Static assets exported once");
            RtxptAdapterSelfTest.require(t.frames()>=13,"Presented native frames");
            System.out.println("RTXPT in-process native smoke PASS: "+t);
        }
    }
}
