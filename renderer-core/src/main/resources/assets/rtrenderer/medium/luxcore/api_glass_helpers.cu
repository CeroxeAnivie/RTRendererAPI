// Minimal adapters for unchanged LuxCore smooth-glass functions. The API has no film or dispersion.
namespace rtr_glass {
struct HitPoint { };
OPENCL_FORCE_INLINE float CosTheta(float3 v){return v.z;}
OPENCL_FORCE_INLINE float SinTheta2(float3 v){return fmaxf(0.f,1.f-v.z*v.z);}
OPENCL_FORCE_INLINE float Sqr(float v){return v*v;}
OPENCL_FORCE_INLINE float3 CalcFilmColor(float3,float,float){return WHITE;}
