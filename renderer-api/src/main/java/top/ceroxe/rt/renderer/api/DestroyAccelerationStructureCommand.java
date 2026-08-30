package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Retires one exact acceleration-structure generation after every recorded GPU use has completed.
 *
 * <p>A bottom-level structure may also be referenced by any resident top-level structure through
 * its device address. Generic backends must reject this command until every such TLAS is retired
 * or replaced, even when the current transaction contains no TLAS use.</p>
 */
public record DestroyAccelerationStructureCommand(AccelerationStructureResource target) implements RenderCommand {
    /** Rejects absent AS identity; a backend must reject unresolved or in-flight targets rather than defer destruction. */
    public DestroyAccelerationStructureCommand {
        target = Objects.requireNonNull(target, "target");
    }
}
