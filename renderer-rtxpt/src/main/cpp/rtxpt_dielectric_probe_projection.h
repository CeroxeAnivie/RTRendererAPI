#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace rtr::dielectric_probe {
struct Texel {
    int32_t x=-1,y=-1;
    const char* status=nullptr;
};

// Invert the shader's ray construction using its packed, row-major matrices.
// Solve only for this point, relative to cameraNear.xyz. Remove the view-space
// perspective center as well: it may be offset by a host's projection matrix.
inline Texel Project(const std::array<double,3>& world,const float* words,uint32_t width,uint32_t height){
    const std::array<double,3> delta{world[0]-words[32],world[1]-words[33],world[2]-words[34]};
    const double scale=std::max({1.,std::abs(delta[0]),std::abs(delta[1]),std::abs(delta[2])});
    const double centerW=words[14];
    if(centerW==0)return {-1,-1,"invalid-projection"};
    const std::array<double,3> center{words[2]/centerW,words[6]/centerW,words[10]/centerW};
    double rows[4][5]{};
    for(unsigned i=0;i<3;++i){
        for(unsigned j=0;j<4;++j)for(unsigned k=0;k<3;++k)
            rows[i][j]+=double(words[16+i*4+k])*(words[k*4+j]-center[k]*words[12+j]);
        rows[i][4]=delta[i]/scale;
    }
    for(unsigned j=0;j<4;++j)rows[3][j]=words[12+j];
    rows[3][4]=1./scale;
    for(unsigned j=0;j<4;++j){
        unsigned pivot=j;
        for(unsigned i=j+1;i<4;++i)if(std::abs(rows[i][j])>std::abs(rows[pivot][j]))pivot=i;
        if(rows[pivot][j]==0)return {-1,-1,"invalid-projection"};
        std::swap(rows[pivot],rows[j]);
        const double divisor=rows[j][j];
        for(unsigned k=j;k<5;++k)rows[j][k]/=divisor;
        for(unsigned i=0;i<4;++i)if(i!=j){
            const double factor=rows[i][j];
            for(unsigned k=j;k<5;++k)rows[i][k]-=factor*rows[j][k];
        }
    }
    const double w=rows[3][4];
    if(!std::isfinite(w))return {-1,-1,"invalid-projection"};
    if(w==0)return {-1,-1,"behind-camera"};
    const double ndcX=rows[0][4]/w,ndcY=rows[1][4]/w;
    const double nearNdc=words[35];
    const double nearW=words[12]*ndcX+words[13]*ndcY+centerW*nearNdc+words[15];
    if(!std::isfinite(nearW)||nearW==0)return {-1,-1,"invalid-projection"};
    if(std::signbit(w)!=std::signbit(nearW))return {-1,-1,"behind-camera"};
    // The shader samples just inside the far NDC endpoint, which is -1, 0, or +1.
    const double farNdc=std::round(words[36]);
    const double depth=rows[2][4]/w;
    if(!(depth>=std::min(nearNdc,farNdc)&&depth<=std::max(nearNdc,farNdc)))return {-1,-1,"outside-depth"};
    const double u=(ndcX-words[37]+1.)*.5;
    const double v=(1.-(ndcY-words[38]))*.5;
    if(!(u>=0&&u<1&&v>=0&&v<1))return {-1,-1,"offscreen"};
    return {std::min(int32_t(width)-1,int32_t(u*width)),std::min(int32_t(height)-1,int32_t(v*height))};
}
}
