#ifndef GS_GPU_SCENE_LIGHTING_GLSL
#define GS_GPU_SCENE_LIGHTING_GLSL
float gsDistributionGgx(float normalHalf, float roughness)
{
    float alpha = max(roughness * roughness, 0.0025);
    float alpha2 = alpha * alpha;
    float denominator = normalHalf * normalHalf * (alpha2 - 1.0) + 1.0;
    return alpha2 / max(GS_PI * denominator * denominator, 1.0e-6);
}

float gsGeometrySmith(float normalView, float normalLight, float roughness)
{
    float r = roughness + 1.0;
    float k = r * r * 0.125;
    float view = normalView / max(normalView * (1.0 - k) + k, 1.0e-6);
    float light = normalLight / max(normalLight * (1.0 - k) + k, 1.0e-6);
    return view * light;
}

vec3 gsFresnelSchlick(float viewHalf, vec3 reflectance)
{
    return reflectance + (vec3(1.0) - reflectance) * pow(1.0 - viewHalf, 5.0);
}

GsRadianceSplit gsEvaluateBrdf(
    vec3 normal,
    vec3 view,
    vec3 light,
    vec3 baseColor,
    float roughness,
    float metallic
)
{
    vec3 halfVector = normalize(view + light);
    float normalView = max(dot(normal, view), 0.0);
    float normalLight = max(dot(normal, light), 0.0);
    float normalHalf = max(dot(normal, halfVector), 0.0);
    float viewHalf = max(dot(view, halfVector), 0.0);
    vec3 f0 = mix(vec3(0.04), baseColor, metallic);
    vec3 fresnel = gsFresnelSchlick(viewHalf, f0);
    float distribution = gsDistributionGgx(normalHalf, roughness);
    float geometry = gsGeometrySmith(normalView, normalLight, roughness);
    vec3 specular = fresnel * distribution * geometry
        / max(4.0 * normalView * normalLight, 1.0e-5);
    vec3 diffuse = (vec3(1.0) - fresnel) * (1.0 - metallic) * baseColor * (1.0 / GS_PI);
    return gsRadianceSplit(diffuse * normalLight, specular * normalLight);
}

GsRadianceSplit gsDirectContribution(
    vec3 position,
    vec3 normal,
    vec3 view,
    vec3 lightDirection,
    vec3 radiance,
    float maximumDistance,
    bool castsShadow,
    vec3 baseColor,
    float roughness,
    float metallic
) {
    if (dot(normal, lightDirection) <= 0.0 || max(max(radiance.r, radiance.g), radiance.b) <= 0.0) {
        return gsRadianceSplit(vec3(0.0), vec3(0.0));
    }
    vec3 visibility = castsShadow
        ? gsShadowTransmittance(position, normal, lightDirection, maximumDistance) : vec3(1.0);
    GsRadianceSplit result = gsEvaluateBrdf(
        normal, view, lightDirection, baseColor, roughness, metallic
    );
    result.diffuse *= radiance * visibility;
    result.specular *= radiance * visibility;
    // Direct-light NRD hitT is the distance to the selected light. It deliberately excludes the
    // camera-to-primary segment, which is never a valid REBLUR hit distance.
    result.diffuseHitDistance = maximumDistance;
    result.specularHitDistance = maximumDistance;
    return result;
}

