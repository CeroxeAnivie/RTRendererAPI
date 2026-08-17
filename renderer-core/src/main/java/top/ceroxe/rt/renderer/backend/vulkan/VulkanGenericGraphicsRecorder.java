package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkRect2D;
import top.ceroxe.rt.renderer.api.ClearValue;
import top.ceroxe.rt.renderer.api.EndRenderPassCommand;
import top.ceroxe.rt.renderer.api.LoadOp;
import top.ceroxe.rt.renderer.api.StoreOp;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureSubresourceRange;

import java.util.List;

/** Records graphics actions into one command buffer; it owns no pipelines or resources. */
final class VulkanGenericGraphicsRecorder {
    private VulkanGenericGraphicsRecorder() { }

    static void record(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            List<VulkanGenericCommandPlan.Action> actions,
            VulkanGenericTextureLayoutUpdates layouts
    ) {
        for (VulkanGenericCommandPlan.Action action : actions) {
            switch (action) {
                case VulkanGenericCommandPlan.BeginPass begin -> beginPass(commandBuffer, stack, begin, layouts);
                case VulkanGenericCommandPlan.EndPass ignored -> VK13.vkCmdEndRendering(commandBuffer);
                case VulkanGenericCommandPlan.BindGraphics bind -> VK10.vkCmdBindPipeline(
                        commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, bind.pipeline().pipeline());
                case VulkanGenericCommandPlan.BindGraphicsBindings bind -> bindGraphicsBindings(stack, commandBuffer, bind);
                case VulkanGenericCommandPlan.GraphicsPushConstants push -> VK10.vkCmdPushConstants(
                        commandBuffer, push.pipeline().layout(), VK10.VK_SHADER_STAGE_ALL_GRAPHICS,
                        push.command().offsetBytes(), push.command().data().bytes());
                case VulkanGenericCommandPlan.BindVertex bind -> {
                    var slice = bind.command().slice();
                    VK10.vkCmdBindVertexBuffers(commandBuffer, bind.command().binding(),
                            stack.longs(bind.record().buffer().buffer()), stack.longs(slice.range().offsetBytes()));
                }
                case VulkanGenericCommandPlan.BindIndex bind -> VK10.vkCmdBindIndexBuffer(
                        commandBuffer, bind.record().buffer().buffer(), bind.command().slice().range().offsetBytes(),
                        bind.command().format() == top.ceroxe.rt.renderer.api.IndexFormat.UINT16
                                ? VK10.VK_INDEX_TYPE_UINT16 : VK10.VK_INDEX_TYPE_UINT32);
                case VulkanGenericCommandPlan.ViewportAction viewport -> {
                    var value = viewport.viewport();
                    VkViewport.Buffer nativeViewport = VkViewport.calloc(1, stack)
                            .x(value.x()).y(value.y()).width(value.width()).height(value.height())
                            .minDepth(value.minimumDepth()).maxDepth(value.maximumDepth());
                    VK10.vkCmdSetViewport(commandBuffer, 0, nativeViewport);
                }
                case VulkanGenericCommandPlan.ScissorAction scissor -> {
                    var value = scissor.scissor();
                    VkRect2D.Buffer nativeScissor = VkRect2D.calloc(1, stack);
                    nativeScissor.get(0).offset().set(value.x(), value.y());
                    nativeScissor.get(0).extent().set(value.width(), value.height());
                    VK10.vkCmdSetScissor(commandBuffer, 0, nativeScissor);
                }
                case VulkanGenericCommandPlan.Draw draw -> {
                    var value = draw.command();
                    VK10.vkCmdDraw(commandBuffer, value.vertexCount(), value.instanceCount(), value.firstVertex(), value.firstInstance());
                }
                case VulkanGenericCommandPlan.DrawIndexed draw -> {
                    var value = draw.command();
                    VK10.vkCmdDrawIndexed(commandBuffer, value.indexCount(), value.instanceCount(), value.firstIndex(),
                            value.vertexOffset(), value.firstInstance());
                }
                case VulkanGenericCommandPlan.MultiDraw multi -> {
                    for (var value : multi.command().draws()) {
                        VK10.vkCmdDraw(commandBuffer, value.vertexCount(), value.instanceCount(), value.firstVertex(), value.firstInstance());
                    }
                }
                case VulkanGenericCommandPlan.MultiDrawIndexed multi -> {
                    for (var value : multi.command().draws()) {
                        VK10.vkCmdDrawIndexed(commandBuffer, value.indexCount(), value.instanceCount(), value.firstIndex(),
                                value.vertexOffset(), value.firstInstance());
                    }
                }
                case VulkanGenericCommandPlan.Indirect indirect -> {
                    var command = indirect.command();
                    if (command.kind().indexed()) {
                        VK10.vkCmdDrawIndexedIndirect(commandBuffer, indirect.arguments().buffer().buffer(),
                                command.arguments().range().offsetBytes(), command.maximumDrawCount(), command.strideBytes());
                    } else {
                        VK10.vkCmdDrawIndirect(commandBuffer, indirect.arguments().buffer().buffer(),
                                command.arguments().range().offsetBytes(), command.maximumDrawCount(), command.strideBytes());
                    }
                }
                default -> { }
            }
        }
    }

