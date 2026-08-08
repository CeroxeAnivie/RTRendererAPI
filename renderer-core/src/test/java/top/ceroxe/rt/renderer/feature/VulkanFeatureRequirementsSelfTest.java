package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.NVRayTracingInvocationReorder;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;

import java.util.Map;
import java.util.Set;

/** Deterministic gate for pre-device requirements that must not depend on a Vulkan driver. */
public final class VulkanFeatureRequirementsSelfTest {
    private VulkanFeatureRequirementsSelfTest() {
    }

    public static void main(String[] arguments) {
        preservesTypedPreDeviceRequirements();
        rejectsRequiredPreferredFeatureOverlap();
        promotesPreferredRequirementsWithoutOverlap();
        preservesQueueRolesAndDetectsOverflow();
        planDefensivelyCopiesProviderRequirements();
        releasesAbandonedProviderPlanExactlyOnce();
        transfersClaimedProviderPlanWithoutDiscardingIt();
        plansSerExtensionAtTheRequestedStrength();
    }

    private static void plansSerExtensionAtTheRequestedStrength() {
        String extension = NVRayTracingInvocationReorder.VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;
        VulkanFeaturePlan preferred = VulkanFeatureRegistry.plan(configuration(RendererFeaturePreference.PREFERRED));
        require(preferred.preferredDeviceExtensions().contains(extension));
        require(!preferred.requiredDeviceExtensions().contains(extension));
        require(preferred.capabilities().feature(Feature.SHADER_EXECUTION_REORDERING).status()
                == Status.BLOCKED);
        require(preferred.capabilities().technology(Technology.SHADER_EXECUTION_REORDERING).status()
                == Status.BLOCKED);

        VulkanFeaturePlan required = VulkanFeatureRegistry.plan(configuration(RendererFeaturePreference.REQUIRED));
        require(required.requiredDeviceExtensions().contains(extension));
        require(!required.preferredDeviceExtensions().contains(extension));
    }

    private static RayTracingRendererConfig configuration(RendererFeaturePreference preference) {
        return RayTracingRendererConfig.expertBuilder()
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .frameGeneration(FrameGenerationOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.builder()
                        .shaderExecutionReordering(preference)
                        .build())
                .build();
    }

