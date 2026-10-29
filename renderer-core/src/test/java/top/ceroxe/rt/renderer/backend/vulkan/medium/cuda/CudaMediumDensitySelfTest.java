package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;

/** Metadata, asset ownership and public coordinate semantics; no renderer/provider required. */
public final class CudaMediumDensitySelfTest {
    public static void main(String[] arguments) {
        byte[] pixels=new byte[40];
        for(int i=0;i<8;i++) {pixels[i*4]=(byte)(i*32);pixels[i*4+1]=(byte)255;pixels[i*4+3]=(byte)255;}
        for(int i=32;i<pixels.length;i++) pixels[i]=(byte)255;
        var texture=TextureAsset.builder(7,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .filter(TextureAsset.Filter.NEAREST).addressMode(TextureAsset.AddressMode.REPEAT)
                .mipChainRgba8(2,pixels).build();
        var field=MediumDensityField.grid(7,2,AffineTransform.identity());
        var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0).withDensityField(field);
        var transform=new AffineTransform(new float[]{2,0,0,10, 0,4,0,-4, 0,0,.5f,3});
        var boundary=new CudaBoundaryMediumAbi.Boundary(0x80000003,MediumBoundary.volume(medium),transform);
        var density=CudaMediumDensityAbi.prepare(medium,List.of(boundary),id->texture);
        require(density.bytes()==160,"two descriptors share the one base-level float atlas");
        require(density.environmentOffset()==0 && density.boundaryOffset(boundary.slot())==64,"descriptor slots");
        require(CudaMediumDensityAbi.requiredBytes(medium,List.of(boundary),id->texture)==160,"metadata-only shared-atlas footprint");
        require(CudaMediumDensityAbi.prepare(medium,List.of(boundary),id->texture,160).bytes()==160,"exact upload budget accepted");
        expectIllegal(()->CudaMediumDensityAbi.prepare(medium,List.of(boundary),id->texture,159));
        expectIllegal(()->CudaMediumDensityAbi.prepare(Medium.vacuum(),List.of(),id->null,-1));
        int[] resolutions={0};
        CudaMediumDensityAbi.prepare(medium,List.of(boundary),id->{resolutions[0]++;return texture;});
        require(resolutions[0]==1,"one immutable texture generation is resolved once per scene");
        ByteBuffer buffer=ByteBuffer.allocate(64+density.bytes()).order(ByteOrder.nativeOrder()); density.write(buffer,64);
        require(buffer.position()==0,"upload preserves the destination cursor");
        expectIllegal(()->density.write(ByteBuffer.allocate(224),63));
        expectIllegal(()->density.write(ByteBuffer.allocate(223),64));
        require(buffer.getInt(64)==192 && buffer.getInt(128)==192,"absolute atlas byte offset after relocation");
        require(buffer.getInt(68)==2 && buffer.getInt(72)==2 && buffer.getInt(76)==2,"vertical slice atlas dimensions");
        for(int i=0;i<8;i++) require(buffer.getFloat(192+i*4)==i*32/255f,"red channel only/base level only");
        float[] matrix=CudaMediumDensityAbi.worldToUvw(field,transform);
        near(matrix[0],.5);near(matrix[3],-5);near(matrix[5],.25);near(matrix[7],1);near(matrix[10],2);near(matrix[11],-6);
        checkAffineComposition();
        var frame=RenderFrameRequest.builder(1,17,13,CameraState.lookAt(0,0,2,0,0,0).build())
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).build();
        var plan=CudaBoundaryMediumAbi.plan(17,13,2,1,density);
        ByteBuffer metadata=ByteBuffer.wrap(CudaBoundaryMediumAbi.scene(plan,frame,List.of(boundary),new int[0],0,221,density))
                .order(ByteOrder.nativeOrder());
        require(plan.sceneBytes()==496 && plan.stateOffset()==496,"density lives before bounded ray state");
        require(metadata.getInt(96)==336 && metadata.getInt(256+48)==400,"environment and boundary absolute descriptors");
        require(metadata.getInt(100)==frame.mediumTransport().scatteringSteps(),"same public integration budget");
        expectIllegal(()->CudaBoundaryMediumAbi.scene(CudaBoundaryMediumAbi.plan(17,13,2,1),frame,List.of(boundary),new int[0],0,221));
        expectIllegal(()->CudaMediumDensityAbi.prepare(medium,List.of(),id->null));
        expectIllegal(()->CudaMediumDensityAbi.prepare(medium,List.of(),id->TextureAsset.color(7,2,4,new byte[32])));
        expectIllegal(()->CudaMediumDensityAbi.prepare(medium.withDensityField(MediumDensityField.grid(7,3,AffineTransform.identity())),List.of(),id->texture));
        require(CudaMediumDensityAbi.prepare(Medium.vacuum(),List.of(),id->null).bytes()==0,"uniform needs no texture or descriptor");
        var inactive=medium.withDensity(0);
        require(CudaMediumDensityAbi.prepare(inactive,List.of(new CudaBoundaryMediumAbi.Boundary(1,MediumBoundary.volume(inactive))),
                id->{throw new AssertionError("inactive density texture must not be resolved");},0).bytes()==0,"inactive media require no density asset");
        var cache=new CudaMediumDensityAbi.Cache();
        var cached=cache.prepare(medium,List.of(boundary),id->texture);
        require(cache.prepare(medium.withDensity(2),List.of(boundary),id->texture)==cached,"same immutable density assets reuse converted upload");
        expectIllegal(()->cache.prepare(medium,List.of(boundary),id->texture,159));
        require(cache.prepare(medium,List.of(boundary),id->texture,160)==cached,"failed budget preflight preserves the current cached upload");
        var replacement=TextureAsset.builder(7,2,4).colorSpace(TextureAsset.ColorSpace.LINEAR).mipChainRgba8(2,pixels).build();
        require(cache.prepare(medium,List.of(boundary),id->replacement)!=cached,"texture generation invalidates upload");
        require(cache.prepare(Medium.vacuum(),List.of(),id->null,0).bytes()==0,"uniform scene releases the previous cached asset generation");
        String wrapper=CudaMediumDensitySource.source();
        require(wrapper.contains("DensityGridTexture_D"),"packaged adapted LuxCore sampler");
        String pinnedSegment=CudaMediumSource.resource("upstream-segment-function.cl");
        String pinnedCompatibility=CudaMediumSource.resource("cudadevice_oclemul_types.cl");
        require(!wrapper.contains(pinnedSegment)&&!wrapper.contains(pinnedCompatibility),
                "density is a wrapper: runtime alone prepends pinned compatibility and segment code");
        String assembled=CudaMediumSource.source()+"\n"+wrapper;
        require(occurrences(assembled,pinnedSegment)==1&&occurrences(assembled,pinnedCompatibility)==1
                &&occurrences(assembled,CudaMediumSource.resource("api_density.cu"))==1,
                "runtime-style density assembly includes each source exactly once");
        require(CudaMediumSource.resource("upstream-densitygrid-functions.cl").contains("DensityGridTexture_ConstEvaluateSpectrum"),"pinned original provenance");
        System.out.println("CudaMediumDensitySelfTest passed: shared atlas, bounded preflight/cache, exact offsets, affine world/object transforms, base red and rejection contracts");
    }
    /** Test inversion by round-tripping independent local points through a reflected/sheared instance. */
    private static void checkAffineComposition() {
        var objectToWorld=new AffineTransform(new float[]{0,-2,.5f,10, 4,.25f,0,-4, 0,.5f,-3,3});
        var field=MediumDensityField.grid(7,2,new AffineTransform(new float[]{.5f,0,.25f,.5f, .25f,2,0,.25f, 0,.5f,1,-.5f}));
        float[] combined=CudaMediumDensityAbi.worldToUvw(field,objectToWorld);
        for(double[] local:new double[][]{{0,0,0},{.25,-.5,.75},{-1,2,3}}) {
            double[] world=map(objectToWorld,local);
            double[] expected=map(field.transform(),local);
            for(int row=0;row<3;row++) {
                double actual=combined[row*4+3];
                for(int column=0;column<3;column++)actual+=combined[row*4+column]*world[column];
                require(Math.abs(actual-expected[row])<2e-6,"reflected/sheared density composition");
            }
        }
    }
    private static double[] map(AffineTransform transform,double[] point) {
        double[] mapped=new double[3];
        for(int row=0;row<3;row++) {
            mapped[row]=transform.element(row*4+3);
            for(int column=0;column<3;column++)mapped[row]+=transform.element(row*4+column)*point[column];
        }
        return mapped;
    }
    private static void near(double actual,double expected) {require(Math.abs(actual-expected)<1e-7,"affine density composition");}
    private static int occurrences(String source,String fragment) {
        int count=0;for(int at=0;(at=source.indexOf(fragment,at))>=0;at+=fragment.length())count++;return count;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void expectIllegal(Runnable operation) {try {operation.run();throw new AssertionError("expected invalid density metadata");}catch(IllegalArgumentException expected) { }}
}
