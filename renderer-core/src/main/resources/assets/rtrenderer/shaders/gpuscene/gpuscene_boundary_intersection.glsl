#ifndef GS_BOUNDARY_INTERSECTION_GLSL
#define GS_BOUNDARY_INTERSECTION_GLSL
// PBRT's robust edge-function principle, adapted to Vulkan without requiring
// shaderFloat64: two-float expansions retain transform/subtraction residuals.
// Only candidates in the existing eight-ULP barycentric edge band use this path.
precise vec2 gsBoundaryWideAdd(vec2 a,vec2 b) {
    precise float s=a.x+b.x;
    precise float v=s-a.x;
    precise float e=(a.x-(s-v))+(b.x-v)+a.y+b.y;
    precise float hi=s+e;
    precise float lo=e-(hi-s);
    return vec2(hi,lo);
}
precise vec2 gsBoundaryWideMultiply(vec2 a,vec2 b) {
    precise float p=a.x*b.x;
    // Split the binary mantissa so both leading factors have an exact product.
    // The precise return qualifier is also required across function boundaries.
    precise float ahi=uintBitsToFloat(floatBitsToUint(a.x)&0xfffff000u);
    precise float bhi=uintBitsToFloat(floatBitsToUint(b.x)&0xfffff000u);
    precise float alo=a.x-ahi,blo=b.x-bhi;
    precise float error=((ahi*bhi-p)+ahi*blo+alo*bhi)+alo*blo;
    precise float e=error+a.x*b.y+a.y*b.x+a.y*b.y;
    precise float hi=p+e;
    precise float lo=e-(hi-p);
    return vec2(hi,lo);
}
precise vec2 gsBoundaryWideDifferenceOfProducts(vec2 a,vec2 b,vec2 c,vec2 d) {
    return gsBoundaryWideAdd(gsBoundaryWideMultiply(a,b),-gsBoundaryWideMultiply(c,d));
}
struct GsBoundaryWide3 {vec2 x;vec2 y;vec2 z;};
precise vec2 gsBoundaryRelativeCoordinate(vec3 p,int component) {
    vec2 value=gsBoundaryWideAdd(vec2(gl_ObjectToWorldEXT[3][component],0),vec2(-gl_WorldRayOriginEXT[component],0));
    for(int axis=0;axis<3;axis++)value=gsBoundaryWideAdd(value,
            gsBoundaryWideMultiply(vec2(gl_ObjectToWorldEXT[axis][component],0),vec2(p[axis],0)));
    return value;
}
GsBoundaryWide3 gsBoundaryRelativePoint(vec3 p) {
    return GsBoundaryWide3(gsBoundaryRelativeCoordinate(p,0),gsBoundaryRelativeCoordinate(p,1),gsBoundaryRelativeCoordinate(p,2));
}
precise vec2 gsBoundaryEdge(GsBoundaryWide3 a,GsBoundaryWide3 b) {
    vec2 x=gsBoundaryWideDifferenceOfProducts(a.y,b.z,a.z,b.y);
    vec2 y=gsBoundaryWideDifferenceOfProducts(a.z,b.x,a.x,b.z);
    vec2 z=gsBoundaryWideDifferenceOfProducts(a.x,b.y,a.y,b.x);
    return gsBoundaryWideAdd(gsBoundaryWideAdd(gsBoundaryWideMultiply(x,vec2(gl_WorldRayDirectionEXT.x,0)),
            gsBoundaryWideMultiply(y,vec2(gl_WorldRayDirectionEXT.y,0))),
            gsBoundaryWideMultiply(z,vec2(gl_WorldRayDirectionEXT.z,0)));
}
float gsBoundaryEdgeSign(vec2 edge){return edge.x!=0.0?edge.x:edge.y;}
vec3 gsBoundaryEdges(GsBoundaryWide3 a,GsBoundaryWide3 b,GsBoundaryWide3 c) {
    return vec3(gsBoundaryEdgeSign(gsBoundaryEdge(b,c)),gsBoundaryEdgeSign(gsBoundaryEdge(c,a)),gsBoundaryEdgeSign(gsBoundaryEdge(a,b)));
}
bool gsBoundaryInside(vec3 edges) {
    float low=min(edges.x,min(edges.y,edges.z)),high=max(edges.x,max(edges.y,edges.z));
    return !(low<0.0&&high>0.0)&&(low!=0.0||high!=0.0);
}
bool gsBoundarySamePoint(vec3 a,vec3 b){return all(equal(a,b));}
bool gsBoundarySharesEdge(vec3 a,vec3 b,vec3 c,vec3 p,vec3 q,vec3 r) {
    int matches=int(gsBoundarySamePoint(a,p)||gsBoundarySamePoint(a,q)||gsBoundarySamePoint(a,r))
            +int(gsBoundarySamePoint(b,p)||gsBoundarySamePoint(b,q)||gsBoundarySamePoint(b,r))
            +int(gsBoundarySamePoint(c,p)||gsBoundarySamePoint(c,q)||gsBoundarySamePoint(c,r));
    return matches>=2;
}
precise vec2 gsBoundaryDistanceWide(GsBoundaryWide3 a,GsBoundaryWide3 b,GsBoundaryWide3 c) {
    vec2 x=gsBoundaryWideDifferenceOfProducts(b.y,c.z,b.z,c.y);
    vec2 y=gsBoundaryWideDifferenceOfProducts(b.z,c.x,b.x,c.z);
    vec2 z=gsBoundaryWideDifferenceOfProducts(b.x,c.y,b.y,c.x);
    vec2 numerator=gsBoundaryWideAdd(gsBoundaryWideAdd(gsBoundaryWideMultiply(a.x,x),gsBoundaryWideMultiply(a.y,y)),gsBoundaryWideMultiply(a.z,z));
    // Retain the edge residuals through the determinant and division too.
    // Summing only their float heads makes t depend on vertex permutation and
    // can collapse a real two-ULP entry/exit interval into one distance.
    vec2 denominator=gsBoundaryWideAdd(gsBoundaryWideAdd(gsBoundaryEdge(b,c),gsBoundaryEdge(c,a)),gsBoundaryEdge(a,b));
    precise float quotient=numerator.x/denominator.x;
    vec2 remainder=gsBoundaryWideAdd(numerator,-gsBoundaryWideMultiply(denominator,vec2(quotient,0)));
    return gsBoundaryWideAdd(vec2(quotient,0),vec2((remainder.x+remainder.y)/denominator.x,0));
}
precise float gsBoundaryDistance(GsBoundaryWide3 a,GsBoundaryWide3 b,GsBoundaryWide3 c,vec3 edges) {
    vec2 distance=gsBoundaryDistanceWide(a,b,c);return distance.x+distance.y;
}
bool gsBoundaryTriangleHitWide(uint mesh,uint primitive,out vec2 bary,out vec2 distance,out float facing) {
    uvec3 indices=gsTriangleIndices(mesh,primitive);
    GsBoundaryWide3 a=gsBoundaryRelativePoint(gsPosition(mesh,indices.x));
    GsBoundaryWide3 b=gsBoundaryRelativePoint(gsPosition(mesh,indices.y));
    GsBoundaryWide3 c=gsBoundaryRelativePoint(gsPosition(mesh,indices.z));
    vec3 edges=gsBoundaryEdges(a,b,c);bary=vec2(0);distance=vec2(0);facing=edges.x+edges.y+edges.z;
    if(any(isnan(edges))||any(isinf(edges))||!gsBoundaryInside(edges))return false;
    distance=gsBoundaryDistanceWide(a,b,c);bary=edges.yz/(edges.x+edges.y+edges.z);
    return !any(isnan(distance))&&!any(isinf(distance));
}
bool gsBoundaryTriangleHit(uint mesh,uint primitive,out vec2 bary,out float distance) {
    vec2 wide;float facing;bool found=gsBoundaryTriangleHitWide(mesh,primitive,bary,wide,facing);
    distance=wide.x+wide.y;return found;
}
// PBRT util/float.h's outward-rounded interval principle: two real crossings
// may round to the same float t. Preserve their order with the enclosing float
// endpoints, only for opposite-facing, shared-edge crossings in that same bin.
// Coplanar triangles and all representable intervals retain their original t.
float gsBoundaryConnectionDistance(uint mesh,uint primitive,vec2 bary,vec2 wide,float facing) {
    float distance=wide.x+wide.y;
    if(min(1.0-bary.x-bary.y,min(bary.x,bary.y))>=8.0*1.192092896e-7)return distance;
    uvec3 indices=gsTriangleIndices(mesh,primitive);
    vec3 a=gsPosition(mesh,indices.x),b=gsPosition(mesh,indices.y),c=gsPosition(mesh,indices.z);
    uint triangles=gsMeshes.words[mesh+GPU_SCENE_MESH_TRIANGLE_COUNT_WORD];
    for(uint other=0u;other<triangles;++other){
        if(other==primitive)continue;
        uvec3 oi=gsTriangleIndices(mesh,other);
        if(!gsBoundarySharesEdge(a,b,c,gsPosition(mesh,oi.x),gsPosition(mesh,oi.y),gsPosition(mesh,oi.z)))continue;
        vec2 otherBary,otherWide;float otherFacing;
        if(!gsBoundaryTriangleHitWide(mesh,other,otherBary,otherWide,otherFacing)
                ||(facing<0.0)==(otherFacing<0.0)||otherWide.x+otherWide.y!=distance)continue;
        float order=gsBoundaryEdgeSign(gsBoundaryWideAdd(wide,-otherWide));
        precise float residual=(wide.x-distance)+wide.y;
        if(order<0.0&&residual<0.0)return uintBitsToFloat(floatBitsToUint(distance)+(distance>0.0?0xffffffffu:1u));
        if(order>0.0&&residual>0.0)return uintBitsToFloat(floatBitsToUint(distance)+(distance>=0.0?1u:0xffffffffu));
    }
    return distance;
}
// Near a shared edge, hardware hit t can reorder a very thin entry/exit pair.
// A straight connection must consume the nearest real crossing after its exact
// previous t, even when traversal reports the neighbouring face as its candidate.
bool gsBoundaryConnectionHit(uint mesh,uint primitive,vec2 bary,float minimum,
        out uint repairedPrimitive,out vec2 repairedBary,out float repairedDistance) {
    repairedPrimitive=primitive;repairedBary=bary;repairedDistance=gl_HitTEXT;
    vec3 weights=vec3(1.0-bary.x-bary.y,bary);
    if(min(weights.x,min(weights.y,weights.z))>=8.0*1.192092896e-7){
        // Once a corrected t has been consumed, a later hardware approximation
        // of the same face must not enter the stack for a second time.
        return gsBoundaryTriangleHit(mesh,primitive,repairedBary,repairedDistance)&&repairedDistance>=minimum;
    }
    float tolerance=8.0*1.192092896e-7*max(1.0,abs(gl_HitTEXT));
    bool found=false;float nearest=1.0e30;
    uvec3 indices=gsTriangleIndices(mesh,primitive);
    vec3 a=gsPosition(mesh,indices.x),b=gsPosition(mesh,indices.y),c=gsPosition(mesh,indices.z);
    uint triangles=gsMeshes.words[mesh+GPU_SCENE_MESH_TRIANGLE_COUNT_WORD];
    for(uint other=0u;other<triangles;other++) {
        uvec3 oi=gsTriangleIndices(mesh,other);
        vec3 p=gsPosition(mesh,oi.x),q=gsPosition(mesh,oi.y),r=gsPosition(mesh,oi.z);
        if(other!=primitive&&!gsBoundarySamePoint(a,p)&&!gsBoundarySamePoint(a,q)&&!gsBoundarySamePoint(a,r)
                &&!gsBoundarySamePoint(b,p)&&!gsBoundarySamePoint(b,q)&&!gsBoundarySamePoint(b,r)
                &&!gsBoundarySamePoint(c,p)&&!gsBoundarySamePoint(c,q)&&!gsBoundarySamePoint(c,r))continue;
        vec2 candidateBary,candidateWide;float facing;
        if(!gsBoundaryTriangleHitWide(mesh,other,candidateBary,candidateWide,facing))continue;
        float candidateDistance=gsBoundaryConnectionDistance(mesh,other,candidateBary,candidateWide,facing);
        if(candidateDistance<minimum
                ||abs(candidateDistance-gl_HitTEXT)>tolerance||candidateDistance>=nearest)continue;
        found=true;nearest=candidateDistance;repairedPrimitive=other;repairedBary=candidateBary;repairedDistance=candidateDistance;
    }
    return found;
}
bool gsBoundaryCandidateValid(uint mesh,uint primitive,vec2 bary) {
    vec3 weights=vec3(1.0-bary.x-bary.y,bary);
    if(min(weights.x,min(weights.y,weights.z))>=8.0*1.192092896e-7)return true;
    uvec3 indices=gsTriangleIndices(mesh,primitive);
    vec3 pa=gsPosition(mesh,indices.x),pb=gsPosition(mesh,indices.y),pc=gsPosition(mesh,indices.z);
    GsBoundaryWide3 a=gsBoundaryRelativePoint(pa),b=gsBoundaryRelativePoint(pb),c=gsBoundaryRelativePoint(pc);
    vec3 edges=gsBoundaryEdges(a,b,c);
    if(any(isnan(edges))||any(isinf(edges)))return true;
    if(gsBoundaryInside(edges))return true;
    // A hardware edge candidate can belong to the adjacent face instead. Rejecting
    // it triangle-by-triangle opens cracks: traversal need not report both faces.
    // Volume-only boundaries may retain a nearby, equally oriented real crossing;
    // an opposite-facing neighbour cannot justify a false silhouette crossing.
    float facing=edges.x+edges.y+edges.z;
    uint triangles=gsMeshes.words[mesh+GPU_SCENE_MESH_TRIANGLE_COUNT_WORD];
    for(uint other=0u;other<triangles;other++) {
        if(other==primitive)continue;
        uvec3 oi=gsTriangleIndices(mesh,other);
        vec3 p=gsPosition(mesh,oi.x),q=gsPosition(mesh,oi.y),r=gsPosition(mesh,oi.z);
        if(!gsBoundarySharesEdge(pa,pb,pc,p,q,r))continue;
        GsBoundaryWide3 wa=gsBoundaryRelativePoint(p),wb=gsBoundaryRelativePoint(q),wc=gsBoundaryRelativePoint(r);
        vec3 oe=gsBoundaryEdges(wa,wb,wc);
        if(!gsBoundaryInside(oe)||((oe.x+oe.y+oe.z)<0.0)!=(facing<0.0))continue;
        float distance=gsBoundaryDistance(wa,wb,wc,oe);
        if(abs(distance-gl_HitTEXT)<=8.0*1.192092896e-7*max(1.0,abs(gl_HitTEXT)))return true;
    }
    return false;
}
#endif