    private static void preservesTypedPreDeviceRequirements() {
        VulkanFeatureRequirements requirements = VulkanFeatureRequirements.builder()
                .requireVulkan12Feature(Vulkan12Feature.DESCRIPTOR_INDEXING)
                .preferVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2)
                .requireQueues(new VulkanQueueRequirements(1, 2, 0))
                .preferQueues(new VulkanQueueRequirements(0, 0, 1))
                .build();
        require(requirements.requiredVulkan12Features().equals(Set.of(Vulkan12Feature.DESCRIPTOR_INDEXING)));
        require(requirements.preferredVulkan13Features().equals(Set.of(Vulkan13Feature.SYNCHRONIZATION_2)));
        require(requirements.requiredQueues().equals(new VulkanQueueRequirements(1, 2, 0)));
        require(requirements.preferredQueues().equals(new VulkanQueueRequirements(0, 0, 1)));
    }

    private static void rejectsRequiredPreferredFeatureOverlap() {
        expectIllegalArgument(() -> VulkanFeatureRequirements.builder()
                .requireVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY)
                .preferVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY)
                .build());
    }

    private static void promotesPreferredRequirementsWithoutOverlap() {
        VulkanFeatureRequirements requirements = VulkanFeatureRequirements.builder()
                .preferInstanceExtension("VK_TEST_instance")
                .preferDeviceExtension("VK_TEST_device")
                .preferVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY)
                .preferVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2)
                .promoteInstanceExtension("VK_TEST_instance")
                .promoteDeviceExtension("VK_TEST_device")
                .promoteVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY)
                .promoteVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2)
                .build();
        require(requirements.requiredInstanceExtensions().equals(Set.of("VK_TEST_instance")));
        require(requirements.preferredInstanceExtensions().isEmpty());
        require(requirements.requiredDeviceExtensions().equals(Set.of("VK_TEST_device")));
        require(requirements.preferredDeviceExtensions().isEmpty());
        require(requirements.requiredVulkan12Features().equals(Set.of(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY)));
        require(requirements.preferredVulkan12Features().isEmpty());
        require(requirements.requiredVulkan13Features().equals(Set.of(Vulkan13Feature.SYNCHRONIZATION_2)));
        require(requirements.preferredVulkan13Features().isEmpty());

        VulkanFeatureRequirements reverseOrder = VulkanFeatureRequirements.builder()
                .mergeInstanceExtension("VK_TEST_instance", true)
                .mergeDeviceExtension("VK_TEST_device", true)
                .mergeVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY, true)
                .mergeVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2, true)
                .mergeInstanceExtension("VK_TEST_instance", false)
                .mergeDeviceExtension("VK_TEST_device", false)
                .mergeVulkan12Feature(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY, false)
                .mergeVulkan13Feature(Vulkan13Feature.SYNCHRONIZATION_2, false)
                .build();
        require(reverseOrder.requiredInstanceExtensions().equals(requirements.requiredInstanceExtensions()));
        require(reverseOrder.requiredDeviceExtensions().equals(requirements.requiredDeviceExtensions()));
        require(reverseOrder.requiredVulkan12Features().equals(requirements.requiredVulkan12Features()));
        require(reverseOrder.requiredVulkan13Features().equals(requirements.requiredVulkan13Features()));
        require(reverseOrder.preferredInstanceExtensions().isEmpty());
        require(reverseOrder.preferredDeviceExtensions().isEmpty());
        require(reverseOrder.preferredVulkan12Features().isEmpty());
        require(reverseOrder.preferredVulkan13Features().isEmpty());
    }

    private static void preservesQueueRolesAndDetectsOverflow() {
        VulkanQueueRequirements combined = new VulkanQueueRequirements(1, 2, 3)
                .plus(new VulkanQueueRequirements(4, 5, 6));
        require(combined.equals(new VulkanQueueRequirements(5, 7, 9)));
        require(combined.additionalGraphicsComputeQueues() == 12);
        expectArithmetic(() -> new VulkanQueueRequirements(Integer.MAX_VALUE, 0, 0)
                .plus(new VulkanQueueRequirements(1, 0, 0)));
        require(Vulkan12Feature.fromStreamlineName("runtimeDescriptorArray") == Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY);
        require(Vulkan13Feature.fromStreamlineName("synchronization2") == Vulkan13Feature.SYNCHRONIZATION_2);
        expectIllegalArgument(() -> Vulkan12Feature.fromStreamlineName("notARealFeature"));
    }

    private static void planDefensivelyCopiesProviderRequirements() {
        Map<String, VulkanQueueRequirements> requiredProviderQueues =
                Map.of("provider", new VulkanQueueRequirements(1, 0, 0));
        Map<String, VulkanQueueRequirements> preferredProviderQueues =
                Map.of("provider", new VulkanQueueRequirements(0, 1, 1));
        VulkanFeaturePlan plan = new VulkanFeaturePlan(
                Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(Vulkan12Feature.RUNTIME_DESCRIPTOR_ARRAY), Set.of(),
                Set.of(), Set.of(Vulkan13Feature.SYNCHRONIZATION_2),
                new VulkanQueueRequirements(1, 0, 0), VulkanQueueRequirements.NONE,
                java.util.List.of(), RenderingFeatureCapabilities.builder().build(),
                Map.of(), Map.of(), requiredProviderQueues, preferredProviderQueues, Map.of()
        );
        expectUnsupported(() -> plan.requiredVulkan12Features().add(Vulkan12Feature.DESCRIPTOR_INDEXING));
        expectUnsupported(() -> plan.preferredVulkan13Features().clear());
        require(plan.requiredQueues().equals(new VulkanQueueRequirements(1, 0, 0)));
        require(plan.providerRequiredQueueRequirements().equals(requiredProviderQueues));
        require(plan.providerPreferredQueueRequirements().equals(preferredProviderQueues));
        expectUnsupported(plan.providerRequiredQueueRequirements()::clear);
        expectUnsupported(plan.providerPreferredQueueRequirements()::clear);
    }

    private static void releasesAbandonedProviderPlanExactlyOnce() {
        TrackingProvider provider = new TrackingProvider();
        VulkanFeaturePlan plan = planWith(provider);
        plan.close();
        plan.close();
        require(provider.discards == 1);
        expectIllegalState(plan::claimProviders);
    }

    private static void transfersClaimedProviderPlanWithoutDiscardingIt() {
        TrackingProvider provider = new TrackingProvider();
        VulkanFeaturePlan plan = planWith(provider);
        require(plan.claimProviders().equals(java.util.List.of(provider)));
        plan.close();
        require(provider.discards == 0);
        expectIllegalState(plan::claimProviders);
    }

    private static VulkanFeaturePlan planWith(VulkanFeatureProvider provider) {
        return new VulkanFeaturePlan(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                VulkanQueueRequirements.NONE, VulkanQueueRequirements.NONE,
                java.util.List.of(provider), RenderingFeatureCapabilities.builder().build(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of()
        );
    }

    private static final class TrackingProvider implements VulkanFeatureProvider {
        private int discards;

        @Override
        public String id() {
            return "tracking";
        }

        @Override
        public VulkanFeatureRequirements requirements(RayTracingRendererConfig configuration) {
            return VulkanFeatureRequirements.builder().build();
        }

        @Override
        public void discardPlan() {
            discards++;
        }

        @Override
        public VulkanFeatureSession open(VulkanFeatureOpenContext context) {
            throw new UnsupportedOperationException("tracking provider is never opened");
        }
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Required and preferred declarations must never describe the same device feature.
        }
    }

    private static void expectArithmetic(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected ArithmeticException");
        } catch (ArithmeticException expected) {
            // Queue counts are passed to a native create-info and must never wrap.
        }
    }

    private static void expectIllegalState(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // A plan has exactly one ownership transfer or discard transition.
        }
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Plans cross the device-creation boundary and therefore must be immutable.
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("contract expectation failed");
    }
}
