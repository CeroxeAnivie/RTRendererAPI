package top.ceroxe.rt.renderer.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** CPU-only tests of actual reference routing, provider isolation and fixed-session ownership. */
public final class RendererSceneRoutingSelfTest {
    private RendererSceneRoutingSelfTest() { }

    public static void main(String[] args) {
        selectsOnlyActualOpticalWork();
        opensOnlyChosenProviderAndConfig();
        cleansUpFailedInitialization();
        rejectsCategoryChangesBeforeMutation();
        delegatesExpertExtensionsAndLifecycle();
        System.out.println("RendererSceneRoutingSelfTest passed");
    }

    private static MaterialAsset glass(long id) {
        return MaterialAsset.builder(id).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).transmission(1).indexOfRefraction(1.5f).build();
    }
    private static MeshAsset mesh(long id,long material) {
        return MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{material}).build();
    }
    private static SceneTransaction scene(boolean transmission,boolean boundary) {
        var instance=SceneInstance.builder(1,1);
        if(boundary)instance.mediumBoundary(MediumBoundary.surface(new Medium(new Rgb(.1f,.2f,.3f),1.5f)));
        return SceneTransaction.builder(1).resetScene()
            .upsert(transmission?glass(1):MaterialAsset.opaque(1,0xffffffff))
            .upsert(glass(2)).upsert(mesh(1,1)).upsert(mesh(2,2)).upsert(instance.build()).build();
    }
    private static RenderFrameRequest frame(long sequence,FramePrimitiveBatch primitives) {
        return RenderFrameRequest.builder(sequence,32,32,CameraState.lookAt(0,0,3,0,0,0).build())
            .minimumSceneRevision(1).primitiveBatch(primitives).build();
    }
    private static RenderFrameRequest frame(long sequence) { return frame(sequence,FramePrimitiveBatch.empty()); }

    private static void selectsOnlyActualOpticalWork() {
        var policy=RendererBackendPolicy.RTXPT_FOR_MEDIA;
        require(policy.select(scene(false,false),frame(0)).backend()==RendererBackendPolicy.Backend.VULKAN,
            "Unused transparent material and mesh do not select RTXPT");
        require(policy.select(scene(true,false),frame(0)).backend()==RendererBackendPolicy.Backend.RTXPT,
            "Referenced transmissive material selects RTXPT even without an explicit boundary");
        require(policy.select(scene(false,true),frame(0)).mediaPresent(),"Explicit persistent medium selects RTXPT");
        var primitive=PrimitiveInstance.builder(1).mediumBoundary(MediumBoundary.surface(Medium.vacuum())).build();
        require(policy.select(scene(false,false),frame(0,FramePrimitiveBatch.of(List.of(primitive)))).mediaPresent(),
            "Frame-local boundary participates in initial selection");
        require(policy.select(scene(false,false),frame(0,FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(2).build())))).mediaPresent(),
            "Frame-local material transmission participates in selection");
        require(RendererBackendPolicy.RTXPT_ONLY.select(scene(false,false),frame(0)).backend()==RendererBackendPolicy.Backend.RTXPT,
            "RTXPT-only owns ordinary surfaces too");
        require(RendererBackendPolicy.VULKAN_ONLY.select(scene(true,true),frame(0)).backend()==RendererBackendPolicy.Backend.VULKAN,
            "Explicit Vulkan never redirects to RTXPT; provider retains its validation contract");
        expect(IllegalArgumentException.class,()->policy.select(SceneTransaction.empty(1),frame(0)));
        expect(IllegalArgumentException.class,()->policy.select(SceneTransaction.builder(1).resetScene()
            .upsert(SceneInstance.builder(1,99).build()).build(),frame(0)));
        expect(IllegalArgumentException.class,()->policy.select(scene(false,false),frame(0,
            FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(99).build())))));
    }

    private static void opensOnlyChosenProviderAndConfig() {
        var vulkan=RendererConfig.recommended();var rtxpt=RendererConfig.pathTracedPresentation();
        for(var policy:RendererBackendPolicy.values()) {
            var fake=new FakeRenderer();var opens=new AtomicInteger();
            var selected=policy.select(scene(false,false),frame(0));
            try(var session=RendererSceneSession.open(policy,scene(false,false),frame(0),vulkan,rtxpt,(provider,config)-> {
                opens.incrementAndGet();
                require(provider.equals(selected.backend().providerId()),"Exact chosen provider");
                require(config==(selected.backend()==RendererBackendPolicy.Backend.RTXPT?rtxpt:vulkan),"Only selected config is opened");
                return fake;
            })) {
                require(opens.get()==1&&fake.applies==1&&fake.frames==1,"One open, one initial publication, one initial frame");
                require(session.initialSubmission().frameSequence()==0,"First-frame admission is observable");
                require(session.selection().equals(selected),"Selection reason is observable");
            }
            require(fake.closed,"Session owns selected renderer lifetime");
        }
    }

    private static void cleansUpFailedInitialization() {
        var fake=new FakeRenderer();fake.failSubmit=true;
        expect(IllegalStateException.class,()->RendererSceneSession.open(RendererBackendPolicy.RTXPT_ONLY,
            scene(false,false),frame(0),RendererConfig.recommended(),RendererConfig.pathTracedPresentation(),(provider,config)->fake));
        require(fake.closed,"Failed initial submission closes the opened provider");
        var calls=new AtomicInteger();
        expect(IllegalStateException.class,()->RendererSceneSession.open(RendererBackendPolicy.RTXPT_ONLY,
            scene(false,false),frame(0),RendererConfig.recommended(),RendererConfig.pathTracedPresentation(),(provider,config)-> {
                calls.incrementAndGet();throw new IllegalStateException("Synthetic unavailable provider");
            }));
        require(calls.get()==1,"Failure never tries another backend");
    }

    private static void rejectsCategoryChangesBeforeMutation() {
        var fake=new FakeRenderer();
        try(var session=open(RendererBackendPolicy.RTXPT_FOR_MEDIA,scene(false,false),fake)) {
            var change=SceneTransaction.builder(2).upsert(SceneInstance.builder(1,2).build()).build();
            expect(IllegalStateException.class,()->session.apply(change));
            require(fake.applies==1,"Routing rejection occurs before provider scene mutation");
            var mediumFrame=frame(1,FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(2).build())));
            expect(IllegalStateException.class,()->session.submit(mediumFrame));
            expect(IllegalStateException.class,()->session.trySubmit(mediumFrame));
            expect(IllegalStateException.class,()->session.submitWorkload(RenderWorkload.rayTracing(mediumFrame)));
            require(fake.frames==1,"No frame reaches wrong backend");
            session.submit(frame(1));
            require(fake.frames==2,"Failed category change leaves the old scene usable");
        }
        var medium=new FakeRenderer();
        try(var session=open(RendererBackendPolicy.RTXPT_FOR_MEDIA,scene(true,false),medium)) {
            session.apply(SceneTransaction.builder(2).upsert(MaterialAsset.opaque(1,0xffffffff)).build());
            expect(IllegalStateException.class,()->session.submit(frame(1)));
            require(medium.frames==1,"Removing media requires an explicit new Vulkan session");
        }
        var forced=new FakeRenderer();
        try(var session=open(RendererBackendPolicy.RTXPT_ONLY,scene(false,false),forced)) {
            session.apply(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,2).build()).build());
            session.submit(frame(1));
            require(forced.frames==2,"RTXPT-only remains fixed across scene categories");
        }
    }

    private static void delegatesExpertExtensionsAndLifecycle() {
        var fake=new FakeRenderer();var session=open(RendererBackendPolicy.VULKAN_ONLY,scene(false,false),fake);
        require(session.extension(FakeRenderer.class).orElseThrow()==fake,"Expert extension identity is preserved");
        require(session.extension(RendererSceneSession.class).orElseThrow()==session,"Session itself is discoverable");
        session.closeAsync().toCompletableFuture().join();
        require(session.status()==Renderer.Status.CLOSED,"Async close follows provider lifetime");
        expect(IllegalStateException.class,()->session.submit(frame(1)));
    }

    private static RendererSceneSession open(RendererBackendPolicy policy,SceneTransaction scene,FakeRenderer fake) {
        return RendererSceneSession.open(policy,scene,frame(0),RendererConfig.recommended(),RendererConfig.pathTracedPresentation(),(provider,config)->fake);
    }
    private static void require(boolean test,String message) { if(!test)throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try { action.run(); } catch(Throwable failure) {
            if(type.isInstance(failure))return;
            throw new AssertionError("Expected "+type+", got "+failure,failure);
        }
        throw new AssertionError("Expected "+type);
    }

    private static final class FakeRenderer implements Renderer {
        boolean closed,failSubmit;
        int applies,frames;
        long revision;
        @Override public Status status() { return closed?Status.CLOSED:Status.READY; }
        @Override public RendererHealth health() { return new RendererHealth(status(),Optional.empty(),RendererHealth.ResourceObligations.none()); }
        @Override public RendererDiagnostics diagnostics() { return RendererDiagnostics.builder().status(status()).build(); }
        @Override public SceneUpdateResult apply(SceneTransaction transaction) { applies++;revision=transaction.revision();return new SceneUpdateResult(revision); }
        @Override public FrameSubmissionResult submit(RenderFrameRequest request) {
            if(failSubmit)throw new IllegalStateException("Synthetic initial admission failure");
            frames++;return FrameSubmissionResult.accepted(request.sequence(),revision,Set.of());
        }
        @Override public FrameSubmissionAttempt trySubmit(RenderFrameRequest request) { return new FrameSubmitted(submit(request)); }
        @Override public boolean readFrame(FrameConsumer consumer) { return false; }
        @Override public Optional<FrameSnapshot> pollFrame() { return Optional.empty(); }
        @Override public Optional<FrameSnapshot> awaitFrame(Duration timeout) { return Optional.empty(); }
        @Override public ResourceTransactionEvidence submitResources(RenderResourceTransaction tx) { return new ResourceTransactionEvidence(tx.revision(),ResourceTransactionEvidence.Outcome.REJECTED,List.of(),"Synthetic no executor"); }
        @Override public Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey key) { return Optional.empty(); }
        @Override public RenderingSemanticCapabilities renderingSemanticCapabilities() { return RenderingSemanticCapabilities.unsupported(); }
        @Override public CommandExecutionEvidence submitCommands(RenderCommandTransaction tx) { return new CommandExecutionEvidence(tx.sequence(),CommandExecutionEvidence.Outcome.REJECTED,CommandExecutionEvidence.Reason.UNSUPPORTED_FEATURE,OptionalLong.empty(),Optional.empty(),0,"Synthetic no executor"); }
        @Override public Optional<CommandExecutionEvidence> commandExecutionEvidence(long sequence) { return Optional.empty(); }
        @Override public void close() { closed=true; }
    }
}
