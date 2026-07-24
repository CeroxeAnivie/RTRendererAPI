package top.ceroxe.mcvulkanrt.renderer.scene;

public record ChunkKey(int x, int z) {
    public long packed() {
        return pack(x, z);
    }

    public static long pack(int x, int z) {
        return (Integer.toUnsignedLong(x) << Integer.SIZE) | Integer.toUnsignedLong(z);
    }
}
