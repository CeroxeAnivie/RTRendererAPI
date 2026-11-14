package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;

/**
 * Owns one prepared TLAS build until a caller-owned queue submission accepts it.
 *
 * <p>The destination is intentionally visible before ownership transfer so the enclosing frame
 * transaction can update its descriptor set. The transaction must then either record and commit
 * this plan after successful submission, or close it. Closing an uncommitted plan releases the
 * destination and keeps descriptor/command preparation failure-atomic.</p>
 */
public final class RtTlasBuildPlan implements AutoCloseable {
    private final PreparedTlasBuild build;
    private final Runnable retiredCallback;
    private boolean recorded;
    private boolean closed;

    RtTlasBuildPlan(
            PreparedTlasBuild build,
            boolean expectedUpdate,
            Runnable retiredCallback
    ) {
        this.build = Objects.requireNonNull(build, "build");
        this.retiredCallback = Objects.requireNonNull(retiredCallback, "retiredCallback");
        if (build.update() != expectedUpdate) {
            throw new IllegalArgumentException("prepared TLAS mode diverges from its lane operation");
        }
    }

    /**
     * Returns the non-owning destination reference that same-submission descriptors may use.
     *
     * @return prepared destination acceleration structure
     */
    public synchronized RtAccelerationStructure accelerationStructure() {
        requireOpen();
        return build.accelerationStructure();
    }

    /**
     * Records upload, build and the build-to-AS-read dependency into the supplied buffer.
     *
     * @param commandBuffer borrowed recording command buffer
     * @param stack borrowed native scratch stack
     */
    public synchronized void record(VkCommandBuffer commandBuffer, MemoryStack stack) {
        requireOpen();
        if (recorded) throw new IllegalStateException("prepared TLAS build was already recorded");
        build.record(commandBuffer, stack);
        recorded = true;
    }

    /**
     * Transfers the destination after the enclosing queue submission succeeds.
     *
     * @return owned built acceleration structure
     */
    public synchronized RtAccelerationStructure commit() {
        requireOpen();
        if (!recorded) {
            throw new IllegalStateException("cannot commit a TLAS build that was not recorded");
        }
        RtAccelerationStructure result = build.releaseAccelerationStructure();
        closed = true;
        try {
            build.close();
        } finally {
            retiredCallback.run();
        }
        return result;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("prepared TLAS build is already committed or closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            build.close();
        } finally {
            retiredCallback.run();
        }
    }
}
