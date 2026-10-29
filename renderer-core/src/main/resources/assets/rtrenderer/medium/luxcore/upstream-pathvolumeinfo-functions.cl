#line 2 "pathvolumeinfo_funcs.cl"

/***************************************************************************
 * Copyright 1998-2020 by authors (see AUTHORS.txt)                        *
 *                                                                         *
 *   This file is part of LuxCoreRender.                                   *
 *                                                                         *
 * Licensed under the Apache License, Version 2.0 (the "License");         *
 * you may not use this file except in compliance with the License.        *
 * You may obtain a copy of the License at                                 *
 *                                                                         *
 *     http://www.apache.org/licenses/LICENSE-2.0                          *
 *                                                                         *
 * Unless required by applicable law or agreed to in writing, software     *
 * distributed under the License is distributed on an "AS IS" BASIS,       *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.*
 * See the License for the specific language governing permissions and     *
 * limitations under the License.                                          *
 ***************************************************************************/

//------------------------------------------------------------------------------
// PathVolumeInfo
//------------------------------------------------------------------------------

OPENCL_FORCE_INLINE void PathVolumeInfo_Init(__global PathVolumeInfo *pvi) {
	pvi->currentVolumeIndex = NULL_INDEX;
	pvi->volumeIndexListSize = 0;

	pvi->scatteredStart = false;
}

OPENCL_FORCE_INLINE void PathVolumeInfo_StartVolume(__global PathVolumeInfo *pvi, const uint volIndex) {
	if (volIndex == NULL_INDEX) {
		// NULL volume, I just ignore the volume
		return;
	}

	// Set the current volume
	pvi->currentVolumeIndex = volIndex;

	// Add the volume to the list
	pvi->volumeIndexList[(pvi->volumeIndexListSize)++] = volIndex;
}

OPENCL_FORCE_INLINE void PathVolumeInfo_AddVolume(__global PathVolumeInfo *pvi, const uint volIndex
		MATERIALS_PARAM_DECL) {
	if ((volIndex == NULL_INDEX) || (pvi->volumeIndexListSize == OPENCL_PATHVOLUMEINFO_SIZE)) {
		// NULL volume or out of space, I just ignore the volume
		return;
	}

	// Update the current volume. ">=" because I want to catch the last added volume.
	if ((pvi->currentVolumeIndex == NULL_INDEX) ||
			(mats[volIndex].volume.priority >= mats[pvi->currentVolumeIndex].volume.priority))
		pvi->currentVolumeIndex = volIndex;

	// Add the volume to the list
	pvi->volumeIndexList[(pvi->volumeIndexListSize)++] = volIndex;
}

OPENCL_FORCE_INLINE void PathVolumeInfo_RemoveVolume(__global PathVolumeInfo *pvi, const uint volIndex
		MATERIALS_PARAM_DECL) {
	if ((volIndex == NULL_INDEX) || (pvi->volumeIndexListSize == 0)) {
		// NULL volume or empty volume list
		return;
	}

	// Update the current volume and the list
	bool found = false;
	uint newCurrentVolumeIndex = NULL_INDEX;
	for (uint i = 0; i < pvi->volumeIndexListSize; ++i) {
		if (found) {
			// Re-compact the list
			pvi->volumeIndexList[i - 1] = pvi->volumeIndexList[i];
		} else if (pvi->volumeIndexList[i] == volIndex) {
			// Found the volume to remove
			found = true;
			continue;
		}

		// Update currentVolume. ">=" because I want to catch the last added volume.
		if ((newCurrentVolumeIndex == NULL_INDEX) ||
				(mats[pvi->volumeIndexList[i]].volume.priority >= mats[newCurrentVolumeIndex].volume.priority))
			newCurrentVolumeIndex = pvi->volumeIndexList[i];
	}
	pvi->currentVolumeIndex = newCurrentVolumeIndex;

	// Update the list size
	--(pvi->volumeIndexListSize);
}

OPENCL_FORCE_INLINE uint PathVolumeInfo_SimulateAddVolume(__global PathVolumeInfo *pvi, const uint volIndex
		MATERIALS_PARAM_DECL) {
	// A volume wins over current if and only if it is the same volume or has an
	// higher priority

	const uint currentVolumeIndex = pvi->currentVolumeIndex;
	if (currentVolumeIndex != NULL_INDEX) {
		if (volIndex != NULL_INDEX) {
			return (mats[currentVolumeIndex].volume.priority > mats[volIndex].volume.priority) ? currentVolumeIndex : volIndex;
		} else
			return pvi->currentVolumeIndex;
	} else
		return volIndex;
}

OPENCL_FORCE_INLINE uint PathVolumeInfo_SimulateRemoveVolume(__global PathVolumeInfo *pvi, const uint volIndex
		MATERIALS_PARAM_DECL) {
	if ((volIndex == NULL_INDEX) || (pvi->volumeIndexListSize == 0)) {
		// NULL volume or empty volume list
		return pvi->currentVolumeIndex;
	}

	// Update the current volume
	bool found = false;
	uint newCurrentVolumeIndex = NULL_INDEX;
	for (uint i = 0; i < pvi->volumeIndexListSize; ++i) {
		if ((!found) && (pvi->volumeIndexList[i] == volIndex)) {
			// Found the volume to remove
			found = true;
			continue;
		}

		// Update currentVolume. ">=" because I want to catch the last added volume.
		if ((newCurrentVolumeIndex == NULL_INDEX) ||
				(mats[pvi->volumeIndexList[i]].volume.priority >= mats[newCurrentVolumeIndex].volume.priority))
			newCurrentVolumeIndex = pvi->volumeIndexList[i];
	}

	return newCurrentVolumeIndex;
}

