#include "../../main/cpp/rtxpt_directional_flux_grid.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <vector>

using std::abs;
using std::max;
using std::min;
using std::sqrt;
#include "../../main/cpp/RtrOpticalFluxBins.hlsli"

using rtr::optical::BoundedDirectionalFluxGrid;
using rtr::optical::DirectionalFluxGrid;
constexpr uint32_t Budget=2*65536;

static void require(bool condition,const char* message){
    if(!condition){std::fprintf(stderr,"%s\n",message);std::exit(1);}
}

// Orthographic, constant-area receiver with periodic boundaries: a flat
// beam on a plane must transport unit mean irradiance at either resolution.
static void checkUniformBeam(DirectionalFluxGrid grid){
    constexpr int nx=256,ny=256;
    const int w=int(grid.width),h=int(grid.height);
    const float photonArea=1.f/(nx*ny),binArea=1.f/(w*h);
    const float half=RtrFluxFootprintHalfWidth(photonArea,binArea);
    std::vector<double> energy(w*h);
    for(int y=0;y<ny;++y)for(int x=0;x<nx;++x){
        // Fixed, cell-local jitter; no frame number or output texel enters it.
        const uint32_t hash=(uint32_t(x)+nx*uint32_t(y))*0x9e3779b9u;
        const float a=(float(hash&255u)+.5f)/256.f;
        const float b=(float((hash>>16u)&255u)+.5f)/256.f;
        const float px=(x+a)*w/nx,py=(y+b)*h/ny;
        unsigned touched=0;
        for(int iy=int(floor(py-half));iy<int(ceil(py+half));++iy)
            for(int ix=int(floor(px-half));ix<int(ceil(px+half));++ix){
                const float area=RtrFluxPixelWeight(ix,px,half)*RtrFluxPixelWeight(iy,py,half);
                if(area<=0)continue;
                energy[((iy+h)%h)*w+(ix+w)%w]+=photonArea*area/(4.f*half*half);
                ++touched;
            }
        require(touched<=16,"a photon exceeded sixteen bins");
    }
    const double sum=std::accumulate(energy.begin(),energy.end(),0.);
    require(abs(sum-1.)<1e-6,"deposits failed to conserve flux");
    require(double(std::count(energy.begin(),energy.end(),0.))/energy.size()<.1,"uniform illuminated plane is mostly empty");
    require(*std::max_element(energy.begin(),energy.end())/binArea<4.,"uniform beam produces single-bin spikes");
}

int main(){
    constexpr auto full=BoundedDirectionalFluxGrid(2560,1494,Budget);
    constexpr auto third=BoundedDirectionalFluxGrid(854,498,Budget);
    static_assert(full.width==427&&full.height==249&&full.width==third.width&&full.height==third.height);
    static_assert(BoundedDirectionalFluxGrid(79,17,Budget).width==79);
    constexpr auto irradiance=rtr::optical::DirectionalIrradianceGrid(854,498,65536);
    static_assert(irradiance.width==143&&irradiance.height==83);
    static_assert(rtr::optical::DirectionalIrradianceGrid(64,32,65536).width==64);
    static_assert(rtr::optical::DirectionalIrradianceGrid(1,1,1).width==1);
    for(const auto size:{DirectionalFluxGrid{3840,2160},{7680,4320},{4320,7680},{1,UINT32_MAX}}){
        const auto grid=BoundedDirectionalFluxGrid(size.width,size.height,Budget);
        require(grid.width&&grid.height&&uint64_t(grid.width)*grid.height<=Budget,"guide exceeded its budget");
    }
    require(RtrFluxMatchesPlane(1.f,.002f,.003f),"same plane rejected");
    require(!RtrFluxMatchesPlane(1.f,.02f,.003f),"parallel foreground accepted");
    require(!RtrFluxMatchesPlane(-1.f,0.f,.003f),"back face accepted");
    checkUniformBeam(full);
    checkUniformBeam(irradiance);
    std::puts("directional flux grid: bounded, flux conserving, plane checked");
}
