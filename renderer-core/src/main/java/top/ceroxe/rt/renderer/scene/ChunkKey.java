package top.ceroxe.rt.renderer.scene;

/**
 * 二维区块坐标。
 *
 * @param x 区块 X 坐标
 * @param z 区块 Z 坐标
 */
public record ChunkKey(int x, int z) {
    /**
     * 将两个区块坐标编码为无冲突 64 位键。
     *
     * @param x 区块 X 坐标
     * @param z 区块 Z 坐标
     * @return 打包坐标
     */
    public static long pack(int x, int z) {
        return (Integer.toUnsignedLong(x) << Integer.SIZE) | Integer.toUnsignedLong(z);
    }

    /**
     * 将当前坐标编码为无冲突整数键。
     *
     * @return 保留两个有符号坐标全部位模式的 64 位键
     */
    public long packed() {
        return pack(x, z);
    }
}
