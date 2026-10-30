package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneRayTracingPipeline.Program;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

/** CPU-only resource, density-coordinate, packing and specialization contracts. */
public final class MediumDensitySelfTest {
    private static final AffineTransform IDENTITY = AffineTransform.identity();
    private static final CameraState CAMERA = CameraState.lookAt(0,0,3,0,0,0).build();
    private static final MediumDensityField FIELD = MediumDensityField.grid(500,2,
            new AffineTransform(new float[]{.5f,0,0,.5f,0,.25f,0,.5f,0,0,1,.5f}));
    private static final Medium GRID = new Medium(new Rgb(.1f,.2f,.3f),new Rgb(.2f,.1f,.05f),
            new Rgb(.01f,0,0),1,1,0,FIELD);

    public static void main(String[] args) {
        verifyPacking();
        verifyUploadPlanning();
        verifySceneValidation();
        verifyFrameValidationAndSpecialization();
        verifyPassiveSpecializationTransitions();
        verifyNativeSelection();
        verifyMetallicSelection();
        verifyCausticSelection();
        verifyCompositeSelection();
        verifyPrimaryQueueSelection();
        verifyHistoryInvalidation();
        System.out.println("MediumDensitySelfTest passed: stable ABI prefix, atlas references/replacement, coordinate composition, frame validation, cached specialization");
    }

    private static TextureAsset texture(long id,int height,TextureAsset.ColorSpace colorSpace) {
        byte[] pixels = new byte[2*height*4];
        Arrays.fill(pixels,(byte)255);
        return TextureAsset.builder(id,2,height).colorSpace(colorSpace).pixelsRgba8(pixels).build();
    }

    private static void verifyPrimaryQueueSelection() {
        for (var program : Program.values()) {
            require(VulkanGpuSceneTemporalCoordinator.primaryQueueProgram(program,false)==program,
                    "queue control must preserve the serial scene program");
            var queued=VulkanGpuSceneTemporalCoordinator.primaryQueueProgram(program,true);
            var expected=switch(program) {
                case SURFACE -> Program.SURFACE_PRIMARY_SHADE;
                case COMPACT_PASSIVE_NO_DIELECTRIC -> Program.COMPACT_PRIMARY_SHADE;
                case GENERAL_PASSIVE_NO_DIELECTRIC -> Program.GENERAL_PRIMARY_SHADE;
                default -> program;
            };
            require(queued==expected,"only proven homogeneous no-dielectric work enters the queue");
        }
    }

    private static SceneInstance instance(long id,Medium medium) {
        return SceneInstance.builder(id,1).mediumBoundary(MediumBoundary.volume(medium)).build();
    }

