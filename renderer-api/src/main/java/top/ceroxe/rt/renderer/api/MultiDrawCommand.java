package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered batch of direct non-indexed draws with every per-draw parameter preserved. */
public record MultiDrawCommand(List<DrawCommand> draws) implements RenderCommand {
    /** Defensively copies a non-empty draw list, including explicit zero-count no-ops. */
    public MultiDrawCommand {
        Objects.requireNonNull(draws, "draws");
        if (draws.isEmpty()) throw new IllegalArgumentException("multi-draw command must not be empty");
        ArrayList<DrawCommand> checked = new ArrayList<>(draws.size());
        for (DrawCommand draw : draws) checked.add(Objects.requireNonNull(draw, "draw"));
        draws = List.copyOf(checked);
    }
}
