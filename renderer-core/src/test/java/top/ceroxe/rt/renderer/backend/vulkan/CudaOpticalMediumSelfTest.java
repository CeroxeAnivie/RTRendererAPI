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
        require(plan.bytes()==plan.emissionOffset()+plan.paths()*16L+plan.queueStrideBytes()*2L,"complete accumulated radiance and disjoint active queues");
        var data=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,frame,List.of(boundary),new int[]{boundary.slot()})).order(ByteOrder.nativeOrder());
        require(plan.activeQueues()&&data.getInt(200)==plan.firstQueueOffset()&&data.getInt(204)==plan.secondQueueOffset(),"active queue offsets are explicit");
        require((data.getInt(188)&256)!=0&&plan.firstQueueOffset()>=plan.emissionOffset()+plan.paths()*16L
                &&plan.secondQueueOffset()+plan.queueStrideBytes()==plan.bytes(),"queues never alias accumulated radiance");
        require(data.getInt(24)==32&&data.getInt(132)==8&&data.getInt(144)==7,"event/sample/frame budgets are explicit");
        require(data.getInt(256)==0&&data.getInt(384)==boundary.slot()&&data.getInt(388)==-7,"unsigned wire slots compact into signed priority table");
        require(data.getFloat(92)==1.5f&&data.getFloat(400)==.2f,"IOR and density-scaled optical coefficients");
        CudaOpticalMediumAbi.updateTile(data,plan,0,plan.pixels(),7,1);
        require(data.getInt(136)==7&&data.getInt(140)==1&&data.getInt(8)==plan.paths(),"last declared split/sample keeps all paths");
        var nativeScene=new MediumSceneIndex.NativeScene(List.of(new MediumSceneIndex.NativeBoundary(boundary.slot(),boundary.boundary(),AffineTransform.identity())),new int[]{boundary.slot()});
        CudaOpticalMediumProfile.requireSupported(frame,nativeScene,true);
        expectUnsupported(()->CudaOpticalMediumProfile.requireSupported(frame,nativeScene,false));
        verifyStorageLayouts();
        verifyConnectionBatchStorage();
        verifyHybridVolumeStorage();
        verifyCounterModes(plan,frame,boundary);
        verifyHeterogeneousScatteringAdmission(frame);
        verifyDensity(frame,medium,boundary);
        var source=CudaOpticalMediumSource.source();
        require(source.contains("GlassMaterial_EvalSpecularReflection")&&source.contains("GlassMaterial_EvalSpecularTransmission")
                &&source.contains("HomogeneousVolume_SegmentScatter")&&source.contains("PathVolumeInfo_AddVolume")
                &&source.contains("RoughGlassMaterial_Sample")&&source.contains("SchlickDistribution_SampleH")
                &&source.contains("DensityGridTexture_ConstEvaluate"),"glass, density-grid free-flight, segment and priority functions remain assembled");
        verifySurfaceProof();
        verifyResidentCapacity();
        System.out.println("CudaOpticalMediumSelfTest passed: primary lifetime, SPP/branches/budgets, priority, smooth/rough glass, heterogeneous scattering admission");
    }
    private static void verifyResidentCapacity() {
        String property="top.ceroxe.rt.medium.cuda.maxResidentBytes",previous=System.getProperty(property);
        try {
            System.clearProperty(property);
            long gib=1024L*1024*1024;
            var large=new top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot(true,0,16*gib,16*gib,List.of());
            var occupied=new top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot(true,8*gib,16*gib,8*gib,List.of());
            var small=new top.ceroxe.rt.renderer.rt.device.VulkanMemoryBudgetSnapshot(true,0,2*gib,2*gib,List.of());
            require(CudaOpticalMediumManager.residentLimit(large)==CudaOpticalMediumAbi.MAXIMUM_RESIDENT_BYTES,
                    "large GPUs use the supported optical address range instead of a hidden 512 MiB clamp");
            require(CudaOpticalMediumManager.residentLimit(occupied)==CudaOpticalMediumManager.residentLimit(large),
                    "our own resident allocations must not make each following frame retile");
            require(CudaOpticalMediumManager.residentLimit(small)==CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES
                    &&CudaOpticalMediumManager.residentLimit(null)==CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES,
                    "small GPUs and missing driver budgets retain bounded legacy tiling");
            var tiled=connectionPlan(CudaOpticalMediumAbi.DEFAULT_RESIDENT_BYTES);
            var full=connectionPlan(CudaOpticalMediumManager.residentLimit(large));
            require(tiled.tilePixels()<tiled.pixels()&&full.tilePixels()==full.pixels(),
                    "1080p complete connection paths must share a full wave when capacity permits");
            require(full.pixels()==tiled.pixels()&&full.samples()==tiled.samples()&&full.primaryBytes()==tiled.primaryBytes()
                    &&full.connectionTransport()&&full.boundaryCount()==tiled.boundaryCount(),
                    "capacity planning changes no spatial samples, boundaries or required outputs");
            require(full.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE<=CudaOpticalMediumAbi.MAXIMUM_RESIDENT_BYTES,
                    "large wave offsets and allocation alignment stay within the signed byte-address ABI");
            System.setProperty(property,"67108864");
            require(CudaOpticalMediumManager.residentLimit(large)==64L*1024*1024,"explicit resident caps are honored");
            System.setProperty(property,Long.toString(4*gib));
            require(CudaOpticalMediumManager.residentLimit(large)==CudaOpticalMediumAbi.MAXIMUM_RESIDENT_BYTES,
                    "configured capacity cannot overflow the optical address ABI");
            System.out.println("Optical capacity: 1080p legacy tilePixels="+tiled.tilePixels()+" tiles="
                    +Math.ceilDiv(tiled.pixels(),tiled.tilePixels())+"; budgeted tilePixels="+full.tilePixels()
                    +" tiles="+Math.ceilDiv(full.pixels(),full.tilePixels())+" scratchBytes="+full.bytes());
        } finally {if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);}
    }
    private static CudaOpticalMediumAbi.Plan connectionPlan(long limit) {
        return CudaOpticalMediumAbi.plan(1920,1080,1,3,CudaMediumDensityAbi.Scene.empty(),limit,
                true,true,true,true,false,false,false,true);
    }
    private static void verifyCounterModes(CudaOpticalMediumAbi.Plan plan,RenderFrameRequest frame,
            CudaBoundaryMediumAbi.Boundary boundary) {
        String previous=System.getProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
        String previousFused=System.getProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY);
        String previousBound=System.getProperty(CudaOpticalMediumAbi.BOUND_CONNECTION_RAYS_PROPERTY);
        try {
            for(boolean reduced:new boolean[]{false,true})for(boolean fused:new boolean[]{false,true})for(boolean bound:new boolean[]{false,true}) {
                System.setProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY,Boolean.toString(reduced));
                System.setProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY,Boolean.toString(fused));
                System.setProperty(CudaOpticalMediumAbi.BOUND_CONNECTION_RAYS_PROPERTY,Boolean.toString(bound));
                var data=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,frame,List.of(boundary),new int[0])).order(ByteOrder.nativeOrder());
                require(data.getInt(184)==(reduced?1:0),"counter mode is explicit in the optical header");
                require(((data.getInt(188)&128)!=0)==fused,"fused initialization mode is explicit in the optical header");
                require(((data.getInt(188)&2048)!=0)==bound,"connection ray range mode is explicit in the optical header");
                CudaOpticalMediumAbi.updateTile(data,plan,0,plan.pixels(),0,0);
                require(data.getInt(184)==(reduced?1:0)&&((data.getInt(188)&128)!=0)==fused&&((data.getInt(188)&2048)!=0)==bound,
                        "tile update preserves counter and fused initialization modes");
            }
            System.clearProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
            require(CudaOpticalMediumAbi.reducedCounters(),"block-reduced counters are enabled by default");
            System.clearProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY);
            require(CudaOpticalMediumAbi.fusedInitialization(),"fused initialization is enabled by default");
        } finally {
            if(previous==null)System.clearProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.REDUCED_COUNTERS_PROPERTY,previous);
            if(previousFused==null)System.clearProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.FUSED_INITIALIZATION_PROPERTY,previousFused);
            if(previousBound==null)System.clearProperty(CudaOpticalMediumAbi.BOUND_CONNECTION_RAYS_PROPERTY);
            else System.setProperty(CudaOpticalMediumAbi.BOUND_CONNECTION_RAYS_PROPERTY,previousBound);
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
    private static void verifyHeterogeneousScatteringAdmission(RenderFrameRequest frame) {
        var field=MediumDensityField.grid(50,1,AffineTransform.identity());
        var medium=new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.1f,.1f,.1f),Rgb.ZERO,1,1,0).withDensityField(field);
        var texture=TextureAsset.builder(50,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .pixelsRgba8(new byte[]{(byte)160,0,0,(byte)255}).build();
        var density=CudaMediumDensityAbi.prepare(medium,List.of(),id->texture);
        var scatteredFrame=frame.toBuilder()
                .environment(frame.environment().toBuilder().cameraMedium(medium).build())
                .mediumTransport(frame.mediumTransport().withScatteringBounces(2)).build();
        var plan=CudaOpticalMediumAbi.plan(137,101,8,0,density,512L*1024*1024);
        var packet=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,scatteredFrame,List.of(),new int[0],density))
                .order(ByteOrder.nativeOrder());
        require(packet.getInt(152)>=plan.priorityOffset()&&packet.getInt(156)==scatteredFrame.mediumTransport().scatteringSteps(),
                "heterogeneous scattering preserves its density descriptor and collision quadrature budget");
        CudaOpticalMediumProfile.requireSupported(scatteredFrame,new MediumSceneIndex.NativeScene(List.of(),new int[0]),true);
        var scatteringBoundary=new MediumSceneIndex.NativeBoundary(51,MediumBoundary.volume(medium),AffineTransform.identity());
        CudaOpticalMediumProfile.requireSupported(scatteredFrame,
                new MediumSceneIndex.NativeScene(List.of(scatteringBoundary),new int[0]),true);
        var refractive=new Medium(medium.absorption(),medium.scattering(),medium.emission(),medium.density(),1.5f,medium.anisotropy(),medium.densityField());
        var opticalBoundary=new CudaBoundaryMediumAbi.Boundary(51,MediumBoundary.surface(refractive).withPriority(-17));
        var boundaries=List.of(opticalBoundary);
        var localDensity=CudaMediumDensityAbi.prepare(medium,boundaries,id->texture);
        require(CudaOpticalMediumAbi.requiresConnections(scatteredFrame,boundaries),"explicit scattering must enable CUDA light connections");
        var connected=CudaOpticalMediumAbi.plan(137,101,8,1,localDensity,512L*1024*1024,
                true,true,true,false,true,false,true,true);
        var connectedData=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(connected,scatteredFrame,boundaries,new int[]{51},localDensity))
                .order(ByteOrder.nativeOrder());
        require(connected.connectionTransport()&&connectedData.getInt(72)==connected.connectionOffset()&&connectedData.getInt(76)==1,
                "connection scratch requires explicit producer/consumer header");
        require(connected.primaryOffset()==connected.connectionOffset()+(long)connected.tilePaths()*CudaOpticalMediumAbi.CONNECTION_BYTES,
                "connection scratch must not overlap primary or final output");
        require(connected.bytes()+65536<=512L*1024*1024,"connection scratch must preserve the public native resident cap");
        require(connectedData.getFloat(412)==1.5f&&connectedData.getFloat(428)==medium.anisotropy()
                        &&connectedData.getFloat(432)==.1f&&connectedData.getInt(388)==-17,
                "selected boundary keeps independent IOR, phase, scattering and signed priority");
        CudaOpticalMediumProfile.requireSupported(scatteredFrame,new MediumSceneIndex.NativeScene(List.of(
                new MediumSceneIndex.NativeBoundary(51,opticalBoundary.boundary(),AffineTransform.identity())),new int[]{51}),true);
        expectUnsupported(()->CudaOpticalMediumProfile.requireSupported(scatteredFrame,new MediumSceneIndex.NativeScene(List.of(
                new MediumSceneIndex.NativeBoundary(51,MediumBoundary.volume(refractive),AffineTransform.identity())),new int[0]),true));
        int basic=CudaOpticalMediumAbi.maximumRounds(scatteredFrame,20,false);
        require(CudaOpticalMediumAbi.maximumRounds(scatteredFrame,20,true)>basic,"light boundary continuation needs its complete round budget");
        var exhaustive=scatteredFrame.toBuilder().mediumTransport(scatteredFrame.mediumTransport().withLightSamples(0)).build();
        require(CudaOpticalMediumAbi.maximumRounds(exhaustive,20,true)>CudaOpticalMediumAbi.maximumRounds(exhaustive,2,true),
                "enumerated lights must expand rounds without dropping light slots");
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
        require(boundaryState.transposedStates()&&!boundaryState.compactStates()&&boundaryState.stateBytes()==256,
                "hybrid state preserves the complete 32-entry stack behind transposed hot headers");
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
        for(boolean transposedPrimary:new boolean[]{false,true})for(boolean transposedState:new boolean[]{false,true})for(boolean transposed:new boolean[]{false,true})for(boolean compactHits:new boolean[]{false,true})for(boolean compactStates:new boolean[]{false,true})for(int samples:new int[]{1,2,4,8}) {
            var layout=CudaOpticalMediumAbi.plan(1920,1080,samples,0,density,cap,true,true,compactHits,compactStates,transposed,transposedState,transposedPrimary);
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
            require(layout.transposedPrimary()==transposedPrimary&&((header.getInt(188)&32)!=0)==transposedPrimary,"full primary field layout is explicit and immutable");
            require(layout.transposedStates()==transposedState&&((header.getInt(188)&16)!=0)==layout.transposedStates(),"scalar hot state layout is explicit for compact and complete stacks");
            require(layout.transposedHits()==(transposed&&compactHits)&&((header.getInt(188)&8)!=0)==layout.transposedHits(),"transposed storage is explicit and limited to compact hits");
            require(header.getInt(4)==CudaOpticalMediumAbi.VERSION&&(header.getInt(188)&3)==layoutFlags,
                    "both producers use an explicit versioned hit layout");
            int first=(layout.pixels()-1)/layout.tilePixels()*layout.tilePixels();
            CudaOpticalMediumAbi.updateTile(header,layout,first,layout.pixels()-first,0,0);
            require(((header.getInt(188)&8)!=0)==layout.transposedHits(),"tail tiles preserve transposed producer/consumer layout");
            require((header.getInt(188)&3)==layoutFlags&&header.getInt(8)==(layout.pixels()-first)*samples,
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
    private static void verifyConnectionBatchStorage() {
        String property=CudaOpticalMediumAbi.BATCH_CONNECTION_HITS_PROPERTY,previous=System.getProperty(property);
        var density=CudaMediumDensityAbi.Scene.empty();
        var frame=RenderFrameRequest.builder(1,137,101,CameraState.lookAt(0,0,0,0,0,-1).build()).build();
        var boundary=new CudaBoundaryMediumAbi.Boundary(1,MediumBoundary.volume(
                new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0)));
        try {
            for(boolean enabled:new boolean[]{false,true})for(boolean compact:new boolean[]{false,true})
                    for(boolean transposed:new boolean[]{false,true})for(int samples:new int[]{1,2,8}) {
                System.setProperty(property,Boolean.toString(enabled));
                long cap=32L*1024*1024;
                var plan=CudaOpticalMediumAbi.plan(137,101,samples,1,density,cap,
                        true,true,compact,false,transposed,false,false,true);
                int planes=enabled?2:1;
                require(plan.connectionBatchSize()==planes,"connection batch mode must be explicit in storage planning");
                require(plan.bytes()+CudaOpticalMediumAbi.ALLOCATION_ALLOWANCE<=cap,"extra hits must not exceed the same resident cap");
                long planeBytes=(long)plan.tilePaths()*plan.hitBytes();
                require(plan.evaluationOffset()==plan.hitOffset()+planes*planeBytes,"all hit planes end before evaluation");
                require(plan.secondConnectionHitOffset()==(enabled?plan.hitOffset()+planeBytes:0),"second hit plane must be disjoint and optional");
                var request=frame.toBuilder().antiAliasing(samples==1?AntiAliasingState.disabled():AntiAliasingState.multisampled(samples)).build();
                var header=ByteBuffer.wrap(CudaOpticalMediumAbi.scene(plan,request,List.of(boundary),new int[0])).order(ByteOrder.nativeOrder());
                require(((header.getInt(188)&8192)!=0)==enabled&&header.getInt(228)==plan.secondConnectionHitOffset()
                        &&header.getInt(232)==planes,"both producers receive the same guarded batch metadata");
                int first=(plan.pixels()-1)/plan.tilePixels()*plan.tilePixels(),tail=plan.pixels()-first;
                CudaOpticalMediumAbi.updateTile(header,plan,first,tail,0,0);
                long tailBytes=(long)header.getInt(8)*plan.hitBytes();
                require(header.getInt(8)==tail*samples&&tailBytes<=planeBytes,"partial tiles use their live transposed stride");
                require(!enabled||header.getInt(228)+tailBytes<=plan.evaluationOffset(),"partial second plane cannot overrun into evaluation");
            }
            System.setProperty(property,"true");
            var disconnected=CudaOpticalMediumAbi.plan(137,101,1,1,density,32L*1024*1024,true,true,true,false);
            require(disconnected.connectionBatchSize()==1&&disconnected.secondConnectionHitOffset()==0,
                    "non-connection paths keep the original single-hit layout");
        } finally {if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);}
    }
    private static void verifyHybridVolumeStorage() {
        String property=CudaOpticalMediumAbi.HYBRID_VOLUME_STATES_PROPERTY,previous=System.getProperty(property);
        try {
            for(int samples:new int[]{1,2,8})for(int boundaries:new int[]{1,32}) {
                System.setProperty(property,"false");
                var original=CudaOpticalMediumAbi.plan(137,101,samples,boundaries,CudaMediumDensityAbi.Scene.empty(),3L*1024*1024,
                        true,true,true,true,true,true,true,true);
                System.setProperty(property,"true");
                var hybrid=CudaOpticalMediumAbi.plan(137,101,samples,boundaries,CudaMediumDensityAbi.Scene.empty(),3L*1024*1024,
                        true,true,true,true,true,true,true,true);
                require(!original.transposedStates()&&hybrid.transposedStates()&&!hybrid.compactStates(),"hybrid full-stack storage has an independent rollback switch");
                require(original.bytes()==hybrid.bytes()&&original.tilePixels()==hybrid.tilePixels()
                        &&original.hitOffset()==hybrid.hitOffset(),"hybrid headers add no capacity or tiling cost");
                long prefixEnd=hybrid.stateOffset()+112L*hybrid.tilePaths();
                require(prefixEnd+144L*hybrid.tilePaths()==hybrid.hitOffset(),"complete 32-entry stacks follow all hot headers without overlap");
            }
        } finally {if(previous==null)System.clearProperty(property);else System.setProperty(property,previous);}
    }
    private static void verifySurfaceProof(){
        var residency=new VulkanSceneResidency();var index=new VulkanSurfaceSceneIndex();long revision=0;
        var glass=MaterialAsset.builder(1).transmission(1).roughness(0).doubleSided(true).build();
        var mesh=MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1);
        var prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass).upsert(mesh).upsert(SceneInstance.builder(1,1).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalTerminalOnly(FramePrimitiveBatch.empty()),"fully transmitting smooth PBR is admitted without a diffuse-lighting path");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().roughness(.2f).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalTerminalOnly(FramePrimitiveBatch.empty()),"fully transmitting rough PBR uses the pinned microfacet sampler");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().transmission(.5f).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalTerminalOnly(FramePrimitiveBatch.empty()),"partial PBR transmission retains weighted surface lighting and native dielectric continuation");
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(glass.toBuilder().transmission(.5f).shadingModel(MaterialAsset.ShadingModel.UNLIT).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalTerminalOnly(FramePrimitiveBatch.empty()),"partial UNLIT transmission uses the retained terminal function");
        var receiver=MaterialAsset.builder(2).shadingModel(MaterialAsset.ShadingModel.PHYSICALLY_BASED)
                .roughness(.8f).metallic(0).doubleSided(true).build();
        var receiverMesh=MeshAsset.triangles(2,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},2);
        prepared=residency.prepare(SceneTransaction.builder(++revision).upsert(receiver).upsert(receiverMesh)
                .upsert(SceneInstance.builder(2,2).build()).build());
        residency.commit(prepared);index.sceneApplied(prepared.changeSet());
        require(index.opticalTerminalOnly(FramePrimitiveBatch.empty()),"opaque nonmetal PBR terminals compose with dielectric transport");
        require(index.hasCausticReceiver(FramePrimitiveBatch.empty()),"opaque nonmetal PBR terminals are indexed as caustic receivers");
    }
    private static void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    private static void expectIllegal(Runnable action){try{action.run();throw new AssertionError("expected invalid optical ABI");}catch(IllegalArgumentException expected){}}
    private static void expectUnsupported(Runnable action){try{action.run();throw new AssertionError("expected unsupported optical frame");}catch(FrameValidationException expected){require(expected.reason()==FrameValidationException.Reason.UNSUPPORTED_MEDIUM_TRANSPORT,"recoverable category");}}
}
