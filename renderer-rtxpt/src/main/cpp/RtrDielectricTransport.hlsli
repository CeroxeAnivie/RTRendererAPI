#ifndef RTR_DIELECTRIC_TRANSPORT_HLSLI
#define RTR_DIELECTRIC_TRANSPORT_HLSLI

// The upstream Fresnel header has no include guard. If the caller has already
// included it (possibly transitively), define this macro before this header.
#ifndef RTR_DIELECTRIC_NATIVE_FRESNEL_INCLUDED
#include "PathTracer/BSDFs/Fresnel.hlsli"
#define RTR_DIELECTRIC_NATIVE_FRESNEL_INCLUDED
#endif

static const uint RTR_DIELECTRIC_CAPACITY = 4u;
static const uint RTR_DIELECTRIC_READY = 0u;
static const uint RTR_DIELECTRIC_DUPLICATE_ENTRY = 1u;
static const uint RTR_DIELECTRIC_UNMATCHED_EXIT = 2u;
static const uint RTR_DIELECTRIC_STACK_FULL = 3u;

struct RtrDielectricMedium
{
    float3 absorption; // Nonnegative linear RGB coefficients per world unit.
    float ior;         // Absolute IOR in [1, 4], not an eta ratio.
};

struct RtrDielectricBoundary
{
    RtrDielectricMedium medium;
    uint materialId;
    uint volumeId; // Stable across every face/mesh of one logical volume; not a primitive ID.
    uint priority; // Authored API priority in [0, 14]; higher wins.
};

struct RtrDielectricStack
{
    RtrDielectricBoundary entries[RTR_DIELECTRIC_CAPACITY];
    uint count; // Only entries [0, count) are live; entry 0 is the active medium.
};

struct RtrDielectricTransition
{
    uint status;
    uint slot;                // Insertion/removal slot in the unchanged input stack.
    bool entering;
    bool changesActiveMedium; // False for a lower-priority boundary; still commit straight crossings.
    float incidentIor;
    float transmittedIor;
};

struct RtrDielectricInterface
{
    float3 reflectedDirection;
    float3 transmittedDirection; // Zero at/beyond the critical angle; do not trace it.
    float reflectance;           // Unpolarized power fraction; transmitted power is 1 - reflectance.
    bool totalInternalReflection; // Includes the critical-angle limit (zero transmitted power).
};

// Start outside all volumes. For a ray starting inside, seed its containing
// volumes with prepare + commit before applying attenuation or tracing a hit.
RtrDielectricStack RtrDielectricEmptyStack()
{
    return (RtrDielectricStack)0;
}

RtrDielectricMedium RtrDielectricActiveMedium(RtrDielectricStack stack)
{
    if (stack.count != 0u)
        return stack.entries[0].medium;

    RtrDielectricMedium vacuum;
    vacuum.absorption = 0.0f;
    vacuum.ior = 1.0f;
    return vacuum;
}

// Apply to the segment BEFORE crossing its ending boundary. Distance is finite
// and nonnegative, in world units (ray t only if the direction is unit length).
float3 RtrDielectricBeerLambert(RtrDielectricMedium medium, float distance)
{
    return exp(-medium.absorption * distance);
}

float3 RtrDielectricSegmentTransmittance(RtrDielectricStack stack, float distance)
{
    if (stack.count == 0u)
        return float3(1.0f, 1.0f, 1.0f);
    return RtrDielectricBeerLambert(stack.entries[0].medium, distance);
}

bool RtrDielectricOutranks(RtrDielectricBoundary a, RtrDielectricBoundary b)
{
    // Match native material ordering on a tie, then distinguish separate
    // volumes of that material without depending on traversal order.
    if (a.priority != b.priority)
        return a.priority > b.priority;
    if (a.materialId != b.materialId)
        return a.materialId > b.materialId;
    return a.volumeId > b.volumeId;
}

