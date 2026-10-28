package demo;

import top.ceroxe.rt.renderer.api.*;

/** CPU-only gallery fixture gate. Does not load a renderer, window, Vulkan device or FG runtime. */
public final class MediumGallerySceneSelfTest {
    public static void main(String[] args) {
        var defaults=MediumGalleryDemo.Options.parse(new String[0]);
        var config=MediumGalleryDemo.configuration(defaults);
        require(defaults.width()==2560 && defaults.height()==1440 && defaults.spp()==1,"2K / 1 spp defaults");
        require(config.frameGeneration().preference().requested() && config.frameGeneration().multiplier()==FrameGenerationOptions.Multiplier.TWO_X,"2x FG requested by default");
        require(!config.cpuFrameReadbackEnabled() && !config.frameReconstruction().preference().requested(),"native-resolution managed presentation");
        for (String[] options:new String[][]{ {},{"--inward=true"},{"--transient=false"},{"--nested=false"},
                {"--heterogeneous=false"},{"--caustics=false"},{"--refraction=false"},{"--scattering=false","--bounces=0"},
                {"--absorption=false","--emission=false"},{"--inside=true","--animate=false"} }) {
            var parsed=MediumGalleryDemo.Options.parse(options);var scene=new MediumGalleryScene(parsed);
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
        reject(new String[]{"--fg=perhaps"});reject(new String[]{"--width=0"});reject(new String[]{"--spp=9"});
        reject(new String[]{"--capture=image.png"});reject(new String[]{"--bounces=-1"});reject(new String[]{"--width=1","--width=2"});
        var capture=MediumGalleryDemo.Options.parse(new String[]{"--fg=false","--capture=build/gallery.png"});
        require(MediumGalleryDemo.configuration(capture).cpuFrameReadbackEnabled(),"explicit non-FG capture policy");
        System.out.println("MediumGallerySceneSelfTest passed: defaults, requested FG, native resolution, manifold fixtures, all feature switches, capture policy");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void reject(String[] args) {
        try {MediumGalleryDemo.Options.parse(args);}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("invalid gallery options accepted");
    }
}
