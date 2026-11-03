// Shader ABI v1. Included after backend receiver, material and query functions.
// t9 layout: RGB indirect tint float3 at byte 0; direct strength float at byte 12.
// Data is an immutable little-endian publication; generation is g.publication.yz.
void RtrEvaluateReceiver(RtrReceiver receiver,out float3 gain,out float3 indirect)
{
    float3 tint=1;float directStrength=1;
    if(g.publication.x>=16)
    {
        tint=max(asfloat(t_Application.Load3(0)),0);
        directStrength=saturate(asfloat(t_Application.Load(12)));
    }
    // Exactly periodic in the application's 2^32 ns clock-low interval; pause
    // keeps this pulse fixed. No GPU/CPU wall-clock read or frame random seed.
    directStrength*=.95+.05*cos(float(g.clock.x)*(6.28318530718/4294967296.0));
    gain=1;indirect=0;
    if((g.grid.z&1)!=0)
    {
        float3 local,dynamic;RtrLocal(receiver,local,dynamic);
        float3 sunVisibility=1,moonVisibility=1;
        if(g.sun.w>0&&dot(receiver.normal,g.sun.xyz)>0)sunVisibility=RtrShadow(receiver,g.sun.xyz,1e20);
        if(g.moon.w>0&&dot(receiver.normal,g.moon.xyz)>0)moonVisibility=RtrShadow(receiver,g.moon.xyz,1e20);
        float3 corrected=RtrStyleGain(g.style,receiver.baseline,receiver.visibility,receiver.fog,
            local,dynamic,g.sun.w,sunVisibility,g.moon.w,moonVisibility);
        gain=lerp(float3(1,1,1),corrected,directStrength);
    }
    // OneBounce uses real retained geometry/materials, native bounce and source queries,
    // and applies receiver albedo, opacity-independent visibility/fog, and GI strength once.
    if((g.grid.z&2)!=0)indirect=RtrOneBounce(receiver)*tint;
}