    private static void verifyPacking() {
        require(VulkanGpuSceneAbi.INSTANCE_MEDIUM_EMISSION_WORD==69 && VulkanGpuSceneAbi.INSTANCE_MEDIUM_DENSITY_TEXTURE_WORD==72
                && VulkanGpuSceneAbi.INSTANCE_RECORD_WORDS==86,"instance prefix and density tail offsets");
        require(VulkanGpuSceneAbi.FRAME_TRANSMITTANCE_CUTOFF_WORD==448 && VulkanGpuSceneAbi.FRAME_MEDIUM_DENSITY_TEXTURE_WORD==449
                && VulkanGpuSceneAbi.FRAME_SCATTERING_BOUNCES_WORD==463 && VulkanGpuSceneAbi.FRAME_UNIFORM_WORDS==472,
                "frame prefix and density/bounce tail offsets");
        var objectToWorld = new AffineTransform(new float[]{-2,.5f,.25f,7,0,3,.5f,-4,0,0,.5f,9});
        var source = instance(1,GRID).toBuilder().transform(objectToWorld).build();
        int[] record = VulkanGpuSceneAbi.packInstance(source,IDENTITY,3,id->4,id->id==500?7:-1);
        int textureWord = VulkanGpuSceneAbi.INSTANCE_MEDIUM_DENSITY_TEXTURE_WORD;
        require(record[0]==4 && record[textureWord]==7 && record[textureWord+1]==2,"density field resolves stable texture slot and slice count");
        var primitive = VulkanGpuSceneAbi.packPrimitive(PrimitiveInstance.from(source),id->4,id->7);
        require(Arrays.equals(Arrays.copyOfRange(record,72,86),Arrays.copyOfRange(primitive,72,86)),
                "persistent and transient instances share the density ABI");
        double[][] probes = {{0,0,0},{.3,-.4,.2},{-1,1,-.5}};
        for (double[] local : probes) {
            double[] world = transform(objectToWorld,local);
            double[] expected = transform(FIELD.transform(),local);
            for (int row=0;row<3;row++) {
                double value = Float.intBitsToFloat(record[textureWord+2+row*4+3]);
                for(int axis=0;axis<3;axis++) value += Float.intBitsToFloat(record[textureWord+2+row*4+axis])*world[axis];
                require(Math.abs(value-expected[row])<2e-6,"GPU world-to-density map follows mirrored/sheared/nonuniform instances");
            }
        }
        int[] inactive = VulkanGpuSceneAbi.packInstance(instance(1,GRID.withDensity(0)),IDENTITY,0,id->0,
                id->{throw new AssertionError("inactive field must not resolve a GPU texture");});
        require(inactive[textureWord]==-1 && inactive[textureWord+1]==1,"zero effective coefficients use uniform sentinel");
        for(int row=0;row<3;row++) for(int column=0;column<4;column++)
            require(Float.intBitsToFloat(inactive[textureWord+2+row*4+column])==(row==column?1:0),"inactive matrix is canonical identity");
        var request = request(GRID,2,FramePrimitiveBatch.empty());
        var tracker = new TemporalHistoryTracker(TemporalRenderingOptions.disabled());
        byte[] bytes = VulkanFrameUniformPacker.pack(request,VulkanFrameExtents.identity(32,32),0,1,tracker.prepare(request,1),
                TemporalRenderingOptions.disabled(),false,false,false,id->7);
        var words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        require(bytes.length==VulkanFrameUniformPacker.BYTE_COUNT && words.get(449)==7 && words.get(450)==2 && words.get(463)==2,
                "environment density slots and scattering bounce budget are packed");
        for(int index=0;index<12;index++) require(Float.intBitsToFloat(words.get(451+index))==FIELD.transform().element(index),
                "environment field stays in absolute world coordinates");
        byte[] reused = new byte[VulkanFrameUniformPacker.BYTE_COUNT];
        Arrays.fill(reused,(byte)255);
        MediumDensitySupport.packEnvironment(ByteBuffer.wrap(reused).order(ByteOrder.LITTLE_ENDIAN),449,GRID.withDensity(0),
                id->{throw new AssertionError("inactive environment must not resolve a GPU texture");});
        var inactiveEnvironment = ByteBuffer.wrap(reused).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        require(inactiveEnvironment.get(449)==-1 && inactiveEnvironment.get(450)==1,"inactive environment uses uniform sentinel");
        for(int index=0;index<12;index++) require(Float.intBitsToFloat(inactiveEnvironment.get(451+index))==(index%5==0?1:0),
                "inactive environment clears reused matrix words");
        expect(IllegalArgumentException.class,()->VulkanFrameUniformPacker.pack(request,0,1,tracker.prepare(request,1),
                TemporalRenderingOptions.disabled(),false));
    }

