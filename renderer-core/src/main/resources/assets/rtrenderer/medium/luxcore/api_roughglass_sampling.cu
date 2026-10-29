// Stack and constant-texture adaptation only. Sampling, Fresnel and microfacet weights above
// are the pinned LuxCore functions. Inlining lets the compiler scalarize the eight-float stack.
OPENCL_FORCE_INLINE bool sample(float3 fixed,float3 tint,float roughness,float n1,float n2,
        float u0,float u1,bool reflection,float3 *direction,float3 *weight) {
    const Material material={{0u,1u,2u,3u,4u,5u,NULL_INDEX,NULL_INDEX}};
    // Keep the sampler's two-lobe PDF independent of tint, including fully black transmission.
    // Applying the constant tint after sampling is algebraically identical for every nonblack tint.
    const HitPoint point={WHITE,roughness,n1,n2};
    float evalStack[8];unsigned int offset=0u,*evalStackOffset=&offset;
    EvalStack_PushFloat3(fixed);EvalStack_PushFloat(u0);EvalStack_PushFloat(u1);
    // The caller either enumerates both first-interface lobes or samples each with probability 1/2.
    EvalStack_PushFloat(reflection?.75f:.25f);
    RoughGlassMaterial_Sample(&material,&point,evalStack,evalStackOffset);
    BSDFEvent event;float pdf;float3 sampledDirection,sampledWeight;
    EvalStack_PopBSDFEvent(event);EvalStack_PopFloat(pdf);
    EvalStack_PopFloat3(sampledDirection);EvalStack_PopFloat3(sampledWeight);
    *direction=sampledDirection;*weight=sampledWeight;
    // The public radiance transport convention includes eta^2 on transmission, including the
    // smooth limit. Keep that API conversion outside the unchanged upstream sampler.
    if(!reflection)*weight*=tint*((n1/n2)*(n1/n2));
    return offset==0u&&isfinite(pdf)&&isfinite(direction->x)&&isfinite(direction->y)&&isfinite(direction->z)
            &&isfinite(weight->x)&&isfinite(weight->y)&&isfinite(weight->z);
}
} // namespace rtr_roughglass
#undef MATERIAL_SAMPLE_RETURN_BLACK
#undef DEFAULT_COS_EPSILON_STATIC
#undef NULL_INDEX
#undef TEXTURES_PARAM
#undef MATERIALS_PARAM_DECL
#undef EvalStack_PushInt
#undef EvalStack_PopInt
#undef EvalStack_PushUInt
#undef EvalStack_PopUInt
#undef EvalStack_PushFloat
#undef EvalStack_PushFloat2
#undef EvalStack_PushFloat3
#undef EvalStack_PopFloat
#undef EvalStack_PopFloat2
#undef EvalStack_PopFloat3
#undef EvalStack_ReadFloat
#undef EvalStack_ReadFloat2
#undef EvalStack_ReadFloat3
#undef EvalStack_PushBSDFEvent
#undef EvalStack_PopBSDFEvent
