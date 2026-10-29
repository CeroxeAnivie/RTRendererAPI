package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.*;

/** Public final HDR compared with independent double affine/slab/trilinear/quadrature code. */
public final class CudaDensityProductionNativeSelfTest {
    private static final long TEXTURE=50,BOUNDARY=10;
    private static final double CAMERA_Z=6,PLANE_Z=-10;
    private static final Duration TIMEOUT=Duration.ofSeconds(45);
    private static final Path OUTPUT=Path.of(System.getProperty(
            "top.ceroxe.rt.medium.densityProductionDirectory","build/luxcore-native-density-production/production"));
    private static final double[][] EIGHT={{.5,.3333},{.25,.6667},{.75,.1111},{.125,.4444},
            {.625,.7778},{.375,.2222},{.875,.5556},{.0625,.8889}};
    private static final List<String> RESULTS=new ArrayList<>();
    private static Renderer renderer;
    private static long sequence,revision;
    private static int width=47,height=35,nativeFrames,cameraFrames,rejections;

    public static void main(String[] arguments) throws Exception {
        Files.createDirectories(OUTPUT);
        String previous=System.getProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
        System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,"true");
        long boundariesBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundarySubmissions.sum();
        long cameraBefore=VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum();
        Grid first=grid(0),replacement=grid(1);
        Medium base=new Medium(new Rgb(.19f,.07f,.13f),Rgb.ZERO,new Rgb(.024f,.011f,.007f),1.3f,1,0);
        AffineTransform worldMap=new AffineTransform(new float[]{.125f,0,0,.5f, 0,.16666667f,0,.5f, 0,0,.125f,.75f});
        Medium world=base.withDensityField(MediumDensityField.grid(TEXTURE,first.nz,worldMap));
        var standard=new Fixture(world,null,null,false,1,13,CAMERA_Z);
        try {
            try(Renderer owned=Renderer.open("vulkan-rt",RendererConfig.builder().maxFramesInFlight(3)
                    .validationEnabled(true).frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
                    .temporalRendering(TemporalRenderingOptions.disabled()).build())) {
                renderer=owned;
                FrameSnapshot vacuum=setup(standard,first);
                FrameSnapshot original=compare("world-nonconstant-trilinear",standard,first,vacuum);
                compare("unchanged-texture-generation-cache-hit",standard,first,vacuum);
                apply(SceneTransaction.builder(++revision).upsert(first.texture(true)).build());
                FrameSnapshot samplerChanged=compare("filter-address-mips-ignored",standard,first,vacuum);
                require(Arrays.equals(bytes(original),bytes(samplerChanged)),"material sampler state changed density output");
                apply(SceneTransaction.builder(++revision).upsert(replacement.texture(false)).build());
                FrameSnapshot updated=compare("same-id-new-texel-generation",standard,replacement,vacuum);
                require(!Arrays.equals(bytes(original),bytes(updated)),"replaced texture generation was not consumed");
                apply(SceneTransaction.builder(++revision).removeTexture(TEXTURE).build());
                expectMissingDensity(standard);
                apply(SceneTransaction.builder(++revision).upsert(first.texture(false)).build());
                compare("deleted-texture-restored-session-recovery",standard,first,vacuum);

                Medium narrow=base.withDensityField(MediumDensityField.grid(TEXTURE,first.nz,
                        new AffineTransform(new float[]{.2f,0,0,.5f, 0,.2f,0,.5f, 0,0,20,.5f})));
                run("world-narrow-domain-one-step-two-SPP",new Fixture(narrow,null,null,false,2,1,CAMERA_Z),first);
                Medium missed=base.withDensityField(MediumDensityField.grid(TEXTURE,first.nz,
                        new AffineTransform(new float[]{.25f,0,0,-8, 0,.25f,0,.5f, 0,0,.125f,.75f})));
                run("outside-density-domain-eight-SPP",new Fixture(missed,null,null,false,8,7,CAMERA_Z),first);

                Medium local=base.withDensityField(MediumDensityField.grid(TEXTURE,first.nz,
                        new AffineTransform(new float[]{.5f,0,0,.5f, 0,.5f,0,.5f, 0,0,.5f,.5f})));
                double angle=Math.toRadians(31),c=Math.cos(angle),s=Math.sin(angle);
                AffineTransform rotated=new AffineTransform(new float[]{(float)(3*c),(float)(-2*s),0,.25f,
                        (float)(3*s),(float)(2*c),0,-.1f, 0,0,2,-1});
                run("rotated-nonuniform-object-boundary-two-SPP",
                        new Fixture(Medium.vacuum(),local,rotated,false,2,19,CAMERA_Z),first);
                AffineTransform sheared=new AffineTransform(new float[]{2.5f,.35f,.4f,.3f, .25f,1.8f,-.2f,-.1f, .2f,.1f,2,-1});
                Medium shearField=base.withDensityField(MediumDensityField.grid(TEXTURE,first.nz,
                        new AffineTransform(new float[]{.5f,.1f,0,.5f, 0,.5f,.08f,.5f, .05f,0,.5f,.5f})));
                run("sheared-object-and-local-field-frame-local-eight-SPP",
                        new Fixture(Medium.vacuum(),shearField,sheared,true,8,23,CAMERA_Z),first);
                run("camera-inside-sheared-density-boundary",
                        new Fixture(Medium.vacuum(),shearField,sheared,false,2,17,-1),first);
                run("world-grid-restored-after-local-priority",
                        new Fixture(world,shearField,sheared,false,2,11,CAMERA_Z),first);

                run("mode-switch-uniform-camera",new Fixture(base,null,null,false,2,8,CAMERA_Z),first);
                run("mode-switch-back-to-world-density",standard,first);
                width=73;height=41;
                run("resize-frame-local-eight-SPP",new Fixture(world,shearField,sheared,true,8,29,CAMERA_Z),replacement);
                width=31;height=23;
                run("resize-down-density-cache-recovery",standard,first);
                require(renderer.health().activeFailure().isEmpty(),"density renderer is unhealthy");
            }
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeBoundarySubmissions.sum()-boundariesBefore==nativeFrames,
                    "a density public frame bypassed the actual native boundary route");
            require(VulkanGpuSceneFrameSubmitter.diagnosticNativeCameraSubmissions.sum()-cameraBefore==cameraFrames,
                    "uniform-camera mode switch did not use its actual route");
            Files.writeString(OUTPUT.resolve("result.json"),"{\n  \"passed\":true,\n  \"scope\":\"public-final-RGBA16F-density\",\n"
                    +"  \"reference\":\"independent double affine solve, box clipping, texel-centered trilinear, clipped midpoint, analytic Beer/emission\",\n"
                    +"  \"tolerance\":\"2 output half ULP + 1 baseline half ULP + 3e-5 absolute; no sample reductions\",\n"
                    +"  \"nativeDensityFrames\":"+nativeFrames+",\n  \"cameraFrames\":"+cameraFrames+",\n  \"recoverableRejections\":"+rejections
                    +",\n  \"comparisons\":[\n"+String.join(",\n",RESULTS)+"\n  ]\n}\n");
            System.out.println("CudaDensityProductionNativeSelfTest passed: comparisons="+RESULTS.size()
                    +" actualNativeDensityFrames="+nativeFrames+" recoverableRejections="+rejections);
        } finally {
            if(previous==null)System.clearProperty(CudaCameraMediumProfile.ENABLE_PROPERTY);
            else System.setProperty(CudaCameraMediumProfile.ENABLE_PROPERTY,previous);
        }
    }

    private static void run(String name,Fixture fixture,Grid grid) throws Exception {
        compare(name,fixture,grid,setup(fixture,grid));
    }

    private static FrameSnapshot setup(Fixture fixture,Grid grid) throws Exception {
        apply(SceneTransaction.builder(++revision).resetScene().upsert(grid.texture(false))
                .upsert(MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.UNLIT).doubleSided(true)
                        .emissive(0xff4080ff,.25f).build())
                .upsert(MeshAsset.triangles(1,new float[]{-100,-100,(float)PLANE_Z,100,-100,(float)PLANE_Z,
                        100,100,(float)PLANE_Z,-100,100,(float)PLANE_Z},new int[]{0,1,2,2,3,0},1))
                .upsert(MediumTransportSelfTest.cube(2,1,1)).upsert(SceneInstance.builder(1,1).build()).build());
        FrameSnapshot vacuum=capture("vacuum-"+revision,request(new Fixture(Medium.vacuum(),null,null,false,fixture.samples,fixture.steps,fixture.cameraZ)));
        if(fixture.boundary!=null&&!fixture.local)apply(SceneTransaction.builder(++revision).upsert(SceneInstance.builder(BOUNDARY,2)
                .transform(fixture.objectToWorld).mediumBoundary(MediumBoundary.volume(fixture.boundary)).build()).build());
        return vacuum;
    }

    private static FrameSnapshot compare(String name,Fixture fixture,Grid grid,FrameSnapshot vacuum) throws Exception {
        FrameSnapshot actual=capture(name,request(fixture));
        if(fixture.boundary!=null||!fixture.environment.densityField().isUniform())nativeFrames++;else cameraFrames++;
        ByteBuffer a=vacuum.pixels().order(ByteOrder.LITTLE_ENDIAN),b=actual.pixels().order(ByteOrder.LITTLE_ENDIAN);
        CameraState camera=camera(fixture.cameraZ);double maxAbsolute=0,maxToleranceRatio=0;
        double[][] inverse=fixture.objectToWorld==null?null:inverse(fixture.objectToWorld);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            double[][] transport=new double[fixture.samples][];
            for(int sample=0;sample<fixture.samples;sample++)transport[sample]=reference(fixture,grid,inverse,
                    new double[]{0,0,fixture.cameraZ},direction(camera,x,y,sample,fixture.samples));
            for(int channel=0;channel<3;channel++) {
                int offset=(y*width+x)*8+channel*2;double base=Float.float16ToFloat(a.getShort(offset)),expected=0;
                for(double[] value:transport)expected+=(base*value[channel]+value[channel+3])/fixture.samples;
                double value=Float.float16ToFloat(b.getShort(offset)),error=Math.abs(value-expected);
                // Both baseline and final output are half-quantized; float affine/ray work adds a bounded small remainder.
                double tolerance=2*halfUlp(expected)+halfUlp(base)+3e-5;
                maxAbsolute=Math.max(maxAbsolute,error);maxToleranceRatio=Math.max(maxToleranceRatio,error/tolerance);
                require(Double.isFinite(value)&&error<=tolerance,name+" pixel="+x+","+y+" channel="+channel
                        +" actual="+value+" expected="+expected+" error="+error+" tolerance="+tolerance);
            }
            require(a.getShort((y*width+x)*8+6)==b.getShort((y*width+x)*8+6),"density changed alpha");
        }
        RESULTS.add(String.format(Locale.ROOT,"    {\"name\":\"%s\",\"width\":%d,\"height\":%d,\"samples\":%d,\"steps\":%d,\"maximumAbsoluteError\":%.12g,\"maximumToleranceRatio\":%.12g}",
                name,width,height,fixture.samples,fixture.steps,maxAbsolute,maxToleranceRatio));
        System.out.printf(Locale.ROOT,"Native density HDR comparison=%s SPP=%d steps=%d maxAbsolute=%.9g toleranceRatio=%.9g%n",
                name,fixture.samples,fixture.steps,maxAbsolute,maxToleranceRatio);
        return actual;
    }

    private static double[] reference(Fixture fixture,Grid grid,double[][] inverse,double[] origin,double[] direction) {
        double end=(PLANE_Z-origin[2])/direction[2];double[] result={1,1,1,0,0,0};
        if(fixture.boundary==null) {integrate(result,fixture.environment,grid,origin,direction,0,end,fixture.steps);return result;}
        double[] localOrigin=map(inverse,origin,1),localDirection=map(inverse,direction,0);
        double[] interval=clip(localOrigin,localDirection,0,end,-1,1);
        if(interval==null) {integrate(result,fixture.environment,grid,origin,direction,0,end,fixture.steps);return result;}
        integrate(result,fixture.environment,grid,origin,direction,0,interval[0],fixture.steps);
        integrate(result,fixture.boundary,grid,localOrigin,localDirection,interval[0],interval[1],fixture.steps);
        integrate(result,fixture.environment,grid,origin,direction,interval[1],end,fixture.steps);
        return result;
    }

    private static void integrate(double[] result,Medium medium,Grid grid,double[] origin,double[] direction,
                                  double first,double last,int steps) {
        if(last<=first||!medium.participating())return;
        double mass=last-first;
        if(!medium.densityField().isUniform()) {
            double[][] field=matrix(medium.densityField().transform());
            double[] mappedOrigin=map(field,origin,1),mappedDirection=map(field,direction,0);
            double[] clipped=clip(mappedOrigin,mappedDirection,first,last,0,1);
            if(clipped==null)return;
            double dt=(clipped[1]-clipped[0])/steps;mass=0;
            for(int step=0;step<steps;step++) {
                double t=clipped[0]+(step+.5)*dt;
                mass+=grid.sample(mappedOrigin[0]+mappedDirection[0]*t,mappedOrigin[1]+mappedDirection[1]*t,
                        mappedOrigin[2]+mappedDirection[2]*t)*dt;
            }
        }
        float[] absorption={medium.absorption().red()*medium.density(),medium.absorption().green()*medium.density(),medium.absorption().blue()*medium.density()};
        float[] emission={medium.emission().red()*medium.density(),medium.emission().green()*medium.density(),medium.emission().blue()*medium.density()};
        for(int channel=0;channel<3;channel++) {
            double sigma=absorption[channel];
            result[channel+3]+=result[channel]*emission[channel]*(sigma==0?mass:-Math.expm1(-sigma*mass)/sigma);
            result[channel]*=Math.exp(-sigma*mass);
        }
    }

    /** Geometric clipping and affine inversion are independent of renderer implementation helpers. */
    private static double[] clip(double[] origin,double[] direction,double first,double last,double low,double high) {
        for(int axis=0;axis<3;axis++) {
            if(Math.abs(direction[axis])<1e-30) {if(origin[axis]<low||origin[axis]>high)return null;}
            else {
                double a=(low-origin[axis])/direction[axis],b=(high-origin[axis])/direction[axis];
                first=Math.max(first,Math.min(a,b));last=Math.min(last,Math.max(a,b));
                if(last<=first)return null;
            }
        }
        return new double[]{first,last};
    }
    private static double[][] matrix(AffineTransform transform) {
        double[][] matrix=new double[4][4];matrix[3][3]=1;
        for(int row=0;row<3;row++)for(int column=0;column<4;column++)matrix[row][column]=transform.element(row*4+column);
        return matrix;
    }
    private static double[][] inverse(AffineTransform transform) {
        double[][] augmented=new double[4][8],source=matrix(transform);
        for(int row=0;row<4;row++) {System.arraycopy(source[row],0,augmented[row],0,4);augmented[row][row+4]=1;}
        for(int column=0;column<4;column++) {
            int pivot=column;for(int row=column+1;row<4;row++)if(Math.abs(augmented[row][column])>Math.abs(augmented[pivot][column]))pivot=row;
            double[] swap=augmented[column];augmented[column]=augmented[pivot];augmented[pivot]=swap;
            double divisor=augmented[column][column];require(Math.abs(divisor)>1e-12,"singular density fixture");
            for(int j=0;j<8;j++)augmented[column][j]/=divisor;
            for(int row=0;row<4;row++)if(row!=column) {double factor=augmented[row][column];
                for(int j=0;j<8;j++)augmented[row][j]-=factor*augmented[column][j];}
        }
        double[][] inverse=new double[4][4];for(int row=0;row<4;row++)System.arraycopy(augmented[row],4,inverse[row],0,4);
        return inverse;
    }
    private static double[] map(double[][] matrix,double[] vector,int w) {
        double[] result=new double[3];for(int row=0;row<3;row++) {result[row]=matrix[row][3]*w;
            for(int column=0;column<3;column++)result[row]+=matrix[row][column]*vector[column];}return result;
    }
    private static double halfUlp(double value) {
        return Math.max(Math.scalb(1.0,-24),Math.scalb(1.0,Math.getExponent(Math.abs(value))-10));
    }
    private static double[] direction(CameraState camera,int x,int y,int sample,int samples) {
        double[] offset=samples==1?new double[]{.5,.5}:samples==2?(sample==0?new double[]{.25,.25}:new double[]{.75,.75}):EIGHT[sample];
        double dx=((x+offset[0])/width*2-1)*camera.tanHalfFovX(),dy=-((y+offset[1])/height*2-1)*camera.tanHalfFovY();
        double scale=1/Math.sqrt(1+dx*dx+dy*dy);return new double[]{dx*scale,dy*scale,-scale};
    }
    private static CameraState camera(double z) {
        return CameraState.lookAt(0,0,z,0,0,z-10).verticalFieldOfViewDegrees(28).aspectRatio((double)width/height).build();
    }
    private static RenderFrameRequest request(Fixture fixture) {
        FramePrimitiveBatch batch=fixture.boundary!=null&&fixture.local?FramePrimitiveBatch.builder()
                .add(PrimitiveInstance.builder(2).transform(fixture.objectToWorld).mediumBoundary(MediumBoundary.volume(fixture.boundary)).build()).build()
                :FramePrimitiveBatch.empty();
        return RenderFrameRequest.builder(++sequence,width,height,camera(fixture.cameraZ)).minimumSceneRevision(revision)
                .clock(FrameClock.of(0,0)).environment(EnvironmentState.builder().skyRadiance(.2f,.4f,.6f).cameraMedium(fixture.environment).build())
                .antiAliasing(fixture.samples==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(fixture.samples))
                .mediumTransport(MediumTransport.balanced().withMaxEvents(32).withMaxDistance(30).withTransmittanceCutoff(0)
                        .withScatteringSteps(fixture.steps)).primitiveBatch(batch).build();
    }
    private static void apply(SceneTransaction transaction) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(true)try {renderer.apply(transaction);return;}catch(SubmissionRejectedException pending) {
            require(System.nanoTime()<deadline,"density scene apply timeout");Thread.sleep(1);}
    }
    private static FrameSnapshot capture(String name,RenderFrameRequest request) throws Exception {
        long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted)) {
            require(System.nanoTime()<deadline,"density frame admission timeout");Thread.sleep(1);}
        deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline) {
            var ready=renderer.pollFrame();
            if(ready.isPresent()&&ready.orElseThrow().frameSequence()==request.sequence()) {
                var frame=ready.orElseThrow();
                require(frame.renderedSceneRevision()==revision&&frame.format()==TextureFormat.RGBA16_FLOAT,"density final HDR provenance");
                if(name!=null)Files.write(OUTPUT.resolve(name+".rgba16f"),bytes(frame));return frame;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("density final HDR timeout");
    }
    private static void expectMissingDensity(Fixture fixture) throws Exception {
        var request=request(fixture);long deadline=System.nanoTime()+TIMEOUT.toNanos();
        while(System.nanoTime()<deadline)try {
            require(!(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted),"missing density texture was accepted");
            Thread.sleep(1);
        } catch(FrameValidationException expected) {
            require(expected.reason()==FrameValidationException.Reason.INVALID_MEDIUM_DENSITY,"wrong missing-density rejection");
            require(renderer.status()==Renderer.Status.READY,"missing density texture poisoned the session");rejections++;return;
        }
        throw new AssertionError("missing-density validation timeout");
    }
    private static byte[] bytes(FrameSnapshot frame) {byte[] bytes=new byte[frame.byteCount()];frame.pixels().get(bytes);return bytes;}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static Grid grid(int generation) {
        byte[] red=new byte[3*2*4];for(int i=0;i<red.length;i++)red[i]=(byte)((i*73+(i/3)*31+generation*91)&255);
        return new Grid(3,2,4,red);
    }
    private record Fixture(Medium environment,Medium boundary,AffineTransform objectToWorld,boolean local,int samples,int steps,double cameraZ) { }
    private record Grid(int nx,int ny,int nz,byte[] red) {
        TextureAsset texture(boolean alternateSampler) {
            int atlasHeight=ny*nz,base=nx*atlasHeight*4,mip=Math.max(1,nx/2)*Math.max(1,atlasHeight/2)*4;
            byte[] rgba=new byte[base+mip];for(int index=0;index<red.length;index++) {
                rgba[index*4]=red[index];rgba[index*4+1]=(byte)255;rgba[index*4+3]=(byte)255;}
            Arrays.fill(rgba,base,rgba.length,alternateSampler?(byte)0:(byte)255);
            return TextureAsset.builder(TEXTURE,nx,atlasHeight).colorSpace(TextureAsset.ColorSpace.LINEAR)
                    .filter(alternateSampler?TextureAsset.Filter.NEAREST:TextureAsset.Filter.LINEAR)
                    .addressMode(alternateSampler?TextureAsset.AddressMode.REPEAT:TextureAsset.AddressMode.CLAMP_TO_EDGE)
                    .mipChainRgba8(2,rgba).build();
        }
        double sample(double x,double y,double z) {
            if(x<0||x>1||y<0||y>1||z<0||z>1)return 0;
            double[] p={x*nx-.5,y*ny-.5,z*nz-.5},fraction=new double[3];int[] cell=new int[3],dimensions={nx,ny,nz};
            for(int axis=0;axis<3;axis++) {cell[axis]=(int)Math.floor(p[axis]);fraction[axis]=p[axis]-cell[axis];}
            double sum=0;
            for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
                int ix=Math.clamp(cell[0]+dx,0,dimensions[0]-1),iy=Math.clamp(cell[1]+dy,0,dimensions[1]-1),iz=Math.clamp(cell[2]+dz,0,dimensions[2]-1);
                sum+=(red[ix+nx*iy+nx*ny*iz]&255)/255.0*(dx==0?1-fraction[0]:fraction[0])
                        *(dy==0?1-fraction[1]:fraction[1])*(dz==0?1-fraction[2]:fraction[2]);
            }
            return sum;
        }
    }
}
