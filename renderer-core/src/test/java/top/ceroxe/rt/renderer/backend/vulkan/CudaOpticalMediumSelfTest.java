package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaBoundaryMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumAbi;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaOpticalMediumSource;
import top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi;

/** Provider-free storage, public event/sample budgets and optical admission. */
public final class CudaOpticalMediumSelfTest {
    public static void main(String[] args){
        var medium=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.01f,0,.02f),2,1.5f,0);
        var camera=CameraState.lookAt(0,0,0,0,0,-1).build();
        var frame=RenderFrameRequest.builder(7,137,101,camera).antiAliasing(AntiAliasingState.multisampled(8))
                .mediumTransport(MediumTransport.balanced().withOpticalSamples(8).withMaxEvents(32))
                .environment(EnvironmentState.builder().cameraMedium(medium).build()).build();
        var boundary=new CudaBoundaryMediumAbi.Boundary(Integer.MIN_VALUE|3,MediumBoundary.surface(medium).withPriority(-7));
        var plan=CudaOpticalMediumAbi.plan(137,101,8,1,512L*1024*1024);
        require(plan.paths()==110696&&plan.tilePixels()==plan.pixels(),"all spatial samples fit one ordinary optical tile");
        require(plan.primaryOffset()>=plan.hitOffset()+plan.tilePaths()*plan.hitBytes()+plan.tilePaths()*16,"initial primary cache is disjoint from mutable hit/evaluation");
        require(plan.transmittanceOffset()==plan.primaryOffset()+plan.cachedPrimaryPaths()*208,"shared initial primary cache matches its declared extent");
        require(plan.primaryBytes()==plan.paths()*208L,"complete Vulkan-owned primary storage remains budgeted");
        require(plan.bytes()==plan.emissionOffset()+plan.paths()*16L,"complete accumulated radiance output");
        var data=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,frame,List.of(boundary),new int[]{boundary.slot()})).order(ByteOrder.nativeOrder());
        require(data.getInt(24)==32&&data.getInt(132)==8&&data.getInt(144)==7,"event/sample/frame budgets are explicit");
        require(data.getInt(256)==0&&data.getInt(384)==boundary.slot()&&data.getInt(388)==-7,"unsigned wire slots compact into signed priority table");
        require(data.getFloat(92)==1.5f&&data.getFloat(400)==.2f,"IOR and density-scaled optical coefficients");
        CudaOpticalMediumAbi.updateTile(data,plan,0,plan.pixels(),7,1);
        require(data.getInt(136)==7&&data.getInt(140)==1&&data.getInt(8)==plan.paths(),"last declared split/sample keeps all paths");
        var nativeScene=new MediumSceneIndex.NativeScene(List.of(new MediumSceneIndex.NativeBoundary(boundary.slot(),boundary.boundary(),AffineTransform.identity())),new int[]{boundary.slot()});
        CudaOpticalMediumProfile.requireSupported(frame,nativeScene,true);
        expectUnsupported(()->CudaOpticalMediumProfile.requireSupported(frame,nativeScene,false));
        verifyStorageLayouts();
        verifyCounterModes(plan,frame,boundary);
        expectIllegal(()->CudaOpticalMediumAbi.requireMedium(new Medium(Rgb.ZERO,new Rgb(.1f,0,0),Rgb.ZERO,1,1,0)
                .withDensityField(MediumDensityField.grid(50,1,AffineTransform.identity()))));
        verifyDensity(frame,medium,boundary);
        var source=CudaOpticalMediumSource.source();
        require(source.contains("GlassMaterial_EvalSpecularReflection")&&source.contains("GlassMaterial_EvalSpecularTransmission")
                &&source.contains("HomogeneousVolume_SegmentScatter")&&source.contains("PathVolumeInfo_AddVolume")
                &&source.contains("RoughGlassMaterial_Sample")&&source.contains("SchlickDistribution_SampleH"),"pinned glass, microfacet, segment and priority functions remain assembled");
        verifySurfaceProof();
        System.out.println("CudaOpticalMediumSelfTest passed: primary lifetime, all SPP/branches/budgets, priority, smooth/rough admission");
    }
    private static void verifyCounterModes(CudaOpticalMediumAbi.Plan plan,RenderFrameRequest frame,
            CudaBoundaryMediumAbi.Boundary boundary) {
        String previous=System.getProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
        try {
            for(boolean reduced:new boolean[]{false,true}) {
                System.setProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY,Boolean.toString(reduced));
                var data=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,frame,List.of(boundary),new int[0])).order(ByteOrder.nativeOrder());
                require(data.getInt(184)==(reduced?1:0),"counter mode is explicit in the optical header");
                CudaOpticalMediumAbi.updateTile(data,plan,0,plan.pixels(),0,0);
                require(data.getInt(184)==(reduced?1:0),"tile update preserves counter mode");
            }
            System.clearProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
            require(CudaOpticalMediumAbi.reducedCounters(),"block-reduced counters are enabled by default");
        } finally {
            if(previous==null)System.clearProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY,previous);
        }
    }
    private static void verifyDensity(RenderFrameRequest frame,Medium medium,CudaBoundaryMediumAbi.Boundary boundary) {
        var field=MediumDensityField.grid(50,1,AffineTransform.identity());
        var heterogeneous=medium.withDensityField(field);
        var texturedFrame=frame.toBuilder().environment(frame.environment().toBuilder().cameraMedium(heterogeneous).build()).build();
        var texturedBoundary=new CudaBoundaryMediumAbi.Boundary(boundary.slot(),MediumBoundary.surface(heterogeneous));
        var texture=TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{(byte)128,0,0,(byte)255}).build();
        var density=CudaMediumDensityAbi.prepare(heterogeneous,List.of(texturedBoundary),id->texture);
        var plan=CudaOpticalMediumAbi.plan(137,101,8,1,density,512L*1024*1024);
        byte[] prefix=CudaOpticalMediumAbi.scenePrefix(plan,texturedFrame,List.of(texturedBoundary),new int[0],density);
        require(prefix.length==plan.sceneBytes()-density.bytes(),"optical per-frame prefix excludes immutable density descriptors and atlas");
        var data=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,texturedFrame,List.of(texturedBoundary),new int[0],density)).order(ByteOrder.nativeOrder());
        var prefixData=ByteBuffer.wrap(prefix).order(ByteOrder.nativeOrder());
        int environmentDescriptor=data.getInt(152),boundaryDescriptor=data.getInt(384+60);
        require(environmentDescriptor>=plan.priorityOffset()+4&&boundaryDescriptor>environmentDescriptor,"density descriptors follow optical priorities");
        require(data.getInt(environmentDescriptor)==data.getInt(boundaryDescriptor),"optical density descriptors share one immutable atlas");
        require(environmentDescriptor==prefix.length,"optical environment descriptor retains its full-storage absolute offset");
        require(data.getInt(environmentDescriptor)==prefix.length+2*CudaMediumDensityAbi.DESCRIPTOR_BYTES,
                "optical atlas payload follows its relocated immutable descriptor");
        for(int offset=0;offset<prefix.length;offset+=4)
            require(data.getInt(offset)==prefixData.getInt(offset),"full optical scene preserves the per-frame prefix");
        require(data.getInt(156)==frame.mediumTransport().scatteringSteps(),"optical density keeps the public quadrature budget");
        require(plan.stateOffset()>=boundaryDescriptor+CudaMediumDensityAbi.DESCRIPTOR_BYTES,"density never overlaps continuation state");
        var identityBoundary=new CudaBoundaryMediumAbi.Boundary(boundary.slot(),texturedBoundary.boundary(),AffineTransform.identity());
        var cached=new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,List.of(identityBoundary),density);
        var equalAssets=new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,
                List.of(new CudaBoundaryMediumAbi.Boundary(boundary.slot(),texturedBoundary.boundary(),AffineTransform.identity())),density);
        require(cached.sameAssets(equalAssets),"equal boundary and density generations share immutable optical assets");
        var movedBoundary=new CudaBoundaryMediumAbi.Boundary(boundary.slot(),texturedBoundary.boundary(),
                new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0}));
        require(!cached.sameAssets(new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,List.of(movedBoundary),density)),
                "optical boundary transform change invalidates immutable assets");
        var reprioritized=new CudaBoundaryMediumAbi.Boundary(boundary.slot(),texturedBoundary.boundary().withPriority(-6),AffineTransform.identity());
        require(!cached.sameAssets(new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,List.of(reprioritized),density)),
                "optical boundary metadata change invalidates immutable assets");
        require(!cached.sameAssets(new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,
                        List.of(new CudaBoundaryMediumAbi.Boundary(boundary.slot()+1,texturedBoundary.boundary(),AffineTransform.identity())),density)),
                "optical boundary identity change invalidates immutable assets");
        var replacement=TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{(byte)64,0,0,(byte)255}).build();
        var replacementDensity=CudaMediumDensityAbi.prepare(heterogeneous,List.of(texturedBoundary),id->replacement);
        require(!cached.sameAssets(new CudaOpticalMediumManager.FrameScene(texturedFrame,plan,prefix,List.of(identityBoundary),replacementDensity)),
                "same-id optical texture generation replacement invalidates immutable assets");
        long minimum=plan.sceneBytes()+plan.paths()*(plan.externalPrimary()?32L:240L)+65536
                +8L*(plan.stateBytes()+plan.hitBytes()+16+(plan.externalPrimary()&&!plan.directPrimary()?208:0));
        expectIllegal(()->CudaOpticalMediumAbi.plan(137,101,8,1,density,minimum-1,plan.externalPrimary(),plan.directPrimary(),plan.compactHits(),plan.compactStates()));
        CudaOpticalMediumProfile.requireSupported(texturedFrame,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true);
    }
    private static void verifyStorageLayouts() {
        long cap=512L*1024*1024;var density=CudaMediumDensityAbi.Scene.empty();
        var external=CudaOpticalMediumAbi.plan(1920,1080,1,0,density,cap,true,false,false,false);
        var direct=CudaOpticalMediumAbi.plan(1920,1080,1,0,density,cap,true,true,false,false);
        var compact=CudaOpticalMediumAbi.plan(1920,1080,1,0,density,cap,true,true,true,false);
        var compactState=CudaOpticalMediumAbi.plan(1920,1080,1,0,density,cap,true,true,true,true);
        require(compactState.stateBytes()==112&&compact.stateBytes()==256&&compactState.tilePixels()==compactState.pixels(),
                "boundary-free compact state fits the complete 1080p frame within the identical cap");
        var boundaryState=CudaOpticalMediumAbi.plan(1920,1080,1,1,density,cap,true,true,true,true);
        require(!boundaryState.compactStates()&&boundaryState.stateBytes()==256,
                "any explicit volume boundary preserves the complete 32-entry stack");
        require(compact.hitBytes()==96&&direct.hitBytes()==208,"only mutable continuation hits use the compact layout");
        require((compact.pixels()+compact.tilePixels()-1)/compact.tilePixels()==2,
                "compact continuation fits the same-budget 1080p frame in two tiles");
        require(compact.primaryBytes()==direct.primaryBytes()&&compact.cachedPrimaryPaths()==0,
                "compact hits retain every full original primary sample");
        require(direct.cachedPrimaryPaths()==0&&direct.transmittanceOffset()==direct.primaryOffset(),
                "direct Vulkan primary reuse reserves no duplicate CUDA hit cache");
        require((direct.pixels()+direct.tilePixels()-1)/direct.tilePixels()==3,
                "removing duplicate primary storage reduces the same-budget 1080p optical path to three tiles");
        var legacy=CudaOpticalMediumAbi.plan(1920,1080,1,0,density,cap,false,false,false,false);
        int externalTiles=(external.pixels()+external.tilePixels()-1)/external.tilePixels();
        int legacyTiles=(legacy.pixels()+legacy.tilePixels()-1)/legacy.tilePixels();
        require(external.externalPrimary()&&!legacy.externalPrimary(),"both same-budget storage paths remain explicit for controlled A/B");
        require(externalTiles==4&&legacyTiles==26,"moving full primary out of CUDA removes the 1080p tile bottleneck");
        require(external.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE<=cap&&legacy.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE<=cap,
                "the CUDA cap is identical in both layouts");
        require(external.primaryBytes()==legacy.primaryBytes()&&external.primaryBytes()==1920L*1080*208,
                "every original primary sample is retained for features and reprojection");
        require(external.transmittanceOffset()==external.primaryOffset()+external.tilePaths()*208,
                "only a tile of reusable initial hits resides inside CUDA storage");
        require(legacy.transmittanceOffset()==legacy.primaryOffset()+legacy.paths()*208,
                "the baseline retains its original full-frame shared primary layout");
        var frame=RenderFrameRequest.builder(1,1920,1080,CameraState.lookAt(0,0,0,0,0,-1).build()).build();
        for(boolean compactHits:new boolean[]{false,true})for(boolean compactStates:new boolean[]{false,true})for(int samples:new int[]{1,2,4,8}) {
            var layout=CudaOpticalMediumAbi.plan(1920,1080,samples,0,density,cap,true,true,compactHits,compactStates);
            require(layout.tilePixels()>0&&layout.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE<=cap,
                    "every SPP/layout keeps the identical CUDA resident cap");
            require(layout.evaluationOffset()==layout.hitOffset()+(long)layout.tilePaths()*layout.hitBytes(),
                    "mutable hits cannot overlap terminal evaluation");
            require(layout.hitOffset()==layout.stateOffset()+(long)layout.tilePaths()*layout.stateBytes(),
                    "both state layouts are disjoint from the mutable hits");
            require(layout.primaryOffset()==layout.evaluationOffset()+(long)layout.tilePaths()*16,
                    "evaluation cannot overlap complete-frame outputs");
            var sampledFrame=frame.toBuilder().antiAliasing(samples==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(samples)).build();
            var header=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(layout,sampledFrame,List.of(),new int[0])).order(ByteOrder.nativeOrder());
            int layoutFlags=(compactHits?1:0)|(compactStates?2:0);
            require(header.getInt(4)==4&&header.getInt(188)==layoutFlags,
                    "both producers use an explicit versioned hit layout");
            int first=(layout.pixels()-1)/layout.tilePixels()*layout.tilePixels();
            CudaOpticalMediumAbi.updateTile(header,layout,first,layout.pixels()-first,0,0);
            require(header.getInt(188)==layoutFlags&&header.getInt(8)==(layout.pixels()-first)*samples,
                    "tail tiles preserve the hit layout and complete sample count");
        }
        var scene=new CudaOpticalMediumManager.FrameScene(frame,external,
                CudaOpticalMediumAbi.scenePrefix(external,frame,List.of(),new int[0],density),List.of(),density);
        require(CudaOpticalMediumManager.requiredBytes(scene)==external.bytes()+65536+external.primaryBytes(),
                "GPU headroom includes shared memory and the new Vulkan-owned primary");
        var eight=CudaOpticalMediumAbi.plan(1920,1080,8,0,density,cap,true);
        require(eight.tilePixels()>0&&eight.bytes()+65536<=cap,"eight-SPP final T/E fits without increasing CUDA residency");
        expectIllegal(()->CudaOpticalMediumAbi.plan(1920,1080,8,0,density,cap,false));
    }
    private static void verifySurfaceProof(){
        var residency=new VulkanSceneResidency();var index=new VulkanSurfaceSceneIndex();long revision=0;
        var glass=MaterialAsset.builder(1).transmission(1).roughness(0).doubleSided(true).build();
        var mesh=MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1);
        var prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass).upsert(mesh).upsert(SceneInstance.builder(1,1).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalUnlitOnly(FramePrimitiveBatch.empty()),"fully transmitting smooth PBR is admitted without a diffuse-lighting path");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().roughness(.2f).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalUnlitOnly(FramePrimitiveBatch.empty()),"fully transmitting rough PBR uses the pinned microfacet sampler");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().transmission(.5f).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(!index.opticalUnlitOnly(FramePrimitiveBatch.empty()),"partial PBR transmission still requires surface lighting");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().transmission(.5f).shadingModel(MaterialAsset.ShadingModel.UNLIT).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalUnlitOnly(FramePrimitiveBatch.empty()),"partial UNLIT transmission uses the retained terminal function");
    }
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    private static void expectIllegal(Runnable action){try{action.run();throw new AssertionError("expected invalid optical ABI");}catch(IllegalArgumentException expected){}}
    private static void expectUnsupported(Runnable action){try{action.run();throw new AssertionError("expected unsupported optical frame");}catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable category");}}
}
