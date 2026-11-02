package top.ceroxe.rt.renderer.api;

/** Starting budgets, not GPU model detection or an FPS guarantee. Explicit configuration can override each field. */
public enum RendererPerformanceTier {
    LOW, MEDIUM, HIGH;
    public RendererResourceOptions resources(){return switch(this){case LOW->RendererResourceOptions.low();case MEDIUM->RendererResourceOptions.medium();case HIGH->RendererResourceOptions.high();};}
    public PathTracingOptions pathTracing(boolean cache){
        var quality=PathTracingQualityOptions.defaults();
        if(this==LOW)quality=quality.withDenoisingLayers(2,5).withCache(quality.cache().withCapacity(1024*1024));
        if(this==HIGH)quality=quality.withCache(quality.cache().withCapacity(4*1024*1024).withTrainingDownscale(4));
        return new PathTracingOptions(1,this==LOW?8:this==HIGH?20:12,this==LOW?1:2,
            cache?RendererFeaturePreference.PREFERRED:RendererFeaturePreference.DISABLED,
            RendererFeaturePreference.DISABLED,RendererFeaturePreference.DISABLED,this==LOW?2:this==HIGH?6:4,quality);
    }
    public FrameReconstructionOptions.Quality reconstructionQuality(){return this==HIGH?FrameReconstructionOptions.Quality.BALANCED:FrameReconstructionOptions.Quality.PERFORMANCE;}
}
