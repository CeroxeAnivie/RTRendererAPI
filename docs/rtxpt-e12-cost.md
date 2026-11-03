# E12: identical-algorithm backend cost comparison

The benchmark fixes the scene, camera, baseline attributes, source composition,
deterministic hemisphere sample pattern, output encoding and requested signals.
Its default workload uses a 2560×1600 logical viewport and 1280×800 lighting grid,
two GI samples, distance 6, strength 0.35, the dynamic-only correction formula,
and only direct-gain plus indirect-contribution RGBA16F planes. The exported
`fixture.json` is the input contract for a legacy-backend adapter. The SHA-256 of
its exact UTF-8 bytes identifies the workload; changing the algorithm or sample
pattern requires a new identity.

Prepare without creating a renderer:

```powershell
./tools/Invoke-NativeLightingCost.ps1 -CompileOnly
./tools/Invoke-NativeLightingCost.ps1 -FixtureOnly
```

Run only when the GPU test window is available:

```powershell
./tools/Invoke-NativeLightingCost.ps1 -ComparisonRoundId my-same-round
./tools/Compare-NativeLightingCost.ps1 `
  -CandidateDirectory build/reports/rtxpt-e12
```

This producer alone reports `BASELINE_NOT_RUN` until an actual old-backend run is supplied.
The repository now includes a real retained Vulkan adapter and a serial two-backend
runner, `tools/Invoke-LightingBackendComparison.ps1`. Its implementation, actual
1280×800 captures, environmental constraints and evidence hashes are recorded in
[the backend comparison](quality/lighting-backend-comparison.md).
It never turns historic FPS, a successful Java compile, a beauty pass, a
different output set, or a different GI algorithm into a baseline. To exercise
the published artifacts, supply their complete classpath with `-ClassPath` and
`-Packaged`; this enables strict packaged shaders and does not select a checkout
runtime. The script runs the compiler/JVM through the 10 GiB bounded job tool.

Each run records:

- `fixture.json`: portable scene geometry, identity instances, materials,
  interpolated baseline attributes, compact sources, camera and complete plan.
- `environment.json`: initial GPU utilization samples, adapter UUID, driver,
  idle performance state, power limit, round identity and pacing policy.
- `cpu.csv`: submission recording, admission backpressure and unimported packet
  retirement separately. These are CPU wall times, not GPU rendering times.
- `gpu.csv`: command-list timestamp intervals with sequence, revision, queue,
  parent and native frame. GPU intervals include scheduling and preemption.
- `run.json`: initial preparation, warmup/sample counts, bounded-query loss,
  actual native execution, an independent untimed ray-count sample and stage
  distributions. Per-ray counters are off during timing.
- `direct.pfm` and `indirect.pfm`: final live deterministic output, captured after
  measurement. These explicit readbacks do not run inside the timing loop.

The measured sequence interval excludes preparation, warmup, counter readback,
query draining and output capture. Every accepted frame acquires and abandons
its own unimported packet. No GL import, GL shader, consumer GPU read or host
reconstruction is measured by this native-only benchmark. Those costs require
the real host adapter. This static fixture also does not replace the separately
required 60-second dynamic and 120-second mixed-pressure tests.

The current implementation fuses receiver tracing, source traversal, shadow
queries and one-bounce GI into one compute dispatch. The harness reports the
whole `NativeLightingDispatch` interval. It cannot isolate those four costs with
command-list timestamps, and does not label ray counts or direct-only/GI-on
differences as their separate execution times. `NativeLightingUpload` and
`NativeLightingPublish`, when instrumented, remain independent scopes. Missing
scopes are recorded as unmeasured. No total is formed by adding overlapping
inclusive scopes.

The old-backend adapter must run this exact fixture and produce the same file
schema, setting a distinct `backend` in `run.json`. Its GPU CSV uses columns
`sequence,revision,native_frame,queue,event,parent,stage,inclusive_ms,begin_tick,end_tick,frequency`.
Use backend-neutral stage names `lighting_dispatch`, `lighting_upload` and
`producer_handoff`, or the native scope names above. The timer frequency and
ticks must reproduce each reported interval. `firstMeasuredSequence`,
`lastMeasuredSequence` and `measuredFrames` identify actual newly executed
frames; each frame needs one complete dispatch interval. Set
`gpuTimerSemantics` to `queue_timestamp_inclusive_ms_including_scheduling_preemption`.
`timingValid` must remain false if there are missing/dropped intervals. Copying
metadata without executing that backend is not a valid baseline.
Set `evidenceKind` to `REAL_GPU_RUN`. Isolated tooling tests use
`SYNTHETIC_TEST_ONLY` and require the explicit `-SyntheticContractTest` switch;
their reports remain labeled synthetic and cannot pass `-RequireComparable`.

```powershell
./tools/Compare-NativeLightingCost.ps1 `
  -CandidateDirectory build/reports/rtxpt-e12 `
  -BaselineDirectory path/to/actual-legacy-e12-run -RequireComparable
```

The comparison checks exact fixture identity, distinct backends, the same round,
adapter/driver/power limit/pacing, and initial GPU utilization. Above 10% or with
unknown utilization it disables cross-run ratios and absolute FPS conclusions.
Both images must pass the predeclared tolerances: maximum absolute error 0.01
and RGB RMSE 0.002 in the declared linear signal domain. PFM data is RGB,
little-endian, unit-scale with the same row order. Thresholds are selected before
the run and must not be relaxed after observing a failure. Only scopes measured
in both runs receive P50 ratios; the report retains P95/P99/max values. A ratio
for one dispatch does not establish whole-game FPS improvement.

Current evidence: the real native run contains 120 complete GPU timestamp samples
with no dropped or pending queries at 1280×800, 64 sources and two GI samples.
Exact contribution-bound pruning reduced the measured dispatch P50 from
34.787296 ms to 12.585856 ms; both direct and indirect PFM files remain bit exact.
See [the scoped performance record](quality/rtxpt-performance.md). This compares
two implementations of this same native algorithm. The separate legacy-backend
adapter has now executed the matching fixture. Its correctness and timing record
is separate from that pruning optimization; see the backend comparison linked
above. Isolated source/shadow costs and consumer/reconstruction costs remain
unmeasured. Busy-GPU runs do not establish a backend speedup.
