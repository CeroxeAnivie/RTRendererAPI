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
    // Dekker's split product keeps the residual explicit. Some shader targets
    // evaluate fma(a,b,-roundedProduct) without preserving that residual.
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
float gsBoundaryDistance(GsBoundaryWide3 a,GsBoundaryWide3 b,GsBoundaryWide3 c,vec3 edges) {
    vec2 x=gsBoundaryWideDifferenceOfProducts(b.y,c.z,b.z,c.y);
    vec2 y=gsBoundaryWideDifferenceOfProducts(b.z,c.x,b.x,c.z);
    vec2 z=gsBoundaryWideDifferenceOfProducts(b.x,c.y,b.y,c.x);
    vec2 numerator=gsBoundaryWideAdd(gsBoundaryWideAdd(gsBoundaryWideMultiply(a.x,x),gsBoundaryWideMultiply(a.y,y)),gsBoundaryWideMultiply(a.z,z));
    return (numerator.x+numerator.y)/(edges.x+edges.y+edges.z);
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
