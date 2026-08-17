package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.BindingKey;
import top.ceroxe.rt.renderer.api.BindingLayoutEntry;
import top.ceroxe.rt.renderer.api.BindingType;
import top.ceroxe.rt.renderer.api.RenderResourceId;
import top.ceroxe.rt.renderer.api.ResourceVersion;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderReflection;
import top.ceroxe.rt.renderer.api.ShaderStage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

/**
 * Pure-JVM boundary checks for descriptor reflection embedded in SPIR-V.
 *
 * <p>These modules intentionally contain only the declarations consumed by the validator. The
 * test does not prove shader execution; it proves that the public binding contract cannot claim
 * a split image/sampler interface for a binary that requires Vulkan's combined descriptor.</p>
 */
public final class VulkanSpirvBindingValidatorSelfTest {
    private VulkanSpirvBindingValidatorSelfTest() {
    }

    public static void main(String[] arguments) {
        BindingLayoutEntry combined = binding(0, 0, BindingType.COMBINED_IMAGE_SAMPLER);
        VulkanSpirvBindingValidator.requireDeclaredInterface(module(
                ShaderStage.FRAGMENT, "main", List.of(combined)
        ));
        VulkanSpirvBindingValidator.requireDeclaredInterface(accelerationStructureModule(
                binding(0, 0, BindingType.ACCELERATION_STRUCTURE, ShaderStage.RAY_GENERATION)
        ));

        requireRejected(() -> VulkanSpirvBindingValidator.requireDeclaredInterface(module(
                ShaderStage.FRAGMENT, "main", List.of(binding(0, 0, BindingType.SAMPLED_TEXTURE))
        )), "split sampled texture declaration must not match OpTypeSampledImage");
        requireRejected(() -> VulkanSpirvBindingValidator.requireDeclaredInterface(module(
                ShaderStage.FRAGMENT, "main", List.of(binding(0, 1, BindingType.COMBINED_IMAGE_SAMPLER))
        )), "descriptor location mismatch must fail closed");
        requireRejected(() -> VulkanSpirvBindingValidator.requireDeclaredInterface(module(
                ShaderStage.VERTEX, "main", List.of(binding(0, 0, BindingType.COMBINED_IMAGE_SAMPLER, ShaderStage.VERTEX))
        )), "declared shader stage must match SPIR-V entry point");
        requireRejected(() -> VulkanSpirvBindingValidator.requireDeclaredInterface(module(
                ShaderStage.FRAGMENT, "entry", List.of(combined)
        )), "declared entry point must match SPIR-V entry point");

        System.out.println("VulkanSpirvBindingValidatorSelfTest passed");
    }

    private static BindingLayoutEntry binding(int set, int binding, BindingType type) {
        return binding(set, binding, type, ShaderStage.FRAGMENT);
    }

    private static BindingLayoutEntry binding(int set, int binding, BindingType type, ShaderStage stage) {
        return new BindingLayoutEntry(
                new BindingKey(set, binding), type, 1, Set.of(stage), false
        );
    }

    private static ShaderModule module(ShaderStage stage, String entryPoint, List<BindingLayoutEntry> bindings) {
        ByteBuffer words = ByteBuffer.allocateDirect(38 * Integer.BYTES).order(ByteOrder.nativeOrder());
        words.putInt(0x07230203); // SPIR-V magic
        words.putInt(0x00010000); // SPIR-V 1.0
        words.putInt(0);
        words.putInt(7);
        words.putInt(0);
        instruction(words, 15, 5, 4, 1, 0x6e69616d, 0); // OpEntryPoint Fragment %1 "main"
        instruction(words, 71, 4, 6, 34, 0); // OpDecorate %6 DescriptorSet 0
        instruction(words, 71, 4, 6, 33, 0); // OpDecorate %6 Binding 0
        instruction(words, 25, 9, 2, 3, 1, 0, 0, 0, 1, 0); // OpTypeImage ... Sampled=1
        instruction(words, 27, 3, 4, 2); // OpTypeSampledImage %4 %2
        instruction(words, 32, 4, 5, 0, 4); // OpTypePointer UniformConstant %4
        instruction(words, 59, 4, 5, 6, 0); // OpVariable %5 %6 UniformConstant
        words.flip();
        return new ShaderModule(
                new RenderResourceId(1L), ResourceVersion.initial(), stage, entryPoint, words,
                new ShaderReflection(bindings, 0)
        );
    }

    private static ShaderModule accelerationStructureModule(BindingLayoutEntry binding) {
        ByteBuffer words = ByteBuffer.allocateDirect(33 * Integer.BYTES).order(ByteOrder.nativeOrder());
        words.putInt(0x07230203); // SPIR-V magic
        words.putInt(0x00010000); // SPIR-V 1.0 header is sufficient for descriptor parsing
        words.putInt(0);
        words.putInt(5);
        words.putInt(0);
        instruction(words, 15, 5, 5313, 1, 0x6e69616d, 0); // OpEntryPoint RayGenerationKHR %1 "main"
        instruction(words, 71, 4, 4, 34, 0); // OpDecorate %4 DescriptorSet 0
        instruction(words, 71, 4, 4, 33, 0); // OpDecorate %4 Binding 0
        instruction(words, 5341, 2, 2); // OpTypeAccelerationStructureKHR %2
        instruction(words, 32, 4, 3, 0, 2); // OpTypePointer UniformConstant %2
        instruction(words, 59, 4, 3, 4, 0); // OpVariable %3 %4 UniformConstant
        words.flip();
        return new ShaderModule(
                new RenderResourceId(2L), ResourceVersion.initial(), ShaderStage.RAY_GENERATION, "main", words,
                new ShaderReflection(List.of(binding), 0)
        );
    }

    private static void instruction(ByteBuffer words, int opcode, int wordCount, int... operands) {
        if (operands.length != wordCount - 1) throw new AssertionError("malformed test instruction");
        words.putInt((wordCount << 16) | opcode);
        for (int operand : operands) words.putInt(operand);
    }

    private static void requireRejected(Runnable operation, String message) {
        try {
            operation.run();
        } catch (VulkanGenericPipelineCompilationException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
