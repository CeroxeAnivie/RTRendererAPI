# Native memory lifecycle repair

The 2026-11-07 administrator allocation trace includes startup, the native malloc failure and exit of the actual streaming consumer. It contains 3,041,122 events with zero lost events and zero commit events without stacks. Matching DLL/PDB and the full resolved peak attribution are retained with the capture. Peak retained committed intervals total 9,064,181,760 bytes; cumulative allocation and working set are different measurements.

## Confirmed sources and defects

| Source at the same peak | Retained committed bytes | Repair |
| --- | ---: | --- |
| Tracy queues/allocator/tracing | 643,829,760 | Official TRACY_ENABLE=OFF for the bridge build; independent boundary/CSV/HUD diagnostics remain |
| Direct attribute buffers | 527,630,336 | Poll the fenced retirement queue on stable and empty frames; retire binding caches that still own replaced resources |
| Complete beauty render targets | 655,949,824 | Do not require beauty initialization for independent native lighting |
| Environment baking | 403,832,832 | Independent lighting does not enter the unused full beauty preparation |
| Light baking | 291,246,080 | Same direct-only lifecycle boundary |
| Shader debug | 123,482,112 | Same direct-only lifecycle boundary |

Attribute ownership defects are proven separately from the total allocation category: the trace does not establish that every one of the nine attribute intervals is unnecessary. Polling used to occur only when another resource was retired. Inactive DEEP/history/encoding/reconstruction binding sets own NVRHI resources and could pin old allocations after their feature stopped executing. Only caches referencing a replaced resource are transferred to the existing reclaimer. Native command instances continue to retain resources used by submitted copies/bindings until GPU completion.

Independent lighting now bootstraps through the original RecreateAccelStructs and preserves original scene refresh, material update, skinning, BLAS/TLAS and descriptor table ownership. It does not require global beauty bindings, render targets, PT variants or shader-debug setup. Output dimensions, current-frame evaluation, scene contributors, opacity and lighting semantics are not reduced. Beauty/postprocess requests keep the full original path. Unload pairs the original pipeline owner and permits an independent session that never created a PT baker.

All memory changes are external bridge code or official build configuration. The only authorized internal exceptions remain noninteractive progress UI and network-only behavior. They do not authorize changes to rendering, sampling or quality algorithms. The official-source audit verifies 4,426 unchanged original files.

## Validation state

- Production retirement logic with actual NVRHI reference types passes a CPU lifetime fixture: inactive buffer/texture references release, unrelated caches remain, submitted bindings and in-flight copy references remain protected, and transfer is not duplicated. The retirement payload is 120 bytes within its 512-byte fixed slot.
- The complete Tracy-OFF native build passes, including official-source and authorized-variant audits. Java production and existing contract classes compile offline.
- Initial configuration failures are retained in build/reports/native-memory-fix; a successful configuration receipt plus exact hook fingerprints now prevents reuse of a failed CMake cache.
- The candidate DLL is 40aef07aa801cc5647455ff1e339f2d34fe286ccdca0b786034ac6ae757b7962. All 46 existing migration-contract frames pass, including opacity/sampler pixels, dense reindex, independent monitors, diagnostic OFF, retained leases, pose history and material re-registration. The new first-frame assertion records lean=1, complete=0. Runtime compiler processes remain prohibited. Contract child process-tree commit peaks at 1.240 GB and exits normally; this small contract is not the real-consumer memory/FPS benchmark.
- Source 7c26bc79fa54437a36478c95874cafe395ebcec0 was pushed before replacing Maven Local 8.0.0. Ten build/local artifact identities match; the published runtime ZIP is 42300d206ec7943005c9c4b3a6459e8e59708b1725675f1a2ac6aa3252c6b683. This is a local publication, not a Central release.
- The same 46-frame contract has host private-commit peaks 2,969,337,856 to 1,096,630,272 bytes and working-set peaks 757,207,040 to 386,691,072 bytes. Eight retained output files are byte-identical. This small contract does not establish large-consumer performance.
- The actual previously failing streaming consumer completed one hidden load/movement/shutdown run with exit code zero. Host private-commit peak is 8,678,465,536 bytes, working-set peak 6,004,461,568 bytes and process-tree commit peak 9,047,617,536 bytes. The previous failed run reached 9,266,843,648 / 4,383,256,576 / 9,500,762,112 bytes respectively. These are unequal-completion peak observations: commit decreased but working set increased; the five-billion-byte working-set goal is not met.
- Initial GPU utilization was five percent, but a 3.079-second VMMap/jcmd snapshot overlapped movement. Keep measured latency/FPS as diagnostic observations rather than clean speedup evidence. The 372 movement-boundary frames contain 349 SceneFullRefresh scopes, 35 Materials scopes and 349 TLAS allocations; structural preparation and material registration remain real work. No general streaming performance or long-term stability target is declared complete.
- The inspected independent-lighting/NONE process loaded the published bridge and no Streamline/NGX modules. Sessions that require beauty/FG/reconstruction retain their actual supported integration and are not declared fully offline.
