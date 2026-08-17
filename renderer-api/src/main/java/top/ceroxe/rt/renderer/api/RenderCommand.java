package top.ceroxe.rt.renderer.api;

/**
 * Closed algebra of commands accepted by a {@link RenderCommandTransaction}.
 *
 * <p>Commands are immutable declarations, not execution evidence. A provider must publish
 * separate typed evidence only after corresponding backend work has actually completed.</p>
 */
public sealed interface RenderCommand permits
        BeginRenderPassCommand,
        EndRenderPassCommand,
        BindGraphicsPipelineCommand,
        BindComputePipelineCommand,
        BindBindingSetCommand,
        SetViewportCommand,
        SetScissorCommand,
        BindVertexBufferCommand,
        BindIndexBufferCommand,
        DrawCommand,
        DrawIndexedCommand,
        MultiDrawCommand,
        MultiDrawIndexedCommand,
        IndirectDrawCommand,
        DispatchCommand,
        DispatchIndirectCommand,
        SetPushConstantsCommand,
        WriteBufferCommand,
        WriteTextureCommand,
        CopyBufferCommand,
        CopyTextureCommand,
        CopyTextureRegionCommand,
        CopyBufferToTextureCommand,
        CopyTextureToBufferCommand,
        ClearColorCommand,
        ClearDepthStencilCommand,
        BuildBottomLevelAccelerationStructureCommand,
        BuildTopLevelAccelerationStructureCommand,
        DestroyAccelerationStructureCommand,
        BindRayTracingPipelineCommand,
        TraceRaysCommand,
        ResourceBarrierCommand {
}
