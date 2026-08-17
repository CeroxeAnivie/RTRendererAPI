package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered batch of direct indexed draws with every per-draw parameter preserved. */
public record MultiDrawIndexedCommand(List<DrawIndexedCommand> draws) implements RenderCommand {
    /** Defensively copies a non-empty draw list, including explicit zero-count no-ops. */
    public MultiDrawIndexedCommand {
        Objects.requireNonNull(draws, "draws");
        if (draws.isEmpty()) throw new IllegalArgumentException("indexed multi-draw command must not be empty");
        ArrayList<DrawIndexedCommand> checked = new ArrayList<>(draws.size());
        for (DrawIndexedCommand draw : draws) checked.add(Objects.requireNonNull(draw, "draw"));
        draws = List.copyOf(checked);
    }
}
