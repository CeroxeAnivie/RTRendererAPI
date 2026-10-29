// DensityGridTexture_D and the trilinear interpolation below are adapted from
// LuxCore b872186d2fe995a762f73708c606847eed55843e texture_densitygrid_funcs.cl.
// Copyright 1998-2020 by the LuxCore authors. Apache-2.0; see LICENSE.txt/AUTHORS.txt.
// Adaptations: float-red storage, explicit descriptor, texel-center coordinates,
// inclusive unit-domain boundary, and API midpoint integration after domain clipping.
namespace rtr_density {
struct __align__(16) Descriptor {
    unsigned int atlasOffset, nx, ny, nz;
    float4 row0,row1,row2;
};
static_assert(sizeof(Descriptor)==64,"native density descriptor ABI");

__device__ float DensityGridTexture_D(const float *pixels,int x,int y,int z,int nx,int ny,int nz) {
    const unsigned int index=((clamp(z,0,nz-1)*ny)+clamp(y,0,ny-1))*nx+clamp(x,0,nx-1);
    return pixels[index];
}

__device__ float DensityGridTexture_ConstEvaluate(const float *pixels,const Descriptor &grid,float3 P) {
    if(!isfinite(P.x)||!isfinite(P.y)||!isfinite(P.z)) return nanf("");
    if(P.x<0.f||P.x>1.f||P.y<0.f||P.y>1.f||P.z<0.f||P.z>1.f) return 0.f;
    const int nx=(int)grid.nx,ny=(int)grid.ny,nz=(int)grid.nz;
    float x=P.x*nx-.5f,y=P.y*ny-.5f,z=P.z*nz-.5f;
    const int vx=(int)floorf(x),vy=(int)floorf(y),vz=(int)floorf(z);
    x-=vx;y-=vy;z-=vz;
    // Pinned upstream eight-corner/mix interpolation, scalar float-red fetch adaptation.
    return
        mix(
            mix(
                mix(
                    DensityGridTexture_D(pixels,vx,vy,vz,nx,ny,nz),
                    DensityGridTexture_D(pixels,vx+1,vy,vz,nx,ny,nz),
                x),
                mix(
                    DensityGridTexture_D(pixels,vx,vy+1,vz,nx,ny,nz),
                    DensityGridTexture_D(pixels,vx+1,vy+1,vz,nx,ny,nz),
                x),
            y),
            mix(
                mix(
                    DensityGridTexture_D(pixels,vx,vy,vz+1,nx,ny,nz),
                    DensityGridTexture_D(pixels,vx+1,vy,vz+1,nx,ny,nz),
                x),
                mix(
                    DensityGridTexture_D(pixels,vx,vy+1,vz+1,nx,ny,nz),
                    DensityGridTexture_D(pixels,vx+1,vy+1,vz+1,nx,ny,nz),
                x),
            y),
        z);
}

__device__ float3 map(const Descriptor &grid,float3 p,float w) {
    return make_float3(grid.row0.x*p.x+grid.row0.y*p.y+grid.row0.z*p.z+grid.row0.w*w,
        grid.row1.x*p.x+grid.row1.y*p.y+grid.row1.z*p.z+grid.row1.w*w,
        grid.row2.x*p.x+grid.row2.y*p.y+grid.row2.z*p.z+grid.row2.w*w);
}
__device__ bool clipAxis(float origin,float direction,float *first,float *last) {
    if(direction==0.f) return origin>=0.f&&origin<=1.f;
    const float a=(0.f-origin)/direction,b=(1.f-origin)/direction;
    *first=fmaxf(*first,fminf(a,b));*last=fminf(*last,fmaxf(a,b));
    return *last>*first;
}
__device__ float mass(const unsigned char *buffer,int descriptorOffset,float3 origin,float3 direction,
                     float distance,unsigned int steps) {
    if(!isfinite(distance)||distance<0.f) return nanf("");
    if(descriptorOffset<0) return distance;
    if(distance<=0.f) return 0.f;
    const Descriptor &grid=*(const Descriptor *)(buffer+(unsigned int)descriptorOffset);
    const float *pixels=(const float *)(buffer+grid.atlasOffset);
    const float3 p=map(grid,origin,1.f),v=map(grid,direction,0.f);
    if(!isfinite(p.x)||!isfinite(p.y)||!isfinite(p.z)||!isfinite(v.x)||!isfinite(v.y)||!isfinite(v.z)
            ||steps<1u||steps>64u||!grid.nx||!grid.ny||!grid.nz) return nanf("");
    float first=0.f,last=distance;
    if(!clipAxis(p.x,v.x,&first,&last)||!clipAxis(p.y,v.y,&first,&last)||!clipAxis(p.z,v.z,&first,&last)) return 0.f;
    const float dt=(last-first)/(float)steps;
    float integrated=0.f;
    for(unsigned int i=0;i<steps;++i) {
        const float t=first+((float)i+.5f)*dt;
        integrated+=DensityGridTexture_ConstEvaluate(pixels,grid,p+v*t);
    }
    return integrated*dt;
}
} // namespace rtr_density
