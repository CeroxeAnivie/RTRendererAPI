# Scene incremental reconciliation

The bridge retains the native scene, material baker, BLAS/TLAS builders and upload authority. This change transfers the prior backend's incremental principles, without transferring its backend implementation or modifying upstream rendering algorithms.

| Domain | Implemented behavior | Boundary |
| --- | --- | --- |
| Transactions | Exact-equal resources preserve persistent roots, reverse dependencies and ordered light views | Real generations, removals and validation remain effective |
| Mesh changes | Separate attributes, same-layout deformation, stable-graph geometry resize and graph topology | Mesh/group identities are preserved only when group count, material order and mesh type match |
| Acceleration | Build only changed meshes; ordinary same-layout BLAS reuse; exact-layout TLAS allocation reuse | Reshapes allocate a correctly sized BLAS; real table dimension changes use native allocation |
| Materials | Existing parameter mutations use public PTMaterial fields and GPUDataDirty; texture redirects use InitFromLoadedTexture | New material registration remains owned by the original baker |
| Lighting metadata | Weak-owner identity/policy caches, geometry delta journal and owner-thread scratch | Dense native indices and true material/texture changes remain validated |
| Lifetime | Both queue fences retain displaced resources; existing reclamation fixes remain active | No early release or custom upstream capacity policy |
| Diagnostics | Actual mesh preparation and material row scopes; cumulative metadata work outside frame timing | OFF does not collect diagnostic-only snapshots |

## Validation

- Existing 46-frame bridge contract passed.
- Seven-frame geometry contract passed after fixing the bridge's range comparison to match native 16-byte alignment. Index reorder, grow, same-layout deformation after grow and shrink retain graph identity; each update has zero SceneFullRefresh and TlasAllocate events.
- Fast streaming pair: identical fixture classes, 3662 meshes, 45000 sparse sources, 466 fresh outputs per runtime. Movement TLAS allocations 8 -> 0; true topology refreshes 8 -> 8; necessary BLAS scopes 363 -> 363.
- Initial GPU utilization was above 10% for both streaming runs. Timing/throughput remains observation, not a clean performance gain claim.
- Eleven fresh GPU material frames passed: same-identity scalar color, existing texture-ID redirection, transmission and opacity, including restoration. All ten updates had zero Materials and SceneFullRefresh events with the original BLAS identity. Roughness and emission are not observable in this direct-shadow signal and are explicitly not claimed GPU-verified.
- Final publication and consumer validation remain pending at this source checkpoint.

The second same-frame native Scene::Refresh is retained where the staged pose and SubgraphPrevTransforms lifecycle requires it. Copying private native packing or previous-transform algorithms to suppress this call would expand maintenance and break ownership. Newly registered materials and actual graph topology remain visible limits, not claimed eliminated work.

## Final candidate checks

The final weak dependency implementation passed the 46-frame lifecycle/registry contract, the 11-frame geometry/mixed-sampler/unused-material contract, and the 20-frame shared-material mutation contract. Related policy updates touch exactly two users and read a shared material policy once; opacity touches those users with zero policy reads; RGB, transmission, roughness, emission and pixel-only updates do neither. Keys, layouts and corner repacking remain unchanged.

The per-packet new-material gate is now evaluated against final users, and each mesh is classified against its own changed domains. Actual domain changes use the original complete AS rebuild once, without an extra deformation prebuild.

| Profile | Movement full refresh old/new | TLAS allocation old/new | Fresh old/new |
| --- | ---: | ---: | ---: |
| medium | 5/0 | 5/0 | 466/466 |
| fast | 8/3 | 8/0 | 466/466 |

Detailed mean/P95/P99/max observations and initial GPU samples are in data/scene-incremental-comparison.json. These measurements do not certify application FPS. The first medium baseline had 466 fresh frames and exit zero but its compiler-guard log was absent, so that wrapper result was kept as failed; the complete retry is used here.

The original UpdateTLAS still owns its per-frame GPU culling/build behavior. Allocation reuse does not mean every native GPU TLAS build was removed. Its original Baker is also required by native AS flags and SceneRenderCache/SubInstanceData, even though the independent lighting shader reads Donut MaterialConstants. Private native algorithms are not duplicated to bypass these dependencies.
