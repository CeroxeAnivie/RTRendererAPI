package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineTessellationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.BlendFactor;
import top.ceroxe.rt.renderer.api.BlendOperation;
import top.ceroxe.rt.renderer.api.ColorTargetBlendState;
import top.ceroxe.rt.renderer.api.CompareOperation;
import top.ceroxe.rt.renderer.api.DepthStencilState;
import top.ceroxe.rt.renderer.api.GraphicsPipelineState;
import top.ceroxe.rt.renderer.api.LogicOperation;
import top.ceroxe.rt.renderer.api.PrimitiveTopology;
import top.ceroxe.rt.renderer.api.RasterState;
import top.ceroxe.rt.renderer.api.RenderPassDescriptor;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.api.VertexAttribute;
import top.ceroxe.rt.renderer.api.VertexBufferLayout;
import top.ceroxe.rt.renderer.api.VertexFormat;
import top.ceroxe.rt.renderer.api.VertexLayout;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns validated Vulkan graphics pipelines without coupling them to frame submission. */
final class VulkanGenericGraphicsPipelines implements AutoCloseable {
    private final VkDevice device;
    private final VulkanGenericResourceRegistry resources;
    private final int maximumBoundDescriptorSets;
    private final Map<GraphicsPipelineState, Compiled> cache = new LinkedHashMap<>();
    private boolean closed;

    VulkanGenericGraphicsPipelines(VkDevice device, VulkanGenericResourceRegistry resources,
                                   int maximumBoundDescriptorSets) {
        this.device = Objects.requireNonNull(device, "device");
        this.resources = Objects.requireNonNull(resources, "resources");
        if (maximumBoundDescriptorSets <= 0) throw new IllegalArgumentException("maximumBoundDescriptorSets must be positive");
        this.maximumBoundDescriptorSets = maximumBoundDescriptorSets;
    }

    Compiled require(GraphicsPipelineState state) {
        requireOpen();
        Objects.requireNonNull(state, "state");
        return cache.computeIfAbsent(state, this::compile);
    }