    private static void verifyUploadPlanning() {
        var residency = new VulkanSceneResidency();
        var memory = new VulkanGpuSceneMemory();
        var identities = new VulkanGpuSceneIdentityIndex();
        var resident = residency.prepare(base(1).upsert(texture(100,1,TextureAsset.ColorSpace.LINEAR))
                .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).upsert(instance(1,GRID)).build());
        var preparedMemory = memory.prepare(resident.changeSet());
        var preparedIdentities = identities.prepare(resident.changeSet());
        var plan = VulkanGpuSceneUploadPlanner.plan(resident.changeSet(),preparedMemory,preparedIdentities);
        var instanceUpload = plan.chunks().stream().filter(chunk->chunk.target()==VulkanGpuSceneUploadPlanner.Target.INSTANCE_RECORDS)
                .findFirst().orElseThrow();
        var words = ByteBuffer.wrap(instanceUpload.payload()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int densitySlot = preparedIdentities.textureSlot(500);
        require(densitySlot>=0 && densitySlot!=500 && words.get(72)==densitySlot,
                "scene upload resolves density identities against the prepared texture generation");
        require(identities.textureSlot(500)==-1,"preparing density uploads does not publish texture slots");
        memory.commit(preparedMemory,0);identities.commit(preparedIdentities);residency.commit(resident);
        require(identities.textureSlot(500)==densitySlot,"admission publishes the prepared density slot");
        var replacement = residency.prepare(SceneTransaction.builder(2).upsert(texture(500,6,TextureAsset.ColorSpace.LINEAR)).build());
        var replacementSlots = identities.prepare(replacement.changeSet());
        var replacementPlan = VulkanGpuSceneUploadPlanner.plan(replacement.changeSet(),memory.prepare(replacement.changeSet()),replacementSlots);
        require(replacementSlots.textureSlot(500)==densitySlot && replacementPlan.chunks().stream()
                .noneMatch(chunk->chunk.target()==VulkanGpuSceneUploadPlanner.Target.INSTANCE_RECORDS),
                "density atlas replacement retains its slot without rewriting unchanged instance records");
    }

    private static double[] transform(AffineTransform matrix,double[] point) {
        double[] result = new double[3];
        for(int row=0;row<3;row++) {
            result[row]=matrix.element(row*4+3);
            for(int axis=0;axis<3;axis++) result[row]+=matrix.element(row*4+axis)*point[axis];
        }
        return result;
    }

    private static SceneTransaction.Builder base(long revision) {
        return SceneTransaction.builder(revision).upsert(MaterialAsset.opaque(1,-1))
                .upsert(MediumTransportSelfTest.cube(1,1,1));
    }

