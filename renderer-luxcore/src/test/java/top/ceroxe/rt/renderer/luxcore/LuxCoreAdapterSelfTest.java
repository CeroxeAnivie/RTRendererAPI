package top.ceroxe.rt.renderer.luxcore;

import top.ceroxe.rt.renderer.api.*;

/** Translation and isolation checks; no native runtime is loaded. */
public final class LuxCoreAdapterSelfTest {
    public static void main(String[] args) {
        var transform=new AffineTransform(new float[]{1,0,0,4,0,2,0,5,0,0,3,6});
        var values=LuxCoreScene.matrix(transform).trim().split("\\s+");
        float[] expected={1,0,0,0,0,2,0,0,0,0,3,0,4,5,6,1};
        require(values.length==16,"matrix element count");
        for(int i=0;i<16;i++)require(Float.parseFloat(values[i])==expected[i],"column-major SDL matrix element "+i);
        var provider=new LuxCoreBackendProvider();require(provider.descriptor().priority()<0,"must not replace Vulkan by default");
        var b=new StringBuilder();var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.3f,.4f),new Rgb(.01f,.02f,.03f),2,1.4f,.5f);
        new LuxCoreScene().medium(b,"test",medium,7,MediumTransport.balanced().withScatteringBounces(2),true);
        require(b.toString().contains(".type = homogeneous"),"upstream homogeneous volume");
        require(b.toString().contains(".absorption = 0.2 0.4 0.6"),"coefficient scaling");
        require(b.toString().contains(".multiscattering = true"),"multiple scattering delegated");
        require(b.toString().contains(".priority = 7"),"native priority");
        String config=LuxCoreRenderer.config(RenderFrameRequest.builder(0,1920,1080,CameraState.lookAt(0,0,5,0,0,0).build()).build());
        require(config.contains("resolutionreduction = 1"),"full resolution");require(config.contains("film.filter.type = NONE"),"no blur filter");
        System.out.println("LuxCoreAdapterSelfTest PASS (no native initialization)");
    }
    static void require(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
