package top.ceroxe.rt.renderer.rt.device;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * RT backend native resource 的作用域容器。
 *
 * <p>Vulkan 资源有严格的所有权和销毁顺序：child resource 必须先于 parent 释放。
 * 这个 scope 只负责生命周期纪律，不创建任何 Vulkan handle。后续 instance/device/
 * allocator/BLAS/TLAS/frame resource wrapper 都必须通过这里注册，异常退出时按逆序关闭。</p>
 */
public final class RtResourceScope implements AutoCloseable {
    private final Deque<Entry> resources = new ArrayDeque<>();
    private boolean closed;

    /**
     * Creates an initially open, empty resource scope.
     */
    public RtResourceScope() {
    }

    /**
     * Transfers close responsibility for a resource to this scope.
     *
     * @param name     non-blank diagnostic resource name
     * @param resource resource to close with this scope
     * @param <T>      concrete resource type
     * @return the same resource for fluent construction
     * @throws IllegalStateException if this scope is already closed
     */
    public synchronized <T extends AutoCloseable> T retain(String name, T resource) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(resource, "resource");
        if (name.isBlank()) {
            throw new IllegalArgumentException("resource name must not be blank");
        }
        if (closed) {
            throw new IllegalStateException("RT resource scope is already closed");
        }
        resources.push(new Entry(name, resource));
        return resource;
    }

    /**
     * Returns the number of resources still owned by this scope.
     *
     * @return retained resource count
     */
    public synchronized int retainedCount() {
        return resources.size();
    }

    /**
     * Returns whether close processing has begun.
     *
     * @return {@code true} after the first call to {@link #close()}
     */
    public synchronized boolean closed() {
        return closed;
    }

    /**
     * Closes retained resources in reverse registration order.
     *
     * @throws RuntimeException if any resource fails to close; later failures are suppressed
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        RuntimeException failure = null;
        while (!resources.isEmpty()) {
            Entry entry = resources.pop();
            try {
                entry.resource().close();
            } catch (Throwable ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                RuntimeException wrapped = new IllegalStateException("failed to close RT resource: " + entry.name(), ex);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private record Entry(String name, AutoCloseable resource) {
    }
}
