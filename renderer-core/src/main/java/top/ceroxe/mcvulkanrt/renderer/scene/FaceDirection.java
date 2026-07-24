package top.ceroxe.mcvulkanrt.renderer.scene;

public enum FaceDirection {
    NEGATIVE_X(-1, 0, 0),
    POSITIVE_X(1, 0, 0),
    NEGATIVE_Y(0, -1, 0),
    POSITIVE_Y(0, 1, 0),
    NEGATIVE_Z(0, 0, -1),
    POSITIVE_Z(0, 0, 1);

    private final int stepX;
    private final int stepY;
    private final int stepZ;

    FaceDirection(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    public int stepX() {
        return stepX;
    }

    public int stepY() {
        return stepY;
    }

    public int stepZ() {
        return stepZ;
    }
}
