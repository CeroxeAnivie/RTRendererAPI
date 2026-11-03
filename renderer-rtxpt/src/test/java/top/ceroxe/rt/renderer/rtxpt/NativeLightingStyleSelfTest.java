package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.NativeLightingStyle;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Numeric contract tests; these are not evidence of the independent GPU execution plan. */
public final class NativeLightingStyleSelfTest {
    private static void near(float actual,float expected) {
        require(Math.abs(actual-expected)<2e-6,"Style formula: "+actual+" expected "+expected);
    }
    public static void main(String[] args) {
        var style=NativeLightingStyle.defaults();
        near(style.directGain(0,.5f,1,1,0,.5f,0,0,1,0,1),1);
        near(style.directGain(0,.5f,1,1,0,0,0,0,1,0,1),.625f);
        // A static bound higher than the baked baseline must not brighten the host again.
        near(style.directGain(0,.5f,1,1,0,.9f,0,0,1,0,1),1);
        near(style.withAdditionMode(NativeLightingStyle.AdditionMode.CLASSIC_ALL_LOCAL_V1)
            .directGain(0,.5f,1,1,0,.9f,0,0,1,0,1),3.4f);
        near(style.directGain(0,.5f,1,1,0,.9f,.9f,0,1,0,1),3.4f);
        near(style.directGain(0,.5f,1,.5f,.5f,0,0,0,1,0,1),.90625f);
        near(style.directGain(0,0,1,1,0,0,0,1,0,0,1),.45f);
        near(style.directGain(0,0,1,1,0,0,0,0,1,1,0),.856f);
        near(style.directGain(1,0,1,1,0,0,0,0,1,1,0),.838f);
        near(style.directGain(2,0,1,1,0,0,0,0,1,1,0),.82f);
        near(style.directGain(0,.5f,1,0,0,0,8,1,0,1,0),1);
        near(style.directGain(0,.5f,1,1,1,0,8,1,0,1,0),1);
        require(style.encodeGain8(1)==128,"Exact neutral compact gain code");
        near(style.decodeGain8(128),1);near(style.decodeGain8(0),0);
        require(style.encodeGi8(0)==0&&style.encodeGi8(1)==255,"GI compact bounds");
        for(int code=0;code<256;code++) {
            require(style.encodeGain8(style.decodeGain8(code))==code,"Every gain code round-trips: "+code);
            require(style.encodeGi8(style.decodeGi8(code))==code,"Every GI code round-trips: "+code);
        }
        float maxGainRelative=0,maxGainAbsolute=0,maxGiAbsolute=0;
        for(int i=0;i<=100000;i++) {
            float gain=i/100000f*15.65f,gi=i/100000f;
            float error=Math.abs(style.decodeGain8(style.encodeGain8(gain))-gain);
            if(gain>1)maxGainRelative=Math.max(maxGainRelative,error/gain);else maxGainAbsolute=Math.max(maxGainAbsolute,error);
            maxGiAbsolute=Math.max(maxGiAbsolute,Math.abs(style.decodeGi8(style.encodeGi8(gi))-gi));
        }
        require(maxGainRelative<.011&&maxGainAbsolute<=1/256f+1e-6&&maxGiAbsolute<.004,"Declared compact quantization bounds");
        System.out.println("NATIVE_LIGHTING_STYLE_CPU PASS samples=100001 neutral=128 gainAbsolute="+maxGainAbsolute+
            " gainRelative="+maxGainRelative+" giAbsolute="+maxGiAbsolute);
    }
}
