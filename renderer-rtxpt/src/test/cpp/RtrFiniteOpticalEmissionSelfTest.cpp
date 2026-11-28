// Compile as C++17 and run on the CPU; DXC can also compile the same file
// with -T cs_6_6 -E CompileEmission to validate the actual HLSL overloads.
#ifdef __HLSL_VERSION
#include "../../main/cpp/RtrFiniteOpticalEmission.hlsli"

StructuredBuffer<float4> inputs : register(t0);
RWStructuredBuffer<float4> outputs : register(u0);

[numthreads(1,1,1)]
void CompileEmission(uint id : SV_DispatchThreadID)
{
    const float4 a=inputs[0],b=inputs[1],c=inputs[2],d=inputs[3];
    RtrDirectionalEmissionTile tile;
    tile.origin=a.xyz;tile.stepX=b.xyz;tile.stepY=c.xyz;tile.direction=d.xyz;
    tile.extent=uint2(8,8);tile.seed=1;
    RtrFiniteOpticalEmissionSample sample;
    if(id==0)sample=RtrSampleFiniteDirectionalEmission(tile,0,d.xyz,d.w,a.w,b.w);
    else if(id==1)sample=RtrSampleFinitePointEmission(a.xyz,b.xyz,c.w,c.xy,a.w,b.w);
    else if(id==2)sample=RtrSampleFiniteSpotEmission(a.xyz,d.xyz,b.xyz,c.z,d.w,c.w,c.xy,a.w,b.w);
    else sample=RtrSampleFiniteParallelogramEmission(a.xyz,b.xyz,c.xyz,d.xyz,b.xyz,c.w,c.xy,d.xy,a.w,b.w);
    outputs[3*id]=float4(sample.ray.Origin,sample.ray.TMin);
    outputs[3*id+1]=float4(sample.ray.Direction,sample.ray.TMax);
    outputs[3*id+2]=float4(sample.flux,0);
}

#else

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>

