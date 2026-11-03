package top.ceroxe.rt.renderer.rtxpt;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Programmatic GLFW state changes; paired with the read-only WM_NULL watcher. */
public final class RtxptWindowResponsivenessNativeSelfTest {
    static SceneTransaction scene(){return SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128))
        .upsert(plane(1,1,2,0,0,0,false)).upsert(SceneInstance.builder(1,1).build()).build();}
    static RenderFrameRequest request(long sequence,int width,int height){return RenderFrameRequest.builder(sequence,width,height,
        CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio((double)width/height).build()).minimumSceneRevision(1).environment(EnvironmentState.neutral()).build();}
    public static void main(String[] args)throws Exception{
        var config=RtxptLightTreeNativeSelfTest.config(4);
        if(args.length==0||args[0].equals("native")){
            try(var r=new RtxptRenderer(config,false)){
                r.apply(scene());r.submit(request(0,512,320));require(r.windowHandle()!=0,"Real native visible window");
                for(int i=0;i<18;i++){r.windowState(i%3);r.renderFrame();r.windowState(0);
                    r.submit(request(i+1,513+(i%3)*31,321+(i%2)*17));r.renderFrame();}
                r.awaitIdle();System.out.println("WINDOW_RESPONSIVENESS_NATIVE PASS stateChanges=36 resizes=18");
            }
        }
        if(args.length==0||args[0].equals("vulkan")){
            try(var r=RtxptComposedRenderer.openVulkanPresentation(RendererBackendPolicy.RTXPT_ONLY,
                RendererConfig.builder().cpuFrameReadbackEnabled(false).build(),config,
                VulkanFramePresenterConfig.builder().visible(true).initialExtent(512,320).build())){
                r.apply(scene());r.submit(request(0,512,320));var presenter=r.presenter();
                var field=presenter.getClass().getDeclaredField("window");field.setAccessible(true);long window=field.getLong(presenter);
                require(window!=0,"Real GLFW presenter window");
                for(int i=0;i<12;i++){
                    org.lwjgl.glfw.GLFW.glfwMaximizeWindow(window);presenter.pollEvents();
                    r.submit(request(i*2+1,513+i,321+i));r.awaitCompositionConsumers();
                    org.lwjgl.glfw.GLFW.glfwRestoreWindow(window);org.lwjgl.glfw.GLFW.glfwSetWindowSize(window,513+i,321+i);presenter.pollEvents();
                    r.submit(request(i*2+2,513+i,321+i));r.awaitCompositionConsumers();
                }
                System.out.println("WINDOW_RESPONSIVENESS_VULKAN PASS maximizeRestorePairs=12 resizes=12");
            }
        }
    }
}
