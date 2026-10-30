package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import top.ceroxe.rt.renderer.api.*;

/** Caches emitter admission and initial medium sets independently of moving camera frames. */
final class CausticSceneIndex {
    private record Light(int slot, SceneLight value) {}
    private final Map<Long, Light> lights = new HashMap<>();
    private long generation, cachedGeneration = -1;
    private FramePrimitiveBatch cachedBatch;
    private Caustics cachedPolicy;
    private EnvironmentState cachedEnvironment;
    private CausticPhotonPlan cachedPlan;
    private int cachedDepositsPerPath;

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        if (changes.reset()) lights.clear();
        for (long id : changes.lights().removedIdentities()) lights.remove(id);
        for (var write : changes.lights().writes()) lights.put(write.id(), new Light(write.slot(), write.value()));
        generation++;
    }

    CausticPhotonPlan plan(RenderFrameRequest request, MediumSceneIndex media) {
        var policy = request.caustics();
        if (!policy.enabled()) return CausticPhotonPlan.DISABLED;
        boolean scattering=!request.environment().cameraMedium().scattering().isZero()
                ||media.hasScattering(request.primitiveBatch());
        int depositsPerPath=scattering?Math.min(policy.maxEvents(),request.mediumTransport().scatteringBounces()+2):1;
        if (generation == cachedGeneration && cachedBatch == request.primitiveBatch()
                && policy.equals(cachedPolicy) && request.environment().equals(cachedEnvironment)
                &&depositsPerPath==cachedDepositsPerPath)return cachedPlan;
        media.validateCausticRegion(request.primitiveBatch(), policy);
        var emitters = new ArrayList<CausticPhotonPlan.Emitter>();
        var env = request.environment();
        if (env.sunIntensity() > 0 && Math.max(env.sunRed(), Math.max(env.sunGreen(), env.sunBlue())) > 0)
            emitters.add(new CausticPhotonPlan.Emitter(-1, new int[0]));
        // Stable order makes seeded frames reproducible across hash-map iteration orders.
        lights.values().stream().sorted(java.util.Comparator.comparingInt(Light::slot)).forEach(entry -> {
            var light = entry.value;
            if (!light.castsShadow() || light.intensity() <= 0 || Math.max(light.red(), Math.max(light.green(), light.blue())) <= 0) return;
            if (light.type() != SceneLight.Type.DIRECTIONAL) {
                double distance = Math.hypot(Math.hypot(light.x()-policy.centerX(), light.y()-policy.centerY()), light.z()-policy.centerZ());
                if (distance-policy.radius() >= light.range()) return;
            }
            int[] initial = light.type() == SceneLight.Type.DIRECTIONAL ? new int[0]
                    : media.containingPoint(request.primitiveBatch(), light.x(), light.y(), light.z());
            emitters.add(new CausticPhotonPlan.Emitter(entry.slot, initial));
        });
        var plan = new CausticPhotonPlan(policy.photons(), emitters,depositsPerPath);
        cachedGeneration = generation; cachedBatch = request.primitiveBatch(); cachedPolicy = policy;
        cachedEnvironment = env;cachedDepositsPerPath=depositsPerPath;cachedPlan=plan;
        return plan;
    }
}
