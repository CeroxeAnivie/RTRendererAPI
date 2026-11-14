package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Asynchronous ownership contract shared by core and vendor-managed BLAS builders.
 *
 * <p>The source geometry remains caller-owned until this operation completes or closes. A
 * successful completion transfers exactly one independently closeable acceleration structure.</p>
 */
public interface RtBottomLevelBuild extends AutoCloseable {
    /**
     * Returns the completed BLAS, or {@code null} while GPU work remains in flight.
     *
     * @return transferred BLAS ownership when complete, otherwise {@code null}
     */
    RtAccelerationStructure completeIfReady();

    /**
     * Waits for all build and compaction work and transfers the completed BLAS.
     *
     * @return completed independently closeable BLAS
     */
    RtAccelerationStructure waitAndComplete();

    /**
     * Returns the submitted Vulkan geometry-range count.
     *
     * @return positive geometry count
     */
    int geometryCount();

    /**
     * Returns the total submitted triangle count.
     *
     * @return positive primitive count
     */
    long primitiveCount();

    /**
     * Returns the original result allocation size.
     *
     * @return positive uncompacted storage size in bytes
     */
    long sourceStorageBytes();

    /**
     * Returns final storage bytes, or {@code -1} before completion selects a result.
     *
     * @return final storage bytes, or {@code -1} while incomplete
     */
    long completedStorageBytes();

    /**
     * Returns whether a compacted allocation replaced the original result.
     *
     * @return {@code true} after compaction selects the final allocation
     */
    boolean compacted();

    @Override
    void close();
}
