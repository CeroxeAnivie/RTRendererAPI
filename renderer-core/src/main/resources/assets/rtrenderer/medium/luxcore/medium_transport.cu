// RTRendererAPI adapters. The preceding PathVolumeInfo functions are pinned upstream excerpts.
enum Status { OK = 0, OVERFLOW = 1, NOT_FOUND = 2, INVALID_VOLUME = 3, CORRUPT_STATE = 4 };

__device__ Status validate(const PathVolumeInfo *state, const unsigned int materialCount) {
    if (state->volumeIndexListSize > OPENCL_PATHVOLUMEINFO_SIZE) return CORRUPT_STATE;
    if (state->currentVolumeIndex != NULL_INDEX && state->currentVolumeIndex >= materialCount)
        return CORRUPT_STATE;
    bool currentPresent = state->currentVolumeIndex == NULL_INDEX && state->volumeIndexListSize == 0;
    for (unsigned int i = 0; i < state->volumeIndexListSize; ++i)
        if (state->volumeIndexList[i] >= materialCount) return CORRUPT_STATE;
        else if (state->volumeIndexList[i] == state->currentVolumeIndex) currentPresent = true;
    return currentPresent ? OK : CORRUPT_STATE;
}

// The wrapper reports rejected mutations; it never silently drops a 33rd boundary.
__device__ Status add(PathVolumeInfo *state, unsigned int volume,
        const Material *materials, unsigned int materialCount) {
    const Status checked = validate(state, materialCount);
    if (checked != OK) return checked;
    if (volume == NULL_INDEX || volume >= materialCount) return INVALID_VOLUME;
    if (state->volumeIndexListSize == OPENCL_PATHVOLUMEINFO_SIZE) return OVERFLOW;
    PathVolumeInfo_AddVolume(state, volume, materials);
    return OK;
}

__device__ Status remove(PathVolumeInfo *state, unsigned int volume,
        const Material *materials, unsigned int materialCount) {
    const Status checked = validate(state, materialCount);
    if (checked != OK) return checked;
    if (volume == NULL_INDEX || volume >= materialCount) return INVALID_VOLUME;
    for (unsigned int i = 0; i < state->volumeIndexListSize; ++i) {
        if (state->volumeIndexList[i] == volume) {
            PathVolumeInfo_RemoveVolume(state, volume, materials);
            return OK;
        }
    }
    // Upstream Remove assumes the boundary is present. Guard it before its size decrement.
    return NOT_FOUND;
}

// Positive anisotropy scatters in the incident ray's forward direction, as specified by the API.
// Double intermediates avoid cancellation for g near zero and singularities near either pole.
__device__ float hgEvaluate(float cosine, float anisotropy) {
    const double g = (double)anisotropy;
    const double c = fmin(fmax((double)cosine, -1.0), 1.0);
    const double a = fabs(g), forwardCosine = g < 0.0 ? -c : c;
    const double gap = 1.0 - a;
    const double denominator = gap * gap + 2.0 * a * (1.0 - forwardCosine);
    return (float)(((1.0 - g) * (1.0 + g)) /
            (12.56637061435917295385 * denominator * sqrt(denominator)));
}

__device__ double hgCosine(float anisotropy, float sample) {
    const double u = fmin(fmax((double)sample, 0.0), 1.0), g = (double)anisotropy;
    if (u == 0.0) return -1.0;
    if (u == 1.0) return 1.0;
    const double t = 2.0 * u - 1.0, denominator = 1.0 + g * t;
    double cosine;
    if (fabs(g) < 0.0001) {
        // Algebraically identical inverse CDF, without subtracting nearly equal numbers.
        cosine = (t + .5 * g * (t * t + 3.0) + g * g * t
                + .5 * g * g * g * (t * t - 1.0)) / (denominator * denominator);
    } else {
        const double quotient = ((1.0 - g) * (1.0 + g)) / denominator;
        cosine = (1.0 + g * g - quotient * quotient) / (2.0 * g);
    }
    return fmin(fmax(cosine, -1.0), 1.0);
}

__device__ float3 hgSample(float3 incident, float anisotropy, float u, float v, float *pdf) {
    incident = normalize(incident);
    const double cosine = hgCosine(anisotropy, u);
    const float sine = (float)sqrt(fmax(0.0, (1.0 - cosine) * (1.0 + cosine)));
    const double phi = 6.28318530717958647693 * (double)v;
    const float3 axis = fabsf(incident.z) < .999f ? make_float3(0.f, 0.f, 1.f) : make_float3(0.f, 1.f, 0.f);
    const float3 tangent = normalize(cross(axis, incident)), bitangent = cross(incident, tangent);
    const float3 direction = normalize(incident * (float)cosine
            + tangent * ((float)cos(phi) * sine) + bitangent * ((float)sin(phi) * sine));
    // Evaluate on the returned float direction so sample and evaluate agree under wire precision.
    *pdf = hgEvaluate(dot(incident, direction), anisotropy);
    return direction;
}
} // namespace rtr_medium
#undef MATERIALS_PARAM_DECL
#undef NULL_INDEX
#undef OPENCL_PATHVOLUMEINFO_SIZE
