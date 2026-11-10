package top.ceroxe.rt.renderer.rtxpt;

import top.ceroxe.rt.renderer.api.NativeLightingStyle;
import top.ceroxe.rt.renderer.api.NativeLightingHostResponse;
import top.ceroxe.rt.renderer.api.NativeLightingPlan;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Numeric contract tests; these are not evidence of the independent GPU execution plan. */
public final class NativeLightingStyleSelfTest {
    private static void near(float actual,float expected) {
        require(Math.abs(actual-expected)<2e-6,"Style formula: "+actual+" expected "+expected);
    }
    public static void main(String[] args) {
        hostResponse();
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
    private static void hostResponse(){
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.HOST_RESPONSE_RATIO_V1,.75f,12,.55f,.18f,.8f,.9f,1,0,255,1);
        float[] table=new float[NativeLightingHostResponse.SAMPLES];
        for(int sky=0;sky<16;sky++)for(int local=0;local<16;local++)for(int c=0;c<3;c++)
            table[(sky*16+local)*3+c]=1/255f+(.9f-c*.1f)*local/15f+.05f*sky/15f;
        var response=new NativeLightingHostResponse(table);float saved=response.values()[0];table[0]=0;near(response.values()[0],saved);
        for(int c=0;c<3;c++)for(int sky=0;sky<16;sky++)for(int level=0;level<16;level++){
            float s=sky/15f,l=level/15f;
            float held=response.directGain(style,c,0,s,1,0,l,l,false,0,1);
            float placed=response.directGain(style,c,l,s,1,0,l,0,true,0,1);
            near(response.sample(c,0,s)*held,response.sample(c,l,s)*placed);
        }
        near(response.directGain(style,0,.5f,.4f,1,0,0,0,false,0,1),1);
        require(response.directGain(style,0,.5f,.4f,1,0,0,0,true,0,1)<1,"Occluded covered static source dims its baked response");
        near(response.directGain(style,0,.5f,.4f,0,0,0,0,true,1,0),1);
        near(response.directGain(style,0,.5f,.4f,1,1,0,0,true,1,0),1);
        require(response.directGain(style,0,0,0,1,0,1,1,false,0,1)>30,"Night response gain is not cut at legacy maximum");
        var encoding=NativeLightingPlan.Encoding.COMPACT_RESPONSE_UNORM8;
        for(int code=0;code<256;code++)require(style.encodeGain8(style.decodeGain8(code,encoding),encoding)==code,"Response gain codes round trip");
        require(style.encodeGain8(1,encoding)==128&&style.encodeGain8(255,encoding)==255,"Response compact neutral/range");
        var black=new NativeLightingHostResponse(new float[768]);near(black.directGain(style,0,0,0,1,0,0,0,false,0,1),1);
        float[] zeroBase=new float[768];zeroBase[45]=1;var zero=new NativeLightingHostResponse(zeroBase);
        near(zero.directGain(style,0,0,0,1,0,1,1,false,0,1),255);
        var partial=style.withAdditionMode(NativeLightingStyle.AdditionMode.HOST_RESPONSE_PARTIAL_SKY_V1);
        near(zero.directGainPartialSky(partial,0,0,0,1,0,1,1,false,1,0,1,0),1);
        near(black.directGainPartialSky(partial,0,0,0,1,0,0,0,false,1,0,1,0),1);
        for(int c=0;c<3;c++) {
            float moonColor=c==0?.8f:c==1?.9f:1;
            float blocked=(1-.25f)*(.55f*.7f*(1-.2f)+.18f*.3f*moonColor*(1-.4f));
            float expected=response.sample(c,.25f,.8f*(1-blocked))/response.sample(c,.25f,.8f);
            near(response.directGainPartialSky(partial,c,.25f,.8f,1,0,0,0,false,.7f,.2f,.3f,.4f),expected);
            near(response.directGainPartialSky(partial,c,.25f,.8f,.5f,.25f,0,0,false,.7f,.2f,.3f,.4f),1+(expected-1)*.375f);
            near(response.directGainPartialSky(partial,c,1,.8f,1,0,0,0,false,1,0,1,0),1);
            require(response.directGainPartialSky(partial,c,.5f,.4f,1,0,0,0,true,0,1,0,1)<1,
                "Partial-sky mode retains covered static-source shadowing");
            float held=response.directGainPartialSky(partial,c,0,.6f,1,0,.5f,.5f,false,0,1,0,1);
            float placed=response.directGainPartialSky(partial,c,.5f,.6f,1,0,.5f,0,true,0,1,0,1);
            near(response.sample(c,0,.6f)*held,response.sample(c,.5f,.6f)*placed);
        }
        boolean rejected=false;
        try {partial.directGain(0,0,0,1,0,0,0,0,1,0,1);}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"Partial-sky mode requires a published host response");
        System.out.println("HOST_RESPONSE_CPU PASS equality=768 missingStatic=true coveredShadow=true fog=true neutral=128 maxGain=255 zeroFinite=true");
    }
}
