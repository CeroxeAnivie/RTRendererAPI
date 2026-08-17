package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit per-frame composition of retained ray-tracing and generic graphics work.
 *
 * <p>The discriminator is intentional: a backend may execute the two lanes together only when
 * it can preserve their ordering and synchronization. It must reject an unsupported combination;
 * it may never reinterpret a graphics transaction as a retained scene or vice versa.</p>
 */
public final class RenderWorkload {
    public enum Mode { RAY_TRACING_SCENE, GRAPHICS_COMMANDS, COMBINED }

    private final Mode mode;
    private final Optional<RenderFrameRequest> sceneFrame;
    private final Optional<RenderCommandTransaction> graphicsCommands;

    private RenderWorkload(Mode mode, RenderFrameRequest sceneFrame, RenderCommandTransaction graphicsCommands) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.sceneFrame = Optional.ofNullable(sceneFrame);
        this.graphicsCommands = Optional.ofNullable(graphicsCommands);
        if (mode == Mode.RAY_TRACING_SCENE && (sceneFrame == null || graphicsCommands != null)) {
            throw new IllegalArgumentException("ray-tracing workload must contain only a scene frame");
        }
        if (mode == Mode.GRAPHICS_COMMANDS && (sceneFrame != null || graphicsCommands == null)) {
            throw new IllegalArgumentException("graphics workload must contain only command transactions");
        }
        if (mode == Mode.COMBINED) {
            if (sceneFrame == null || graphicsCommands == null) {
                throw new IllegalArgumentException("combined workload requires both explicit lanes");
            }
            if (sceneFrame.sequence() != graphicsCommands.sequence()) {
                throw new IllegalArgumentException("combined workload lanes must share one frame sequence");
            }
        }
    }

    public static RenderWorkload rayTracing(RenderFrameRequest frame) {
        return new RenderWorkload(Mode.RAY_TRACING_SCENE, Objects.requireNonNull(frame, "frame"), null);
    }

    public static RenderWorkload graphics(RenderCommandTransaction commands) {
        return new RenderWorkload(Mode.GRAPHICS_COMMANDS, null, Objects.requireNonNull(commands, "commands"));
    }

    public static RenderWorkload combined(RenderFrameRequest frame, RenderCommandTransaction commands) {
        return new RenderWorkload(Mode.COMBINED, Objects.requireNonNull(frame, "frame"),
                Objects.requireNonNull(commands, "commands"));
    }

    public Mode mode() { return mode; }
    public Optional<RenderFrameRequest> sceneFrame() { return sceneFrame; }
    public Optional<RenderCommandTransaction> graphicsCommands() { return graphicsCommands; }
}
