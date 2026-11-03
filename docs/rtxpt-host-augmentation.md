# Host-lit augmentation implementation and connection

Use `RtxptRenderers.openHostSurface(plan, augmentation)` for the compatible finite-signal policy. Expert callers can derive `RtxptRenderers.hostSurfaceConfiguration(plan.composition()).copyBuilder()` and pass the full configuration explicitly. Policy convenience does not replace the host's GPU input/output lease contract; see [ordinary and expert use](ordinary-and-expert.md).

`HostSurfaceAugmentation` applies the existing deterministic native style
evaluator to host-supplied receivers. It shares the same resident scene,
BLAS/TLAS, material descriptors, compact sources, analytic lights, shadow rules
and finite one-bounce helpers. `RtrHostAugmentation.hlsl` excludes the native
camera entry point with `RTR_NATIVE_LIGHTING_LIBRARY`: it never traces a camera
primary, including on a missing/invalid/fallback-requested input pixel.

The augmentation input allocation has the original eight surface planes plus
RGBA16F `HOST_LIGHTING_BASELINE=(B,S,V,F)` and `HOST_LIT_COLOR=(RGB,alpha)`.
B/S are the host's actual resolved local/sky baseline values, not albedo or
guessed values reconstructed from baked RGB. V/F are visibility and fog factors.
All four values are in [0,1]. The host-lit color is the actual selected lit
diffuse layer, straight alpha, before final host fog/HUD/unrelated overlays.
The material base-color plane remains actual albedo for bounce evaluation.

The common working domain is explicit: `SCENE_LINEAR` or `DISPLAY_LINEAR`.
Color inputs, source values and added indirect use that same declared domain.
This pass performs no exposure, tone mapping, sRGB decoding or automatic domain
conversion. Display-linear correction does not become scene-linear HDR merely
because its storage is RGBA16F. The output packet must report the active domain.
The host applies its final fog once after composition; F only attenuates the
gain correction and new indirect contribution.

The supported output bits follow the host packet order: COMBINED=1,
DIRECT_MODULATION=2, DIFFUSE_INDIRECT=8, EMISSION=64 and COVERAGE=128.
Physical direct diffuse/specular/transmission belong to split transport.
The style pass does not fabricate those AOVs as zeros or relabel correction as
physical direct irradiance. Gain-only skips GI; GI-only skips gain queries;
emission/coverage-only executes no illumination queries. Only requested output
textures are allocated. The shared native helper is prepared with output mask
zero to avoid duplicate native full-grid signal allocations.

For each valid supplied surface, combined is `H*gain + GI + E`. If the plan
declares `HOST_EMISSION`, H already includes E, so the modulated base is
`max(H-E,0)` before adding E once. Otherwise H excludes E and the API adds it once.
Gain and indirect use the unchanged `RtrEvaluateReceiver` formula. Primary
albedo appears once inside its indirect result. Black H with zero GI/E remains
black for any finite gain. Invalid/background surfaces produce neutral gain,
zero indirect/coverage and leave the actual H color/alpha unchanged in combined.
Combined already includes its GI/emission; consumers must not add those planes
again. Stable instance/primitive mapping validates receiver identity, while
secondary queries use actual retained material/texture/alpha data.

## Exact root connection points

The following pieces intentionally stay outside this independent change's file
ownership. Connect them in the existing renderer/JNI rather than creating a
second renderer or scene.

1. Include `rtxpt_host_augmentation.h` after the native/host headers; add
   `RtrHostAugmentation hostAugmentation;` to `HostPathTracer`.
2. Copy `RtrHostAugmentation.hlsl` into the runtime's
   `ShaderDynamic/Source/Rtxpt/ProcessingPasses/` and add
   `ProcessingPasses/RtrHostAugmentation.hlsl -T cs -E main` to ShaderMake's
   configuration. The existing native-library guard is required. Rebuild the
   native ABI inventory after changing that shared shader's bytes.
3. Expose `HostSurfaceAugmentationControl` on `RtxptRenderer`. Its setter calls
   `configuration.validateFor(hostSurfacePlan)` and prepares before accepting.
   Pack `configuration.lighting()` with the existing
   `RtxptNativeLightingParameters.pack(...)`. This is a separate configuration
   from independent native primary mode; do not call `nativeLightingPlan(...)`
   to select it. Default finite augmentation should request modulation|indirect
   unless the caller explicitly requests a real combined host-color output.
4. For each accepted augmentation frame, before the normal/full PT branch:

```cpp
RtrHostAugmentation::Plan p;
p.width = lightingGridWidth; p.height = lightingGridHeight;
p.outputs = requestedHostOutputMask;
p.colorDomain = declaredDomain; // 0 scene-linear, 1 display-linear
p.hostColorPresent = true;     // actual plane 9 is part of the input lease
hostAugmentation.Configure(nativeLighting, p, packedNativeParameters);
try {
    hostAugmentation.Prepare(GetDevice(), *m_shaderFactory,
                            m_bindlessLayout, nativeLighting);
} catch (...) {
    hostAugmentation.DiscardPendingConfiguration();
    throw; // old active plan/outputs remain usable
}
```

   Preparation creates no scene/AS and does not set `nativeLighting.active`.
   Validate configuration/outputs before consuming an input reservation.
5. Use the existing independent native render preparation: refresh retained
   scene/material buffers, update changed skinned BLAS/TLAS, upload analytic
   lights, then dispatch on the existing open command list:

```cpp
hostAugmentation.Dispatch(GetDevice(), m_commandList, m_scene, m_topLevelAS,
    sparseLighting, m_CommonPasses->m_LinearWrapSampler, sceneLightBuffer,
    uint32_t(sceneLights.size()),
    lightTree.root == rtr::RetainedLightTree::None ? 0 : lightTree.root,
    lightSlotCapacity, nativeLighting, hostSurface,
    surfaceRead->textures, hostTimeNanos, hostDeltaNanos);
```

   The input array has ten handles for augmentation. The pass calls
   `hostSurface.Prepare`, `nativeLighting.UploadAttributes` and source upload;
   do not duplicate those calls in this branch. It skips PT beauty, denoising
   layers, RR/guides, resolve, bloom and Present. Record/execute the command list,
   then call the existing `FinishHostRead()` so all ten resources return to
   COMMON and the input done fence is signaled after their last GPU read.
6. Publish `hostAugmentation.Outputs()` through the same `surfaceFrames.Publish`
   ownership path; use the actual lighting-grid dimensions, requested mask and
   `Reconstruction.NONE`. Do not call `hostOutputs.Resolve` in augmentation.
   Keep the existing split-mode outputs and RR path unchanged. The producer
   packet needs the declared working-domain field; it cannot retain an
   unconditional scene-linear label for display-linear output.
7. Native work counters may use the existing optional `nativeLighting.work`:
   primary count remains zero and receiver/shadow/bounce counts are actual.
   Host statistics increment supplied-primary or invalid/background only;
   fallback and fill counts remain zero because neither pass exists here.
   After execution, use existing `RecordWork` functions only when enabled.

## Checks

The standalone shader compiles with the pinned DXC `cs_6_6`, 320-byte shared
native constants and 48-byte augmentation constants. A C++20 `/Zs` wrapper
compiles the actual pass against the pinned Donut/NVRHI headers. The pure Java
contract checks black-base behavior, emission ownership, demand masks, domains
and unsupported physical AOV rejection. These checks do not substitute for the
root's pending real host-input GPU/lease/resize tests.
