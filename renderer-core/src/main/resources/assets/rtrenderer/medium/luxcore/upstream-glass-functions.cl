// Unmodified function excerpts from pinned LuxCore b872186d2fe995a762f73708c606847eed55843e.
// Sources: include/slg/materials/materialdefs_funcs_generic.cl and materialdefs_funcs_glass.cl.
// Copyright 1998-2020 LuxCore authors; Apache-2.0, see LICENSE.txt and AUTHORS.txt.
OPENCL_FORCE_INLINE float FrDiel2(const float cosi, const float cost, const float eta) {
	float Rparl = eta * cosi;
	Rparl = (cost - Rparl) / (cost + Rparl);
	float Rperp = eta * cost;
	Rperp = (cosi - Rperp) / (cosi + Rperp);

	return (Rparl * Rparl + Rperp * Rperp) * .5f;
}

OPENCL_FORCE_INLINE float FresnelCauchy_Evaluate(const float eta, const float cosi) {
	// Compute indices of refraction for dielectric
	const bool entering = (cosi > 0.f);

	// Compute _sint_ using Snell's law
	const float eta2 = eta * eta;
	const float sint2 = (entering ? 1.f / eta2 : eta2) *
		fmax(0.f, 1.f - cosi * cosi);
	// Handle total internal reflection
	if (sint2 >= 1.f)
		return 1.f;
	else
		return FrDiel2(fabs(cosi), sqrt(fmax(0.f, 1.f - sint2)),
			entering ? eta : 1.f / eta);
}

OPENCL_FORCE_INLINE float3 GlassMaterial_WaveLength2RGB(const float waveLength) {
	float r, g, b;
	if ((waveLength >= 380.f) && (waveLength < 440.f)) {
		r = -(waveLength - 440.f) / (440 - 380.f);
		g = 0.f;
		b = 1.f;
	} else if ((waveLength >= 440.f) && (waveLength < 490.f)) {
		r = 0.f;
		g = (waveLength - 440.f) / (490.f - 440.f);
		b = 1.f;
	} else if ((waveLength >= 490.f) && (waveLength < 510.f)) {
		r = 0.f;
		g = 1.f;
		b = -(waveLength - 510.f) / (510.f - 490.f);
	} else if ((waveLength >= 510.f) && (waveLength < 580.f)) {
		r = (waveLength - 510.f) / (580.f - 510.f);
		g = 1.f;
		b = 0.f;
	} else if ((waveLength >= 580.f) && (waveLength < 645.f)) {
		r = 1.f;
		g = -(waveLength - 645.f) / (645 - 580.f);
		b = 0.f;
	} else if ((waveLength >= 645.f) && (waveLength < 780.f)) {
		r = 1.f;
		g = 0.f;
		b = 0.f;
	} else
		return BLACK;

	// The intensity fall off near the upper and lower limits
	float factor;
	if ((waveLength >= 380.f) && (waveLength < 420.f))
		factor = .3f + .7f * (waveLength - 380.f) / (420.f - 380.f);
	else if ((waveLength >= 420) && (waveLength < 700))
		factor = 1.f;
	else
		factor = .3f + .7f * (780.f - waveLength) / (780.f - 700.f);

	const float3 result = MAKE_FLOAT3(r, g, b) * factor;

	// To normalize the output
	const float3 normFactor = MAKE_FLOAT3(1.f / .5652729f, 1.f / .36875f, 1.f / .265375f);
	
	return result * normFactor;
}

OPENCL_FORCE_INLINE float GlassMaterial_WaveLength2IOR(const float waveLength, const float IOR, const float B) {
	// Cauchy's equation for relationship between the refractive index and wavelength
	// note: Cauchy's lambda is expressed in micrometers while waveLength is in nanometers

	// This is the formula suggested by Neo here, with a changed naming convention from B->A and C-> B:
	// https://github.com/LuxCoreRender/BlendLuxCore/commit/d3fed046ab62e18226e410b42a16ca1bccefb530#commitcomment-26617643
	
	// Compute Cauchy-A assuming the user input IOR at 587.56 nm 
	// (Fraunhofer d-line, Helium, used in one definition of the Abbe number)
	//const float A = IOR - B / Sqr(587.56f / 1000.f);

	// Use the user input IOR directly as Cauchy-A. Equivalent to the B used by old LuxRender.
	const float A = IOR;

	// Cauchy's equation
	const float cauchyEq = A + B / Sqr(waveLength / 1000.f);

	return cauchyEq;
}

OPENCL_FORCE_INLINE float3 GlassMaterial_EvalSpecularReflection(__global const HitPoint *hitPoint,
		const float3 localFixedDir, const float3 kr,
		const float nc, const float nt,
		float3 *sampledDir, const float localFilmThickness, const float localFilmIor) {
	if (Spectrum_IsBlack(kr))
		return BLACK;

	const float costheta = CosTheta(localFixedDir);
	*sampledDir = MAKE_FLOAT3(-localFixedDir.x, -localFixedDir.y, localFixedDir.z);

	const float ntc = nt / nc;
	const float3 result = kr * FresnelCauchy_Evaluate(ntc, costheta);
	
	if (localFilmThickness > 0.f) {
		const float3 filmColor = CalcFilmColor(localFixedDir, localFilmThickness, localFilmIor);
		return result * filmColor;
	}
	return result;
}

OPENCL_FORCE_INLINE float3 GlassMaterial_EvalSpecularTransmission(__global const HitPoint *hitPoint,
		const float3 localFixedDir, const float u0,
		const float3 kt, const float nc, const float nt, const float cauchyB,
		float3 *sampledDir) {
	if (Spectrum_IsBlack(kt))
		return BLACK;

	// Compute transmitted ray direction
	float3 lkt;
	float lnt;
	if (cauchyB > 0.f) {
		// Select the wavelength to sample
		const float waveLength = mix(380.f, 780.f, u0);

		lnt = GlassMaterial_WaveLength2IOR(waveLength, nt, cauchyB);

		lkt = kt * GlassMaterial_WaveLength2RGB(waveLength);
	} else {
		lnt = nt;
		lkt = kt;
	}

	const float ntc = lnt / nc;
	const float costheta = CosTheta(localFixedDir);
	const bool entering = (costheta > 0.f);
	const float eta = entering ? (nc / lnt) : ntc;
	const float eta2 = eta * eta;
	const float sini2 = SinTheta2(localFixedDir);
	const float sint2 = eta2 * sini2;

	// Handle total internal reflection for transmission
	if (sint2 >= 1.f)
		return BLACK;

	const float cost = sqrt(fmax(0.f, 1.f - sint2)) * (entering ? -1.f : 1.f);
	*sampledDir = MAKE_FLOAT3(-eta * localFixedDir.x, -eta * localFixedDir.y, cost);

	float ce;
//	if (!hitPoint.fromLight)
		ce = (1.f - FresnelCauchy_Evaluate(ntc, cost)) * eta2;
//	else {
//		const float absCosSampledDir = fabsf(CosTheta(*sampledDir));
//		ce = (1.f - FresnelTexture::CauchyEvaluate(ntc, costheta)) * fabsf(localFixedDir.z / absCosSampledDir);
//	}

	return lkt * ce;
}
