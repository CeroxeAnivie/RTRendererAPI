package top.ceroxe.rt.renderer.rtxpt;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** CPU-only adapter contract: no GPU and no native loading. */
public final class RtxptAdapterSelfTest {
    static SceneTransaction scene(long revision) {
        return scene(revision,new Medium(new Rgb(.2f,.6f,.8f),1.5f));
    }
    static SceneTransaction scene(long revision,Medium medium) {
        var material=MaterialAsset.builder(1).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1).roughness(.04f).indexOfRefraction(1.5f).build();
        float[] p={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
        int[] idx={4,5,6,4,6,7,0,2,1,0,3,2,0,4,7,0,7,3,1,2,6,1,6,5,3,7,6,3,6,2,0,1,5,0,5,4};
        long[] materials=new long[12];Arrays.fill(materials,1);
        return SceneTransaction.builder(revision).resetScene().upsert(material).upsert(MeshAsset.builder(1,p,idx,materials).build())
            .upsert(SceneInstance.builder(1,1).mediumBoundary(MediumBoundary.surface(medium).withPriority(4)).build()).build();
    }
    static CameraState camera(){return CameraState.lookAt(0,1,6,0,0,0).verticalFieldOfViewDegrees(55).aspectRatio(16d/9).build();}
    static RenderFrameRequest frame(long sequence){return RenderFrameRequest.builder(sequence,1920,1080,camera()).minimumSceneRevision(1)
        .environment(EnvironmentState.builder().skyRadiance(.4f,.6f,.9f).ambientIntensity(1).sunDirection(0,-1,0).sunIntensity(4).build()).build();}
    public static void main(String[] args) throws Exception {
        RtxptLightingSelfTest.run();
        inputContractRejectsIgnoredControls();
        featureFallbackRecoversWithoutLosingIntent();
        var s=new RtxptScene();s.retain(scene(1));var frame=frame(0);var items=s.items(frame);
        var mesh=s.meshes.get(1L);var expanded=RtxptScene.ExportGeometry.from(mesh);
        require(expanded.vertexCount()==36&&expanded.normals().remaining()==108,"Missing normals become flat triangle-corner normals");
        for(int corner=0;corner<36;corner++) {
            int original=mesh.triangleIndices().get(corner);
            require(expanded.indices().get(corner)==corner,"Expanded triangle order is preserved");
            for(int axis=0;axis<3;axis++)require(expanded.positions().get(corner*3+axis)==mesh.positions().get(original*3+axis),"Expanded positions are unchanged");
            float nx=expanded.normals().get(corner*3),ny=expanded.normals().get(corner*3+1),nz=expanded.normals().get(corner*3+2);
            require(Math.abs(nx*nx+ny*ny+nz*nz-1)<1e-6,"Every fallback normal is a unit vector");
        }
        require(expanded.normals().get(2)==1&&expanded.normals().get(20)==-1,"Flat normals follow outward triangle winding");
        float[] authoredNormals=new float[mesh.vertexCount()*3];java.util.Arrays.fill(authoredNormals,1);
        require(RtxptScene.ExportGeometry.from(mesh.toBuilder().normals(authoredNormals).build()).vertexCount()==mesh.vertexCount(),"Authored normals keep indexed geometry");
        Path dir=Files.createTempDirectory("rtxpt-adapter-test-");try {
            Path file=s.export(dir,frame,items);String json=Files.readString(file),gltf=Files.readString(dir.resolve("content.gltf"));
            require(json.contains("EnvironmentLight")&&json.contains("DirectionalLight"),"Native light graph");
            require(gltf.contains("KHR_materials_transmission")&&gltf.contains("KHR_materials_ior"),"Native transmission and IOR");
            require(gltf.contains("\"name\":\"i1\"")&&s.interiors.size()==1,"Interior assigned to imported material");
            require(s.materialIors.equals(Map.of(new RtxptScene.Variant(1,items.getFirst().boundary(),1).name(),1.5f)),"Boundary IOR is retained for native material upload");
            var opaque=new RtxptScene(s);
            opaque.retain(SceneTransaction.builder(2)
                .upsert(MaterialAsset.builder(1).transmission(0).indexOfRefraction(2.618034f).build())
                .upsert(SceneInstance.builder(1,1).build()).build());
            opaque.export(dir.resolve("opaque"),frame,opaque.items(frame));
            require(opaque.interiors.isEmpty(),"Opaque IOR does not invent a medium boundary");
            require(opaque.materialIors.equals(Map.of(new RtxptScene.Variant(1,null,1).name(),2.618034f)),"Opaque IOR survives export for native BSDF upload");
            require(Files.size(dir.resolve("content.bin"))>0,"Geometry is exported");
            require(gltf.contains("\"NORMAL\""),"Donut always receives explicit normals");
            require(RtxptScene.sameStructure(items,items),"Static scene does not reload");
            var moved=List.of(new RtxptScene.Item(items.getFirst().name(),1,new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0}),items.getFirst().boundary(),1));
            require(RtxptScene.sameStructure(items,moved),"Transform edits do not reload assets");
            var profile=RtxptOptions.profile(RendererConfig.pathTracedPresentation());
            require(RtxptOptions.resolve(profile).reconstruction()==RtxptAccess.Reconstruction.DLSS_RR,"Ordinary API selects native ray reconstruction");
            var rrUltraQuality=profile.toBuilder().frameReconstruction(profile.frameReconstruction().toBuilder()
                .quality(FrameReconstructionOptions.Quality.ULTRA_QUALITY).build()).build();
            rejected(()->RtxptOptions.resolve(rrUltraQuality),
                "RTXPT RR Ultra Quality is rejected before native pipeline creation");
            var srUltraQuality=RtxptOptions.resolve(profile.toBuilder().denoising(DenoisingOptions.disabled())
                .frameReconstruction(profile.frameReconstruction().toBuilder()
                    .quality(FrameReconstructionOptions.Quality.ULTRA_QUALITY).build()).build());
            require(srUltraQuality.reconstruction()==RtxptAccess.Reconstruction.DLSS
                &&srUltraQuality.dlssQuality()==RtxptOptions.DlssQuality.ULTRA_QUALITY,
                "Ultra Quality remains available for the independent DLSS SR path");
            var diGi=new PathTracingOptions(1,20,2,RendererFeaturePreference.DISABLED,
                RendererFeaturePreference.REQUIRED,RendererFeaturePreference.REQUIRED);
            var giSharc=new PathTracingOptions(1,20,2,RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.DISABLED,RendererFeaturePreference.REQUIRED);
            var diGiOptions=RtxptOptions.resolve(profile.toBuilder().pathTracing(diGi).build());
            require(diGiOptions.restirDi()&&diGiOptions.restirGi(),
                "RTXPT ReSTIR DI and GI remain independently composable without SHaRC");
            var giSharcOptions=RtxptOptions.resolve(profile.toBuilder().pathTracing(giSharc).build());
            require(giSharcOptions.restirGi()&&giSharcOptions.sharc(),
                "RTXPT ReSTIR GI and SHaRC remain independently composable without DI");
            require(giSharcOptions.reconstruction()==RtxptAccess.Reconstruction.DLSS,
                "ReSTIR GI retains the tested standalone-NRD and DLSS SR path");
            var diSharc=new PathTracingOptions(1,20,2,RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.REQUIRED,RendererFeaturePreference.DISABLED);
            var diSharcOptions=RtxptOptions.resolve(profile.toBuilder().pathTracing(diSharc).build());
            require(diSharcOptions.restirDi()&&diSharcOptions.sharc()
                &&diSharcOptions.reconstruction()==RtxptAccess.Reconstruction.DLSS
                &&diSharcOptions.standaloneDenoiser(),
                "ReSTIR DI plus SHaRC retains the measured standalone-NRD and DLSS SR path");
            var allReuse=new PathTracingOptions(1,20,2,RendererFeaturePreference.REQUIRED,
                RendererFeaturePreference.REQUIRED,RendererFeaturePreference.REQUIRED);
            rejected(()->RtxptOptions.resolve(profile.toBuilder().pathTracing(allReuse).build()),
                "RTXPT DI+GI+SHaRC is rejected before native shader compilation");
            for(boolean standalone:new boolean[]{false,true}) {
                var reconstruction=standalone?RtxptAccess.Reconstruction.DLSS:RtxptAccess.Reconstruction.DLSS_RR;
                var actual=new RtxptAccess.RuntimeStatus(1280,720,reconstruction,standalone,true,false,false,true,
                    false,true,true,3,1,3,true,true,true,true,true,false,4,false);
                var capabilities=RtxptRenderer.featureCapabilities(profile,actual);
                String implementation=standalone?"nvidia.nrd":"nvidia.dlss_rr";
                var denoising=capabilities.feature(RenderingFeatureCapabilities.Feature.DENOISING);
                require(denoising.status()==RenderingFeatureCapabilities.Status.ACTIVE
                    &&denoising.implementation().equals(implementation),"Common denoising names the actual NRD/RR implementation");
                require(capabilities.technology(RenderingFeatureCapabilities.Technology.RAY_TRACING_DENOISING)
                    .implementation().equals(implementation),"Feature and technology agree on the active denoiser");
                require(RtxptRenderer.featureCapabilities(RendererFeatureProfile.disabled(),actual)
                    .feature(RenderingFeatureCapabilities.Feature.DENOISING).status()==RenderingFeatureCapabilities.Status.DISABLED,
                    "Unrequested denoising remains disabled in the common capability contract");
            }
            var responsiveProfile=profile.toBuilder().denoising(profile.denoising().toBuilder()
                .strategy(DenoisingOptions.Strategy.RESPONSIVE).build()).build();
            var responsive=RtxptOptions.resolve(responsiveProfile);
            require(responsive.reconstruction()==RtxptAccess.Reconstruction.DLSS&&responsive.standaloneDenoiser()
                &&responsive.relaxDenoiser(),"Responsive denoising uses native RELAX followed by DLSS SR");
            require(!responsive.restirDi()&&!responsive.restirGi(),"RELAX does not silently enable ReSTIR DI/GI");
            require(RtxptOptions.resolve(responsiveProfile.toBuilder().frameReconstruction(FrameReconstructionOptions.disabled()).build())
                .reconstruction()==RtxptAccess.Reconstruction.NONE,"Responsive native denoising does not force upscaling");
            require(!RtxptOptions.resolve(profile).relaxDenoiser(),"Balanced keeps the existing RR/default NRD policy");
            var taaOnly=new RtxptAccess.RuntimeStatus(1920,1080,RtxptAccess.Reconstruction.TAA,false,false,false,false,false,
                false,false,false,6,1,1,false,false,false,false,false,false,4,false);
            require(!RtxptRenderer.requestedReconstructionActive(profile.frameReconstruction(),taaOnly),
                "Upstream TAA fallback must not pass the required DLSS SR execution check");
            require(RtxptRenderer.featureCapabilities(profile,taaOnly).feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION)
                .status()==RenderingFeatureCapabilities.Status.NOT_SUPPORTED,"Unavailable DLSS is not mislabeled active");
            var taaFallback=profile.toBuilder().frameReconstruction(profile.frameReconstruction().toBuilder()
                .fallback(FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL).build()).build();
            var fallback=RtxptRenderer.featureCapabilities(taaFallback,taaOnly).feature(RenderingFeatureCapabilities.Feature.FRAME_RECONSTRUCTION);
            require(fallback.status()==RenderingFeatureCapabilities.Status.FALLBACK&&fallback.implementation().equals("nvidia.rtxpt.taa"),
                "An explicitly allowed TAA fallback reports its actual implementation");
            require(RtxptOptions.resolve(RendererFeatureProfile.disabled()).reconstruction()==RtxptAccess.Reconstruction.NONE,"Disabled common features stay disabled");
            for(var multiplier:FrameGenerationOptions.Multiplier.values()) {
                var fg=FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                    .mode(multiplier==FrameGenerationOptions.Multiplier.TWO_X?FrameGenerationOptions.Mode.FRAME_GENERATION:FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION)
                    .multiplier(multiplier).fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build();
                var resolved=RtxptOptions.resolve(RendererFeatureProfile.disabled().toBuilder().frameGeneration(fg).build());
                require(resolved.frameGeneration().equals(fg)&&resolved.generatedFrames()==multiplier.presentedFramesPerNativeFrame()-1,
                    "Fixed DLSS FG cadence maps to RTXPT's native control");
            }
            var adaptive=FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.ADAPTIVE).multiplier(FrameGenerationOptions.Multiplier.FOUR_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build();
            require(RtxptOptions.normalize(RendererFeatureProfile.disabled().toBuilder().frameGeneration(adaptive).build())
                .frameGeneration().preference()==RendererFeaturePreference.DISABLED,"Unsupported adaptive cadence falls back to native presents");
            var requiredAdaptive=FrameGenerationOptions.builder().preference(RendererFeaturePreference.REQUIRED)
                .mode(FrameGenerationOptions.Mode.ADAPTIVE).multiplier(FrameGenerationOptions.Multiplier.FOUR_X).build();
            boolean rejectedAdaptive=false;
            try { RtxptOptions.resolve(RendererFeatureProfile.disabled().toBuilder().frameGeneration(requiredAdaptive).build()); }
            catch(IllegalArgumentException expected) { rejectedAdaptive=true; }
            require(rejectedAdaptive,"Required adaptive MFG is rejected instead of being misreported as fixed-rate generation");
            var live=new boolean[1];var applies=new int[1];
            var control=new RtxptFeatureController(new RtxptFeatureController.Host(){public boolean initialized(){return live[0];}public void ready(){}public void apply(RendererFeatureProfile p){applies[0]++;}
                public RendererFeatureProfile effective(RendererFeatureProfile p){return p;}public RendererFeatureProfile normalize(RendererFeatureProfile p){return RtxptOptions.normalize(p);}},RtxptOptions.normalize(profile));
            var off=control.plan(RendererFeatureProfile.disabled());require(control.apply(off).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Expert API applies the same policy before initialization");
            require(control.apply(off).outcome()==RendererFeatureApplyResult.Outcome.STALE_PLAN,"Expert plans are single-use");
            live[0]=true;var rebuild=control.plan(profile);require(control.apply(rebuild).outcome()==RendererFeatureApplyResult.Outcome.REQUIRES_RENDERER_REBUILD,"No hidden upstream pipeline rebuild");
            var latency=control.plan(control.effectiveProfile().toBuilder().lowLatency(LowLatencyOptions.recommended()).build());
            require(control.apply(latency).outcome()==RendererFeatureApplyResult.Outcome.APPLIED&&applies[0]==2,"Live expert latency control uses the common protocol");
            var fixedFg=FrameGenerationOptions.builder().preference(RendererFeaturePreference.PREFERRED)
                .mode(FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION).multiplier(FrameGenerationOptions.Multiplier.THREE_X)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES).build();
            var fgPlan=control.plan(control.effectiveProfile().toBuilder().frameGeneration(fixedFg).build());
            require(fgPlan.disposition()==RendererFeaturePlan.Disposition.APPLICABLE,"Fixed DLSS FG is a live Streamline control");
            require(control.apply(fgPlan).outcome()==RendererFeatureApplyResult.Outcome.APPLIED&&applies[0]==3,
                "Fixed DLSS FG applies through the expert controller");
            require(Medium.class.getRecordComponents().length==2,"No unsupported medium knobs");
            require(MediumBoundary.class.getRecordComponents().length==2,"No invisible/inward compatibility knobs");
            var colored=new RtxptScene();colored.retain(scene(1));
            colored.retain(SceneTransaction.builder(2).upsert(MaterialAsset.builder(1)
                .baseColorRgba8(0xff0000ff).emissive(0xffff0000,2).build())
                .upsert(SceneLight.point(9,1,2,3).range(10.5f).intensity(310).build()).build());
            Path colorDir=dir.resolve("rgba-contract");
            String lightJson=Files.readString(colored.export(colorDir,frame,colored.items(frame)));
            String colorJson=Files.readString(colorDir.resolve("content.gltf"));
            require(colorJson.contains("\"baseColorFactor\":[1.0,0.0,0.0,1.0]"),"RGBA8 low byte is red, matching Vulkan");
            require(colorJson.contains("\"emissiveFactor\":[0.0,0.0,1.0]"),"Emissive RGBA8 keeps red/blue channel order");
            require(lightJson.contains("\"radius\":0,\"range\":10.5"),"Point light keeps the authored range and zero emitter radius");
            require(!lightJson.contains("NaN")&&!lightJson.contains("Infinity"),"Directionless point lights export finite identity rotations");
        } finally {try(var walk=Files.walk(dir)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println("RTXPT adapter contracts PASS");
    }
    private static void inputContractRejectsIgnoredControls() {
        var config=RendererConfig.pathTracedPresentation();
        RtxptInputContract.validateConfiguration(config);
        rejected(()->RtxptInputContract.validateConfiguration(config.copyBuilder()
            .sceneShaders(SceneShaderProgram.builder().shader(ShaderStage.RAY_MISS,"#version 460\nvoid main() {}").build()).build()),
            "Custom shaders are rejected before native initialization");
        rejected(()->RtxptInputContract.validateConfiguration(config.copyBuilder()
            .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F).build()),"HDR export cannot silently become SDR presentation");
        rejected(()->RtxptInputContract.validateConfiguration(config.copyBuilder().validationEnabled(true).build()),
            "Unimplemented validation control is rejected");
        RtxptInputContract.validateFrame(frame(0));
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().antiAliasing(AntiAliasingState.multisampled(4)).build()),
            "Frame-local deterministic multisampling cannot silently become one sample");
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().lightmap(new LightmapState(1,new int[LightmapState.ENTRY_COUNT])).build()),
            "Authored lightmap cannot silently disappear");
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().textureSampling(TextureSamplingState.anisotropic(8)).build()),
            "Frame-local texture sampling cannot silently disappear");
        rejected(()->RtxptInputContract.validateFrame(frame(0).toBuilder().resetTemporalHistory(HistoryResetReason.EXPLICIT_RESET).build()),
            "Unsupported explicit temporal reset cannot be accepted as executed");
        RtxptInputContract.requireAdvancingRevision(0,0,false);
        RtxptInputContract.requireAdvancingRevision(1,0,true);
        try { RtxptInputContract.requireAdvancingRevision(1,1,true);throw new AssertionError("Duplicate scene revision accepted"); }
        catch(SceneRevisionException expected) { }
        try { RtxptInputContract.requireAdvancingRevision(0,1,true);throw new AssertionError("Stale scene revision accepted"); }
        catch(SceneRevisionException expected) { }
    }
    static void rejected(Runnable action,String message) {
        try { action.run();throw new AssertionError(message); }
        catch(IllegalArgumentException expected) { }
    }
    private static void featureFallbackRecoversWithoutLosingIntent() {
        boolean[] fallback={false};
        var controller=new RtxptFeatureController(new RtxptFeatureController.Host() {
            public boolean initialized(){return true;}
            public void ready(){ }
            public void apply(RendererFeatureProfile profile){ }
            public RendererFeatureProfile normalize(RendererFeatureProfile profile){return RtxptOptions.normalize(profile);}
            public RendererFeatureProfile effective(RendererFeatureProfile profile){
                return fallback[0]?profile.toBuilder().frameGeneration(FrameGenerationOptions.disabled()).build():profile;
            }
        },RendererFeatureProfile.disabled());
        var enabled=RendererFeatureProfile.disabled().toBuilder().frameGeneration(FrameGenerationOptions.recommended()).build();
        var enable=controller.plan(enabled);
        require(controller.apply(enable).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Initial FG enable applies");
        require(controller.featureControlDiagnostics().latestResult().isPresent(),"Applied result is observable at its generation");
        fallback[0]=true;
        require(!controller.effectiveProfile().frameGeneration().preference().requested(),"Native fallback is reported honestly");
        var diagnostics=controller.featureControlDiagnostics();
        require(diagnostics.effectiveProfile().equals(controller.effectiveProfile())&&diagnostics.latestResult().isEmpty(),
            "Diagnostics remain valid after runtime state changes instead of retaining a mismatched result");
        fallback[0]=false;
        require(controller.effectiveProfile().equals(enabled),"Recovery resolves committed intent, not the disabled fallback snapshot");
        var pending=controller.plan(enabled);
        fallback[0]=true;
        require(controller.apply(pending).outcome()==RendererFeatureApplyResult.Outcome.STALE_PLAN,
            "A runtime state change between plan and apply invalidates the old generation");
        var disable=controller.plan(RendererFeatureProfile.disabled());
        require(disable.disposition()==RendererFeaturePlan.Disposition.APPLICABLE,
            "Explicit disable while temporarily off still updates the configured intent");
        require(controller.apply(disable).outcome()==RendererFeatureApplyResult.Outcome.APPLIED,"Explicit disable applies during fallback");
        fallback[0]=false;
        require(!controller.effectiveProfile().frameGeneration().preference().requested(),"Disabled intent stays disabled when native fallback ends");
        require(controller.featureControlDiagnostics().latestResult().isPresent(),"Stable generation keeps its terminal result");
        var autoQuality=RendererFeatureProfile.disabled().toBuilder().frameReconstruction(FrameReconstructionOptions.recommended()).build();
        var negotiated=new RtxptFeatureController(new RtxptFeatureController.Host() {
            public boolean initialized(){return true;}
            public void ready(){ }
            public void apply(RendererFeatureProfile profile){ }
            public RendererFeatureProfile normalize(RendererFeatureProfile profile){return RtxptOptions.normalize(profile);}
            public RendererFeatureProfile effective(RendererFeatureProfile profile){
                return profile.toBuilder().frameReconstruction(profile.frameReconstruction().toBuilder()
                    .quality(FrameReconstructionOptions.Quality.BALANCED).build()).build();
            }
        },autoQuality);
        var target=negotiated.effectiveProfile().toBuilder().frameGeneration(FrameGenerationOptions.recommended()).build();
        require(negotiated.plan(target).disposition()==RendererFeaturePlan.Disposition.APPLICABLE,
            "Actual AUTO-to-BALANCED negotiation must not force a renderer rebuild for a live FG change");
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
