# 7.2.0-rc.2

Local continuation of the 7.2.0 resource and sparse-query repair batch. Coarse treelets whose conservative support cannot fit the regional cell budget are split into disjoint children; only unindexable leaves or total-budget overflow require global fallback.

RC1 actual-host DEEP evidence exposed 43 broad global roots visited by almost every query, including empty cells: about 71.6 million global visits per frame and a valley observation of 93.21 FPS versus the historical 7.1.7 observation of 109.52 FPS. These are retained failed-candidate results, not an accepted optimization. Its second session hit the 7.5 GiB child Job limit and failed Streamline allocation; that alone does not prove a session leak. Allocation callbacks now preserve HRESULT details and return empty resources on failure.

The NVIDIA native bridge remains byte-identical to 7.1.7 for this local candidate. Same-quality correctness and actual-host DEEP followed by OFF acceptance remain required. No final FPS gain, GitHub source release or binary publication is claimed here.
