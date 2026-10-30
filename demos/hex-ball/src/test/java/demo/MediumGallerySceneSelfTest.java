package demo;

import top.ceroxe.rt.renderer.api.*;

/** CPU-only gallery fixture gate. Does not load a renderer, window, Vulkan device or FG runtime. */
public final class MediumGallerySceneSelfTest {
    public static void main(String[] args) {
        var defaults=MediumGalleryDemo.Options.parse(new String[0]);
        var config=MediumGalleryDemo.configuration(defaults);
        require(defaults.width()==2560 && defaults.height()==1440 && defaults.spp()==1,"2K / 1 spp defaults");
        require(!defaults.fg() && !config.frameGeneration().preference().requested(),"native frame path is the default");
        require(!defaults.caustics() && !config.cpuFrameReadbackEnabled() && !config.frameReconstruction().preference().requested(),"native-resolution managed presentation; caustics are not claimed by default");
        require(defaults.scene()==MediumGalleryScene.Preset.PBR_DIRECT_UNIFORM && defaults.controls(),"interactive PBR acceptance preset is the default");
        for (String[] options:new String[][]{ {},{"--inward=true"},{"--transient=false"},{"--nested=false"},
                {"--heterogeneous=false"},{"--caustics=false"},{"--refraction=false"},{"--scattering=false","--bounces=0"},
                {"--absorption=false","--emission=false"},{"--inside=true","--animate=false"} }) {
            var withGallery=java.util.Arrays.copyOf(options,options.length+1);withGallery[options.length]="--scene=gallery";
            var parsed=MediumGalleryDemo.Options.parse(withGallery);var scene=new MediumGalleryScene(parsed);
            var tx=scene.transaction();
            require(tx.reset() && tx.upserts().lights().size()==2,"one complete deterministic scene transaction");
            for (var mesh:tx.upserts().meshes()) require(mesh.isClosedManifold(),"gallery mesh is closed and welded: "+mesh.id());
            var frame=scene.frame(3,parsed.width(),parsed.height(),1.25,1.0/60);
            require(frame.antiAliasing().samplesPerPixel()==1 && frame.depthProjection().known(),"FG camera and depth inputs");
            require(frame.caustics().enabled()==parsed.caustics() && frame.mediumTransport().scatteringBounces()==parsed.bounces(),"feature policies reach request");
            require(scene.transientVolumes().mediumBoundaryIndices().size()==(parsed.transientVolumes()?2:0),"persistent/transient volume selection");
            for (var instance:tx.upserts().instances()) instance.mediumBoundary().ifPresent(boundary->
                    require(tx.upserts().meshes().stream().anyMatch(mesh->mesh.id()==instance.meshAssetId()&&mesh.isClosedManifold()),"every medium has a resident manifold"));
        }
        for (var preset:MediumGalleryScene.Preset.values()) {
            if(preset==MediumGalleryScene.Preset.GALLERY)continue;
            var parsed=MediumGalleryDemo.Options.parse(new String[]{"--scene="+preset.id,"--bounces=3"});
            var scene=new MediumGalleryScene(parsed,preset,7);var tx=scene.transaction();
            var frame=scene.frame(0,640,360,0,1.0/60);
            var cameraMedium=frame.environment().cameraMedium();
            require(tx.reset() && tx.revision()==7,"preset is a full revisioned scene transaction: "+preset.id);
            require(frame.minimumSceneRevision()==7 && frame.caustics().enabled()==preset.caustic(),"preset frame and optical caustic policy match: "+preset.id);
            require(tx.upserts().lights().size()==2,"preset has complete lighting: "+preset.id);
            if(!preset.caustic())
                require(tx.upserts().meshes().stream().allMatch(MeshAsset::isClosedManifold),"volume preset geometry is closed: "+preset.id);
            if(preset.ordinal()<=MediumGalleryScene.Preset.PBR_MULTIPLE_DENSITY.ordinal()) {
                require(!cameraMedium.densityField().isUniform()==preset.density,"PBR density mode is routed through camera medium: "+preset.id);
                require(!cameraMedium.scattering().isZero(),"PBR preset enables scattering: "+preset.id);
                require(frame.mediumTransport().scatteringBounces()==(preset.multiple?3:0),"PBR preset selects direct or multiple scatter transport: "+preset.id);
                require(tx.upserts().textures().stream().anyMatch(texture->texture.id()==2)==preset.density,"density texture follows preset mode: "+preset.id);
            }
            if(preset==MediumGalleryScene.Preset.BOUNDARY_OPTICAL_SCATTER) {
                require(cameraMedium.isVacuum(),"boundary scattering remains on explicit closed boundaries");
                var boundary=tx.upserts().instances().stream().map(instance->instance.mediumBoundary().orElse(null))
                        .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                require(boundary.medium().indexOfRefraction()==1.48f && !boundary.medium().scattering().isZero(),"boundary preset combines refraction and scattering");
            }
            if(preset==MediumGalleryScene.Preset.ABSORPTION_EMISSION) {
                require(!cameraMedium.absorption().isZero() && !cameraMedium.emission().isZero() && cameraMedium.scattering().isZero(),"absorption and emission preset routes those terms independently");
            }
            if(preset==MediumGalleryScene.Preset.GLASS_SCATTER_CAUSTICS) {
                require(cameraMedium.indexOfRefraction()==1 && cameraMedium.densityField().isUniform()
                        && !cameraMedium.scattering().isZero(),"optical caustic preset uses global uniform IOR-one scattering");
                require(tx.upserts().instances().stream().noneMatch(instance->instance.mediumBoundary().isPresent()),
                        "optical caustic preset avoids explicit medium boundaries");
                require(tx.upserts().materials().stream().anyMatch(material->material.transmission()==1),"caustic preset contains refractive glass");
            }
            if(preset.caustic()&&preset.density) {
                require(tx.upserts().textures().stream().anyMatch(t->t.id()==2),"caustic density atlas is resident");
                require(preset==MediumGalleryScene.Preset.BOUNDARY_DENSITY_CAUSTICS?cameraMedium.isVacuum():!cameraMedium.densityField().isUniform(),
                        "caustic density follows the global or explicit medium");
            }
        }
        reject(new String[]{"--fg=perhaps"});reject(new String[]{"--width=0"});reject(new String[]{"--spp=9"});
        reject(new String[]{"--capture=image.png","--fg=true"});reject(new String[]{"--bounces=-1"});reject(new String[]{"--width=1","--width=2"});
        reject(new String[]{"--scene=unknown"});reject(new String[]{"--cycle-seconds=-1"});
        reject(new String[]{"--caustics=true"});
        var noCaustics=MediumGalleryDemo.Options.parse(new String[]{"--scene=glass-scatter-caustics","--caustics=false"});
        require(!new MediumGalleryScene(noCaustics).frame(0,640,360,0,0).caustics().enabled(),"specialized caustic preset has an explicit off control");
        var capture=MediumGalleryDemo.Options.parse(new String[]{"--fg=false","--capture=build/gallery.png"});
        require(MediumGalleryDemo.configuration(capture).cpuFrameReadbackEnabled(),"explicit non-FG capture policy");
        System.out.println("MediumGallerySceneSelfTest passed: native defaults, gallery feature switches, four PBR medium combinations, explicit boundary refraction/scattering, glass/scattering/caustics cross-feature, absorption/emission, revision and capture policy");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void reject(String[] args) {
        try {MediumGalleryDemo.Options.parse(args);}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("invalid gallery options accepted");
    }
}
