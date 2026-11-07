package top.ceroxe.rt.renderer.api;

import java.util.Collection;

/** Retained asset admission limits, not JVM heap or total VRAM limits. */
public record RendererResourceOptions(long geometryBytes,long textureBytes,long totalAssetBytes) {
    private static final long MIB=1024L*1024;
    public RendererResourceOptions {
        if(geometryBytes<1||textureBytes<1||totalAssetBytes<1)
            throw new IllegalArgumentException("Retained asset byte budgets must be positive");
    }
    public static RendererResourceOptions low(){return new RendererResourceOptions(64*MIB,64*MIB,128*MIB);}
    public static RendererResourceOptions medium(){return new RendererResourceOptions(256*MIB,256*MIB,384*MIB);}
    public static RendererResourceOptions high(){return new RendererResourceOptions(1024*MIB,1024*MIB,1536*MIB);}
    public static RendererResourceOptions defaults(){return medium();}
    public RendererResourceOptions withAssetBytes(long geometry,long textures,long total){return new RendererResourceOptions(geometry,textures,total);}
    /** Validates raw immutable mesh/texture payloads before native admission. No silent asset removal. */
    public void validateAssets(Collection<MeshAsset> meshes,Collection<TextureAsset> textures){
        long geometry=0,pixels=0;
        for(var mesh:meshes)geometry=Math.addExact(geometry,meshBytes(mesh));
        for(var texture:textures)pixels=Math.addExact(pixels,texture.rgba8().remaining());
        validateAssetBytes(geometry,pixels);
    }
    /** Checks exact retained payload totals; this does not allocate or evict assets. */
    public void validateAssetBytes(long geometry,long pixels){
        if(geometry<0||pixels<0)throw new IllegalArgumentException("Retained asset totals must not be negative");
        if(geometry>geometryBytes||pixels>textureBytes||Math.addExact(geometry,pixels)>totalAssetBytes)
            throw new SceneAssetBudgetExceededException(geometry,pixels,this);
    }
    public static long meshBytes(MeshAsset m){return 4L*(m.positions().remaining()+m.normals().remaining()+m.tangents().remaining()
        +(long)m.textureCoordinates().remaining()+m.lightmapCoordinates().remaining()+m.vertexColorsRgba8().remaining()+m.triangleIndices().remaining())
        +8L*m.triangleMaterialIds().remaining();}
}