    private static void verifySceneValidation() {
        var registry = new PersistentSceneRegistry();
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(instance(1,GRID)).build()));
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(instance(1,GRID.withDensity(0))).build()));
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(instance(1,GRID))
                .upsert(texture(500,4,TextureAsset.ColorSpace.SRGB)).build()));
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(instance(1,GRID))
                .upsert(texture(500,3,TextureAsset.ColorSpace.LINEAR)).build()));
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(instance(1,GRID))
                .upsert(texture(500,1,TextureAsset.ColorSpace.LINEAR)).build()));
        var overflow = GRID.withDensityField(MediumDensityField.grid(500,2,
                new AffineTransform(new float[]{1,Float.MAX_VALUE,0,0,0,1,0,0,0,0,1,0})));
        var overflowInstance = instance(1,overflow).toBuilder().transform(
                new AffineTransform(new float[]{1,0,0,0,0,1,0,-2,0,0,1,0})).build();
        expect(SceneValidationException.class,()->registry.prepare(base(1).upsert(overflowInstance)
                .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).build()));
        registry.commit(registry.prepare(base(1).upsert(instance(1,GRID))
                .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(2).removeTexture(500).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(2)
                .upsert(texture(500,3,TextureAsset.ColorSpace.LINEAR)).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(2)
                .upsert(texture(500,4,TextureAsset.ColorSpace.SRGB)).build()));
        require(registry.state().revision()==1,"invalid density texture replacement remains atomic");
        var switched = GRID.withDensityField(MediumDensityField.grid(700,1,IDENTITY));
        registry.commit(registry.prepare(SceneTransaction.builder(2).upsert(texture(700,1,TextureAsset.ColorSpace.LINEAR))
                .upsert(instance(1,switched)).removeTexture(500).build()));
        expect(SceneValidationException.class,()->registry.prepare(SceneTransaction.builder(3).removeTexture(700).build()));
        registry.commit(registry.prepare(SceneTransaction.builder(3).upsert(instance(1,Medium.vacuum())).removeTexture(700).build()));
        registry.commit(registry.prepare(SceneTransaction.builder(4).resetScene().build()));
        registry.commit(registry.prepare(SceneTransaction.builder(5).upsert(texture(500,1,TextureAsset.ColorSpace.SRGB)).build()));
        registry.commit(registry.prepare(SceneTransaction.builder(6).removeTexture(500).build()));
        require(registry.state().textures()==0,"density references retire after replacement and reset");
    }

    private static RenderFrameRequest request(Medium environment,int bounces,FramePrimitiveBatch batch) {
        return RenderFrameRequest.builder(1,32,32,CAMERA).primitiveBatch(batch)
                .environment(EnvironmentState.builder().cameraMedium(environment).build())
                .mediumTransport(MediumTransport.balanced().withScatteringBounces(bounces)).build();
    }

    private static void verifyNativeSelection() {
        var residency = new VulkanSceneResidency();
        var media = new MediumSceneIndex();
        var surfaces = new VulkanSurfaceSceneIndex();
        var empty = FramePrimitiveBatch.empty();
        var absorption = new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0);
        var scattering = new Medium(Rgb.ZERO,new Rgb(.1f,.2f,.3f),Rgb.ZERO,1,1,0);
        apply(residency,media,surfaces,SceneTransaction.builder(1)
                .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).build());
        record RouteCase(Medium medium,int bounces,CudaMediumFrameProfile.Route expected) { }
        for (var test : new RouteCase[] {
                new RouteCase(Medium.vacuum(),0,CudaMediumFrameProfile.Route.NONE),
                new RouteCase(absorption,0,CudaMediumFrameProfile.Route.CAMERA),
                new RouteCase(absorption.withDensityField(FIELD),0,CudaMediumFrameProfile.Route.BOUNDARY),
                new RouteCase(scattering,0,CudaMediumFrameProfile.Route.PBR_SCATTERING),
                new RouteCase(scattering,2,CudaMediumFrameProfile.Route.PBR_SCATTERING),
                new RouteCase(GRID,0,CudaMediumFrameProfile.Route.PBR_SCATTERING),
                new RouteCase(GRID,2,CudaMediumFrameProfile.Route.PBR_SCATTERING)}) {
            var frame = request(test.medium(),test.bounces(),empty);
            var selected = CudaMediumFrameProfile.select(frame,media,surfaces,true,true);
            require(selected.route()==test.expected(),"native route: expected="+test.expected()
                    +", actual="+selected.route()+", medium="+test.medium()+", bounces="+test.bounces());
            require(selected.program()==Program.SURFACE,"native executor supplies all participating-medium radiance");
            var withData = frame.toBuilder().shaderData(java.util.List.of(
                    new ResourceData(java.nio.ByteBuffer.allocateDirect(16)))).build();
            require(CudaMediumFrameProfile.select(withData,media,surfaces,true,true).route()==selected.route(),
                    "application data layouts cannot disable native medium execution");
        }
        apply(residency,media,surfaces,SceneTransaction.builder(2)
                .upsert(MaterialAsset.builder(1).doubleSided(true).shadingModel(MaterialAsset.ShadingModel.UNLIT).build())
                .upsert(MediumTransportSelfTest.cube(1,1,1)).upsert(instance(1,absorption)).build());
        var boundary = CudaMediumFrameProfile.select(request(absorption,0,empty),media,surfaces,true,true);
        require(boundary.route()==CudaMediumFrameProfile.Route.BOUNDARY && boundary.boundaries().boundaries().size()==1,
                "boundary admission retains the scene snapshot for execution");
        require(CudaMediumFrameProfile.select(request(scattering,2,empty),media,surfaces,true,true).route()
                ==CudaMediumFrameProfile.Route.BOUNDARY_SCATTERING,"explicit scattering boundaries precede camera-only profiles");
        apply(residency,media,surfaces,SceneTransaction.builder(3)
                .upsert(instance(1,absorption).toBuilder().mediumBoundary(MediumBoundary.surface(absorption)).build())
                .upsert(MaterialAsset.builder(1).doubleSided(true).transmission(1).roughness(.4f).build()).build());
        require(CudaMediumFrameProfile.select(request(absorption,0,empty),media,surfaces,true,true).route()
                ==CudaMediumFrameProfile.Route.OPTICAL,"surface-interacting boundaries must reach the optical executor");
        var rejected = request(scattering,2,empty);
        var expert = CudaMediumFrameProfile.select(rejected,media,surfaces,false,true);
        require(expert.route()==CudaMediumFrameProfile.Route.NONE && expert.program()==Program.SURFACE,
                "application scene programs retain ownership of their transport");
        expect(FrameValidationException.class,()->CudaMediumFrameProfile.select(rejected,media,surfaces,true,false));
        require(CudaMediumFrameProfile.select(request(absorption,0,empty),media,surfaces,true,true).route()
                ==CudaMediumFrameProfile.Route.OPTICAL,"a rejected request must not poison later admission");
    }

    private static void verifyMetallicSelection() {
        var residency=new VulkanSceneResidency();var media=new MediumSceneIndex();var surfaces=new VulkanSurfaceSceneIndex();
        var batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).build()).build();
        apply(residency,media,surfaces,SceneTransaction.builder(1)
                .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR))
                .upsert(MaterialAsset.builder(1).metallic(.2f).doubleSided(false).build())
                .upsert(MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1)).build());
        for(var medium:new Medium[]{new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0),GRID}) {
            for(int bounces:new int[]{0,2}) {
                var frame=request(medium,bounces,batch);
                var selected=CudaMediumFrameProfile.select(frame,media,surfaces,true,true);
                require(selected.route()==CudaMediumFrameProfile.Route.OPTICAL,
                        "metallic medium frames must trace reflected segments through native optical state");
                var metadata=CudaOpticalMediumManager.scene(frame,32,32,selected.boundaries(),media::densityTexture,
                        new top.ceroxe.rt.renderer.backend.vulkan.medium.cuda.CudaMediumDensityAbi.Cache(),false,0,true);
                require(metadata.plan().connectionTransport(),"metal reflection requires a complete receiver connection record");
            }
        }
        require(CudaMediumFrameProfile.select(request(Medium.vacuum(),0,batch),media,surfaces,true,true).route()
                ==CudaMediumFrameProfile.Route.NONE,"vacuum metal retains ordinary surface transport");
        require(!surfaces.hasMetallicReflection(FramePrimitiveBatch.empty()),"unused metallic assets do not select optical transport");
    }

    private static void verifyCausticSelection() {
        var residency=new VulkanSceneResidency();var media=new MediumSceneIndex();var surfaces=new VulkanSurfaceSceneIndex();
        var absorption=new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,Rgb.ZERO,1,1,0);
        var batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).build()).build();
        long revision=1;
        for(var model:new MaterialAsset.ShadingModel[]{MaterialAsset.ShadingModel.UNLIT,MaterialAsset.ShadingModel.PHYSICALLY_BASED}) {
            apply(residency,media,surfaces,SceneTransaction.builder(revision++).resetScene()
                    .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR))
                    .upsert(MaterialAsset.builder(1).shadingModel(model).doubleSided(false).build())
                    .upsert(MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1)).build());
            for(var medium:new Medium[]{absorption,GRID}) {
                var frame=request(medium,2,batch).toBuilder().caustics(Caustics.photonMap(0,0,0,10,1024,1)).build();
                require(CudaMediumFrameProfile.select(frame,media,surfaces,true,true).route()==CudaMediumFrameProfile.Route.OPTICAL,
                        "caustics with any participating medium must retain complete photon/camera transport");
            }
        }
    }

    private static void verifyCompositeSelection() {
        var residency=new VulkanSceneResidency();var media=new MediumSceneIndex();var surfaces=new VulkanSurfaceSceneIndex();
        var batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(1).surfaceVisibility(.5f).build()).build();
        long revision=1;
        for(var material:new MaterialAsset[]{
                MaterialAsset.builder(1).baseColorRgba8(0x80ffffff).build(),
                MaterialAsset.builder(1).transmission(.5f).doubleSided(false).build(),
                MaterialAsset.builder(1).surfaceOverlay(SurfaceOverlayState.depthBias(.01f)).build(),
                MaterialAsset.builder(1).shadingModel(MaterialAsset.ShadingModel.LIGHTMAP_MODULATED).build()}) {
            apply(residency,media,surfaces,SceneTransaction.builder(revision++).resetScene()
                    .upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).upsert(material)
                    .upsert(MeshAsset.triangles(1,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},1)).build());
            var frame=request(GRID,2,batch);
            var selected=CudaMediumFrameProfile.select(frame,media,surfaces,true,true);
            require(selected.route()==CudaMediumFrameProfile.Route.OPTICAL&&surfaces.opticalTerminalOnly(batch),
                    "surface compositing must reach native continuation with current frame-local facts");
            require(!surfaces.opticalOpaqueRayOnly(batch),"coverage and sidedness keep their hit-stage decisions");
        }
    }

    private static void verifyFrameValidationAndSpecialization() {
        var residency = new VulkanSceneResidency();var index = new MediumSceneIndex();var surfaces = new VulkanSurfaceSceneIndex();
        apply(residency,index,surfaces,base(1).upsert(texture(500,4,TextureAsset.ColorSpace.LINEAR)).build());
        var empty = FramePrimitiveBatch.empty();
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),8,empty),index,surfaces)==Program.SURFACE,
                "unused bounce budget does not load a volume shader");
        require(VulkanGpuSceneTemporalCoordinator.program(request(GRID.withDensity(0),8,empty),index,surfaces)==Program.SURFACE,
                "nonparticipating field does not load a volume shader");
        var env = request(GRID,0,empty);index.validateDensityFields(env);
        require(index.densityTextureSlot(500)==0
                && VulkanGpuSceneTemporalCoordinator.program(env,index,surfaces)==Program.COMPACT_VOLUME,"environment density uses volume specialization");
        var local = FramePrimitiveBatch.builder().add(PrimitiveInstance.from(instance(1,GRID))).build();
        var localRequest = request(Medium.vacuum(),0,local);index.validateDensityFields(localRequest);
        require(index.hasDensityField(local) && index.hasScattering(local),"frame-local field and scattering classification");
        require(!index.hasDensityField(empty) && !index.hasScattering(empty),"frame-local retirement removes volume facts");
        var homogeneous = GRID.withDensityField(MediumDensityField.uniform());
        apply(residency,index,surfaces,SceneTransaction.builder(2).upsert(instance(1,homogeneous)).build());
        require(!index.hasDensityField(empty) && index.hasScattering(empty),"resident homogeneous scattering classification");
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)==Program.COMPACT,
                "single scattering retains the ordinary compact program");
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),1,empty),index,surfaces)==Program.COMPACT_VOLUME,
                "additional scattering selects volume program");
        apply(residency,index,surfaces,SceneTransaction.builder(3).upsert(instance(1,GRID)).build());
        require(index.hasDensityField(empty),"resident field replacement updates cached classification");
        var crowded=SceneTransaction.builder(4);
        for(int id=2;id<=9;id++) crowded.upsert(instance(id,GRID));
        apply(residency,index,surfaces,crowded.build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)==Program.GENERAL_VOLUME,
                "more than eight referenced volumes select the full volume stack");
        apply(residency,index,surfaces,SceneTransaction.builder(5).removeInstances(1,2,3,4,5,6,7,8,9).build());
        require(!index.hasDensityField(empty) && !index.hasScattering(empty),"persistent retirement removes volume facts");
        index.validateDensityFields(localRequest);
        apply(residency,index,surfaces,SceneTransaction.builder(6).upsert(texture(500,3,TextureAsset.ColorSpace.LINEAR)).build());
        expect(FrameValidationException.class,()->index.validateDensityFields(localRequest));
        expect(FrameValidationException.class,()->index.validateDensityFields(env));
        apply(residency,index,surfaces,SceneTransaction.builder(7).removeTexture(500).build());
        expect(FrameValidationException.class,()->index.validateDensityFields(env));
        apply(residency,index,surfaces,SceneTransaction.builder(8).resetScene().upsert(texture(700,1,TextureAsset.ColorSpace.LINEAR)).build());
        require(index.densityTextureSlot(500)==-1 && index.densityTextureSlot(700)==0,"texture identity remapping cannot reuse stale density slots");
    }

    private static void verifyPassiveSpecializationTransitions() {
        var residency = new VulkanSceneResidency();
        var index = new MediumSceneIndex();
        var surfaces = new VulkanSurfaceSceneIndex();
        var empty = FramePrimitiveBatch.empty();
        var absorbing = new Medium(new Rgb(.1f,.2f,.3f),Rgb.ZERO,new Rgb(.01f,0,0),1,1,0);
        var scattering = GRID.withDensityField(MediumDensityField.uniform());
        apply(residency,index,surfaces,base(1).upsert(instance(1,absorbing)).build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),8,empty),index,surfaces)
                ==Program.COMPACT_PASSIVE_NO_DIELECTRIC,"unused scattering budget must preserve absorption specialization");
        require(VulkanGpuSceneTemporalCoordinator.program(request(scattering,0,empty),index,surfaces)
                ==Program.COMPACT,"environment scattering cannot enter a passive program");
        var local = FramePrimitiveBatch.builder().add(PrimitiveInstance.from(instance(2,scattering))).build();
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,local),index,surfaces)
                ==Program.COMPACT,"frame-local scattering must leave the passive program");
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                ==Program.COMPACT_PASSIVE_NO_DIELECTRIC,"retiring local scattering must restore the passive program");
        apply(residency,index,surfaces,SceneTransaction.builder(2).upsert(instance(1,scattering)).build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                ==Program.COMPACT,"persistent scattering replacement must invalidate passive classification");
        apply(residency,index,surfaces,SceneTransaction.builder(3).upsert(instance(1,absorbing)).build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                ==Program.COMPACT_PASSIVE_NO_DIELECTRIC,"persistent absorption replacement must restore passive classification");
        var crowded = SceneTransaction.builder(4);
        for(int id=2;id<=9;id++)crowded.upsert(instance(id,absorbing));
        apply(residency,index,surfaces,crowded.build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                ==Program.GENERAL_PASSIVE_NO_DIELECTRIC,"passive transport must retain all 32 general boundary slots");
        var surfaceBoundary=SceneInstance.builder(10,1).mediumBoundary(MediumBoundary.surface(absorbing)).build();
        apply(residency,index,surfaces,SceneTransaction.builder(5).upsert(surfaceBoundary).build());
        require(index.hasSurfaceInteraction(empty) && VulkanGpuSceneTemporalCoordinator.program(
                request(Medium.vacuum(),0,empty),index,surfaces)==Program.GENERAL_PASSIVE,
                "a reachable dielectric surface boundary must retain Fresnel and refraction code");
        apply(residency,index,surfaces,SceneTransaction.builder(6).removeInstance(10).build());
        require(!index.hasSurfaceInteraction(empty),"retiring a dielectric surface boundary restores no-dielectric eligibility");
        var localSurface=FramePrimitiveBatch.builder().add(PrimitiveInstance.from(surfaceBoundary)).build();
        require(index.hasSurfaceInteraction(localSurface) && VulkanGpuSceneTemporalCoordinator.program(
                request(Medium.vacuum(),0,localSurface),index,surfaces)==Program.GENERAL_PASSIVE,
                "frame-local surface boundaries need optics even with an opaque mesh material");
        require(!index.hasSurfaceInteraction(empty) && VulkanGpuSceneTemporalCoordinator.program(
                request(Medium.vacuum(),0,empty),index,surfaces)==Program.GENERAL_PASSIVE_NO_DIELECTRIC,
                "retiring frame-local surface boundaries must invalidate their cached classification");
        apply(residency,index,surfaces,SceneTransaction.builder(7)
                .upsert(MaterialAsset.builder(2).transmission(1).build())
                .upsert(MediumTransportSelfTest.cube(2,2,1)).build());
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                ==Program.GENERAL_PASSIVE_NO_DIELECTRIC,"unused transmissive assets do not require optics");
        var localGlass=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(2).build()).build();
        require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,localGlass),index,surfaces)
                ==Program.GENERAL_PASSIVE,"referenced frame-local glass must disable no-dielectric specialization");
        apply(residency,index,surfaces,SceneTransaction.builder(8)
                .upsert(MaterialAsset.opaque(2,-1).toBuilder().baseColorRgba8(0x80ffffff).build()).build());
        require(!surfaces.opaque(localGlass) && VulkanGpuSceneTemporalCoordinator.program(
                request(Medium.vacuum(),0,localGlass),index,surfaces)==Program.GENERAL_PASSIVE_NO_DIELECTRIC,
                "alpha coverage remains legal without dielectric transport");
        String previous=System.getProperty("top.ceroxe.rt.shader.disablePassiveNoDielectric");
        try {
            System.setProperty("top.ceroxe.rt.shader.disablePassiveNoDielectric","true");
            require(VulkanGpuSceneTemporalCoordinator.program(request(Medium.vacuum(),0,empty),index,surfaces)
                    ==Program.GENERAL_PASSIVE,"control selector preserves the established passive program");
        } finally {
            if(previous==null) System.clearProperty("top.ceroxe.rt.shader.disablePassiveNoDielectric");
            else System.setProperty("top.ceroxe.rt.shader.disablePassiveNoDielectric",previous);
        }
        apply(residency,index,surfaces,base(9).resetScene().build());
        require(!index.hasSurfaceInteraction(empty) && index.boundaryCount()==0,
                "scene reset retires every persistent boundary classification");
    }

    private static void verifyHistoryInvalidation() {
        var tracker = new TemporalHistoryTracker(TemporalRenderingOptions.balanced());
        var first = RenderFrameRequest.builder(1,32,32,CAMERA)
                .environment(EnvironmentState.builder().cameraMedium(GRID).build()).build();
        tracker.commit(tracker.prepare(first,1));
        var unchanged = RenderFrameRequest.builder(2,32,32,CAMERA).environment(first.environment()).build();
        require(tracker.prepare(unchanged,1).historyValid(),"stable density fields retain temporal history");
        var fieldChange = RenderFrameRequest.builder(2,32,32,CAMERA).environment(EnvironmentState.builder()
                .cameraMedium(GRID.withDensityField(MediumDensityField.grid(500,2,IDENTITY))).build()).build();
        require(tracker.prepare(fieldChange,1).invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),
                "environment density transform changes invalidate temporal shading");
        var bounceChange = RenderFrameRequest.builder(2,32,32,CAMERA).environment(first.environment())
                .mediumTransport(first.mediumTransport().withScatteringBounces(1)).build();
        require(tracker.prepare(bounceChange,1).invalidations().contains(HistoryInvalidationReason.FRAME_SHADING_CHANGED),
                "multiple-scattering budget changes invalidate temporal shading");
    }

    private static void apply(VulkanSceneResidency residency,MediumSceneIndex index,VulkanSurfaceSceneIndex surfaces,SceneTransaction transaction) {
        var prepared=residency.prepare(transaction);residency.commit(prepared);index.sceneApplied(prepared.changeSet());surfaces.sceneApplied(prepared.changeSet());
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try {action.run();} catch(Throwable failure) {if(type.isInstance(failure)) return;throw failure;}
        throw new AssertionError("expected "+type.getSimpleName());
    }
}
