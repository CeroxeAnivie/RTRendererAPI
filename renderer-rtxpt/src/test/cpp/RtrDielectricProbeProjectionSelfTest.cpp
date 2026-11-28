#include <Windows.h>
#include "../../main/cpp/rtxpt_dielectric_probe_projection.h"
#include <cstdio>
#include <cstdlib>
#include <string_view>

using rtr::dielectric_probe::Project;
using rtr::dielectric_probe::Texel;

static void expect(const char* label,Texel texel,int x,int y,std::string_view status={}){
    if(texel.x==x&&texel.y==y&&std::string_view(texel.status?texel.status:"")==status)return;
    std::fprintf(stderr,"%s: unexpected texel (%d,%d), status=%s\n",label,texel.x,texel.y,texel.status?texel.status:"ok");
    std::exit(1);
}

int main(){
    // Perspective with unit tangents, near=1, far=16, negative view Z forward.
    std::array<float,39> camera{};
    camera[0]=camera[5]=camera[15]=camera[16]=camera[21]=camera[26]=1;
    camera[11]=-1;camera[14]=-15.f/16;camera[36]=.9999f;
    const std::array<double,3> target{.5,.25,-4};
    expect("initial",Project(target,camera.data(),64,32),36,15);
    camera[32]=.25f;
    expect("translated",Project(target,camera.data(),64,32),34,15);
    camera[37]=1.f/32;camera[38]=-1.f/16;
    expect("jittered",Project(target,camera.data(),64,32),33,14);
    camera[37]=camera[38]=0;
    camera[3]=.125f;
    expect("asymmetric",Project(target,camera.data(),64,32),30,15);
    camera[3]=0;
    // Add a view translation to the inverse projection, and move the actual ray origin with it.
    camera[2]=-.46875f;camera[3]=.5f;camera[32]=.75f;
    expect("offset perspective center",Project(target,camera.data(),64,32),30,15);
    camera[2]=camera[3]=0;camera[32]=.25f;
    camera[35]=1;camera[36]=.0001f;
    camera[14]=15.f/16;camera[15]=1.f/16;
    expect("reversed depth",Project(target,camera.data(),64,32),34,15);
    expect("behind",Project({0,0,4},camera.data(),64,32),-1,-1,"behind-camera");
    expect("offscreen",Project({10,0,-4},camera.data(),64,32),-1,-1,"offscreen");
    expect("past far clip",Project({0,0,-20},camera.data(),64,32),-1,-1,"outside-depth");
    expect("before near clip",Project({0,0,-.5},camera.data(),64,32),-1,-1,"outside-depth");
    camera[16]=camera[26]=0;camera[18]=1;camera[24]=-1;
    expect("turned left",Project({-3.75,.25,-.5},camera.data(),64,32),36,15);
    camera[32]=16777216.f;
    expect("large world",Project({16777212.,.25,-.5},camera.data(),64,32),36,15);
    camera[5]=0;
    expect("singular",Project(target,camera.data(),64,32),-1,-1,"invalid-projection");
    std::puts("DIELECTRIC_PROBE_PROJECTION PASS");
}
