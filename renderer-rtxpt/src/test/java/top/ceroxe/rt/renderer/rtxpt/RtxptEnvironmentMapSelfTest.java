package top.ceroxe.rt.renderer.rtxpt;

import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.renderer.api.EnvironmentMap;
import top.ceroxe.rt.renderer.api.EnvironmentState;

/** Focused CPU checks for immutable linear HDR environment input and its native payload. */
public final class RtxptEnvironmentMapSelfTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Invalid environment map accepted");
    }

    private static void bounds() {
        require(new EnvironmentMap(2, 1, new float[6]).width() == 2, "Minimum dimensions accepted");
        require(new EnvironmentMap(8192, 1, new float[8192 * 3]).width() == 8192, "Maximum width accepted");
        require(new EnvironmentMap(2, 4096, new float[2 * 4096 * 3]).height() == 4096, "Maximum height accepted");
        for (int[] dimensions : new int[][]{{1, 1}, {0, 1}, {-1, 1}, {2, 0}, {2, -1},
                {8193, 1}, {2, 4097}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            reject(() -> new EnvironmentMap(dimensions[0], dimensions[1], new float[6]));
        }
        reject(() -> new EnvironmentMap(2, 1, new float[5]));
        reject(() -> new EnvironmentMap(2, 1, new float[7]));
        reject(() -> new EnvironmentMap(8192, 4096, new float[6]));
        for (float invalid : new float[]{-.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            reject(() -> new EnvironmentMap(2, 1, new float[]{0, 0, 0, 0, invalid, 0}));
        }
        try { new EnvironmentMap(2, 1, null); throw new AssertionError("Null RGB input accepted"); }
        catch (NullPointerException expected) { }
        var hdr = new EnvironmentMap(2, 1, new float[]{0, .5f, 2f, Float.MAX_VALUE, 1f, 8f});
        require(hdr.linearRgb()[3] == Float.MAX_VALUE, "Finite HDR radiance is not clamped");
    }

    private static void ownership() {
        float[] source = {1f, .5f, 2f, 4f, 8f, 16f, 3f, 6f, 9f, .25f, .125f, .0625f};
        float[] expected = source.clone();
        var map = new EnvironmentMap(2, 2, source);
        int hash = map.hashCode(); source[0] = 100f;
        float[] exposed = map.linearRgb(); exposed[1] = 200f;
        require(Arrays.equals(map.linearRgb(), expected) && map.hashCode() == hash, "Constructor and getter preserve immutable storage and cached hash");
        var equal = new EnvironmentMap(2, 2, expected);
        require(map.equals(equal) && equal.equals(map) && map.hashCode() == equal.hashCode(), "Equal maps compare by dimensions and exact channel values");
        require(!map.equals(new EnvironmentMap(4, 1, expected)) && !map.equals(null), "Map dimensions participate in equality");
        float[] changed = expected.clone(); changed[1] = Math.nextUp(changed[1]);
        require(!map.equals(new EnvironmentMap(2, 2, changed)), "Sub-RGBA8 radiance changes remain distinct");
        float[] target = new float[expected.length + 4]; Arrays.fill(target, -1f); map.copyLinearRgbTo(target, 2);
        require(target[0] == -1f && target[1] == -1f && target[target.length - 1] == -1f
                && Arrays.equals(Arrays.copyOfRange(target, 2, target.length - 2), expected), "Direct copy preserves channel order and caller-owned padding");
        target[2] = 1000f; require(map.linearRgb()[0] == expected[0], "Direct copy does not expose map storage");
        try { map.copyLinearRgbTo(new float[6], 0); throw new AssertionError("Short destination accepted"); }
        catch (IndexOutOfBoundsException expectedFailure) { }
    }

    private static void payload() {
        float[] radiance = {2f, .25f, 8f, 4f, .5f, 16f, 3f, .75f, 32f, 6f, 1.25f, 64f};
        var map = new EnvironmentMap(2, 2, radiance);
        var constant = EnvironmentState.builder().skyRadiance(.1f, .2f, .3f).ambientIntensity(9f)
                .sunDirection(0, -1, 0).sunRadiance(.8f, .7f, .6f).sunIntensity(5f).build();
        require(EnvironmentState.neutral().environmentMap() == null && constant.environmentMap() == null, "Existing environments retain the constant-sky path");
        var legacy = RtxptLighting.of(constant, List.of());
        require(Arrays.equals(legacy.sky(), new float[]{.1f, .2f, .3f, 9f}), "Constant-sky payload remains exactly four floats");
        var mapped = constant.toBuilder().environmentMap(map).build();
        var copy = mapped.toBuilder().build();
        require(copy.environmentMap() == map && copy.equals(mapped) && copy.hashCode() == mapped.hashCode()
                && mapped.toString().contains("environmentMap=EnvironmentMap[width=2, height=2"), "Environment copy and identity include the immutable map");
        require(!mapped.equals(constant) && mapped.toBuilder().environmentMap(null).build().equals(constant), "Null clears only the map and restores existing constant sky properties");
        var equal = constant.toBuilder().environmentMap(new EnvironmentMap(2, 2, radiance)).build();
        var lighting = RtxptLighting.of(mapped, List.of());
        require(mapped.equals(equal) && mapped.hashCode() == equal.hashCode()
                && lighting.equals(RtxptLighting.of(equal, List.of())), "Content-equal maps retain lighting equality used to avoid repeated native encoding");
        float[] sky = lighting.sky();
        require(sky.length == radiance.length + 6 && sky[0] == .1f && sky[1] == .2f && sky[2] == .3f
                && sky[3] == 9f && sky[4] == 2f && sky[5] == 2f, "Map payload appends width and height after the legacy header");
        for (int index = 0; index < radiance.length; index++) {
            require(Float.floatToRawIntBits(sky[index + 6]) == Float.floatToRawIntBits(radiance[index]),
                    "Map radiance retains exact bits and row order without sRGB conversion or ambient multiplication");
        }
        require(Arrays.equals(legacy.sun(), lighting.sun()) && Arrays.equals(legacy.records(), lighting.records()), "Environment map leaves independent sun and other light records unchanged");
        sky[6] = 10000f; require(lighting.sky()[6] == radiance[0], "Encoded payload is independent of immutable map storage");
    }

    public static void main(String[] args) {
        bounds(); ownership(); payload();
        System.out.println("ENVIRONMENT_MAP_CPU PASS bounds=true defensiveCopies=true hdr=true equality=true copy=true clear=true constantUnchanged=true mapPayload=true noAmbientScale=true sunIndependent=true");
    }
}
