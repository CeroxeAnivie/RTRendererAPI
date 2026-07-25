package top.ceroxe.rt.renderer.scene;

/**
 * 六个轴对齐体素面方向及其单位步进。
 */
public enum FaceDirection {
    /**
     * X 负方向。
     */
    NEGATIVE_X(-1, 0, 0),
    /**
     * X 正方向。
     */
    POSITIVE_X(1, 0, 0),
    /**
     * Y 负方向。
     */
    NEGATIVE_Y(0, -1, 0),
    /**
     * Y 正方向。
     */
    POSITIVE_Y(0, 1, 0),
    /**
     * Z 负方向。
     */
    NEGATIVE_Z(0, 0, -1),
    /**
     * Z 正方向。
     */
    POSITIVE_Z(0, 0, 1);

    private final int stepX;
    private final int stepY;
    private final int stepZ;

    FaceDirection(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    /**
     * 返回 X 轴单位步进。
     *
     * @return -1、0 或 1
     */
    public int stepX() {
        return stepX;
    }

    /**
     * 返回 Y 轴单位步进。
     *
     * @return -1、0 或 1
     */
    public int stepY() {
        return stepY;
    }

    /**
     * 返回 Z 轴单位步进。
     *
     * @return -1、0 或 1
     */
    public int stepZ() {
        return stepZ;
    }
}
