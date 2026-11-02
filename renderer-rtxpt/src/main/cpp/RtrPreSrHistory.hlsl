// Port of gsTemporalGeometryMatches / gsResolveTemporal from the Vulkan route.
// The full current path-traced radiance is retained; no lighting term is removed.
#define NON_PATH_TRACING_PASS 1
#include "../Shaders/Libraries/ShaderDebug/ShaderDebug.hlsl"
#include "../Shaders/Bindings/ShaderResourceBindings.hlsli"
#include "../Shaders/PathTracerBridgeDonut.hlsli"
#include "../Shaders/PathTracer/PathTracer.hlsli"

Texture2D<float4> t_RtrHistoryColor : register(t0, space100);
Texture2D<float4> t_RtrHistoryGeometry : register(t1, space100);
RWTexture2D<float4> u_RtrHistoryColor : register(u0, space100);
RWTexture2D<float4> u_RtrHistoryGeometry : register(u1, space100);

[numthreads(8, 8, 1)]
void main(uint3 thread : SV_DispatchThreadID)
{
    const uint2 pixel = thread.xy;
    const uint2 size = uint2(g_Const.ptConsts.imageWidth, g_Const.ptConsts.imageHeight);
    if (any(pixel >= size)) return;
    const float3 current = u_OutputColor[pixel].rgb;
    float3 resolved = current;
    float4 geometry = 0;
    float age = 1;
    DenoisingLayersContext layers = DenoisingLayersContext::make(u_DenoisingLayersHeader,
        u_DenoisingLayersBuffer, u_StableRadiance, g_Const.ptConsts);
    const uint dominant = layers.LoadDominantIndex(pixel);
    const uint branch = layers.GetBranchID(pixel, dominant);
    if (branch < cDenoisingLayerEnqueuedBranchID)
    {
        DenoisingLayer surface = layers.LoadDenoisingLayer(pixel, dominant);
        float3 throughput, worldMotion;
        surface.GetThpAndWorldMotionVector(throughput, worldMotion);
        // Match the old route's first opaque/static surface domain. Stable
        // reflection/refraction branches and moving objects do not reuse it.
        if ((surface.VertexIndexAndRoughness >> 16) == 1 && all(abs(worldMotion) < 1e-6))
        {
            const float3 normal = surface.GetNormal();
            const float logDepth = log2(1 + max(surface.SceneLength, 0));
            geometry = float4(normal * .5 + .5, max(logDepth, 1e-4));
            const float2 motionPixels = u_MotionVectors[pixel].xy;
            const float2 previousPosition = float2(pixel) + .5 + motionPixels + asfloat(g_MiniConst.params1.xy);
            const int2 previousPixel = int2(floor(previousPosition));
            if (g_MiniConst.params.x != 0 && all(previousPixel >= 0) && all(previousPixel < int2(size)))
            {
                const float4 previousGeometry = t_RtrHistoryGeometry.Load(int3(previousPixel, 0));
                const float3 position = surface.RayOrigin + surface.RayDir * surface.LastRayTCurrent;
                const float3 previousCamera = g_Const.ptConsts.prevCamera.PosW;
                const float expectedDepth = log2(1 + length(position - previousCamera));
                const bool accepted = previousGeometry.a > 0
                    && dot(normal, normalize(previousGeometry.rgb * 2 - 1)) >= .85
                    && abs(expectedDepth - previousGeometry.a) <= max(.025, expectedDepth * .02);
                if (accepted)
                {
                    const float4 previous = t_RtrHistoryColor.Load(int3(previousPixel, 0));
                    const float previousAge = clamp(previous.a, 1, 8);
                    const float boundedWeight = min(previousAge / (previousAge + 1), 7.0 / 8);
                    const float confidence = exp(-length(motionPixels) * .05);
                    const float3 radius = max(abs(current) * .35, .025);
                    const float3 clipped = clamp(previous.rgb, current - radius, current + radius);
                    resolved = lerp(current, clipped, boundedWeight * confidence);
                    age = min(1 + previousAge * confidence, 8);
                }
            }
        }
    }
    u_RtrHistoryColor[pixel] = float4(resolved, age);
    u_RtrHistoryGeometry[pixel] = geometry;
    u_OutputColor[pixel] = float4(resolved, 1);
}
