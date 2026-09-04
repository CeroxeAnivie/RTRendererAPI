package top.ceroxe.rt.renderer.api;

/**
 * Complete, capability-driven policies for applications that do not need expert tuning.
 *
 * <p>A preset describes the application's frame-consumption contract, not a GPU vendor or game
 * engine. Unsupported optional technologies retain their documented renderer fallback. MFG is
 * never selected automatically.</p>
 */
public enum RendererPreset {
    /** Renderer-owned asynchronous CPU readback suitable for ordinary application integration. */
    CPU_READBACK,
    /** Renderer-owned Vulkan presentation with automatic FG 2x and latency pacing when supported. */
    MANAGED_GPU_PRESENTATION;

    /**
     * Returns the complete immutable policy represented by this preset.
     *
     * <p>Use this only when an expert needs to inspect or deliberately copy a preset before
     * changing it. Ordinary applications pass the preset directly to {@link RendererBootstrap}.</p>
     *
     * @return complete immutable renderer configuration
     */
    public RendererConfig configuration() {
        RendererConfig.Builder builder = RendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.recommended())
                // NRD remains an explicit expert opt-in until its temporal integration passes
                // the moving-object visual acceptance gate on all supported adapters.
                .denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.recommended());
        if (this == MANAGED_GPU_PRESENTATION) {
            builder.cpuFrameReadbackEnabled(false)
                    .frameGeneration(FrameGenerationOptions.recommended())
                    .lowLatency(LowLatencyOptions.recommended());
        }
        return builder.build();
    }
}
