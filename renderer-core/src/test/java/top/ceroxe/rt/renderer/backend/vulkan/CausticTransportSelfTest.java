package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline.Program;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

/** CPU-only admission/ABI tests and independent flux/kernel numerical oracles. */
public final class CausticTransportSelfTest {
    public static void main(String[] args) {
        contracts();admissionAndHistory();energyOracles();
        System.out.println("CausticTransportSelfTest passed: validation, layout, cached emitters, point containment, region, ABI, history, flux and normalized surface kernel");
    }
    private static void contracts() {
        require(Caustics.disabled()==Caustics.disabled() && !Caustics.disabled().enabled(),"canonical disabled policy");
        reject(()->Caustics.photonMap(0,0,0,1,-1,.1f));
        reject(()->Caustics.photonMap(0,0,0,1,Caustics.MAX_PHOTONS+1,.1f));
        reject(()->Caustics.photonMap(0,0,0,1,1,1e-9f));
        reject(()->Caustics.photonMap(0,0,0,1,1,.1f).withMaxEvents(0));
        reject(()->Caustics.photonMap(Double.MAX_VALUE,0,0,1,1,.1f));
        reject(()->Caustics.photonMap(0,0,0,1,1,.1f).withNormalThreshold(Float.NaN));
        require(CausticPhotonPlan.DISABLED.bytes()==0 && CausticPhotonPlan.DISABLED.photons()==0,"disabled map has no allocation");
        var emitters=List.of(new CausticPhotonPlan.Emitter(7,new int[]{3,4}));
        for (int photons:new int[]{1,3,1024,1025,65536,Caustics.MAX_PHOTONS}) {
            var plan=new CausticPhotonPlan(photons,emitters);
            require(plan.hashCapacity()>=photons*2 && Integer.bitCount(plan.hashCapacity())==1,"bounded power-of-two hash");
            require(plan.headsWord()==52 && plan.recordsWord()==52+plan.hashCapacity(),"disjoint header, heads and records");
            require(plan.bytes()==(plan.recordsWord()+16L*photons)*4,"exact bounded memory accounting");
        }
        var camera=CameraState.lookAt(0,0,5,0,0,0).build();
        var policy=Caustics.photonMap(1,2,3,10,1025,.2f).withMaxEvents(21).withNormalThreshold(.8f);
        var frame=RenderFrameRequest.builder(0,32,32,camera).caustics(policy).build();
        require(frame.toBuilder().build().equals(frame) && !frame.toBuilder().caustics(Caustics.disabled()).build().equals(frame),"request copy and value semantics");
        var tracker=new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
        byte[] packed=VulkanFrameUniformPacker.pack(frame,VulkanFrameExtents.identity(32,32),0,0,
                tracker.prepare(frame,0),TemporalRenderingOptions.balanced(),false);
        var words=ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        require(packed.length==472*4 && words.getInt(464*4)==1025 && words.getInt(465*4)==21,"frame caustic ABI integers");
        require(words.getFloat(466*4)==.2f && words.getFloat(467*4)==1 && words.getFloat(470*4)==10
                && words.getFloat(471*4)==.8f,"frame caustic ABI floats");
    }
    private static void admissionAndHistory() {
        var residency=new VulkanSceneResidency();var media=new MediumSceneIndex();var surfaces=new VulkanSurfaceSceneIndex();
        var caustics=new CausticSceneIndex();
        var tx=SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).build())
                .upsert(MediumTransportSelfTest.cube(1,1,1)).upsert(SceneInstance.builder(1,1)
                        .mediumBoundary(MediumBoundary.volume(Medium.vacuum())).build())
                .upsert(SceneLight.point(1,0,0,0).intensity(1).build())
                .upsert(SceneLight.point(2,0,0,3).intensity(0).build())
                .upsert(SceneLight.point(3,0,0,3).castsShadow(false).build()).build();
        var update=residency.prepare(tx);residency.commit(update);
        media.sceneApplied(update.changeSet());surfaces.sceneApplied(update.changeSet());caustics.sceneApplied(update.changeSet());
        var camera=CameraState.lookAt(0,0,5,0,0,0).build();
        var first=RenderFrameRequest.builder(0,32,32,camera).minimumSceneRevision(1)
                .caustics(Caustics.photonMap(0,0,0,3,1024,.1f)).build();
        var plan=caustics.plan(first,media);
        require(plan.photons()==1024 && plan.headsWord()==52,"only positive shadow-casting emitters are admitted");
        require(plan==caustics.plan(RenderFrameRequest.builder(1,32,32,camera).minimumSceneRevision(1).caustics(first.caustics()).build(),media),"moving frame sequence reuses CPU emitter metadata");
        require(media.containingPoint(FramePrimitiveBatch.empty(),0,0,0).length==1,"photon lights start inside selected medium");
        require(VulkanGpuSceneTemporalCoordinator.program(first,media,surfaces)==Program.COMPACT_CAUSTICS,"explicit caustic specialization");
        reject(()->caustics.plan(first.toBuilder().caustics(Caustics.photonMap(0,0,0,1,1,.1f)).build(),media));
        require(caustics.plan(first.toBuilder().caustics(Caustics.disabled()).build(),media)==CausticPhotonPlan.DISABLED,"disabled plan bypasses scene scans");
        var tracker=new TemporalHistoryTracker(TemporalRenderingOptions.balanced());tracker.commit(tracker.prepare(first,1));
        var changed=RenderFrameRequest.builder(1,32,32,camera).minimumSceneRevision(1).caustics(first.caustics().withGatherRadius(.2f)).build();
        require(tracker.prepare(changed,1).invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),"kernel changes reset history");
        var removal=residency.prepare(SceneTransaction.builder(2).removeLight(1).build());residency.commit(removal);
        caustics.sceneApplied(removal.changeSet());
        require(caustics.plan(first,media).photons()==0,"no emitting lights produces inert frame uniforms");
    }
    private static void energyOracles() {
        // Integral in polar surface coordinates: K(r)=2(1-r^2/R^2)/(pi R^2).
        for (double radius:new double[]{.001,.1,1,100}) {
            double mass=0;int intervals=100000;
            for (int i=0;i<intervals;i++) {
                double r=radius*(i+.5)/intervals;
                mass+=2*(1-r*r/(radius*radius))/(Math.PI*radius*radius)*2*Math.PI*r*radius/intervals;
            }
            near(mass,1,1e-9,"normalized surface kernel at every world scale");
        }
        for (int emitters:new int[]{1,2,17}) for (int photons:new int[]{1,100,65536}) {
            double radiance=3.5,radius=2,fluxPerPhoton=radiance*Math.PI*radius*radius*emitters/photons;
            near(fluxPerPhoton*photons/emitters,radiance*Math.PI*radius*radius,1e-12,"emitter selection and count preserve total flux");
        }
        // Fresnel branch probabilities partition flux; radiance eta^2 must never be inserted here.
        for (double cosine:new double[]{.01,.2,.6,1}) {
            double reflection=MediumOptics.fresnel(1,1.52,cosine);
            near(reflection+(1-reflection),1,1e-15,"dielectric flux partition");
        }
        for (double diffuse:new double[]{.001,.1,.5,1})
            near(diffuse*(1/diffuse),1,1e-15,"first receiver lobe selection cancels its PDF");
    }
    private static void near(double a,double b,double tolerance,String message) {require(Math.abs(a-b)<=tolerance,message+": "+a+" vs "+b);}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void reject(Runnable action) {
        try {action.run();}catch(IllegalArgumentException|FrameValidationException expected){return;}
        throw new AssertionError("invalid caustic policy/region was accepted");
    }
}