    void updateBindings(Compiled pipeline, BindingSet bindings) {
        if (pipeline.descriptors() != null) {
            pipeline.descriptors().update(bindings, resources, resources.samplers());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Compiled pipeline : cache.values()) {
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                VK10.vkDestroyPipeline(device, pipeline.pipeline(), null);
                VK10.vkDestroyPipelineLayout(device, pipeline.layout(), null);
            }
        }
        cache.clear();
        if (failure != null) throw failure;
    }

    private Compiled compile(GraphicsPipelineState state) {
        long pipeline = VK10.VK_NULL_HANDLE;
        long layout = VK10.VK_NULL_HANDLE;
        VulkanGenericDescriptorSetBank descriptors = null;
        java.util.ArrayList<Long> shaderModules = new java.util.ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!state.program().bindingLayout().entries().isEmpty()) {
                descriptors = VulkanGenericDescriptorSetBank.create(
                        stack, device, state.program().bindingLayout(), maximumBoundDescriptorSets
                );
            }
            layout = createPipelineLayout(stack, state, descriptors);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(
                    state.program().modules().size(), stack
            );
            int stageIndex = 0;
            for (ShaderModule module : state.program().modules()) {
                long shaderModule = createShaderModule(stack, module);
                shaderModules.add(shaderModule);
                VkPipelineShaderStageCreateInfo stage = stages.get(stageIndex++)
                        .sType$Default().stage(stageFlags(module.stage())).module(shaderModule)
                        .pName(stack.UTF8(module.entryPoint()));
                // Shader modules are kept alive until pipeline creation returns. The native handles
                // are released in the finally block after vkCreateGraphicsPipelines.
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = vertexInput(stack, state.vertexLayout());
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(topology(state.topology()))
                    .primitiveRestartEnable(state.primitiveRestartEnabled());
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = rasterization(stack, state.rasterState());
            VkPipelineMultisampleStateCreateInfo multisample = multisample(stack, state.multisampleState());
            VkPipelineDepthStencilStateCreateInfo depthStencil = depthStencil(stack, state.depthStencilState().orElse(null));
            VkPipelineColorBlendStateCreateInfo blend = blend(stack, state.blendState());
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default().pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType$Default()
                    .colorAttachmentCount(state.colorTargetFormats().size())
                    .pColorAttachmentFormats(colorFormats(stack, state.colorTargetFormats()))
                    .depthAttachmentFormat(state.depthStencilFormat().map(VulkanGenericTextureImage::format).orElse(VK10.VK_FORMAT_UNDEFINED))
                    .stencilAttachmentFormat(state.depthStencilFormat()
                            .filter(format -> format.supports(top.ceroxe.rt.renderer.api.TextureAspect.STENCIL))
                            .map(VulkanGenericTextureImage::format).orElse(VK10.VK_FORMAT_UNDEFINED));
            VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default().pNext(rendering.address())
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport).pRasterizationState(raster).pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil).pColorBlendState(blend).pDynamicState(dynamic)
                    .layout(layout);
            if (state.topology().isPatchList()) {
                int points = state.patchControlPoints().orElseThrow();
                createInfo.pTessellationState(VkPipelineTessellationStateCreateInfo.calloc(stack)
                        .sType$Default().patchControlPoints(points));
            }
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            VulkanFailures.check(VK10.vkCreateGraphicsPipelines(device, VK10.VK_NULL_HANDLE, createInfo, null, output),
                    "vkCreateGraphicsPipelines.generic");
            pipeline = output.get(0);
            Compiled result = new Compiled(state, pipeline, layout, descriptors);
            pipeline = VK10.VK_NULL_HANDLE;
            layout = VK10.VK_NULL_HANDLE;
            descriptors = null;
            return result;
        } finally {
            for (long shaderModule : shaderModules) VK10.vkDestroyShaderModule(device, shaderModule, null);
            if (pipeline != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipeline(device, pipeline, null);
            if (layout != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipelineLayout(device, layout, null);
            if (descriptors != null) descriptors.close();
        }
    }

    private long createShaderModule(MemoryStack stack, ShaderModule module) {
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(module.spirv());
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreateShaderModule(device, info, null, output), "vkCreateShaderModule.genericGraphics");
        return output.get(0);
    }

    private long createPipelineLayout(MemoryStack stack, GraphicsPipelineState state,
                                      VulkanGenericDescriptorSetBank descriptors) {
        org.lwjgl.vulkan.VkPipelineLayoutCreateInfo info = org.lwjgl.vulkan.VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default();
        if (descriptors != null) {
            LongBuffer layouts = stack.mallocLong(descriptors.groups().size());
            for (int group : descriptors.groups()) layouts.put(descriptors.layout(group));
            layouts.flip();
            info.pSetLayouts(layouts);
        }
        int pushSize = state.program().pushConstantByteSize();
        if (pushSize > 0) {
            org.lwjgl.vulkan.VkPushConstantRange.Buffer ranges = org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_ALL_GRAPHICS).offset(0).size(pushSize);
            info.pPushConstantRanges(ranges);
        }
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreatePipelineLayout(device, info, null, output),
                "vkCreatePipelineLayout.genericGraphics");
        return output.get(0);
    }

    private static VkPipelineVertexInputStateCreateInfo vertexInput(MemoryStack stack, VertexLayout layout) {
        VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(layout.buffers().size(), stack);
        for (int i = 0; i < layout.buffers().size(); i++) {
            VertexBufferLayout value = layout.buffers().get(i);
            if (value.stepMode() == VertexBufferLayout.StepMode.INSTANCE && value.stepRate() != 1) {
                throw new UnsupportedOperationException("Vulkan graphics path requires instance step rate one");
            }
            bindings.get(i).binding(value.binding()).stride(value.strideBytes())
                    .inputRate(value.stepMode() == VertexBufferLayout.StepMode.INSTANCE
                            ? VK10.VK_VERTEX_INPUT_RATE_INSTANCE : VK10.VK_VERTEX_INPUT_RATE_VERTEX);
        }
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(layout.attributes().size(), stack);
        for (int i = 0; i < layout.attributes().size(); i++) {
            VertexAttribute value = layout.attributes().get(i);
            attributes.get(i).location(value.shaderLocation()).binding(value.bufferBinding())
                    .format(vertexFormat(value.format())).offset(value.byteOffset());
        }
        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes);
    }

    private static VkPipelineRasterizationStateCreateInfo rasterization(MemoryStack stack, RasterState state) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                .rasterizerDiscardEnable(state.rasterizerDiscardEnabled()).depthClampEnable(state.depthClampEnabled())
                .polygonMode(switch (state.polygonMode()) {
                    case FILL -> VK10.VK_POLYGON_MODE_FILL;
                    case LINE -> VK10.VK_POLYGON_MODE_LINE;
                    case POINT -> VK10.VK_POLYGON_MODE_POINT;
                }).cullMode(switch (state.cullMode()) {
                    case NONE -> VK10.VK_CULL_MODE_NONE;
                    case FRONT -> VK10.VK_CULL_MODE_FRONT_BIT;
                    case BACK -> VK10.VK_CULL_MODE_BACK_BIT;
                    case FRONT_AND_BACK -> VK10.VK_CULL_MODE_FRONT_AND_BACK;
                }).frontFace(state.frontFace() == RasterState.FrontFace.CLOCKWISE
                        ? VK10.VK_FRONT_FACE_CLOCKWISE : VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthBiasEnable(state.depthBiasEnabled()).depthBiasConstantFactor((float) state.depthBiasConstantFactor())
                .depthBiasClamp((float) state.depthBiasClamp()).depthBiasSlopeFactor((float) state.depthBiasSlopeFactor())
                .lineWidth((float) state.lineWidth());
    }

    private static VkPipelineMultisampleStateCreateInfo multisample(MemoryStack stack, top.ceroxe.rt.renderer.api.MultisampleState state) {
        if (state.sampleShadingEnabled() || state.alphaToCoverageEnabled() || state.alphaToOneEnabled()) {
            throw new UnsupportedOperationException("advanced multisample shading is not enabled by the generic Vulkan path");
        }
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .rasterizationSamples(sampleCount(state.sampleCount())).pSampleMask(stack.ints((int) state.sampleMask()))
                .alphaToCoverageEnable(false).alphaToOneEnable(false);
    }

    private static VkPipelineDepthStencilStateCreateInfo depthStencil(MemoryStack stack, DepthStencilState state) {
        VkPipelineDepthStencilStateCreateInfo result = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
        if (state == null) return result;
        result.depthTestEnable(state.depthTestEnabled()).depthWriteEnable(state.depthWriteEnabled())
                .depthCompareOp(compare(state.depthCompare())).depthBoundsTestEnable(state.depthBoundsTestEnabled())
                .minDepthBounds((float) state.minimumDepthBounds()).maxDepthBounds((float) state.maximumDepthBounds())
                .stencilTestEnable(state.stencilTestEnabled());
        if (state.stencilTestEnabled()) {
            result.front(stencilFace(state.frontStencil())).back(stencilFace(state.backStencil()));
        }
        return result;
    }

    private static org.lwjgl.vulkan.VkStencilOpState stencilFace(top.ceroxe.rt.renderer.api.StencilFaceState state) {
        return org.lwjgl.vulkan.VkStencilOpState.calloc().failOp(stencilOp(state.stencilFail()))
                .passOp(stencilOp(state.pass())).depthFailOp(stencilOp(state.depthFail()))
                .compareOp(compare(state.compare())).compareMask(state.compareMask())
                .writeMask(state.writeMask()).reference(state.reference());
    }

    private static VkPipelineColorBlendStateCreateInfo blend(MemoryStack stack, top.ceroxe.rt.renderer.api.BlendState state) {
        VkPipelineColorBlendAttachmentState.Buffer targets = VkPipelineColorBlendAttachmentState.calloc(state.targets().size(), stack);
        for (int i = 0; i < state.targets().size(); i++) {
            ColorTargetBlendState target = state.targets().get(i);
            targets.get(i).blendEnable(target.blendEnabled()).srcColorBlendFactor(blendFactor(target.sourceColorFactor()))
                    .dstColorBlendFactor(blendFactor(target.destinationColorFactor())).colorBlendOp(blendOp(target.colorOperation()))
                    .srcAlphaBlendFactor(blendFactor(target.sourceAlphaFactor())).dstAlphaBlendFactor(blendFactor(target.destinationAlphaFactor()))
                    .alphaBlendOp(blendOp(target.alphaOperation())).colorWriteMask(colorWriteMask(target.writeMask()));
        }
        VkPipelineColorBlendStateCreateInfo result = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                .logicOpEnable(state.logicOperation().isPresent()).pAttachments(targets)
                .blendConstants(stack.floats((float) state.constantRed(), (float) state.constantGreen(),
                        (float) state.constantBlue(), (float) state.constantAlpha()));
        state.logicOperation().ifPresent(value -> result.logicOp(logicOp(value)));
        return result;
    }

    private static IntBuffer colorFormats(MemoryStack stack, List<TextureFormat> formats) {
        IntBuffer result = stack.mallocInt(formats.size());
        for (TextureFormat format : formats) result.put(VulkanGenericTextureImage.format(format));
        return result.flip();
    }

    private static int stageFlags(ShaderStage stage) {
        return switch (stage) {
            case VERTEX -> VK10.VK_SHADER_STAGE_VERTEX_BIT;
            case TESSELLATION_CONTROL -> VK10.VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
            case TESSELLATION_EVALUATION -> VK10.VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
            case GEOMETRY -> VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
            case FRAGMENT -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            default -> throw new UnsupportedOperationException("non-graphics shader stage in graphics pipeline: " + stage);
        };
    }

    private static int topology(PrimitiveTopology value) {
        return switch (value) {
            case POINT_LIST -> VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case LINE_LIST -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case LINE_STRIP -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLE_LIST -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case LINE_LIST_WITH_ADJACENCY -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY;
            case LINE_STRIP_WITH_ADJACENCY -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY;
            case TRIANGLE_LIST_WITH_ADJACENCY -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY;
            case TRIANGLE_STRIP_WITH_ADJACENCY -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
            case PATCH_LIST -> VK10.VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
        };
    }

    private static int sampleCount(int value) {
        return switch (value) {
            case 1 -> VK10.VK_SAMPLE_COUNT_1_BIT; case 2 -> VK10.VK_SAMPLE_COUNT_2_BIT;
            case 4 -> VK10.VK_SAMPLE_COUNT_4_BIT; case 8 -> VK10.VK_SAMPLE_COUNT_8_BIT;
            case 16 -> VK10.VK_SAMPLE_COUNT_16_BIT; case 32 -> VK10.VK_SAMPLE_COUNT_32_BIT;
            case 64 -> VK10.VK_SAMPLE_COUNT_64_BIT; default -> throw new IllegalArgumentException("unsupported sample count: " + value);
        };
    }

    private static int vertexFormat(VertexFormat value) {
        return switch (value) {
            case UINT8 -> VK10.VK_FORMAT_R8_UINT; case UINT8X2 -> VK10.VK_FORMAT_R8G8_UINT; case UINT8X3 -> VK10.VK_FORMAT_R8G8B8_UINT; case UINT8X4 -> VK10.VK_FORMAT_R8G8B8A8_UINT;
            case SINT8 -> VK10.VK_FORMAT_R8_SINT; case SINT8X2 -> VK10.VK_FORMAT_R8G8_SINT; case SINT8X3 -> VK10.VK_FORMAT_R8G8B8_SINT; case SINT8X4 -> VK10.VK_FORMAT_R8G8B8A8_SINT;
            case UNORM8 -> VK10.VK_FORMAT_R8_UNORM; case UNORM8X2 -> VK10.VK_FORMAT_R8G8_UNORM; case UNORM8X3 -> VK10.VK_FORMAT_R8G8B8_UNORM; case UNORM8X4 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case SNORM8 -> VK10.VK_FORMAT_R8_SNORM; case SNORM8X2 -> VK10.VK_FORMAT_R8G8_SNORM; case SNORM8X3 -> VK10.VK_FORMAT_R8G8B8_SNORM; case SNORM8X4 -> VK10.VK_FORMAT_R8G8B8A8_SNORM;
            case UINT16 -> VK10.VK_FORMAT_R16_UINT; case UINT16X2 -> VK10.VK_FORMAT_R16G16_UINT; case UINT16X3 -> VK10.VK_FORMAT_R16G16B16_UINT; case UINT16X4 -> VK10.VK_FORMAT_R16G16B16A16_UINT;
            case SINT16 -> VK10.VK_FORMAT_R16_SINT; case SINT16X2 -> VK10.VK_FORMAT_R16G16_SINT; case SINT16X3 -> VK10.VK_FORMAT_R16G16B16_SINT; case SINT16X4 -> VK10.VK_FORMAT_R16G16B16A16_SINT;
            case UNORM16 -> VK10.VK_FORMAT_R16_UNORM; case UNORM16X2 -> VK10.VK_FORMAT_R16G16_UNORM; case UNORM16X3 -> VK10.VK_FORMAT_R16G16B16_UNORM; case UNORM16X4 -> VK10.VK_FORMAT_R16G16B16A16_UNORM;
            case SNORM16 -> VK10.VK_FORMAT_R16_SNORM; case SNORM16X2 -> VK10.VK_FORMAT_R16G16_SNORM; case SNORM16X3 -> VK10.VK_FORMAT_R16G16B16_SNORM; case SNORM16X4 -> VK10.VK_FORMAT_R16G16B16A16_SNORM;
            case FLOAT16 -> VK10.VK_FORMAT_R16_SFLOAT; case FLOAT16X2 -> VK10.VK_FORMAT_R16G16_SFLOAT; case FLOAT16X3 -> VK10.VK_FORMAT_R16G16B16_SFLOAT; case FLOAT16X4 -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case UINT32 -> VK10.VK_FORMAT_R32_UINT; case UINT32X2 -> VK10.VK_FORMAT_R32G32_UINT; case UINT32X3 -> VK10.VK_FORMAT_R32G32B32_UINT; case UINT32X4 -> VK10.VK_FORMAT_R32G32B32A32_UINT;
            case SINT32 -> VK10.VK_FORMAT_R32_SINT; case SINT32X2 -> VK10.VK_FORMAT_R32G32_SINT; case SINT32X3 -> VK10.VK_FORMAT_R32G32B32_SINT; case SINT32X4 -> VK10.VK_FORMAT_R32G32B32A32_SINT;
            case FLOAT32 -> VK10.VK_FORMAT_R32_SFLOAT; case FLOAT32X2 -> VK10.VK_FORMAT_R32G32_SFLOAT; case FLOAT32X3 -> VK10.VK_FORMAT_R32G32B32_SFLOAT; case FLOAT32X4 -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new UnsupportedOperationException("vertex format is not portable in Vulkan generic graphics path: " + value);
        };
    }

    private static int compare(CompareOperation value) { return switch (value) {
        case NEVER -> VK10.VK_COMPARE_OP_NEVER; case LESS -> VK10.VK_COMPARE_OP_LESS; case EQUAL -> VK10.VK_COMPARE_OP_EQUAL;
        case LESS_OR_EQUAL -> VK10.VK_COMPARE_OP_LESS_OR_EQUAL; case GREATER -> VK10.VK_COMPARE_OP_GREATER; case NOT_EQUAL -> VK10.VK_COMPARE_OP_NOT_EQUAL;
        case GREATER_OR_EQUAL -> VK10.VK_COMPARE_OP_GREATER_OR_EQUAL; case ALWAYS -> VK10.VK_COMPARE_OP_ALWAYS; }; }
    private static int blendOp(BlendOperation value) { return switch (value) {
        case ADD -> VK10.VK_BLEND_OP_ADD; case SUBTRACT -> VK10.VK_BLEND_OP_SUBTRACT; case REVERSE_SUBTRACT -> VK10.VK_BLEND_OP_REVERSE_SUBTRACT;
        case MINIMUM -> VK10.VK_BLEND_OP_MIN; case MAXIMUM -> VK10.VK_BLEND_OP_MAX; }; }
    private static int blendFactor(BlendFactor value) { return switch (value) {
        case ZERO -> VK10.VK_BLEND_FACTOR_ZERO; case ONE -> VK10.VK_BLEND_FACTOR_ONE; case SOURCE_COLOR -> VK10.VK_BLEND_FACTOR_SRC_COLOR;
        case ONE_MINUS_SOURCE_COLOR -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR; case DESTINATION_COLOR -> VK10.VK_BLEND_FACTOR_DST_COLOR;
        case ONE_MINUS_DESTINATION_COLOR -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR; case SOURCE_ALPHA -> VK10.VK_BLEND_FACTOR_SRC_ALPHA;
        case ONE_MINUS_SOURCE_ALPHA -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA; case DESTINATION_ALPHA -> VK10.VK_BLEND_FACTOR_DST_ALPHA;
        case ONE_MINUS_DESTINATION_ALPHA -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA; case CONSTANT_COLOR -> VK10.VK_BLEND_FACTOR_CONSTANT_COLOR;
        case ONE_MINUS_CONSTANT_COLOR -> VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR; case CONSTANT_ALPHA -> VK10.VK_BLEND_FACTOR_CONSTANT_ALPHA;
        case ONE_MINUS_CONSTANT_ALPHA -> VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA; case SOURCE_ALPHA_SATURATE -> VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        case SOURCE1_COLOR -> VK10.VK_BLEND_FACTOR_SRC1_COLOR; case ONE_MINUS_SOURCE1_COLOR -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR;
        case SOURCE1_ALPHA -> VK10.VK_BLEND_FACTOR_SRC1_ALPHA; case ONE_MINUS_SOURCE1_ALPHA -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA; }; }
    private static int colorWriteMask(top.ceroxe.rt.renderer.api.ColorWriteMask value) {
        int result = 0;
        if (value.contains(top.ceroxe.rt.renderer.api.ColorWriteMask.Component.RED)) result |= VK10.VK_COLOR_COMPONENT_R_BIT;
        if (value.contains(top.ceroxe.rt.renderer.api.ColorWriteMask.Component.GREEN)) result |= VK10.VK_COLOR_COMPONENT_G_BIT;
        if (value.contains(top.ceroxe.rt.renderer.api.ColorWriteMask.Component.BLUE)) result |= VK10.VK_COLOR_COMPONENT_B_BIT;
        if (value.contains(top.ceroxe.rt.renderer.api.ColorWriteMask.Component.ALPHA)) result |= VK10.VK_COLOR_COMPONENT_A_BIT;
        return result;
    }
    private static int logicOp(LogicOperation value) { return VK10.VK_LOGIC_OP_COPY + value.ordinal(); }
    private static int stencilOp(top.ceroxe.rt.renderer.api.StencilOperation value) { return switch (value) {
        case KEEP -> VK10.VK_STENCIL_OP_KEEP; case ZERO -> VK10.VK_STENCIL_OP_ZERO; case REPLACE -> VK10.VK_STENCIL_OP_REPLACE;
        case INCREMENT_AND_CLAMP -> VK10.VK_STENCIL_OP_INCREMENT_AND_CLAMP; case DECREMENT_AND_CLAMP -> VK10.VK_STENCIL_OP_DECREMENT_AND_CLAMP;
        case INVERT -> VK10.VK_STENCIL_OP_INVERT; case INCREMENT_AND_WRAP -> VK10.VK_STENCIL_OP_INCREMENT_AND_WRAP; case DECREMENT_AND_WRAP -> VK10.VK_STENCIL_OP_DECREMENT_AND_WRAP; }; }

    record Compiled(GraphicsPipelineState state, long pipeline, long layout, VulkanGenericDescriptorSetBank descriptors)
            implements AutoCloseable {
        @Override public void close() { if (descriptors != null) descriptors.close(); }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("generic graphics pipeline owner is closed"); }
}
