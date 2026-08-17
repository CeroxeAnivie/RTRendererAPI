package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Sets the exact viewport consumed by subsequent draws in the active pass. */
public record SetViewportCommand(Viewport viewport) implements RenderCommand {
    /** Rejects an absent viewport. */
    public SetViewportCommand {
        Objects.requireNonNull(viewport, "viewport");
    }
}
