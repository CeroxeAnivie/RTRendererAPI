# Generic streaming scene performance test

tools/Test-StreamingScenePerformance.ps1 runs the existing RtxptStreamingWorkload fixture. It does not build code, change runtime files, or compile shaders. Compile the test once and export build/renderer-validation-classpath.txt before running. Baseline and current native runtimes use those exact same class bytes; the runner records their hashes and refuses a class change between cases.

The permanent heavy defaults are 3,662 resident meshes, 45,000 sparse sources, 2560×1600 requested output, and 1280×800 direct-gain evaluation. Signal encoding, reconstruction, scene population, normals, shadow roles and sampling quality match the existing fixture. This test does not lower quality to meet a rate target.

## Logical movement

| Profile | Logical speed | Default logical movement duration | Default path length |
| --- | ---: | ---: | ---: |
| medium | 16 units/s | 3 s | 48 units |
| fast | 48 units/s | 3 s | 144 units |
| legacy | 709 units over the configured tick count | 15 s with the original 300 ticks | 709 units |

The fixture default remains --motion-profile legacy: it keeps the original 709-unit path and original update behavior. The permanent runner explicitly selects medium and fast. Both use a 20 Hz logical scene tick and nine render frames per tick by default. Logical time is authored input time, not a promise that the renderer reaches 180 frames/s, and no wall-clock sleeps are inserted into the measured interval. Both profiles follow the existing 72-degree turning path. The reported travelled distance sums sampled chords; the fixture checks it against the exact discrete trajectory, not a continuous-arc approximation.

Entering tiles publish distinct mesh and instance identities; leaving tiles remove them. The resident mesh count remains fixed. Eight animated instances publish their poses every render frame in medium/fast, including stationary sampling. Every logical movement tick updates up to 150 sparse sources. Every moving render frame publishes a texture generation: full-content no-op generations alternate with a real 16×16 rectangle whose values change on every edit; every 32 frames one full no-op slot changes sampler policy. Rectangle generations retain the actual preceding generation. The initial texture is white, retaining the original material response before animation.

Tile entry is discrete: the center is floor(position/16), and a new desired tile set is produced only after a cell boundary is crossed. Speed increases the travelled distance and generally increases crossings, rather than applying an artificial update multiplier. A one-second medium path travels 16 units on a turning arc and stays in its original cell; a one-second fast path travels 48 units and crosses three cell boundaries. With the default three-second window, medium crosses three boundaries and fast crosses ten. The result records actual mesh entries/removals and authored center/boundary changes. A short medium case with no boundary crossing does not validate medium streaming churn, even though pose, texture and light updates remain real.

Default sampling is one cold scene upload, 30 warmup frames, 90 separately measured stationary frames, and 540 moving frames (60 logical ticks). -Ticks, -FramesPerTick, -StationaryFrames and -WarmupFrames change duration, while -Meshes, -Sources and -TextureSize explicitly change workload size. Results retain all such inputs and actual mesh, instance, pose, light and texture updates. Reduced runs are named by their recorded sizes; they cannot stand in for the heavy defaults.

## Running

Current runtime, both movement profiles and OFF/DEEP:

~~~powershell
pwsh -NoProfile -File tools/Test-StreamingScenePerformance.ps1 -RequireIdle -OutputDirectory build/reports/streaming-scene-performance-current
~~~

For a baseline/current comparison, also pass -BaselineRuntimeDirectory with a preserved complete runtime bin directory. Each case is a separate process and runs serially through Invoke-RtxptHost.ps1, whose Windows Job bounds the process tree to 10 GiB. The renderer window stays hidden. Both runtimes must provide the existing precompiled index and report their native runtime-compiler prohibition guard. A missing profile fails; the runner does not warm a shader cache or enable compiler fallback.

-Profiles fast -Diagnostics DEEP selects one targeted case. -CheckPaths resolves configuration without launching a renderer. Always select a new output directory; existing run results are not overwritten.

## Outputs and interpretation

- environment.json records bytecode/runtime identities, fixed extents, process limits and requested workloads.
- Each case has its own initial three-sample GPU baseline, native log and full fixture result. Initial GPU over 10% or unknown permits observations only; -RequireIdle instead refuses that case before launch.
- throughput.csv and summary.md separate stationary and moving API pipeline throughput from complete simulated-loop throughput. The pipeline boundary remains public apply through fresh GPU-only output lease. Complete simulation measures actual wall time from before input generation through frame validation, lease release, diagnostics, phase resource queries and loop bookkeeping. Renderer creation, endpoint image capture and renderer close are separate. Neither rate is application FPS or GPU busy time.
- latency.csv separates mean, P95, P99 and maximum for every phase, complete simulated frame, and available public boundary stage. Input preparation and diagnostic polling stay outside apply-to-fresh timing but are included in complete simulation timing. Cold upload and warmup are not merged into steady-state results.
- comparison.csv pairs equal profile/diagnostic/phase/stage/statistic measurements, lists baseline, current, absolute and percentage differences, and suppresses gain claims when either initial GPU baseline is busy or unknown. Regressions remain in the table. This sequential pair is not repeated ABBA evidence.
- OFF and DEEP use the same generated workload. OFF has no diagnostic boundary samples; missing samples are not reported as zero-cost stages. Complete public apply/submit/fresh timings remain available in both modes.

Native boundary groups preserve per-event statistics. Native frame groups first sum same-stage completed events by native sequence/revision, then report frame distributions with measured zero-work frames included. Unavailable CPU work samples remain unavailable. Inclusive wall, self wall, thread CPU and self CPU are separate; neither stages nor threads are assumed to run sequentially. JNI Frame wall contains owner-thread resource work and rendering waits. Its self wall is a cross-thread containment remainder, not exclusive CPU work or GPU busy time, and must not be added to ResourcePlan. Unmatched native frame identities are reported explicitly. No private upstream execution is invented.

resource-work.csv reports phase start/end/delta command counts. Seven-field runtimes retain their original counters; nine-field runtimes additionally expose resident_mesh_validation_visits and resident_mesh_validation_skipped. Missing methods or counters are explicitly unavailable, never zero. Reading these counters is outside apply-to-fresh timing and inside complete simulation timing.

## Earlier result boundaries

Older result files remain unchanged. For the first one-second fast DEEP heavy run, stationary apply-to-fresh throughput was 188.613646 frames/s and moving throughput was 79.982056 frames/s. Adding the separately recorded preparation/fresh/poll means gives derived partial means of 5.384187/14.095878 ms and partial rates of 185.729073/70.942726 frames/s. These derived rates still omit other bookkeeping and are not measured complete simulated-loop throughput. A complete frame P95 cannot be recovered by adding component P95 values. New fixture bytecode is required to obtain measured complete-loop wall time and per-frame native distributions; old bytecode and results are not relabeled.

The result records fresh-frame revision/attribute/clock checks and real update counts. A synthetic throughput target does not replace visual acceptance or another consumer's complete workload measurement. Use the focused RtxptBridgeMigrationNativeSelfTest for rectangle-upload byte counts, sampler/scalar pixels, metadata reindexing and live-lease semantics rather than duplicating those checks in this throughput fixture.
