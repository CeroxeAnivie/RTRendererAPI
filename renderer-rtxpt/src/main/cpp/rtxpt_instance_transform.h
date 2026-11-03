#pragma once

// Public transforms multiply column vectors; Donut nodes multiply row vectors.
// Extract scale from the rows after transposition. The upstream decomposition
// extracts column lengths, which changes a rotated nonuniform scale.
template<class T>
void RtrSetInstanceTransform(donut::engine::SceneGraphNode* node,const T* p){
    using namespace donut::math;
    double3 x(p[0],p[4],p[8]),y(p[1],p[5],p[9]),z(p[2],p[6],p[10]);
    double3 scaling(length(x),length(y),length(z)),translation(p[3],p[7],p[11]);
    if(scaling.x<=0||scaling.y<=0||scaling.z<=0)throw std::invalid_argument("Singular instance transform");
    if(dot(cross(x,y),z)<0)scaling.x=-scaling.x;
    daffine3 rotationOnly(x/scaling.x,y/scaling.y,z/scaling.z,double3(0.0));
    dquat rotation;decomposeAffine<double>(rotationOnly,nullptr,&rotation,nullptr);
    node->SetTransform(&translation,&rotation,&scaling);
}
