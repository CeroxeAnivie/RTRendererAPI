# Streaming frame materialization

2026-11-08. Unpublished 8.0.0 candidate on main. The 180 frames/s target is not met. Source changes are not a Maven Local release.

## Code-level omissions found

1. `Host::RefreshRetainedDirectStructures` transitioned each newly built BLAS from write to read before the next independent build. The next native NVRHI build commits that dependency. The retained `38158f88` phase-batched strategy was missing from this path. The adapter now plans the complete batch, prepares inputs/targets, invokes the same original NVRHI builder for every required BLAS, and emits final read dependencies after all builds. Automatic and scratch-reuse barriers, flags, queue edges and retirement remain native.
2. Decode materialized per-mesh channels before the final shared batch existed. The old `5c649d25` upload planner's final-allocation strategy is now adapted at the final-live boundary: validated owned raw spans, layout/1 MiB/32-mesh planning, one resize of final Donut channels, original packing and uploader. JNI owns one immutable packet copy; Java memory is never borrowed past its lifetime.
3. The first implementation deferred structural updates but still eagerly uploaded stable attributes. A new same-packet contract exposed 352 uploaded bytes where only the final surviving 88-byte mesh was required. Attribute-only updates now join final preparation too. Replaced/removed inputs are filtered before upload; structural work uses the original graph path, and stable attributes use the original attribute uploader. The failing assertion remains unchanged and passes after the production fix.

Other changes in this candidate retain exact texture-parent inheritance, one final graph/table preparation per frame and a shared native changed-row journal. These eliminate adapter rediscovery; they do not remove required native dense reindexing.

## Evidence

Evidence root: `build/reports/chain-reconciliation/`.

- `materialization-contracts/result.json`: all six affected resource/material/beauty entries passed for DLL `744e36de122e48414da6859456557da71fc0909c9f067af223cebba060cd7f32`.
- `final-attributes-input-lifetime/mesh-input-lifetime-cases.csv`: final-only 88-byte upload, attributes and positions A/B/A, fresh pixels, no replay, last-delete/reset input bytes and packets exactly zero. This is the subsequent attribute-deferral production fix, before the diagnostic-scope-only follow-up.
- `final-attributes-contracts/result.json`: topology passed; migration exposed a missing relocated `AttributePreparation` event. The follow-up restores that event at actual deferred execution. The failed record is retained; it is not a functional-output failure or a reason to relax the test.
- `shared-row-physical-sum`: earlier direct baseline, initial GPU 0%, 466 fresh outputs; moving pipeline 64.684050 and complete simulation 56.839168 frames/s.
- `materialization-phase-physical-sum`: initial GPU 42/44/43%, 466 fresh outputs; moving pipeline 31.154052 and complete simulation 27.962947 frames/s, observation only. Native structures mean/P95 0.2521/0.3315 ms versus baseline 3.99037/4.249856 ms; mismatched external load prevents an isolated gain claim. Both execute 4,683 required builds, 76 allocations, 1,098 mesh-buffer objects and 243,210,960 mesh-upload bytes.
- `shared-row-busy-control`: alternate runtime control never reached its first GPU sample; main Java thread was in native Frame. `threads.txt` and interruption note are retained. No throughput result is claimed from it.

The fixed trajectory is 20 ticks, 9 frames/tick, 72 degrees, 24 flow meshes/frame, 48 materials, 3,662 resident meshes, 45,000 sources, 256 admission frames, 30 stationary frames and 180 movement frames. A previous 300-tick/180-frame partial trajectory has 61 fewer necessary mesh builds and is not a matching baseline. Physical-sum and finite-channel-max are separate authored contracts; their FPS must never be crossed as an optimization comparison.

## Ownership and limits

Raw input gauges count each decoded mesh packet once: current bytes, lifetime high-water bytes and live packets. They exclude undecoded JNI queue entries and are neither working set nor GPU residency. One surviving mesh currently retains its complete packet, including unrelated fields. The measured physical-sum run retained 255,172,301 bytes at movement end and peaked at 262,990,337 bytes. Last-owner release is verified; a general total-memory bound is not established by the per-packet size limit.

Native Donut clears CPU position/index channels after upload. Exact future geometry-equality proofs therefore still require adapter-owned source data. Do not invent a zero-copy view of cleared native vectors.

The locked native SceneGraph resource trackers are private; changing a registered geometry's material pointer without native unregister/register breaks reference counts. The adapter must retain that original lifecycle. Original WriteGeometryBuffer/WriteInstanceBuffer support full-table writes, with no exposed range source. Neither limitation is solved by copying native packing or modifying vendor code.

All rendering algorithms and shaders in the verified 20-repository/4,426-file upstream remain unmodified. The only audited source variants are the existing noninteractive progress and network preferences. No new branch, worktree or consumer launch is part of this candidate.

## Idle-device verification before consumer run

2026-11-08. Tested native DLL: 027636831c9aaee313818ce6c4db94e93ccd8125a2bf29ef957701a0694415b0. Both source models completed 466/466 fresh outputs, with initial device utilization 0/0/0 percent and no diagnostic loss. Configuration matches the sequential baseline; this is not an ABBA experiment.

| Source model | Metric | Baseline | Candidate | Change |
|---|---|---:|---:|---:|
| physical sum | stationary pipeline frames/s | 384.281848 | 321.470707 | -16.35% |
| physical sum | movement pipeline frames/s | 64.684050 | 60.672737 | -6.20% |
| physical sum | complete simulation frames/s | 56.839168 | 53.736104 | -5.46% |
| finite channel max | stationary pipeline frames/s | 291.180153 | 265.339732 | -8.87% |
| finite channel max | movement pipeline frames/s | 56.204518 | 54.789956 | -2.52% |
| finite channel max | complete simulation frames/s | 50.228473 | 48.911716 | -2.62% |

Physical-sum GPU structures mean/P95 improved from 3.990370/4.266080 ms to 0.311879/0.468992 ms, but complete apply-to-fresh regressed from 15.459762/18.6785 ms to 16.481867/21.2136 ms. CPU upload and remaining serial work offset the measured structure improvement. Actual required builds and mesh upload bytes did not decrease. The 180 frames/s target remains unmet. These are API pipeline rates, not a consumer application's FPS.

The final-live attribute contract reduced the two-edit/remove case from 352 to 88 uploaded bytes without weakening its assertion. Input lifetime checks verify deletion and reset release the owned packets; surviving meshes may retain their whole input packet, so this is not a process-memory bound. Migration, deformation and material-mutation contracts passed with this exact diagnostic-complete DLL. Earlier unaffected contract identities remain separate in the raw evidence.

Evidence: build/reports/chain-reconciliation/final-frame-physical-sum, final-frame-finite-channel-max, final-attributes-input-lifetime, and final-attributes-diagnostics-contracts. The user requested testing this candidate in the consumer despite the measured API throughput regression. Publication identity and the consumer result will be recorded separately; neither is implied by this source note.