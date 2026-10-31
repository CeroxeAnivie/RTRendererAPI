package demo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.UnaryOperator;
import top.ceroxe.rt.renderer.api.*;

/** Real, headless medium workloads. Coverage is inferred from submitted assets and requests. */
final class MediumPerformanceCases {
    enum Feature {
        ABSORPTION, EMISSION, UNIFORM, DENSITY_GRID, ZERO_DENSITY, DIRECT_SCATTER, MULTIPLE_SCATTER,
        PHASE_FORWARD, PHASE_BACKWARD, PERSISTENT_BOUNDARY, TRANSIENT_BOUNDARY, NESTED_PRIORITY,
        INWARD_BOUNDARY, REFRACTION, SMOOTH_GLASS, ROUGH_GLASS, FIRST_INTERFACE_SPLIT,
        FIRST_INTERFACE_STOCHASTIC, PBR_SINGLE_SIDED, PBR_DOUBLE_SIDED, UNLIT, METAL_PARTIAL,
        METAL_FULL, PARTIAL_TRANSMISSION, MATERIAL_ALPHA, VERTEX_ALPHA, TEXTURE_ALPHA,
        ALPHA_PRODUCT, LIGHTMAP, SURFACE_VISIBILITY, FOG, OVERLAY_ALPHA, OVERLAY_MULTIPLY,
        OVERLAY_MASK_REJECT, OVERLAY_DEPTH_REJECT, BUILTIN_SHADER_DATA, SURFACE_CAUSTICS,
        VOLUME_CAUSTICS, POINT_LIGHT, SPOT_LIGHT, DIRECTIONAL_LIGHT, SHADOW_DISABLED,
        SPP_2, SPP_8, ALL_LIGHTS, FIRST_INTERFACE_OPTICAL_SAMPLES
    }
    @FunctionalInterface interface Frames { RenderFrameRequest frame(long sequence,int width,int height); }
    record Workload(String id,SceneTransaction transaction,RendererConfig config,Frames frames) {
        RenderFrameRequest frame(long sequence,int width,int height){return frames.frame(sequence,width,height);}
        Set<Feature> features(){return inspect(transaction,frame(0,1920,1080));}
    }
    private MediumPerformanceCases() { }

