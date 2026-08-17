package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Retires one exact acceleration-structure generation after every recorded GPU use has completed. */
public record DestroyAccelerationStructureCommand(AccelerationStructureResource target) implements RenderCommand {
    /** Rejects absent AS identity; a backend must reject unresolved or in-flight targets rather than defer destruction. */
    public DestroyAccelerationStructureCommand {
        target = Objects.requireNonNull(target, "target");
    }
}
