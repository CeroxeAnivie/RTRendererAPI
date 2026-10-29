// RTRendererAPI constant material/texture view for the unmodified LuxCore rough-glass sampler.
// Values already came through the retained Vulkan material and texture pipeline; no new scene copy.
namespace rtr_roughglass {
using rtr_glass::CosTheta;
using rtr_glass::FresnelCauchy_Evaluate;
using rtr_glass::CalcFilmColor;
OPENCL_FORCE_INLINE float3 Spectrum_Clamp(float3 value) {return clamp(value,BLACK,WHITE);}
typedef unsigned int BSDFEvent;
enum : unsigned int { NONE=0u, REFLECT=1u, TRANSMIT=2u, GLOSSY=4u };
struct HitPoint { float3 tint;float roughness,exteriorIor,interiorIor; };
struct RoughGlassView {
    unsigned int ktTexIndex,krTexIndex,nuTexIndex,nvTexIndex,exteriorIorTexIndex,interiorIorTexIndex;
    unsigned int filmThicknessTexIndex,filmIorTexIndex;
};
struct Material { RoughGlassView roughglass; };
OPENCL_FORCE_INLINE float3 Texture_GetSpectrumValue(unsigned int slot,const HitPoint *point) {
    return slot==0u?point->tint:WHITE;
}
OPENCL_FORCE_INLINE float Texture_GetFloatValue(unsigned int,const HitPoint *point) {return point->roughness;}
OPENCL_FORCE_INLINE float ExtractExteriorIors(const HitPoint *point,unsigned int) {return point->exteriorIor;}
OPENCL_FORCE_INLINE float ExtractInteriorIors(const HitPoint *point,unsigned int) {return point->interiorIor;}
#define MATERIALS_PARAM_DECL
#define TEXTURES_PARAM
#define NULL_INDEX 0xffffffffu
#define DEFAULT_COS_EPSILON_STATIC 0.000001f
#define MATERIAL_SAMPLE_RETURN_BLACK { \
    EvalStack_PushFloat3(BLACK); EvalStack_PushFloat3(BLACK); \
    EvalStack_PushFloat(0.f); EvalStack_PushBSDFEvent(NONE); return; }
