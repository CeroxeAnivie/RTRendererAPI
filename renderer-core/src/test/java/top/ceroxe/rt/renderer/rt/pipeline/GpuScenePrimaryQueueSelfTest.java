package top.ceroxe.rt.renderer.rt.pipeline;

/** CPU scheduling arithmetic: capacity must never truncate a pixel's sample group. */
public final class GpuScenePrimaryQueueSelfTest {
    public static void main(String[] args) {
        for (int samples : new int[]{1,2,4,8}) for (int[] extent : new int[][]{{1,1},{160,90},{1920,1080},{8192,8192}}) {
            var plan = GpuScenePrimaryQueue.plan(extent[0], extent[1], samples);
            require(plan.bytes() <= (long) GpuScenePrimaryQueue.MAX_ITEMS * GpuScenePrimaryQueue.ITEM_BYTES,
                    "scratch must respect fixed per-slot byte budget");
            require(plan.bytes() == (long) plan.tilePixels() * samples * GpuScenePrimaryQueue.ITEM_BYTES,
                    "all samples of one pixel must fit together");
            long visited = 0;
            for (int first=0;first<plan.pixels();) {
                int count=Math.min(plan.tilePixels(),plan.pixels()-first);
                visited+=(long) count*samples;
                first+=count;
            }
            require(visited==plan.primaryPaths(),"tiling must preserve every original pixel/sample exactly once");
            require((long) (plan.dispatchPairs()-1)*plan.tilePixels()<plan.pixels(),"last tile is nonempty");
        }
        rejects(() -> GpuScenePrimaryQueue.plan(0,1,1));
        rejects(() -> GpuScenePrimaryQueue.plan(1,1,9));
        rejects(() -> GpuScenePrimaryQueue.plan(Integer.MAX_VALUE,2,1));
        System.out.println("GpuScenePrimaryQueueSelfTest passed: bounded storage, complete sample groups, no overflow/drop");
    }
    private static void rejects(Runnable value) {
        try { value.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("invalid queue extent or budget accepted");
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
