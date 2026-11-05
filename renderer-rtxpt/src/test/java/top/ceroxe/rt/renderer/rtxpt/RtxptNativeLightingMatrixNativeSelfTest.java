package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Numeric receiver/transport matrices. Every case captures the newly submitted GPU signal. */
public final class RtxptNativeLightingMatrixNativeSelfTest {
    static final Path OUT=Path.of(System.getProperty("top.ceroxe.rt.rtxpt.testOutput",
        "build/reports/rtxpt-native-matrix")).toAbsolutePath();
    static final int W=33,H=25,CENTER=((H/2)*W+W/2)*3;
    static final float EPSILON=.003f;
    static final NativeLightingPlan.Directional SUN_OFF=new NativeLightingPlan.Directional(0,0,1,0,1,1,1);
    static final NativeLightingPlan.Directional SUN_ON=new NativeLightingPlan.Directional(0,0,1,1,1,1,1);
    static int rgba(int red,int green,int blue){return 0xff000000|(blue<<16)|(green<<8)|red;}
    static MaterialAsset material(long id,int red,int green,int blue){return MaterialAsset.builder(id)
        .baseColorRgba8(rgba(red,green,blue)).roughness(.8f).build();}
    static MeshAsset plane(long id,long material,float half,float z,float local,float sky,boolean reverse){
        return MeshAsset.builder(id,new float[]{-half,-half,z,half,-half,z,half,half,z,-half,half,z},
            reverse?new int[]{0,2,1,0,3,2}:new int[]{0,1,2,0,2,3},new long[]{material,material})
            .textureCoordinates(new float[]{0,0,1,0,1,1,0,1})
            .lightmapCoordinates(new float[]{local,sky,local,sky,local,sky,local,sky}).build();
    }
    static NativeLightingPlan plan(Set<NativeLightingPlan.Signal> signals,int samples,
            NativeLightingPlan.Directional sun,boolean fast){
        return new NativeLightingPlan(W,H,signals,samples,6,.35f,NativeLightingStyle.defaults(),sun,null,fast);
    }
    static NativeLightingPlan directPlan(NativeLightingPlan.Directional sun){return plan(Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,sun,true);}
    static RenderFrameRequest frame(long sequence,long revision){return frame(sequence,revision,
        CameraState.lookAt(0,0,3,0,0,0).verticalFieldOfViewDegrees(52).aspectRatio(1.5).build());}
    static RenderFrameRequest frame(long sequence,long revision,CameraState camera){return RenderFrameRequest.builder(sequence,96,64,camera)
        .minimumSceneRevision(revision).environment(EnvironmentState.neutral()).build();}
    static double[] capture(RtxptRenderer renderer,String label,int signal)throws Exception {
        Path file=OUT.resolve(label+".pfm");renderer.captureNativeLighting(file,signal);
        double[] raw=RtxptLightTreeNativeSelfTest.linear(file);
        require(raw.length==W*H*3,"Exact physical lighting grid: "+label);
        // PFM rows are bottom-to-top; the public projection uses a top-left origin.
        double[] values=new double[raw.length];for(int y=0;y<H;y++)System.arraycopy(raw,(H-1-y)*W*3,values,y*W*3,W*3);
        return values;
    }
    static void near(double actual,double expected,double tolerance,String reason){
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,
            reason+" expected="+expected+" actual="+actual+" tolerance="+tolerance);
    }
    static void neutral(double[] values,double expected,String reason){for(double value:values)near(value,expected,.00001,reason);}
    static SparseLightSource source(long id,double x,double y,double z,float intensity,boolean shadow){
        return new SparseLightSource(id,SparseLightSource.Shape.POINT,x,y,z,0,0,0,0,-1,0,
            1,1,1,intensity,8,2,1,-1,SparseLightSource.Composition.PHYSICAL_SUM,0,shadow,SparseLightSource.Layer.DYNAMIC);
    }
    static void publication(RtxptRenderer renderer,long generation,NativeLightingPublication.Instance... instances){
        renderer.publishLightingAttributes(new NativeLightingPublication(generation,List.of(instances),List.of(),null,null));
    }
    static void packet(RtxptRenderer renderer,long sequence,long revision,FrameClock clock){
        try(var lease=renderer.acquireLightingFrame().orElseThrow()){
            var output=lease.frame();require(output.sequence()==sequence&&output.sceneRevision()==revision,
                "Captured output packet belongs to the new scene/frame");
            require(output.clock().equals(clock),"GPU output carries the submitted clock without wall-clock substitution");
        }
        renderer.awaitIdle();
    }
    public static void main(String[] args)throws Exception {
        Files.createDirectories(OUT);List<String> cases=args.length==0?List.of("gi","roles","pose","projection","time"):List.of(args);
        for(String value:cases)switch(value){
            case "gi"->gi();case "roles"->rolesAndAlpha();case "pose"->poseAndOpaqueReference();
            case "projection"->projection();case "time"->timeAndOrigin();case "cpu"->cpuOracle();
            case "topology-coverage"->topologyCoverage();
            default->throw new IllegalArgumentException("Unknown native matrix case: "+value);
        }
        System.out.println("RTXPT_NATIVE_MATRIX PASS cases="+cases);
    }

    /** Two deterministic rays hit a bounce-only plane. Its albedo and source field have a CPU oracle. */
    static void gi()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(plan(Set.of(NativeLightingPlan.Signal.INDIRECT_DIFFUSE,NativeLightingPlan.Signal.NORMAL_DISTANCE),2,SUN_OFF,true));
            renderer.lightingWorkStatisticsEnabled(true);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128))
                .upsert(material(2,255,0,0)).upsert(plane(1,1,2,0,0,0,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build(),
                List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            publication(renderer,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));
            renderer.submit(frame(0,1));double[] red=capture(renderer,"gi-red-bounce",1);
            renderer.awaitIdle();var guide=capture(renderer,"gi-receiver-normal",2);
            System.out.println("RTXPT_GI_DIAGNOSTIC normal="+Arrays.toString(Arrays.copyOfRange(guide,CENTER,CENTER+3))+" work="+renderer.lightingWorkStatistics());
            double expected=giOracle(128/255.0);
            near(red[CENTER],expected,.003,"Two actual red bounce receivers match the declared finite-source GI formula");
            near(red[CENTER+1],0,1e-6,"Red bounce does not invent green");near(red[CENTER+2],0,1e-6,"Red bounce does not invent blue");
            require(red[CENTER]>.005,"GI test has an observable reflected-light contribution");
            renderer.submit(frame(1,1));require(Arrays.equals(red,capture(renderer,"gi-red-repeat",1)),
                "Fixed scene executes reproducible GI on a new frame");
            var before=renderer.retainedUpdateTimings();
            renderer.apply(SceneTransaction.builder(2).upsert(material(2,0,255,0)).build());renderer.submit(frame(2,2));
            double[] green=capture(renderer,"gi-green-bounce",1);
            near(green[CENTER],0,1e-6,"Bounce material update removes old red");
            near(green[CENTER+1],expected,.003,"Bounce material color participates in new green transport");
            renderer.apply(SceneTransaction.builder(3).upsert(material(1,64,64,64)).build());renderer.submit(frame(3,3));
            double[] half=capture(renderer,"gi-half-receiver-albedo",1);
            near(half[CENTER+1],green[CENTER+1]*.5,.0015,"Receiver albedo is applied once, rather than squared or omitted");
            var after=renderer.retainedUpdateTimings();require(before.builtBlases()==after.builtBlases(),
                "Changing diffuse colors does not rebuild geometry");
            publication(renderer,2,new NativeLightingPublication.Instance(1,23,.5f,.25f,1,1,0,0));
            renderer.submit(frame(4,3));near(capture(renderer,"gi-visibility-fog",1)[CENTER+1],half[CENTER+1]*.375,.001,
                "Receiver visibility and fog are each applied once to GI");
            renderer.apply(SceneTransaction.builder(4).build(),List.of(),LongBuffer.wrap(new long[]{1}));
            renderer.submit(frame(5,4));neutral(capture(renderer,"gi-source-off",1),0,"Source shutoff has no stale indirect light");
            renderer.apply(SceneTransaction.builder(5).build(),List.of(source(1,0,0,.5,8,false)),LongBuffer.allocate(0));
            renderer.submit(frame(6,5));near(capture(renderer,"gi-source-restored",1)[CENTER+1],half[CENTER+1]*.375,.001,
                "Restoring the source restores current GI");
            renderer.apply(SceneTransaction.builder(6).removeInstance(2).build());renderer.submit(frame(7,6));
            neutral(capture(renderer,"gi-bounce-removed",1),0,"Removing the bounce geometry removes all reflected light");
            renderer.apply(SceneTransaction.builder(7).upsert(SceneInstance.builder(2,2).build()).build());
            publication(renderer,3,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));renderer.submit(frame(8,7));
            near(capture(renderer,"gi-bounce-restored",1)[CENTER+1],half[CENTER+1]*.375,.001,"Bounce-only geometry can be restored without stale color");
            packet(renderer,8,7,FrameClock.of(0,0));require(renderer.nativeLightingExecution().fullBeautyFrames()==0,"GI matrix never runs full beauty");
            System.out.printf(Locale.ROOT,"RTXPT_NATIVE_GI_MATRIX PASS samples=2 distance=6 strength=.35 oracle=%.9f measured=%.9f%n",expected,red[CENTER]);
        }
    }
    static double giOracle(double receiverAlbedo){
        double sum=0;
        for(int i=0;i<2;i++){
            double radius=Math.sqrt((i+.5)/2),angle=(i+.5)*2.39996323,z=Math.sqrt(1-radius*radius);
            double rayDistance=(1-EPSILON)/z;
            double x=radius*Math.cos(angle)*rayDistance,y=radius*Math.sin(angle)*rayDistance;
            double distance=Math.sqrt(x*x+y*y+.5*.5);
            sum+=8*Math.pow(1-distance/8,2)/Math.max(distance*distance,1);
        }
        return receiverAlbedo*sum*(.35/2);
    }

    static TextureAsset alpha(int alpha){return TextureAsset.color(7,1,1,new byte[]{-1,-1,-1,(byte)alpha});}
    /** The foreground plane is never a receiver; only its shadow role and true alpha alter the rear receiver. */
    static void rolesAndAlpha()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(directPlan(SUN_ON));
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,255,255,255)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(7).roughness(.8f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
            publication(renderer,1,NativeLightingPublication.Instance.shadowOnly(2));renderer.submit(frame(0,1));
            near(capture(renderer,"roles-shadow-only",0)[CENTER],.45,.001,"Shadow-only foreground casts a shadow onto the actual receiver");
            publication(renderer,2,new NativeLightingPublication.Instance(2,0,1,0,1,1,0,0));renderer.submit(frame(1,1));
            near(capture(renderer,"roles-excluded",0)[CENTER],1,.0001,"Excluded instance neither receives nor projects");
            publication(renderer,3,new NativeLightingPublication.Instance(2,2,1,0,1,1,0,0));renderer.submit(frame(2,1));
            near(capture(renderer,"roles-single-sided-backface",0)[CENTER],1,.0001,"Single-sided shadow ignores a backface");
            publication(renderer,4,NativeLightingPublication.Instance.shadowOnly(2));renderer.submit(frame(3,1));
            near(capture(renderer,"roles-double-sided",0)[CENTER],.45,.001,"Double-sided shadow includes the same backface");
            renderer.apply(SceneTransaction.builder(2).upsert(alpha(0)).build());renderer.submit(frame(4,2));
            near(capture(renderer,"roles-cutout-transparent",0)[CENTER],1,.0001,"Alpha-tested transparent texels do not project an opaque rectangle");
            renderer.apply(SceneTransaction.builder(3).upsert(alpha(255)).build());renderer.submit(frame(5,3));
            near(capture(renderer,"roles-cutout-restored",0)[CENTER],.45,.001,"A new alpha frame restores the current shadow");
            publication(renderer,5,new NativeLightingPublication.Instance(1,23,.5f,.5f,1,1,0,0));renderer.submit(frame(6,3));
            near(capture(renderer,"roles-visible-fog",0)[CENTER],1-.55*.25,.001,"Receiver fade and fog control only the lighting correction");
            publication(renderer,6,new NativeLightingPublication.Instance(2,1,1,0,1,1,0,0));renderer.submit(frame(7,3));
            near(capture(renderer,"roles-front-receiver",0)[CENTER],1,.0001,"Making foreground a receiver changes the actual primary hit");
            packet(renderer,7,3,FrameClock.of(0,0));
            // Sun shadow intersects x=4,z=3, outside this camera's visible half-space.
            renderer.nativeLightingPlan(directPlan(new NativeLightingPlan.Directional(.8f,0,.6f,1,1,1,1)));
            renderer.apply(SceneTransaction.builder(4).resetScene().upsert(material(1,255,255,255))
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(3,1,.5f,3,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(3,3)
                    .transform(new AffineTransform(new float[]{1,0,0,4,0,1,0,0,0,0,1,0})).build()).build());
            publication(renderer,7,NativeLightingPublication.Instance.receiver(1),NativeLightingPublication.Instance.shadowOnly(3));
            renderer.submit(frame(8,4));near(capture(renderer,"roles-offscreen-shadow",0)[CENTER],.45,.001,
                "An offscreen-only occluder still contributes a directional shadow");
            publication(renderer,8,new NativeLightingPublication.Instance(3,0,1,0,1,1,0,0));renderer.submit(frame(9,4));
            near(capture(renderer,"roles-offscreen-excluded",0)[CENTER],1,.0001,"Excluding offscreen occluder removes its shadow");
            renderer.nativeLightingPlan(directPlan(SUN_OFF));
            renderer.apply(SceneTransaction.builder(5).resetScene().upsert(material(1,255,255,255))
                .upsert(plane(1,1,2,0,.5f,0,true)).upsert(SceneInstance.builder(1,1).build()).build());
            publication(renderer,9,new NativeLightingPublication.Instance(1,9,1,0,1,1,0,0));renderer.submit(frame(10,5));
            near(capture(renderer,"roles-culled-receiver-backface",0)[CENTER],1,.0001,"Receiver backface culling rejects the rear face");
            publication(renderer,10,new NativeLightingPublication.Instance(1,1,1,0,1,1,0,0));renderer.submit(frame(11,5));
            near(capture(renderer,"roles-two-sided-receiver",0)[CENTER],.625,.001,"Two-sided receiver accepts the same rear face");
            packet(renderer,11,5,FrameClock.of(0,0));
            System.out.println("RTXPT_NATIVE_ROLE_ALPHA_MATRIX PASS shadow-only excluded single-sided double-sided texture-animation fade offscreen receiver-culling");
        }
    }

    static void topologyShadowFrame(RtxptRenderer renderer,long sequence,long revision,double expected,String label)throws Exception {
        renderer.submit(frame(sequence,revision));
        near(capture(renderer,"topology-coverage-"+label,0)[CENTER],expected,.001,"Current topology/coverage: "+label);
        packet(renderer,sequence,revision,FrameClock.of(0,0));
    }
    static void topologyCoverage()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(directPlan(SUN_ON));
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,255,255,255)).upsert(alpha(255))
                .upsert(MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(7).roughness(.8f).build())
                .upsert(plane(1,1,2,0,0,1,false)).upsert(plane(2,2,4,1,0,0,false))
                .upsert(SceneInstance.builder(1,1).build()).upsert(SceneInstance.builder(2,2).build()).build());
            publication(renderer,1,NativeLightingPublication.Instance.receiver(1),NativeLightingPublication.Instance.shadowOnly(2));
            topologyShadowFrame(renderer,0,1,.45,"initial");
            renderer.apply(SceneTransaction.builder(2).removeInstance(2).removeMesh(2)
                .upsert(plane(3,2,4,1,0,0,false)).upsert(SceneInstance.builder(3,3).build()).build());
            publication(renderer,2,NativeLightingPublication.Instance.receiver(1),NativeLightingPublication.Instance.shadowOnly(3));
            topologyShadowFrame(renderer,1,2,.45,"replacement");
            renderer.apply(SceneTransaction.builder(3).upsert(alpha(0)).build());
            topologyShadowFrame(renderer,2,3,1,"replacement-transparent");
            renderer.apply(SceneTransaction.builder(4).upsert(SceneInstance.builder(4,3)
                .transform(new AffineTransform(new float[]{1,0,0,100,0,1,0,0,0,0,1,0})).build()).build());
            publication(renderer,3,NativeLightingPublication.Instance.receiver(1),NativeLightingPublication.Instance.shadowOnly(3),NativeLightingPublication.Instance.shadowOnly(4));
            topologyShadowFrame(renderer,3,4,1,"shared");
            renderer.apply(SceneTransaction.builder(5).removeInstance(3).build());
            topologyShadowFrame(renderer,4,5,1,"shared-detach");
            renderer.apply(SceneTransaction.builder(6).upsert(SceneInstance.builder(4,3).build()).build());
            topologyShadowFrame(renderer,5,6,1,"shared-moved");
            renderer.apply(SceneTransaction.builder(7).upsert(alpha(255)).build());
            topologyShadowFrame(renderer,6,7,.45,"shared-opaque");
            renderer.apply(SceneTransaction.builder(8).upsert(TextureAsset.color(8,1,1,new byte[]{-1,-1,-1,0}))
                .upsert(MaterialAsset.builder(3).blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(8).roughness(.8f).build())
                .upsert(plane(3,3,4,1,0,0,false)).build());
            topologyShadowFrame(renderer,7,8,1,"changed-material");
            renderer.apply(SceneTransaction.builder(9).upsert(TextureAsset.color(8,1,1,new byte[]{-1,-1,-1,-1})).build());
            topologyShadowFrame(renderer,8,9,.45,"changed-material-opaque");
            renderer.apply(SceneTransaction.builder(10).removeInstance(4).removeMesh(3).build());
            topologyShadowFrame(renderer,9,10,1,"last-detach");
            renderer.apply(SceneTransaction.builder(11).upsert(plane(5,3,4,1,0,0,false)).upsert(SceneInstance.builder(5,5).build()).build());
            publication(renderer,4,NativeLightingPublication.Instance.receiver(1),NativeLightingPublication.Instance.shadowOnly(5));
            topologyShadowFrame(renderer,10,11,.45,"reintroduced-material");
            System.out.println("RTXPT_TOPOLOGY_COVERAGE PASS frames=11 replacement=true shared=true materialChange=true lastDetach=true");
        }
    }

    static MeshAsset tilted(long id,float[] positions,float[] normals){return MeshAsset.builder(id,positions,
        new int[]{0,1,2,0,2,3},new long[]{1,1}).normals(normals)
        .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();}
    static float[] authoredPositions(){float[] result=new float[12];int k=0;for(float[] p:new float[][]{{-2,-2},{2,-2},{2,2},{-2,2}}){
        result[k++]=p[0]*.8f;result[k++]=p[1];result[k++]=p[0]*-.6f;}return result;}
    static float[] repeatedNormal(double x,double y,double z){float[] n=new float[12];for(int i=0;i<4;i++){n[i*3]=(float)x;n[i*3+1]=(float)y;n[i*3+2]=(float)z;}return n;}
    static void poseAndOpaqueReference()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            var signals=Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE);
            renderer.nativeLightingPlan(plan(signals,0,SUN_OFF,true));
            double c=Math.cos(.35),s=Math.sin(.35);float[] transform={(float)(c*1.4),0,(float)(s*2.2),0,0,.7f,0,0,(float)(-s*1.4),0,(float)(c*2.2),0};
            float[] vertices=authoredPositions();double nx=c*(.6/1.4)+s*(.8/2.2),nz=-s*(.6/1.4)+c*(.8/2.2),length=Math.hypot(nx,nz);nx/=length;nz/=length;
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,180,140,90))
                .upsert(tilted(1,vertices,repeatedNormal(.6,0,.8)))
                .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(transform)).build()).build());
            renderer.submit(frame(0,1));double[] transformed=capture(renderer,"pose-inverse-transpose",2);
            near(transformed[CENTER],nx,.012,"Nonuniform instance uses inverse-transpose normal X");
            near(transformed[CENTER+1],0,.012,"Nonuniform instance normal Y");near(transformed[CENTER+2],nz,.012,"Nonuniform instance uses inverse-transpose normal Z");
            double[] direct=capture(renderer,"opaque-fast-direct",0);
            renderer.nativeLightingPlan(plan(signals,0,SUN_OFF,false));renderer.submit(frame(1,1));
            double[] reference=capture(renderer,"opaque-reference-normal",2),referenceDirect=capture(renderer,"opaque-reference-direct",0);
            require(Arrays.equals(transformed,reference)&&Arrays.equals(direct,referenceDirect),"Opaque fast path equals forced full candidate checks");
            float[] baked=new float[12];for(int i=0;i<4;i++)for(int row=0;row<3;row++)baked[i*3+row]=
                transform[row*4]*vertices[i*3]+transform[row*4+1]*vertices[i*3+1]+transform[row*4+2]*vertices[i*3+2]+transform[row*4+3];
            renderer.apply(SceneTransaction.builder(2).upsert(tilted(2,baked,repeatedNormal(nx,0,nz)))
                .upsert(SceneInstance.builder(2,2).build()).removeInstance(1).build());renderer.submit(frame(2,2));
            double[] equivalent=capture(renderer,"pose-baked-reference",2);int compared=0;
            for(int i=0;i<equivalent.length;i+=3)if(lengthSquared(transformed,i)>.9&&lengthSquared(equivalent,i)>.9){
                for(int channel=0;channel<3;channel++)near(transformed[i+channel],equivalent[i+channel],.015,"Transformed normals agree with baked CPU geometry");compared++;}
            require(compared>150,"Pose comparison covers a surface region, rather than an isolated successful hit");
            packet(renderer,2,2,FrameClock.of(0,0));
            System.out.println("RTXPT_NATIVE_POSE_OPAQUE_MATRIX PASS inverse-transpose bakedReferencePixels="+compared+" fastVsReference=exact");
        }
    }
    static double lengthSquared(double[] values,int i){return values[i]*values[i]+values[i+1]*values[i+1]+values[i+2]*values[i+2];}

    static ExactProjectionState exact(int width,int height,ExactProjectionState.DepthConvention depth,
            ExactProjectionState.JitterConvention jitter,double jx,double jy,boolean asymmetric,double ox,double oy,double oz){
        double near=.1,far=100,a=(depth==ExactProjectionState.DepthConvention.ZERO_TO_ONE?0:
            depth==ExactProjectionState.DepthConvention.NEGATIVE_ONE_TO_ONE?-1:1);
        double b=depth==ExactProjectionState.DepthConvention.REVERSED_ZERO_TO_ONE?0:
            depth==ExactProjectionState.DepthConvention.REVERSED_NEGATIVE_ONE_TO_ONE?-1:1;
        double z=(near*a-far*b)/(far-near),w=near*(a+z),fy=1/Math.tan(Math.toRadians(52)/2),fx=fy/((double)width/height);
        return ExactProjectionState.builder(width,height).matrixLayout(ExactProjectionState.MatrixLayout.ROW_MAJOR)
            .coordinateSystem(ExactProjectionState.CoordinateSystem.RIGHT_HANDED_NEGATIVE_Z_FORWARD).depthConvention(depth)
            .cameraToWorld(new double[]{1,0,0,ox,0,1,0,oy,0,0,1,oz,0,0,0,1})
            .clipFromView(new double[]{fx,0,asymmetric?.17:0,0,0,fy,asymmetric?-.11:0,0,0,0,z,w,0,0,-1,0})
            .jitter(jitter,jx,jy).build();
    }
    static void projection()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(directPlan(SUN_OFF));
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,255,255,255))
                .upsert(plane(1,1,8,0,0,0,false).toBuilder().lightmapCoordinates(new float[]{0,0,1,0,1,1,0,1}).build())
                .upsert(SceneInstance.builder(1,1).build()).build());
            float[] lookup=new float[512];for(int y=0;y<16;y++)for(int x=0;x<16;x++)lookup[(y*16+x)*2]=(.6f*x+.4f*y)/15;
            renderer.publishLightingAttributes(new NativeLightingPublication(1,List.of(),List.of(),lookup,null));
            long sequence=0;int compared=0;
            for(var depth:ExactProjectionState.DepthConvention.values())for(boolean asymmetric:new boolean[]{false,true})
                for(var jitter:ExactProjectionState.JitterConvention.values()){
                    int width=asymmetric?97:2560,height=asymmetric?65:1600;
                    double jx=jitter==ExactProjectionState.JitterConvention.NONE?0:jitter==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET?.35:.007;
                    double jy=jitter==ExactProjectionState.JitterConvention.NONE?0:jitter==ExactProjectionState.JitterConvention.PIXEL_CENTER_OFFSET?-.27:-.009;
                    var projection=exact(width,height,depth,jitter,jx,jy,asymmetric,0,0,3);
                    var request=RenderFrameRequest.builder(sequence,width,height,CameraState.exactProjection(projection))
                        .minimumSceneRevision(1).environment(EnvironmentState.neutral()).build();
                    renderer.submit(request);double[] values=capture(renderer,"projection-"+depth+"-"+asymmetric+"-"+jitter,0);
                    for(int y=2;y<H-2;y+=2)for(int x=2;x<W-2;x+=2){
                        var ray=projection.rayForPixel((x+.5)*width/W-.5,(y+.5)*height/H-.5);
                        double distance=-ray.originZ()/ray.directionZ(),px=ray.originX()+ray.directionX()*distance,py=ray.originY()+ray.directionY()*distance;
                        require(Math.abs(px)<7.9&&Math.abs(py)<7.9,"Projection oracle remains inside the known receiver");
                        double baseline=.6*(px+8)/16+.4*(py+8)/16,expected=1-.75*baseline;
                        near(values[(y*W+x)*3],expected,.0015,"GPU exact ray agrees with public CPU pixel-center mapping: "+depth+" "+jitter);compared++;
                    }
                    packet(renderer,sequence,1,FrameClock.of(0,0));sequence++;
                }
            require(renderer.nativeLightingExecution().lightingFrames()==sequence,"Every projection combination really executed a new frame");
            System.out.println("RTXPT_NATIVE_PROJECTION_MATRIX PASS depthConventions=4 perspectiveAndAsymmetric=true jitterConventions=3 logical=2560x1600,97x65 grid=33x25 oraclePixels="+compared);
        }
    }

    static void timeAndOrigin()throws Exception {
        try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            renderer.nativeLightingPlan(directPlan(SUN_OFF));
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,180,180,180))
                .upsert(plane(1,1,2,0,.25f,0,false)).upsert(SceneInstance.builder(1,1).build()).build(),
                List.of(source(1,0,0,2,1,false)),LongBuffer.allocate(0));
            renderer.submit(frame(0,1).toBuilder().clock(FrameClock.of(100,0)).build());double[] initial=capture(renderer,"time-source-initial",0);
            packet(renderer,0,1,FrameClock.of(100,0));
            renderer.apply(SceneTransaction.builder(2).build(),List.of(source(1,1.5,0,2,1,false)),LongBuffer.allocate(0));
            renderer.submit(frame(1,2).toBuilder().clock(FrameClock.of(200,100)).build());double[] moved=capture(renderer,"time-source-moved",0);
            require(Math.abs(initial[CENTER]-moved[CENTER])>.005,"Moving source changes the actual newly rendered correction");
            packet(renderer,1,2,FrameClock.of(200,100));var before=renderer.retainedUpdateTimings();
            double ox=128,oy=64,oz=-32;
            renderer.apply(SceneTransaction.builder(3).upsert(SceneInstance.builder(1,1).transform(
                new AffineTransform(new float[]{1,0,0,(float)ox,0,1,0,(float)oy,0,0,1,(float)oz})).build()).build(),
                List.of(source(1,ox+1.5,oy,oz+2,1,false)),LongBuffer.allocate(0));
            var camera=CameraState.lookAt(ox,oy,oz+3,ox,oy,oz).verticalFieldOfViewDegrees(52).aspectRatio(1.5).build();
            renderer.submit(frame(2,3,camera).toBuilder().clock(FrameClock.of(-1000,0)).build());
            double[] rebased=capture(renderer,"time-origin-rebased",0);
            for(int y=9;y<16;y++)for(int x=12;x<21;x++)for(int channel=0;channel<3;channel++)
                near(rebased[(y*W+x)*3+channel],moved[(y*W+x)*3+channel],.002,"Joint scene/camera/source origin shift preserves the same light field");
            packet(renderer,2,3,FrameClock.of(-1000,0));var after=renderer.retainedUpdateTimings();
            require(before.builtBlases()==after.builtBlases(),"Rigid origin migration retains BLAS geometry");
            renderer.submit(frame(3,3,camera).toBuilder().clock(FrameClock.of(-1000,0)).build());
            require(Arrays.equals(rebased,capture(renderer,"time-paused",0)),"Paused clock re-executes deterministic current inputs");
            packet(renderer,3,3,FrameClock.of(-1000,0));
            renderer.apply(SceneTransaction.builder(4).resetScene().build());renderer.submit(frame(4,4).toBuilder().clock(FrameClock.of(0,0)).build());
            neutral(capture(renderer,"time-empty-world",0),1,"World replacement publishes a fresh neutral result");packet(renderer,4,4,FrameClock.of(0,0));
            require(renderer.nativeLightingExecution().lightingFrames()==5&&renderer.nativeLightingExecution().fullBeautyFrames()==0,
                "Clock seek, pause, origin migration and empty world each execute the independent lighting plan");
            System.out.println("RTXPT_NATIVE_TIME_ORIGIN_MATRIX PASS sourceMotion revision clockSeek pause originShift emptyWorld");
        }
    }
    static void cpuOracle(){
        require(giOracle(128/255.0)>.005,"GI oracle has useful signal");near(giOracle(64/255.0),giOracle(128/255.0)/2,1e-12,"Albedo oracle is linear");
        for(var depth:ExactProjectionState.DepthConvention.values()){
            var projection=exact(96,64,depth,ExactProjectionState.JitterConvention.NONE,0,0,false,0,0,3);
            var ray=projection.rayForPixel(47.5,31.5);near(ray.directionX(),0,1e-10,"Projection center X");near(ray.directionY(),0,1e-10,"Projection center Y");near(ray.directionZ(),-1,1e-10,"Projection center Z");
        }
        System.out.println("RTXPT_NATIVE_MATRIX_CPU PASS");
    }
}
