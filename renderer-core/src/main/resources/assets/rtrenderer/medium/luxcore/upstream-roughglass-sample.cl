// Unmodified function excerpts from LuxCore b872186d2fe995a762f73708c606847eed55843e.
// Copyright 1998-2020 LuxCore authors; Apache-2.0, see LICENSE.txt and AUTHORS.txt.
// Source: include/slg/materials/materialdefs_funcs_roughglass.cl; RoughGlassMaterial_Sample.
OPENCL_FORCE_INLINE void RoughGlassMaterial_Sample(__global const Material* restrict material,
		__global const HitPoint *hitPoint,
		__global float *evalStack, uint *evalStackOffset
		MATERIALS_PARAM_DECL) {
	float u0, u1, passThroughEvent;
	EvalStack_PopFloat(passThroughEvent);
	EvalStack_PopFloat(u1);
	EvalStack_PopFloat(u0);
	float3 fixedDir;
	EvalStack_PopFloat3(fixedDir);

	if (fabs(fixedDir.z) < DEFAULT_COS_EPSILON_STATIC) {
		MATERIAL_SAMPLE_RETURN_BLACK;
	}

	const float3 ktVal = Texture_GetSpectrumValue(material->roughglass.ktTexIndex, hitPoint TEXTURES_PARAM);
	const float3 krVal = Texture_GetSpectrumValue(material->roughglass.krTexIndex, hitPoint TEXTURES_PARAM);
	const float3 kt = Spectrum_Clamp(ktVal);
	const float3 kr = Spectrum_Clamp(krVal);

	const bool isKtBlack = Spectrum_IsBlack(kt);
	const bool isKrBlack = Spectrum_IsBlack(kr);
	if (isKtBlack && isKrBlack) {
		MATERIAL_SAMPLE_RETURN_BLACK;
	}

	const float nuVal = Texture_GetFloatValue(material->roughglass.nuTexIndex, hitPoint TEXTURES_PARAM);
	const float nvVal = Texture_GetFloatValue(material->roughglass.nvTexIndex, hitPoint TEXTURES_PARAM);
	const float u = clamp(nuVal, 1e-9f, 1.f);
	const float v = clamp(nvVal, 1e-9f, 1.f);
	const float u2 = u * u;
	const float v2 = v * v;
	const float anisotropy = (u2 < v2) ? (1.f - u2 / v2) : u2 > 0.f ? (v2 / u2 - 1.f) : 0.f;
	const float roughness = u * v;

	float3 wh;
	float d, specPdf;
	SchlickDistribution_SampleH(roughness, anisotropy, u0, u1, &wh, &d, &specPdf);
	if (wh.z < 0.f)
		wh = -wh;
	const float cosThetaOH = dot(fixedDir, wh);

	const float nc = ExtractExteriorIors(hitPoint, material->roughglass.exteriorIorTexIndex TEXTURES_PARAM);
	const float nt = ExtractInteriorIors(hitPoint, material->roughglass.interiorIorTexIndex TEXTURES_PARAM);
	const float ntc = nt / nc;

	const float coso = fabs(fixedDir.z);

	// Decide to transmit or reflect
	float threshold;
	if (!isKrBlack) {
		if (!isKtBlack)
			threshold = .5f;
		else
			threshold = 0.f;
	} else {
		if (!isKtBlack)
			threshold = 1.f;
		else {
			MATERIAL_SAMPLE_RETURN_BLACK;
		}
	}

	float pdfW;
	BSDFEvent event;
	float3 sampledDir;
	float3 result;
	if (passThroughEvent < threshold) {
		// Transmit

		const bool entering = (CosTheta(fixedDir) > 0.f);
		const float eta = entering ? (nc / nt) : ntc;
		const float eta2 = eta * eta;
		const float sinThetaIH2 = eta2 * fmax(0.f, 1.f - cosThetaOH * cosThetaOH);
		if (sinThetaIH2 >= 1.f) {
			MATERIAL_SAMPLE_RETURN_BLACK;
		}
		float cosThetaIH = sqrt(1.f - sinThetaIH2);
		if (entering)
			cosThetaIH = -cosThetaIH;
		const float length = eta * cosThetaOH + cosThetaIH;
		sampledDir = length * wh - eta * fixedDir;

		const float lengthSquared = length * length;
		pdfW = specPdf * fabs(cosThetaIH) / lengthSquared;
		if (pdfW <= 0.f) {
			MATERIAL_SAMPLE_RETURN_BLACK;
		}

		const float cosi = fabs(sampledDir.z);

		const float G = SchlickDistribution_G(roughness, fixedDir, sampledDir);
		float factor = (d / specPdf) * G * fabs(cosThetaOH) / threshold;

		//if (!hitPoint.fromLight) {
			const float F = FresnelCauchy_Evaluate(ntc, cosThetaIH);
			result = (factor / coso) * kt * (1.f - F);
		//} else {
		//	const Spectrum F = FresnelCauchy_Evaluate(ntc, cosThetaOH);
		//	result = (factor / cosi) * kt * (Spectrum(1.f) - F);
		//}

		pdfW *= threshold;
		event = GLOSSY | TRANSMIT;
	} else {
		// Reflect
		pdfW = specPdf / (4.f * fabs(cosThetaOH));
		if (pdfW <= 0.f) {
			MATERIAL_SAMPLE_RETURN_BLACK;
		}

		sampledDir = 2.f * cosThetaOH * wh - fixedDir;

		const float cosi = fabs(sampledDir.z);
		if ((cosi < DEFAULT_COS_EPSILON_STATIC) || (fixedDir.z * sampledDir.z < 0.f)) {
			MATERIAL_SAMPLE_RETURN_BLACK;
		}

		const float G = SchlickDistribution_G(roughness, fixedDir, sampledDir);
		float factor = (d / specPdf) * G * fabs(cosThetaOH) / (1.f - threshold);

		const float F = FresnelCauchy_Evaluate(ntc, cosThetaOH);
		//factor /= (!hitPoint.fromLight) ? coso : cosi;
		factor /= coso;
		result = factor * F * kr;
		
		const float localFilmThickness = (material->roughglass.filmThicknessTexIndex != NULL_INDEX) 
										 ? Texture_GetFloatValue(material->roughglass.filmThicknessTexIndex, hitPoint TEXTURES_PARAM) : 0.f;
		if (localFilmThickness > 0.f) {
			const float localFilmIor = (material->roughglass.filmIorTexIndex != NULL_INDEX) 
									   ? Texture_GetFloatValue(material->roughglass.filmIorTexIndex, hitPoint TEXTURES_PARAM) : 1.f;
			result *= CalcFilmColor(fixedDir, localFilmThickness, localFilmIor);
		}

		pdfW *= (1.f - threshold);
		event = GLOSSY | REFLECT;
	}

	EvalStack_PushFloat3(result);
	EvalStack_PushFloat3(sampledDir);
	EvalStack_PushFloat(pdfW);
	EvalStack_PushBSDFEvent(event);
}

