# Native-lighting submission

The direct-lighting consumer now follows retained resources, exact dirty domains and incremental submission without running the unrelated beauty tail on ordinary initialized frames. It still calls the original RTXPT/Donut material, scene and acceleration operations. No upstream rendering implementation, allocator defaults or shader was patched.

## Changes

- Retain two original NVRHI command lists; native fence-aware upload/scratch versions own reuse.
- Decode and validate pure resident-instance poses and empty transactions, then omit GPU resource commands. Original frame refresh preserves current/previous transforms.
- Override three supported scene buffer factories with the exact original final descriptors, avoiding the temporary base-buffer allocation. Original 1024-row rounding, indexing and uploads remain unchanged.
- Material-set removal retains the previous original bake. A bounded shared-pointer set keeps its reverse references alive. New or changed definitions still use the original full registration.
- Initialized native-lighting frames compose exposed native scene/material/AS lifecycle calls and the existing direct dispatch. Beauty AA, tone mapping and blit are omitted. Initialization, resize and unsupported native view/debug/FG configurations use the original complete frame. Beauty consumers retain their existing route.

The existing nine-field resource counter packet stays unchanged. A separate internal ten-field packet records list ownership, poses, and actual lean/full/empty frame decisions; inspecting it is outside measured frame timing. No GPU readback, synchronous logging or per-frame counter allocation is added.

## Verified results

- Original-source audit: 20 repositories, 4,426 files; original files unchanged, unexpected source files zero. The only existing permitted noninteractive progress-window guard remains separately audited.
- Native bridge and Java compilation pass. The initial native attempt needed the original CaptureScriptManager header; no upstream source change was required.
- All 46 migration-contract frames pass: sampler/opacity and real pixels, mixed texture/attribute/topology updates, dense reindex, independent diagnostics, OFF behavior, live output lease, exact current/previous transforms, and unique-material deletion/re-registration. Stable pose frames each execute exactly one lean frame and zero complete-frame fallbacks.
- Four preexisting PFM outputs are byte-identical to the previously accepted 8.0.0 fixture. This is regression evidence, not a claim of complete equivalence to every historical consumer.
- Identical-class progressive pair: 3,662 meshes, 45,000 sources, 256 admission batches, 30 stationary frames and 180 fast movement frames. Each runtime accepts 466/466 fresh outputs with zero lost/failed native events. Candidate: 465 lean frames and one initialization fallback; all 210 stationary/movement frames use the lean route.

| Actual scope events | Baseline | Candidate | Difference | Change |
| --- | ---: | ---: | ---: | ---: |
| Admission full scene refresh | 255 | 255 | 0 | 0% |
| Admission TLAS allocation | 255 | 255 | 0 | 0% |
| Admission BLAS builds | 3,647 | 3,647 | 0 | 0% |
| Admission BufferUpload scopes | 688 | 744 | +56 | +8.14% |
| Movement full scene refresh | 3 | 3 | 0 | 0% |
| Movement BLAS builds | 183 | 183 | 0 | 0% |
| Movement BufferUpload scopes | 18 | 16 | -2 | -11.11% |

Upload scope counts are not bytes or driver allocations; no unsupported cause is assigned to their change. Candidate creates one resource list and one structure list, reopening them 435/257 times; old runtime lacks these counters. During 30 pose transactions, neither list is reopened and 240 poses are preserved.

GPU was externally busy, so no FPS or timing improvement is claimed. Raw phase mean/P95/P99/max remain in the evidence files. The full scene refresh and new-material registration costs are still present: the original public material baker has no incremental registration interface, and the original nonvirtual refresh controls graph indexing/packing/upload. No private-state access, copied refresh algorithm, fabricated metadata file, removed contributor or stale output substitutes for these missing interfaces.

Evidence: [summary data](data/native-lighting-submission.json), build/reports/scene-update-fix/lean-contract and build/reports/scene-update-fix/lean-progressive-comparison. Real-host startup/movement/crash validation remains pending formal publication and dependency verification.
