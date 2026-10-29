#define BLACK make_float3(0.f,0.f,0.f)

#define WHITE make_float3(1.f,1.f,1.f)

OPENCL_FORCE_INLINE bool Spectrum_IsEqual(const float3 a, const float3 b) {
	return all(isequal(a, b));
}

OPENCL_FORCE_INLINE bool Spectrum_IsBlack(const float3 a) {
	return Spectrum_IsEqual(a, BLACK);
}

OPENCL_FORCE_INLINE float Spectrum_Filter(const float3 s)  {
	return (s.x + s.y + s.z) * 0.33333333f;
}

OPENCL_FORCE_INLINE float3 Spectrum_Exp(const float3 s) {
	return MAKE_FLOAT3(exp(s.x), exp(s.y), exp(s.z));
}