    private static void beginPass(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            VulkanGenericCommandPlan.BeginPass begin,
            VulkanGenericTextureLayoutUpdates layouts
    ) {
        VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(begin.colors().size(), stack);
        for (int i = 0; i < begin.colors().size(); i++) {
            colors.get(i).set(attachment(stack, begin.colors().get(i), VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            transition(commandBuffer, stack, begin.colors().get(i).record(), begin.colors().get(i).attachment().view().range(),
                    VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, layouts);
            if (begin.colors().get(i).resolveRecord() != null) {
                transition(commandBuffer, stack, begin.colors().get(i).resolveRecord(),
                        begin.colors().get(i).resolveView().range(), VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, layouts);
            }
        }
        VkRenderingAttachmentInfo depth = begin.depth() == null ? null : attachment(stack, begin.depth(), VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkRenderingAttachmentInfo stencil = begin.stencil() == null ? null : attachment(stack, begin.stencil(), VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        if (begin.depth() != null) transition(commandBuffer, stack, begin.depth().record(), begin.depth().attachment().view().range(),
                VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, layouts);
        if (begin.stencil() != null && (begin.depth() == null || begin.stencil().record() != begin.depth().record())) {
            transition(commandBuffer, stack, begin.stencil().record(), begin.stencil().attachment().view().range(),
                    VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, layouts);
        }
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(area(stack, begin.descriptor().width(), begin.descriptor().height()))
                .layerCount(begin.descriptor().layerCount()).pColorAttachments(colors);
        if (depth != null) info.pDepthAttachment(depth);
        if (stencil != null) info.pStencilAttachment(stencil);
        VK13.vkCmdBeginRendering(commandBuffer, info);
    }

    private static VkRect2D area(MemoryStack stack, int width, int height) {
        VkRect2D result = VkRect2D.calloc(stack);
        result.offset().set(0, 0);
        result.extent().set(width, height);
        return result;
    }

    private static VkRenderingAttachmentInfo attachment(
            MemoryStack stack, VulkanGenericCommandPlan.ResolvedAttachment resolved, int layout
    ) {
        var declaration = resolved.attachment();
        VkRenderingAttachmentInfo info = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                .imageView(resolved.record().views().require(declaration.view()))
                .imageLayout(layout).loadOp(loadOp(declaration.loadOperation())).storeOp(storeOp(declaration.storeOperation()));
        declaration.clearValue().ifPresent(value -> setClear(info.clearValue(), value));
        if (resolved.resolveView() != null) {
            info.resolveMode(org.lwjgl.vulkan.VK12.VK_RESOLVE_MODE_AVERAGE_BIT)
                    .resolveImageView(resolved.resolveRecord().views().require(resolved.resolveView()))
                    .resolveImageLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        return info;
    }

    private static void setClear(VkClearValue target, ClearValue value) {
        switch (value) {
            case ClearValue.Color color -> target.color(c -> c.float32(0, color.red()).float32(1, color.green())
                    .float32(2, color.blue()).float32(3, color.alpha()));
            case ClearValue.Depth depth -> target.depthStencil(d -> d.depth(depth.value()).stencil(0));
            case ClearValue.Stencil stencil -> target.depthStencil(d -> d.depth(1.0f).stencil(stencil.value()));
        }
    }

    private static int loadOp(LoadOp value) { return switch (value) {
        case LOAD -> VK10.VK_ATTACHMENT_LOAD_OP_LOAD; case CLEAR -> VK10.VK_ATTACHMENT_LOAD_OP_CLEAR; case DISCARD -> VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE; }; }
    private static int storeOp(StoreOp value) { return value == StoreOp.STORE ? VK10.VK_ATTACHMENT_STORE_OP_STORE : VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE; }

    private static void transition(
            VkCommandBuffer commandBuffer, MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record, TextureSubresourceRange range,
            int newLayout, VulkanGenericTextureLayoutUpdates layouts
    ) {
        for (int mip = range.baseMipLevel(); mip < range.mipEndExclusive(); mip++) {
            for (int layer = range.baseArrayLayer(); layer < range.arrayLayerEndExclusive(); layer++) {
                int old = layouts.layout(record, range.aspect(), mip, layer);
                if (old != newLayout) {
                    org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer barrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                            .sType$Default().oldLayout(old).newLayout(newLayout)
                            .srcAccessMask(access(old)).dstAccessMask(access(newLayout))
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .image(record.image().image());
                    barrier.subresourceRange().aspectMask(VulkanGenericTextureMappings.aspectMask(range.aspect()))
                            .baseMipLevel(mip).levelCount(1).baseArrayLayer(layer).layerCount(1);
                    VK10.vkCmdPipelineBarrier(commandBuffer, stage(old), stage(newLayout), 0, null, null, barrier);
                }
            }
        }
        layouts.set(record, range, newLayout);
    }

    private static int stage(int layout) { return switch (layout) {
        case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        default -> VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT; }; }
    private static int access(int layout) { return switch (layout) {
        case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
        case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        case VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        default -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT; }; }

    private static void bindGraphicsBindings(MemoryStack stack, org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
                                             VulkanGenericCommandPlan.BindGraphicsBindings bind) {
        var descriptors = bind.pipeline().descriptors();
        if (descriptors == null) return;
        var groups = descriptors.groups();
        var sets = stack.mallocLong(groups.size());
        for (int group : groups) sets.put(descriptors.set(group));
        sets.flip();
        var offsets = stack.mallocInt(bind.command().dynamicOffsets().size());
        for (long offset : bind.command().dynamicOffsets()) offsets.put((int) offset);
        offsets.flip();
        VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, bind.pipeline().layout(),
                0, sets, offsets);
    }
}