    static List<Workload> all() {
        var result=new ArrayList<Workload>();
        for(var preset:MediumGalleryScene.Preset.values()) {
            if(preset==MediumGalleryScene.Preset.GALLERY)continue;
            result.add(gallery(preset.id,preset.id));
            if(preset.caustic())result.add(gallery(preset.id+"-off",preset.id,"--caustics=false"));
        }
        for(var preset:MediumCertificationDemo.Preset.values()) {
            var fixture=MediumCertificationDemo.Preset.fixture(preset,1,0,1920,1080,0,0);
            result.add(new Workload("route-"+preset.name().toLowerCase(Locale.ROOT),fixture.scene(),configuration(false),
                    (s,w,h)->fixture.frame(s,w,h,0,0,preset.rig())));
        }
        result.add(gallery("gallery-inward-nested","gallery","--inward=true","--transient=false","--inside=true"));
        result.add(gallery("gallery-transient-nested","gallery","--transient=true"));
        for(String alpha:List.of("material","vertex","texture","product"))
            result.add(composite("alpha-"+alpha,alpha,0));
        for(int domain:new int[]{1,2})result.add(composite("alpha-product-domain-"+domain,"product",domain));
        result.add(composite("lightmap-visibility-fog","lightmap",0));
        for(String mode:List.of("over","multiply"))for(String match:List.of("match","mask","depth"))
            result.add(composite("overlay-"+mode+"-"+match,"overlay-"+mode+"-"+match,0));
        for(int domain=0;domain<3;domain++)for(String metal:List.of("metal-partial","metal-full"))
            result.add(composite(metal+"-domain-"+domain,metal,domain));
        for(String variant:List.of("partial-transmission","zero-density","data","stochastic","optical-samples","spp2","spp8"))
            result.add(composite(variant,variant,variant.equals("data")?2:0));
        // Newly closed cross-feature paths must have their own workload, not just independent switches.
        result.add(composite("metal-density-multiple-caustics","metal-caustics",2));
        result.add(composite("alpha-density-multiple-caustics","alpha-caustics",2));
        verify(result);
        return List.copyOf(result);
    }
    static Workload focused(){return gallery("pbr-direct-uniform","pbr-direct-uniform");}
    private static Workload gallery(String id,String preset,String... extra) {
        var args=new ArrayList<>(List.of("--width=1920","--height=1080","--spp=1","--scene="+preset,
                "--fg=false","--animate=false","--controls=false","--photons=65536"));
        args.addAll(List.of(extra));
        var options=MediumGalleryDemo.Options.parse(args.toArray(String[]::new));
        var scene=new MediumGalleryScene(options);
        return new Workload(id,scene.transaction(),MediumGalleryDemo.configuration(options),(s,w,h)->scene.frame(s,w,h,0,0));
    }
    private static RendererConfig configuration(boolean data) {
        var options=MediumGalleryDemo.Options.parse(new String[]{"--fg=false","--animate=false","--controls=false"});
        var builder=MediumGalleryDemo.configuration(options).copyBuilder();
        if(data)builder.sceneShaders(SceneShaderProgram.builder().dataBindingCount(1).build());
        return builder.build();
    }
    private static Workload composite(String id,String variant,int domain) {
        boolean metal=variant.startsWith("metal"),overlay=variant.startsWith("overlay"),caustics=variant.endsWith("caustics");
        boolean materialAlpha=variant.equals("material")||variant.equals("product")||variant.equals("alpha-caustics")||overlay;
        boolean vertexAlpha=variant.equals("vertex")||variant.equals("product");
        boolean textureAlpha=variant.equals("texture")||variant.equals("product");
        boolean dielectric=variant.equals("partial-transmission")||variant.equals("stochastic")||variant.equals("optical-samples");
        var front=MaterialAsset.builder(1).doubleSided(true).baseColorRgba8(materialAlpha?0x80a0c0e0:0xffa0c0e0)
                .shadingModel(metal||caustics||dielectric?MaterialAsset.ShadingModel.PHYSICALLY_BASED:
                        variant.equals("lightmap")?MaterialAsset.ShadingModel.LIGHTMAP_MODULATED:MaterialAsset.ShadingModel.UNLIT)
                .metallic(metal||caustics?(variant.equals("metal-partial")?.2f:1):0).roughness(metal||caustics?.025f:.15f)
                .transmission(variant.equals("partial-transmission")?.5f:dielectric?1:0).indexOfRefraction(1.5f);
        if(materialAlpha||vertexAlpha||textureAlpha)front.blendMode(MaterialAsset.BlendMode.TRANSLUCENT);
        if(textureAlpha)front.baseColorTextureId(10);
        if(overlay)front.blendMode(MaterialAsset.BlendMode.TRANSLUCENT).surfaceOverlay(SurfaceOverlayState.depthEqual(.01f,
                variant.contains("multiply")?SurfaceOverlayState.CompositionMode.MULTIPLY:SurfaceOverlayState.CompositionMode.ALPHA_OVER));
        var mesh=plane(1,overlay?1.005f:0,1).toBuilder();
        if(vertexAlpha)mesh.vertexColorsRgba8(new int[]{0x80ffffff,0x80ffffff,0x80ffffff,0x80ffffff});
        var tx=SceneTransaction.builder(1).resetScene().upsert(front.build()).upsert(mesh.build())
                .upsert(MaterialAsset.builder(2).doubleSided(!caustics).shadingModel(caustics?MaterialAsset.ShadingModel.PHYSICALLY_BASED:MaterialAsset.ShadingModel.UNLIT)
                        .roughness(1).baseColorRgba8(variant.equals("alpha-caustics")?0x80406080:0xff406080).build())
                .upsert(MaterialAsset.builder(3).doubleSided(true).transmission(1).roughness(0).indexOfRefraction(1.5f).build())
                .upsert(caustics?ceiling(2,2,2):plane(2,overlay?(variant.endsWith("depth")?.5f:1):-2,2)).upsert(plane(3,5,2))
                .upsert(cube(4,3)).upsert(cube(5,2))
                .upsert(SceneInstance.builder(1,1).renderState(InstanceRenderState.builder()
                        .overlayReceiverMask(overlay?(variant.endsWith("mask")?2:1):0).build())
                        .surfaceVisibility(variant.equals("lightmap")?.5f:1).build())
                .upsert(SceneInstance.builder(2,2).renderState(InstanceRenderState.builder().surfaceMask(1).build()).build())
                // Keep compositing and reflection on the same proven native optical executor.
                .upsert(SceneInstance.builder(4,4).transform(transform(1,1,1,20,0,0)).build())
                .upsert(TextureAsset.builder(10,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                        .pixelsRgba8(new byte[]{-1,-1,-1,(byte)(textureAlpha?128:255)}).build())
                .upsert(TextureAsset.builder(50,1,2).colorSpace(TextureAsset.ColorSpace.LINEAR)
                        .pixelsRgba8(new byte[]{(byte)128,-1,-1,-1,(byte)128,-1,-1,-1}).build())
                .upsert(SceneLight.point(1,-2,3,2).intensity(12).range(20).build())
                .upsert(SceneLight.spot(2,2,3,2,-.3f,-.5f,-1).intensity(10).range(20).coneDegrees(15,40).build())
                .upsert(SceneLight.directional(3,0,-.3f,-1).intensity(.3f).castsShadow(false).build());
        if(!caustics)tx.upsert(SceneInstance.builder(3,3).build());
        var medium=new Medium(new Rgb(.02f,.03f,.05f),new Rgb(.025f,.03f,.04f),new Rgb(.002f,.003f,.004f),1,1,.3f);
        if(domain==2)medium=medium.withDensityField(MediumDensityField.grid(50,2,
                new AffineTransform(new float[]{.005f,0,0,.5f,0,.005f,0,.5f,0,0,.125f,.25f})));
        if(variant.equals("zero-density"))medium=medium.withDensity(0);
        FramePrimitiveBatch batch=FramePrimitiveBatch.empty();
        if(domain==1)batch=FramePrimitiveBatch.builder().add(PrimitiveInstance.builder(5)
                .transform(transform(10,10,1.5f,0,0,.75f)).mediumBoundary(MediumBoundary.volume(medium).withPriority(3)).build()).build();
        final Medium cameraMedium=domain==1?Medium.vacuum():medium;
        final FramePrimitiveBatch frameBatch=batch;
        int[] lightmap=new int[LightmapState.ENTRY_COUNT];Arrays.fill(lightmap,0xff808080);
        var transport=new MediumTransport(16,variant.equals("optical-samples")?2:1,8,0,12,1e-4f,caustics?2:0);
        if(variant.equals("stochastic"))transport=transport.withFirstInterfaceSampling(MediumTransport.FirstInterfaceSampling.STOCHASTIC_SINGLE_BRANCH);
        final MediumTransport budget=transport;
        var scene=tx.build();
        return new Workload(id,scene,configuration(variant.equals("data")),(s,w,h)-> {
            var frame=RenderFrameRequest.builder(s,w,h,(caustics?CameraState.lookAt(0,0,1,0,0,2):CameraState.lookAt(0,0,3,0,0,0))
                            .verticalFieldOfViewDegrees(44).aspectRatio(w/(double)h).build())
                    .minimumSceneRevision(1).environment(EnvironmentState.builder().cameraMedium(cameraMedium)
                            .skyRadiance(.05f,.07f,.1f).ambientIntensity(.2f).sunIntensity(.4f).sunDirection(0,0,-1).build())
                    .primitiveBatch(frameBatch).mediumTransport(budget)
                    .antiAliasing(new AntiAliasingState(variant.equals("spp8")?8:variant.equals("spp2")?2:1));
            if(variant.equals("lightmap"))frame.lightmap(new LightmapState(1,lightmap))
                    .fog(DistanceFogState.builder().color(.1f,.15f,.2f).opacity(.2f).sphericalRange(1,15).build());
            if(variant.equals("data"))frame.shaderData(List.of(new ResourceData(ByteBuffer.allocateDirect(16)
                    .order(ByteOrder.nativeOrder()).putLong(s).putLong(~s).flip())));
            if(caustics)frame.caustics(Caustics.photonMap(0,0,-1,6,65536,.18f).withMaxEvents(8));
            return frame.build();
        });
    }
    static Workload map(Workload source,String id,UnaryOperator<RenderFrameRequest> mapper) {
        return new Workload(id,source.transaction(),source.config(),(s,w,h)->mapper.apply(source.frame(s,w,h)));
    }
    static void verify(List<Workload> workloads) {
        var ids=new HashSet<String>();var covered=EnumSet.noneOf(Feature.class);
        for(var work:workloads) {
            require(ids.add(work.id()),"duplicate performance case "+work.id());
            var frame=work.frame(0,1920,1080);
            require(frame.minimumSceneRevision()==work.transaction().revision(),"unpinned workload "+work.id());
            require(!work.config().cpuFrameReadbackEnabled(),"performance case enables CPU image readback "+work.id());
            covered.addAll(inspect(work.transaction(),frame));
        }
        var missing=EnumSet.allOf(Feature.class);missing.removeAll(covered);
        require(missing.isEmpty(),"uncovered medium performance features: "+missing);
    }
    static Set<Feature> inspect(SceneTransaction tx,RenderFrameRequest frame) {
        var found=EnumSet.noneOf(Feature.class);var media=new ArrayList<Medium>();media.add(frame.environment().cameraMedium());
        var boundaries=new ArrayList<MediumBoundary>();
        tx.upserts().instances().forEach(i->i.mediumBoundary().ifPresent(b->{found.add(Feature.PERSISTENT_BOUNDARY);boundaries.add(b);}));
        frame.primitiveBatch().primitives().forEach(i->i.mediumBoundary().ifPresent(b->{found.add(Feature.TRANSIENT_BOUNDARY);boundaries.add(b);}));
        boundaries.forEach(b->{media.add(b.medium());if(b.orientation()==MediumBoundary.Orientation.INWARD)found.add(Feature.INWARD_BOUNDARY);});
        if(boundaries.size()>1&&boundaries.stream().map(MediumBoundary::priority).distinct().count()>1)found.add(Feature.NESTED_PRIORITY);
        for(var medium:media) {
            if(!medium.absorption().isZero())found.add(Feature.ABSORPTION);
            if(!medium.emission().isZero())found.add(Feature.EMISSION);
            if(medium.density()==0)found.add(Feature.ZERO_DENSITY);
            if(!medium.isVacuum())found.add(medium.densityField().isUniform()?Feature.UNIFORM:Feature.DENSITY_GRID);
            if(!medium.scattering().isZero()&&medium.density()>0) {
                found.add(frame.mediumTransport().scatteringBounces()>0?Feature.MULTIPLE_SCATTER:Feature.DIRECT_SCATTER);
                if(medium.anisotropy()>0)found.add(Feature.PHASE_FORWARD);
                if(medium.anisotropy()<0)found.add(Feature.PHASE_BACKWARD);
            }
        }
        boolean ma=false,va=false,ta=false;
        for(var material:tx.upserts().materials()) {
            if((material.baseColorRgba8()>>>24)<255){found.add(Feature.MATERIAL_ALPHA);ma=true;}
            if(material.metallic()>0)found.add(material.metallic()==1?Feature.METAL_FULL:Feature.METAL_PARTIAL);
            if(material.transmission()>0) {
                found.add(Feature.REFRACTION);found.add(material.roughness()<.01f?Feature.SMOOTH_GLASS:Feature.ROUGH_GLASS);
                if(material.transmission()<1)found.add(Feature.PARTIAL_TRANSMISSION);
            }
            switch(material.shadingModel()) {
                case PHYSICALLY_BASED -> found.add(material.doubleSided()?Feature.PBR_DOUBLE_SIDED:Feature.PBR_SINGLE_SIDED);
                case UNLIT -> found.add(Feature.UNLIT);
                case LIGHTMAP_MODULATED -> found.add(Feature.LIGHTMAP);
            }
            if(material.surfaceOverlay().enabled())found.add(material.surfaceOverlay().compositionMode()==SurfaceOverlayState.CompositionMode.MULTIPLY
                    ?Feature.OVERLAY_MULTIPLY:Feature.OVERLAY_ALPHA);
            if(material.baseColorTextureId()>=0)for(var texture:tx.upserts().textures())if(texture.id()==material.baseColorTextureId()) {
                var bytes=texture.rgba8();for(int i=3;i<bytes.remaining();i+=4)if(Byte.toUnsignedInt(bytes.get(i))<255){ta=true;found.add(Feature.TEXTURE_ALPHA);break;}
            }
        }
        for(var mesh:tx.upserts().meshes()){var colors=mesh.vertexColorsRgba8();while(colors.hasRemaining())if((colors.get()>>>24)<255){va=true;found.add(Feature.VERTEX_ALPHA);break;}}
        if(ma&&va&&ta)found.add(Feature.ALPHA_PRODUCT);
        tx.upserts().instances().forEach(i->{if(i.surfaceVisibility()<1)found.add(Feature.SURFACE_VISIBILITY);});
        if(frame.fog().opacity()>0)found.add(Feature.FOG);
        // Overlay fixtures use a visible front quad and explicitly different receiver masks/depths.
        boolean overlay=found.contains(Feature.OVERLAY_ALPHA)||found.contains(Feature.OVERLAY_MULTIPLY);
        if(overlay) {
            var front=tx.upserts().instances().stream().filter(i->i.id()==1).findFirst().orElseThrow();
            var back=tx.upserts().instances().stream().filter(i->i.id()==2).findFirst().orElseThrow();
            if((front.renderState().overlayReceiverMask()&back.renderState().surfaceMask())==0)found.add(Feature.OVERLAY_MASK_REJECT);
            float frontZ=tx.upserts().meshes().stream().filter(m->m.id()==1).findFirst().orElseThrow().positions().get(2);
            float backZ=tx.upserts().meshes().stream().filter(m->m.id()==2).findFirst().orElseThrow().positions().get(2);
            if(frontZ-backZ>.01f)found.add(Feature.OVERLAY_DEPTH_REJECT);
        }
        if(!frame.shaderData().isEmpty())found.add(Feature.BUILTIN_SHADER_DATA);
        if(frame.caustics().enabled()){found.add(Feature.SURFACE_CAUSTICS);if(found.contains(Feature.DIRECT_SCATTER)||found.contains(Feature.MULTIPLE_SCATTER))found.add(Feature.VOLUME_CAUSTICS);}
        for(var light:tx.upserts().lights()) {
            found.add(switch(light.type()){case POINT->Feature.POINT_LIGHT;case SPOT->Feature.SPOT_LIGHT;case DIRECTIONAL->Feature.DIRECTIONAL_LIGHT;});
            if(!light.castsShadow())found.add(Feature.SHADOW_DISABLED);
        }
        if(frame.antiAliasing().samplesPerPixel()==2)found.add(Feature.SPP_2);
        if(frame.antiAliasing().samplesPerPixel()==8)found.add(Feature.SPP_8);
        if(frame.mediumTransport().lightSamples()==0)found.add(Feature.ALL_LIGHTS);
        if(frame.mediumTransport().opticalSamples()>1)found.add(Feature.FIRST_INTERFACE_OPTICAL_SAMPLES);
        found.add(frame.mediumTransport().firstInterfaceSampling()==MediumTransport.FirstInterfaceSampling.STOCHASTIC_SINGLE_BRANCH
                ?Feature.FIRST_INTERFACE_STOCHASTIC:Feature.FIRST_INTERFACE_SPLIT);
        return Collections.unmodifiableSet(found);
    }
    private static MeshAsset plane(long id,float z,long material) {
        return MeshAsset.triangles(id,new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},new int[]{0,1,2,0,2,3},material)
                .toBuilder().textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).lightmapCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static MeshAsset ceiling(long id,float z,long material) {
        return MeshAsset.triangles(id,new float[]{-100,-100,z,100,-100,z,100,100,z,-100,100,z},new int[]{0,2,1,0,3,2},material);
    }
    private static MeshAsset cube(long id,long material) {
        return MeshAsset.triangles(id,new float[]{-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1},
                new int[]{0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5},material);
    }
    private static AffineTransform transform(float x,float y,float z,float tx,float ty,float tz) {
        return new AffineTransform(new float[]{x,0,0,tx,0,y,0,ty,0,0,z,tz});
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
