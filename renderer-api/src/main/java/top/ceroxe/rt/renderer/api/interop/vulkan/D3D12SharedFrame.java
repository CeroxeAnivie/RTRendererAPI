package top.ceroxe.rt.renderer.api.interop.vulkan;

/**
 * One borrowed, dedicated D3D12 RGBA8_UNORM texture after the producer's SDR tone mapping.
 * It has one mip, one array layer and one sample. Handles are Win32 NT handles retained by the
 * producer; importing does not transfer handle ownership. Keep both handles and their resources
 * alive until {@link VulkanFramePresenter#retireD3D12SharedFrames()} or presenter close completes.
 *
 * <p>Each texture slot owns an independent shared ID3D12Fence. D3D12 releases the texture in
 * COMMON and signals odd {@code readyValue}; Vulkan waits, acquires GENERAL/EXTERNAL, consumes
 * it, releases GENERAL/EXTERNAL and signals the next even {@code completionValue}. The producer
 * must enqueue a queue wait for completion before writing or destroying that slot. CPU waiting
 * is not part of normal frame delivery.</p>
 *
 * @param resourceId unique positive allocation generation; never reuse for another texture
 * @param frameSequence nonnegative complete-scene frame sequence
 * @param adapterLuid packed Windows adapter LUID (LowPart in low 32 bits)
 * @param width positive texture width
 * @param height positive texture height
 * @param allocationSize dedicated allocation size from D3D12 GetResourceAllocationInfo
 * @param memoryHandle borrowed D3D12_RESOURCE shared NT handle
 * @param fenceHandle borrowed D3D12_FENCE shared NT handle, private to this texture slot
 * @param readyValue positive odd producer signal
 * @param completionValue next even consumer signal; exactly readyValue + 1
 */
public record D3D12SharedFrame(long resourceId,long frameSequence,long adapterLuid,int width,int height,
        long allocationSize,long memoryHandle,long fenceHandle,long readyValue,long completionValue) {
    /** Validates the fixed image and per-slot ownership protocol without using a native driver. */
    public D3D12SharedFrame {
        if(resourceId<=0||frameSequence<0)throw new IllegalArgumentException("Invalid external resource/frame identity");
        if(width<=0||height<=0||width>16384||height>16384)
            throw new IllegalArgumentException("D3D12 shared extent must be within [1,16384]");
        if(allocationSize<(long)width*height*4)
            throw new IllegalArgumentException("D3D12 allocation is smaller than its RGBA8 pixels");
        if(memoryHandle==0||memoryHandle==-1||fenceHandle==0||fenceHandle==-1)
            throw new IllegalArgumentException("D3D12 shared NT handles must be valid borrowed handles");
        if(readyValue<=0||(readyValue&1)==0||readyValue==Long.MAX_VALUE||completionValue!=readyValue+1)
            throw new IllegalArgumentException("Per-slot fence requires odd ready and next even completion values");
    }
}
