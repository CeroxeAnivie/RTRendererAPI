# Hex-ball RTXPT performance investigation

## Frozen comparison

RTX 5080 Laptop; 2560x1440 output; 1 SPP; FG disabled; original metal-ball
DemoScene, HexPhysics and camera on both paths. DLSS remains enabled. RTXPT
retains its restored DLSS-RR reconstruction: the accepted clear image must not
be replaced by the earlier noisy, no-denoiser configuration to claim a speedup.

Already captured 30-second results in `data/hexball-native-20261101`:

| Path | Completed FPS | Wall ms/frame | Evidence |
| --- | ---: | ---: | --- |
| Original Vulkan | 230.775737 | 4.333 | vulkan-fg-off.log |
| RTXPT, no denoising (rejected image quality) | 207.945629 | 4.809 | rtxpt-fg-off.log |
| RTXPT, restored DLSS-RR | 113.824691 | 8.785 | rtxpt-reconstruction-fixed-fg-off.log |

The restored-RR run contains a screenshot readback at frame 120. It proves the
configuration/image correction and approximate performance gap, but a fresh run
without capture is required for precise throughput comparison. Its initial GPU
utilization was 0%. These are different rendering pipelines, not proof that Java
or Vulkan cannot use hardware ray tracing.

## Stage evidence collection

`rtxpt_gpu_profile.h` observes the existing upstream `GpuProfiler` begin/end
scopes, including nested scopes and both graphics and compute queues. Its
timestamp heap, readback buffer, metadata ring and name storage are preallocated.
It samples every 16 frames after 240 warmup frames, resolves once per used queue
per sampled frame, polls completed fences, and never waits for queries inside
the frame loop. Full rings drop diagnostic samples instead of blocking rendering.
The CSV is buffered. With the environment switch absent, no timestamp work or
profiling resources are created. It does not enable Tracy, disable async prefix,
or enable the upstream `--perfLog` mode (which would disable async prefix).

Reference: pinned NVIDIA RTXPT 1.9.0, commit
`303b408ab81790a5051abd7573830b7a4112eb8e`,
`Rtxpt/SampleCommon/GpuProfiler.cpp`, `QueueContext::SubmitFrame/Collect`.
The host follows that per-queue timestamp/resolve/fence logic; it does not replace
the upstream rendering algorithms. RTXPT license notices remain in its payload.

Run `tools/Measure-HexBallComparison.ps1 -Backend Rtxpt -Tag <unique-tag>
-StageProfile`, then `python tools/Summarize-RtxptGpuProfile.py <csv>`.
The same helper without `-StageProfile` measures completed-frame throughput.
It records source commit, native DLL hash, arguments, initial GPU state, and exit.
Source changes must be committed before hardware testing. Network operations use
only this machine's process-level proxy and do not add proxy settings to the repo.

The summary subtracts direct child intervals before ranking exclusive scope
costs. Queue sums are reported separately because async queues overlap; they must
not be added to produce frame wall time. `Frame` exclusive time is unscoped work
or gaps inside the queue's measured frame interval, not a diagnosed shader.
Only evidence with zero dropped samples and a successful exit can establish the
first detailed ranking. Final speed acceptance uses diagnostics disabled.
