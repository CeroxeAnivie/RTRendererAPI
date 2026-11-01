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

## First measured stage ranking

`rtxpt-profile-v1`: initial GPU 0%, 3351 completed frames / 30.010294 s,
111.659251 FPS; 195 sampled frames per queue, zero dropped samples, exit 0.

| Queue | Exclusive scope | Mean ms/frame |
| --- | --- | ---: |
| Graphics | DLSS-RR | 5.7280 |
| Compute | PathTrace | 1.7341 |
| Compute | PathTracePrePass | 0.8827 |
| Compute | PathTraceSharcUpdate | 0.3110 |
| Compute | ProcessFeedbackHistoryP3 | 0.2657 |
| Compute | DLSSRR_PrepareInputs | 0.2642 |
| Compute | ProcessFeedbackHistoryP2 | 0.2515 |
| Compute | ProcessFeedbackHistoryP1a | 0.2260 |
| Compute | DenoiseSpecHitT | 0.2227 |
| Graphics | All exclusive scopes, sum | 6.0519 |
| Compute | All exclusive scopes, sum | 5.4166 |

These queue intervals overlap and cannot be summed to wall ms/frame. DLSS-RR
accounts for 94.65% of the graphics queue interval. Its measured interval alone
exceeds the old Vulkan pipeline's previously recorded total GPU interval
(3.413854 ms), but async competition can lengthen an interval: this is not yet
proof of isolated neural-network execution cost.

The old path uses ordinary DLSS SR, not RR. Both resolve AUTO/BALANCED to the
SDK's balanced mode. The RTXPT frame reports input 1485x835 and output 2560x1440.
The old surface-only shader traces primary surfaces, evaluates direct PBR and
one opaque secondary reflection (`gpuscene_optics.glsl`,
`gsEvaluateSecondaryTransport`, `RTRENDERER_SURFACE_ONLY`); RTXPT also builds
denoising layers, updates SHaRC and lighting feedback, then evaluates DLSS-RR.
Neither rendered pixel count nor `1 SPP` alone describes that full work.

Next controlled comparisons keep scene/output/SPP/path budget fixed: diagnostics
off baseline; upstream async-prefix off (identical render policy); explicit
upstream RR model presets D/E/F. Preset changes are diagnostic alternatives, not
accepted image-quality-preserving optimizations. Default model F stays unchanged.
Official model source: Streamline 2.14.16 `include/sl_dlss_d.h` and
`docs/ProgrammingGuideDLSS_RR.md`; RTXPT exposes D/E/F in its native UI.

The runner uses direct log file handles. An NGX updater child inherited the first
PowerShell capture pipe and delayed the shell's completion despite the renderer
having exited; `Run-HexBallProcess.py` prevents that harness-only delay. It does
not change rendering or frame-window timing.

## SR comparison correction and concrete input-order defect

The RR result is not a same-technology comparison with the old SR path. The
requested comparison uses `--original-balls --original-settings`: the exact
`DemoRendererProfile.interactive(config)` policy shared by the original demo.
Runtime must report DLSS (SR), balanced, input 1485x835/output 2560x1440, 1 SPP,
no FG, no NRD, no RR, no SHaRC/ReSTIR, SER and Reflex enabled. After the user's
instruction, the old demo is not run again; its frozen 230.775737 FPS remains a
historical reference, not a new contemporaneous paired measurement.

`rtxpt-sr-match-off-v4`: 5678 frames / 30.005992 s, 189.230579 FPS; initial GPU
0%, exit 0. This is before-fix evidence for the SR image defect, not image-quality
acceptance. Switching RR to SR must not be called a code optimization.

Confirmed source defect: upstream `Sample::PostProcessAA` called `EvaluateDLSS`
before `NoDenoiserFinalMerge` when standalone denoising was disabled. The merge
produces current-frame color: `ProcessingPasses/PostProcess.hlsl`,
`NO_DENOISER_FINAL_MERGE`, writes `u_OutputColor` from current denoising layers.
SR read the previous contents with this frame's depth and motion vectors. Old
Vulkan records surface shading before `recordPostTrace` calls reconstruction.

The host patch moves that single merge before copy/TAA/SR and resource handoff.
It does not add a pass or modify the upstream path tracer. RR retains its prefix
guide merge; NRD retains its earlier denoised merge. This fixes the temporal-input
dependency; residual 1-SPP noise and image quality still require visual evidence.

`Assert-RtxptSrFrameOrder.py` checks source order and, with a measured CSV, checks
merge-end before DLSS-begin in every sampled SR frame, with no RR execution.
Final throughput is measured again without stage diagnostics.
