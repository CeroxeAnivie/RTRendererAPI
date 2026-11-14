package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.LogicOperation;
import top.ceroxe.rt.renderer.api.RenderPipelineStage;

import java.util.EnumSet;

/** Pure regression for the complete portable-to-Vulkan logic-operation mapping. */
public final class VulkanGenericGraphicsPipelinesSelfTest {
    private VulkanGenericGraphicsPipelinesSelfTest() { }

    public static void main(String[] args) {
        int[] expected = {
                VK10.VK_LOGIC_OP_CLEAR,
                VK10.VK_LOGIC_OP_AND,
                VK10.VK_LOGIC_OP_AND_REVERSE,
                VK10.VK_LOGIC_OP_COPY,
                VK10.VK_LOGIC_OP_AND_INVERTED,
                VK10.VK_LOGIC_OP_NO_OP,
                VK10.VK_LOGIC_OP_XOR,
                VK10.VK_LOGIC_OP_OR,
                VK10.VK_LOGIC_OP_NOR,
                VK10.VK_LOGIC_OP_EQUIVALENT,
                VK10.VK_LOGIC_OP_INVERT,
                VK10.VK_LOGIC_OP_OR_REVERSE,
                VK10.VK_LOGIC_OP_COPY_INVERTED,
                VK10.VK_LOGIC_OP_OR_INVERTED,
                VK10.VK_LOGIC_OP_NAND,
                VK10.VK_LOGIC_OP_SET
        };
        LogicOperation[] values = LogicOperation.values();
        require(values.length == expected.length, "logic operation enum size changed unexpectedly");
        for (int index = 0; index < expected.length; index++) {
            require(VulkanGenericGraphicsPipelines.logicOp(values[index]) == expected[index],
                    "logic operation mapping is incorrect for " + values[index]);
            require(expected[index] >= VK10.VK_LOGIC_OP_CLEAR && expected[index] <= VK10.VK_LOGIC_OP_SET,
                    "logic operation mapping produced an invalid Vulkan enum for " + values[index]);
        }
        require(VulkanGenericCommandSession.stageMask(EnumSet.of(RenderPipelineStage.GEOMETRY_SHADER))
                        == VK10.VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT,
                "geometry shader barrier stage did not map to Vulkan geometry stage");
        System.out.println("VulkanGenericGraphicsPipelinesSelfTest passed: mapped=" + expected.length);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
