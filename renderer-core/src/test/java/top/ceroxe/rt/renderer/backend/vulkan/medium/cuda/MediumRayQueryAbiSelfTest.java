package top.ceroxe.rt.renderer.backend.vulkan.medium.cuda;

public final class MediumRayQueryAbiSelfTest {
    public static void main(String[] args) {
        if (MediumRayQueryAbi.bytes(4096) != 524288L || MediumRayQueryAbi.bytes(1920 * 1080) != 265420800L)
            throw new AssertionError("record byte count");
        if (MediumRayQueryAbi.HIT_IDS != 64 || MediumRayQueryAbi.PATH_STATE != 80)
            throw new AssertionError("std430 offsets");
        expect(() -> MediumRayQueryAbi.bytes(0));
        expect(() -> MediumRayQueryAbi.requireCount(4097, 4096));
        MediumRayQueryAbi.requireCount(4096, 4096);
        System.out.println("MediumRayQueryAbiSelfTest passed");
    }
    private static void expect(Runnable operation) {
        try { operation.run(); throw new AssertionError("invalid capacity accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
