struct TestRayRecord {
    float4 originMin, directionMax;
    uint4 control;
    float4 hit;
    uint4 ids;
    float4 state0, state1, state2;
};
static_assert(sizeof(TestRayRecord)==128,"native ray record ABI");
extern "C" __global__ void test_init_rays(TestRayRecord* records,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x; if(i>=count)return;
    TestRayRecord r={};
    r.originMin=make_float4(i%4==0?10.f:((i%64)+.5f)/32.f-1.f,((i/64)+.5f)/32.f-1.f,-1.f,.00001f);
    r.directionMax=make_float4(0,0,1,100);
    r.control=make_uint4(1,0,i,0);
    r.hit=make_float4(-1,0,0,0);r.ids=make_uint4(0xffffffffu);
    r.state0=make_float4(1,1,1,0);r.state2=make_float4(-1,-1,0,0);
    records[i]=r;
}
extern "C" __global__ void test_init_shadow(TestRayRecord* records,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    TestRayRecord r={};r.originMin=make_float4(i%4==0?10.f:((i%64)+.5f)/32.f-1.f,((i/64)+.5f)/32.f-1.f,-1.f,.00001f);
    r.directionMax=make_float4(0,0,1,100);r.control=make_uint4(1,1,i,77);records[i]=r;
}
extern "C" __global__ void test_advance_rays(TestRayRecord* records,int count) {
    int i=blockIdx.x*blockDim.x+threadIdx.x;if(i>=count)return;
    TestRayRecord r=records[i];if(r.control.x==0)return;
    if(r.ids.w!=r.control.w){r.state1.y+=1;records[i]=r;return;}
    if(r.hit.x<0){r.control.x=0;records[i]=r;return;}
    float3 a=make_float3(.1f,.2f,.3f),s=make_float3(0),e=make_float3(.01f,0,0),tr,em;
    HomogeneousVolume_SegmentScatter(.5f,false,r.hit.x,&a,&s,&e,&tr,&em);
    r.state1.z+=r.state0.x*em.x;r.state1.w+=r.state0.y*em.y;
    r.state0.x*=tr.x;r.state0.y*=tr.y;r.state0.z*=tr.z;r.state0.w+=r.hit.x;
    int hits=(int)r.state1.x;if(hits==0)r.state2.x=r.ids.x;else if(hits==1)r.state2.y=r.ids.x;
    r.state1.x+=1;r.control.w+=1;
    r.originMin.x+=r.directionMax.x*(r.hit.x+.0001f);
    r.originMin.y+=r.directionMax.y*(r.hit.x+.0001f);
    r.originMin.z+=r.directionMax.z*(r.hit.x+.0001f);
    records[i]=r;
}
