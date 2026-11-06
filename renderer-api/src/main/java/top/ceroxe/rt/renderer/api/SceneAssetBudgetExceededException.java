package top.ceroxe.rt.renderer.api;

/** Atomic scene admission rejection. The previously accepted scene remains usable. */
public final class SceneAssetBudgetExceededException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final long geometryBytes, textureBytes, totalAssetBytes;
    private final RendererResourceOptions limits;

    public SceneAssetBudgetExceededException(long geometryBytes, long textureBytes, RendererResourceOptions limits) {
        super("Retained scene asset budget exceeded: geometry="+geometryBytes+"/"+limits.geometryBytes()
                +", textures="+textureBytes+"/"+limits.textureBytes()+", total="+Math.addExact(geometryBytes,textureBytes)+"/"+limits.totalAssetBytes());
        this.geometryBytes=geometryBytes;this.textureBytes=textureBytes;
        totalAssetBytes=Math.addExact(geometryBytes,textureBytes);this.limits=limits;
    }
    public long geometryBytes(){return geometryBytes;}
    public long textureBytes(){return textureBytes;}
    public long totalAssetBytes(){return totalAssetBytes;}
    public RendererResourceOptions limits(){return limits;}
}
