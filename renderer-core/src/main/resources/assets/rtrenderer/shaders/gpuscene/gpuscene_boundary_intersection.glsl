#ifndef GS_BOUNDARY_INTERSECTION_GLSL
#define GS_BOUNDARY_INTERSECTION_GLSL
// PBRT's robust edge-function principle, adapted to Vulkan without requiring
// shaderFloat64: two-float expansions retain transform/subtraction residuals.
// Only candidates in the existing eight-ULP barycentric edge band use this path.
vec2 gsBoundaryWideAdd(vec2 a,vec2 b) {
    precise float s=a.x+b.x;
    precise float v=s-a.x;
    precise float e=(a.x-(s-v))+(b.x-v)+a.y+b.y;
    precise float hi=s+e;
    precise float lo=e-(hi-s);
    return vec2(hi,lo);
}
vec2 gsBoundaryWideMultiply(vec2 a,vec2 b) {
    precise float p=a.x*b.x;
    precise float e=fma(a.x,b.x,-p)+a.x*b.y+a.y*b.x+a.y*b.y;
    precise float hi=p+e;
    precise float lo=e-(hi-p);
    return vec2(hi,lo);
}
vec2 gsBoundaryWideDifferenceOfProducts(vec2 a,vec2 b,vec2 c,vec2 d) {
    return gsBoundaryWideAdd(gsBoundaryWideMultiply(a,b),-gsBoundaryWideMultiply(c,d));
}
struct GsBoundaryWide3 {vec2 x;vec2 y;vec2 z;};
vec2 gsBoundaryRelativeCoordinate(vec3 p,int component) {
    vec2 value=gsBoundaryWideAdd(vec2(gl_ObjectToWorldEXT[3][component],0),vec2(-gl_WorldRayOriginEXT[component],0));
    for(int axis=0;axis<3;axis++)value=gsBoundaryWideAdd(value,
            gsBoundaryWideMultiply(vec2(gl_ObjectToWorldEXT[axis][component],0),vec2(p[axis],0)));
    return value;
}
GsBoundaryWide3 gsBoundaryRelativePoint(vec3 p) {
    return GsBoundaryWide3(gsBoundaryRelativeCoordinate(p,0),gsBoundaryRelativeCoordinate(p,1),gsBoundaryRelativeCoordinate(p,2));
}
vec2 gsBoundaryEdge(GsBoundaryWide3 a,GsBoundaryWide3 b) {
    vec2 x=gsBoundaryWideDifferenceOfProducts(a.y,b.z,a.z,b.y);
    vec2 y=gsBoundaryWideDifferenceOfProducts(a.z,b.x,a.x,b.z);
    vec2 z=gsBoundaryWideDifferenceOfProducts(a.x,b.y,a.y,b.x);
    return gsBoundaryWideAdd(gsBoundaryWideAdd(gsBoundaryWideMultiply(x,vec2(gl_WorldRayDirectionEXT.x,0)),
            gsBoundaryWideMultiply(y,vec2(gl_WorldRayDirectionEXT.y,0))),
            gsBoundaryWideMultiply(z,vec2(gl_WorldRayDirectionEXT.z,0)));
}
float gsBoundaryEdgeSign(vec2 edge){return edge.x!=0.0?edge.x:edge.y;}
bool gsBoundaryCandidateValid(uint mesh,uint primitive,vec2 bary) {
    vec3 weights=vec3(1.0-bary.x-bary.y,bary);
    if(min(weights.x,min(weights.y,weights.z))>=8.0*1.192092896e-7)return true;
    uvec3 indices=gsTriangleIndices(mesh,primitive);
    GsBoundaryWide3 a=gsBoundaryRelativePoint(gsPosition(mesh,indices.x));
    GsBoundaryWide3 b=gsBoundaryRelativePoint(gsPosition(mesh,indices.y));
    GsBoundaryWide3 c=gsBoundaryRelativePoint(gsPosition(mesh,indices.z));
    vec3 edges=vec3(gsBoundaryEdgeSign(gsBoundaryEdge(b,c)),gsBoundaryEdgeSign(gsBoundaryEdge(c,a)),gsBoundaryEdgeSign(gsBoundaryEdge(a,b)));
    if(any(isnan(edges))||any(isinf(edges)))return true;
    float low=min(edges.x,min(edges.y,edges.z)),high=max(edges.x,max(edges.y,edges.z));
    return !(low<0.0&&high>0.0)&&(low!=0.0||high!=0.0);
}
#endif
