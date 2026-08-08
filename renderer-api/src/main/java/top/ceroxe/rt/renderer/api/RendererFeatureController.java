package top.ceroxe.rt.renderer.api;

/**
 * Expert extension for explicit, generation-safe runtime feature transitions.
 *
 * <p>Obtain this stable service through
 * {@code renderer.extension(RendererFeatureController.class)}. Planning is read-only. Applying a
 * plan never silently rebuilds a swapchain, pipeline, scene, or renderer; the returned typed
 * outcome leaves those ownership decisions with the application.</p>
 */
public interface RendererFeatureController {
    /**
     * Returns the currently effective profile, which may differ from configured intent after fallback.
     *
     * @return effective feature profile
     */
    RendererFeatureProfile effectiveProfile();

    /**
     * Produces a single-use assessment bound to the current controller generation.
     *
     * @param target requested feature profile
     * @return generation-bound plan
     */
    RendererFeaturePlan plan(RendererFeatureProfile target);

    /**
     * Applies one plan or returns the exact boundary preventing an in-session transition.
     *
     * @param plan previously prepared plan
     * @return typed application result
     */
    RendererFeatureApplyResult apply(RendererFeaturePlan plan);

    /**
     * Returns immutable counters and the latest plan/result without probing vendor runtimes.
     *
     * @return immutable control diagnostics
     */
    RendererFeatureControlDiagnostics featureControlDiagnostics();
}