// Compile the production header verbatim with just the HLSL types and
// intrinsics it uses. Keep the energy and distribution oracles below separate.
using uint = std::uint32_t;
struct uint2 { uint x,y; };
struct float2 {
    float x,y;
    float2(float u,float v):x(u),y(v) {}
    float2(uint u,uint v):x(float(u)),y(float(v)) {} // HLSL vector constructor converts components.
};
struct float3 {
    float x,y,z;
    float3()=default;
    float3(float u,float v,float w):x(u),y(v),z(w) {}
};
struct RayDesc { float3 Origin,Direction; float TMin,TMax; };
static float2 operator+(float2 v,float n) { return {v.x+n,v.y+n}; }
static float2 operator*(float2 v,float n) { return {v.x*n,v.y*n}; }
static float3 operator+(float3 a,float3 b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
static float3 operator*(float3 v,float n) { return {v.x*n,v.y*n,v.z*n}; }
static float3 operator*(float n,float3 v) { return v*n; }
static float rcp(float x) { return 1.f/x; }
static float saturate(float x) { return x<0.f?0.f:x>1.f?1.f:x; }
static void sincos(float angle,float& s,float& c) { s=std::sin(angle);c=std::cos(angle); }
using std::sqrt;
#include "../../main/cpp/RtrFiniteOpticalEmission.hlsli"

static constexpr double Pi=3.14159265358979323846;
static constexpr float Epsilon=.002f, Reach=12.f;
static constexpr uint Side=64, Count=Side*Side;

static void near(const char* label,double actual,double expected,double tolerance)
{
    if(std::abs(actual-expected)<=tolerance)return;
    std::fprintf(stderr,"%s: actual %.12g expected %.12g tolerance %.12g\n",label,actual,expected,tolerance);
    std::exit(1);
}

static void vectorNear(const char* label,float3 actual,float3 expected,double tolerance)
{
    near(label,actual.x,expected.x,tolerance);
    near(label,actual.y,expected.y,tolerance);
    near(label,actual.z,expected.z,tolerance);
}

static double dot(float3 a,float3 b) { return double(a.x)*b.x+double(a.y)*b.y+double(a.z)*b.z; }

struct FluxSum {
    double r=0,g=0,b=0;
    void add(float3 flux) { r+=flux.x;g+=flux.y;b+=flux.z; }
    void check(const char* label,float3 input,double factor) const {
        near(label,r,input.x*factor,2e-6);
        near(label,g,input.y*factor,2e-6);
        near(label,b,input.z*factor,2e-6);
    }
};

static void directional()
{
    const RtrDirectionalEmissionTile tile{{0,0,0},{.25f,0,.125f},{.125f,.5f,-.25f},{0,0,-1},{16,8},7};
    const float3 irradiance{1.f,.5f,.125f};
    // The tilted grid projects to a sheared 16 by 8 array of .25 by .5 cells.
    const float projectedCellArea=.25f*.5f;
    FluxSum sum;
    for(uint y=0;y<tile.extent.y;++y)for(uint x=0;x<tile.extent.x;++x){
        const auto sample=RtrSampleFiniteDirectionalEmission(tile,{x,y},irradiance,projectedCellArea,Epsilon,Reach);
        const auto original=RtrEmitDirectionalFlux(tile,{x,y},Epsilon,Reach);
        vectorNear("existing directional origin",sample.ray.Origin,original.Origin,0);
        vectorNear("existing directional direction",sample.ray.Direction,original.Direction,0);
        near("directional near",sample.ray.TMin,Epsilon,0);
        near("directional far",sample.ray.TMax,Reach,0);
        const float t=sample.ray.Origin.y/.5f;
        const float s=(sample.ray.Origin.x-.125f*t)/.25f;
        near("directional ray stays inside its cell",s>=float(x)&&s<float(x+1)&&t>=float(y)&&t<float(y+1),1,0);
        sum.add(sample.flux);
    }
    sum.check("directional projected-area budget",irradiance,16);
}

static void point()
{
    const float3 position{2,-1,4}, power{2,1,.25f};
    FluxSum total,cap;
    double x2=0,y2=0,z2=0;
    for(uint y=0;y<Side;++y)for(uint x=0;x<Side;++x){
        const auto sample=RtrSampleFinitePointEmission(position,power,1.f/Count,{(float(x)+.5f)/Side,(float(y)+.5f)/Side},Epsilon,Reach);
        const auto d=sample.ray.Direction;
        vectorNear("point origin",sample.ray.Origin,position,0);
        near("point unit direction",dot(d,d),1,4e-7);
        total.add(sample.flux);
        if(d.z>.75f)cap.add(sample.flux);
        x2+=d.x*d.x;y2+=d.y*d.y;z2+=d.z*d.z;
    }
    total.check("point power",power,1);
    cap.check("point solid angle fraction",power,1./8);
    for(double moment:{x2,y2,z2})near("isotropic second moment",moment/Count,1./3,1e-4);
    const double capSolidAngle=2*Pi*(1-.75);
    // Same angular flux crosses spheres of both radii; cap area alone grows.
    const double nearIrradiance=cap.r/(capSolidAngle*2*2);
    const double farIrradiance=cap.r/(capSolidAngle*6*6);
    near("point irradiance",nearIrradiance,power.x/(4*Pi*4),1e-12);
    near("point inverse-square spreading",nearIrradiance/farIrradiance,9,1e-12);
}

static void area()
{
    const float3 corner{0,0,3}, u{2,0,0}, v{1,3,0};
    const float3 radiance{1.f,.5f,.125f};
    const float area=6.f;
    for(const float3 normal:{float3{0,0,1},float3{0,0,-1},float3{.6f,0,-.8f},float3{.6f,.8f,0}}){
        FluxSum total,cap;
        double cosineSum=0,originX=0,originY=0;
        for(uint y=0;y<Side;++y)for(uint x=0;x<Side;++x){
            const float2 uv{(float(x)+.5f)/Side,(float(y)+.5f)/Side};
            const float2 positionUv{uv.y,1-uv.x};
            const auto sample=RtrSampleFiniteParallelogramEmission(corner,u,v,normal,radiance,area/Count,positionUv,uv,Epsilon,Reach);
            originX+=sample.ray.Origin.x;originY+=sample.ray.Origin.y;
            near("area plane",sample.ray.Origin.z,3,0);
            near("patch position u",sample.ray.Origin.x,2*positionUv.x+positionUv.y,0);
            near("patch position v",sample.ray.Origin.y,3*positionUv.y,0);
            const auto d=sample.ray.Direction;
            const double cosine=dot(normal,d);
            near("area emits into its front hemisphere",cosine>0,1,0);
            near("area unit direction",dot(d,d),1,4e-7);
            total.add(sample.flux);
            if(cosine>.5)cap.add(sample.flux);
            cosineSum+=cosine;
        }
        total.check("one-sided area power",radiance,Pi*area);
        cap.check("Lambertian cone power",radiance,Pi*area*.75);
        near("Lambertian mean cosine",cosineSum/Count,2./3,1.3e-4);
        near("patch centroid x",originX/Count,1.5,0);
        near("patch centroid y",originY/Count,1.5,0);
    }
}

static void spot()
{
    const float3 position{2,-1,4}, intensity{.5f,1.f,.125f};
    struct Cone {float inner,outer;};
    const Cone cones[]={{.625f,.25f},{.25f,.25f},{.5f,-1.f},
        {std::nextafter(.5f,1.f),.5f},{std::nextafter(1.f,0.f),std::nextafter(1.f,0.f)}};
    for(const float3 axis:{float3{0,0,1},float3{0,0,-1},float3{.6f,0,-.8f}}){
        for(const auto cone:cones){
            const double solidAngle=2*Pi*(1.-cone.outer);
            const double midCosine=(1.+cone.outer)/2;
            FluxSum total;
            uint upperHalf=0;
            for(uint y=0;y<Side;++y)for(uint x=0;x<Side;++x){
                const float2 uv{(float(x)+.5f)/Side,(float(y)+.5f)/Side};
                const auto sample=RtrSampleFiniteSpotEmission(position,axis,intensity,cone.inner,cone.outer,1.f/Count,uv,Epsilon,Reach);
                vectorNear("spot origin",sample.ray.Origin,position,0);
                near("spot near",sample.ray.TMin,Epsilon,0);
                near("spot far",sample.ray.TMax,Reach,0);
                near("spot unit direction",dot(sample.ray.Direction,sample.ray.Direction),1,5e-7);
                const double cosine=dot(sample.ray.Direction,axis);
                const double expected=cone.outer+(1.-cone.outer)*uv.x;
                near("spot uniform solid angle",cosine,expected,3e-7);
                upperHalf+=cosine>midCosine;
                total.add(sample.flux);
            }
            if(1.-cone.outer>1e-5)near("spot equal solid angle halves",upperHalf,Count/2,0);
            const double power=2*Pi*((1.-cone.inner)+(cone.inner-double(cone.outer))/2);
            total.check("spot integrated power",intensity,power);
            // For the narrowest nonzero cap, the regular absolute tolerance
            // could accept an implementation that drops all its flux.
            near("spot integrated green relative to cap",total.g/solidAngle,power/solidAngle,4e-7);
        }
        const auto zero=RtrSampleFiniteSpotEmission(position,axis,intensity,1,1,1.f/Count,{.5f,.5f},Epsilon,Reach);
        vectorNear("zero-width spot direction",zero.ray.Direction,axis,0);
        vectorNear("zero-width spot power",zero.flux,{0,0,0},0);
    }
    // A quarter of the way across a one-ULP transition must not round to the rim.
    const float outer=.5f, inner=std::nextafter(outer,1.f);
    const float u=(inner-outer)*.5f;
    const auto shoulder=RtrSampleFiniteSpotEmission(position,{0,0,1},intensity,inner,outer,1,{u,0.f},Epsilon,Reach);
    near("spot smoothstep at quarter transition",shoulder.flux.y,Pi*(5./32),5e-8);
    const auto rim=RtrSampleFiniteSpotEmission(position,{0,0,1},intensity,outer,outer,1,{0.f,0.f},Epsilon,Reach);
    near("hard-edged spot includes its rim",rim.flux.y,Pi,1e-7);
}

int main()
{
    directional();
    point();
    area();
    spot();
    std::puts("FINITE_OPTICAL_EMISSION_CPU PASS: directional, isotropic point, Lambertian patch, spot");
}
#endif