// entering means the hit is on an outward-wound geometric front face. An exit
// removes the exact (materialId, volumeId) pair even when it is not active.
// Failures are explicit and never mutate the stack or assume a missing medium.
RtrDielectricTransition RtrDielectricPrepareTransition(
    RtrDielectricStack stack, RtrDielectricBoundary boundary, bool entering)
{
    RtrDielectricTransition transition = (RtrDielectricTransition)0;
    transition.entering = entering;
    transition.incidentIor = RtrDielectricActiveMedium(stack).ior;
    transition.transmittedIor = transition.incidentIor;

    uint matchingSlot = RTR_DIELECTRIC_CAPACITY;
    uint insertionSlot = 0u;
    [unroll]
    for (uint i = 0u; i < RTR_DIELECTRIC_CAPACITY; ++i)
    {
        if (i < stack.count)
        {
            const RtrDielectricBoundary entry = stack.entries[i];
            if (entry.materialId == boundary.materialId && entry.volumeId == boundary.volumeId)
                matchingSlot = i;
            if (entering && RtrDielectricOutranks(entry, boundary))
                ++insertionSlot;
        }
    }

    if (entering)
    {
        if (matchingSlot != RTR_DIELECTRIC_CAPACITY)
            transition.status = RTR_DIELECTRIC_DUPLICATE_ENTRY;
        else if (stack.count == RTR_DIELECTRIC_CAPACITY)
            transition.status = RTR_DIELECTRIC_STACK_FULL;
        else
        {
            transition.slot = insertionSlot;
            transition.changesActiveMedium = insertionSlot == 0u;
            if (transition.changesActiveMedium)
                transition.transmittedIor = boundary.medium.ior;
        }
    }
    else if (matchingSlot == RTR_DIELECTRIC_CAPACITY)
        transition.status = RTR_DIELECTRIC_UNMATCHED_EXIT;
    else
    {
        transition.slot = matchingSlot;
        transition.changesActiveMedium = matchingSlot == 0u;
        if (transition.changesActiveMedium)
            transition.transmittedIor = stack.count > 1u ? stack.entries[1].medium.ior : 1.0f;
    }
    return transition;
}

// Commit only for a ray that actually crosses the interface, never reflection
// or TIR. A prepared transition is valid only for the unchanged stack and the
// same boundary. For coincident exit/entry faces, commit exits then entries on
// a temporary stack, evaluate one interface from the original to the final
// active IOR, and adopt the temporary stack only on transmission.
void RtrDielectricCommitCrossing(inout RtrDielectricStack stack,
    RtrDielectricBoundary boundary, RtrDielectricTransition transition)
{
    if (transition.status != RTR_DIELECTRIC_READY)
        return;

    if (transition.entering)
    {
        // Constant indices keep the bounded stack eligible for register storage.
        [unroll]
        for (int i = int(RTR_DIELECTRIC_CAPACITY) - 1; i > 0; --i)
        {
            if (uint(i) > transition.slot && uint(i) <= stack.count)
                stack.entries[i] = stack.entries[i - 1];
        }
        [unroll]
        for (uint i = 0u; i < RTR_DIELECTRIC_CAPACITY; ++i)
        {
            if (i == transition.slot)
                stack.entries[i] = boundary;
        }
        ++stack.count;
    }
    else
    {
        --stack.count;
        [unroll]
        for (uint i = 0u; i < RTR_DIELECTRIC_CAPACITY - 1u; ++i)
        {
            if (i >= transition.slot && i < stack.count)
                stack.entries[i] = stack.entries[i + 1u];
        }
    }
}

// Unit incident direction points toward the hit; the unit geometric normal
// points into the incident medium (outward on entry, negated on exit).
// The result describes directions and power, not a sampled BSDF or estimator.
// A camera-path radiance estimator applies eta^2=(incidentIor/transmittedIor)^2
// to transmission separately from its Fresnel and sampling weights.
RtrDielectricInterface RtrDielectricEvaluateInterface(float3 incidentDirection,
    float3 incidentNormal, float incidentIor, float transmittedIor)
{
    RtrDielectricInterface result = (RtrDielectricInterface)0;
    const float cosIncident = saturate(-dot(incidentDirection, incidentNormal));
    result.reflectedDirection = incidentDirection + (2.0f * cosIncident) * incidentNormal;

    // Also avoids the native equal-IOR grazing 0/0. Absorption may still change
    // at this interface; that is independent of Fresnel reflectance.
    if (incidentIor == transmittedIor)
    {
        result.transmittedDirection = incidentDirection;
        return result;
    }

    const float eta = incidentIor / transmittedIor;
    float cosTransmitted;
    result.reflectance = saturate(evalFresnelDielectric(eta, cosIncident, cosTransmitted));
    result.totalInternalReflection = cosTransmitted == 0.0f;
    if (result.totalInternalReflection)
    {
        result.reflectance = 1.0f;
        return result;
    }

    // Snell: scale the tangential component by eta, and take the transmitted
    // normal component from the same native evaluation that computed Fresnel.
    result.transmittedDirection = eta * incidentDirection
        + (eta * cosIncident - cosTransmitted) * incidentNormal;
    return result;
}

#endif // RTR_DIELECTRIC_TRANSPORT_HLSLI