GsRadianceSplit gsSceneLight(
    uint slot,
    vec3 position,
    vec3 normal,
    vec3 view,
    vec3 baseColor,
    float roughness,
    float metallic
) {
    uint base = slot * GPU_SCENE_LIGHT_RECORD_WORDS;
    uint flags = gsLights.words[base + GPU_SCENE_LIGHT_FLAGS_WORD];
    if ((flags & GPU_SCENE_FLAG_ACTIVE) == 0u) return gsRadianceSplit(vec3(0.0), vec3(0.0));

    uint type = (flags >> 1u) & 0x7u;
    vec3 color = vec3(
        uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_COLOR_WORD + 1u]),
        uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_COLOR_WORD + 2u])
    );
    float intensity = uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_INTENSITY_WORD]);
    vec3 direction;
    float maximumDistance;
    float attenuation = 1.0;
    if (type == GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction = -normalize(vec3(
            uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD + 1u]),
            uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD + 2u])
        ));
        maximumDistance = 1.0e20;
    } else {
        vec3 lightPosition = vec3(
            gsFiniteDoubleAsFloat(gsLights.words[base + GPU_SCENE_LIGHT_POSITION_X_WORD],
                gsLights.words[base + GPU_SCENE_LIGHT_POSITION_X_WORD + 1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base + GPU_SCENE_LIGHT_POSITION_Y_WORD],
                gsLights.words[base + GPU_SCENE_LIGHT_POSITION_Y_WORD + 1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base + GPU_SCENE_LIGHT_POSITION_Z_WORD],
                gsLights.words[base + GPU_SCENE_LIGHT_POSITION_Z_WORD + 1u])
        );
        vec3 delta = lightPosition - position;
        maximumDistance = length(delta);
        if (maximumDistance <= 1.0e-4) return gsRadianceSplit(vec3(0.0), vec3(0.0));
        direction = delta / maximumDistance;
        float range = uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_RANGE_WORD]);
        float rangeFade = clamp(1.0 - maximumDistance / max(range, 1.0e-4), 0.0, 1.0);
        attenuation = rangeFade * rangeFade / max(maximumDistance * maximumDistance, 1.0);
        if (type == GPU_SCENE_LIGHT_SPOT) {
            vec3 spotDirection = normalize(vec3(
                uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD + 1u]),
                uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_DIRECTION_WORD + 2u])
            ));
            float cone = dot(spotDirection, -direction);
            float inner = uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_INNER_CONE_WORD]);
            float outer = uintBitsToFloat(gsLights.words[base + GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
            attenuation *= smoothstep(outer, inner, cone);
        }
    }
    bool castsShadow = (flags & GPU_SCENE_FLAG_CASTS_SHADOW) != 0u;
    return gsDirectContribution(position, normal, view, direction, color * intensity * attenuation,
        maximumDistance, castsShadow, baseColor, roughness, metallic);
}

GsRadianceSplit gsEvaluatePbrSurface(GpuScenePayload surfaceSample, vec3 rayOrigin)
{
    vec3 position = surfaceSample.worldPositionAndDistance.xyz;
    vec3 normal = normalize(surfaceSample.worldNormalAndRoughness.xyz);
    float roughness = clamp(surfaceSample.worldNormalAndRoughness.w, 0.025, 1.0);
    vec3 baseColor = max(surfaceSample.baseColorAndOpacity.rgb, vec3(0.0));
    vec3 emissive = max(surfaceSample.emissiveAndMetallic.rgb, vec3(0.0));
    float metallic = clamp(surfaceSample.emissiveAndMetallic.a, 0.0, 1.0);
    vec3 view = normalize(rayOrigin - position);
    vec3 sky = gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD);
    float ambient = uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]);
    GsRadianceSplit radiance = gsRadianceSplit(
        emissive + baseColor * sky * ambient * (1.0 - metallic),
        vec3(0.0)
    );

    vec3 sunDirection = -normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sunRadiance = gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)
        * uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    GsRadianceSplit sun = gsDirectContribution(position, normal, view, sunDirection, sunRadiance,
        1.0e20, true, baseColor, roughness, metallic);
    gsAccumulateRadianceSplit(radiance, sun);

    uint lightCount = gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];
    for (uint slot = 0u; slot < lightCount; ++slot) {
        GsRadianceSplit light = gsSceneLight(
            slot, position, normal, view, baseColor, roughness, metallic
        );
        gsAccumulateRadianceSplit(radiance, light);
    }
    gsFinalizeRadianceHitDistances(radiance);
    return radiance;
}

#endif
