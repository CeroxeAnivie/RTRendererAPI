package top.ceroxe.rt.renderer.api;

/** Direct compute dispatch dimensions; zero in any dimension is an explicit no-op. */
public record DispatchCommand(int groupsX, int groupsY, int groupsZ) implements RenderCommand {
    public DispatchCommand {
        if (groupsX < 0 || groupsY < 0 || groupsZ < 0) {
            throw new IllegalArgumentException("dispatch group counts must not be negative");
        }
    }
}
