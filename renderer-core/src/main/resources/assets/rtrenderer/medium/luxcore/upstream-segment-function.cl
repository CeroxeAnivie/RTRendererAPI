OPENCL_FORCE_INLINE float HomogeneousVolume_SegmentScatter(const float u, 
		const bool scatterAllowed, const float segmentLength,
		const float3 *sigmaA, const float3 *sigmaS, const float3 *emission,
		float3 *segmentTransmittance, float3 *segmentEmission) {
	// This code must work also with segmentLength = INFINITY

	bool scatter = false;
	*segmentTransmittance = WHITE;
	*segmentEmission = BLACK;

	//--------------------------------------------------------------------------
	// Check if there is a scattering event
	//--------------------------------------------------------------------------

	float scatterDistance = segmentLength;
	const float sigmaSValue = Spectrum_Filter(*sigmaS);
	if (scatterAllowed && (sigmaSValue > 0.f)) {
		// Determine scattering distance
		const float proposedScatterDistance = -log(1.f - u) / sigmaSValue;

		scatter = (proposedScatterDistance < segmentLength);
		scatterDistance = scatter ? proposedScatterDistance : segmentLength;

		// Note: scatterDistance can not be infinity because otherwise there would
		// have been a scatter event before.
		const float tau = scatterDistance * sigmaSValue;
		const float pdf = exp(-tau) * (scatter ? sigmaSValue : 1.f);
		*segmentTransmittance *= 1.f / pdf;
	}

	//--------------------------------------------------------------------------
	// Volume transmittance
	//--------------------------------------------------------------------------
	
	const float3 sigmaT = *sigmaA + *sigmaS;
	if (!Spectrum_IsBlack(sigmaT)) {
		if (isinf(scatterDistance)) {
			// This avoid NaN in case scatterDistance is inf
			*segmentTransmittance = BLACK;
		} else {
			const float3 tau = scatterDistance * sigmaT;
			*segmentTransmittance *= Spectrum_Exp(-tau) * (scatter ? sigmaT : WHITE);
		}
	}

	//--------------------------------------------------------------------------
	// Volume emission
	//--------------------------------------------------------------------------

	*segmentEmission += (*segmentTransmittance) * scatterDistance * (*emission);

	return scatter ? scatterDistance : -1.f;
}