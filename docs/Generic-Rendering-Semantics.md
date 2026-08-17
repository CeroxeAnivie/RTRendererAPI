# Generic Rendering Semantics

`3.0.0` has one `Renderer` surface with two explicit workload modes: retained scene and command
transactions. A missing scene field never turns a scene request into a command transaction, and a
command transaction is never silently rewritten as a PBR scene.

## Ordinary path

Use `Renderer` scene methods when the application wants the renderer to own resource lifetime,
frame pacing, and the retained ray-tracing scene. This path is intentionally small and remains the
backward-compatible default.

## Expert command path

The expert path is:

1. publish immutable `BufferResource` and `TextureResource` storage generations with `submitResources`;
2. build an immutable `RenderCommandTransaction` with explicit sequence and command order;
3. use `BeginRenderPassCommand`, a validated `GraphicsPipelineState`, explicit bindings and draw
   commands, then `EndRenderPassCommand`;
4. observe `CommandExecutionEvidence` until it reaches `GPU_COMPLETED` or `OUTPUT_PRODUCED`.

The Vulkan backend records dynamic-rendering passes, typed attachment transitions, load/store and
resolve operations, descriptor sets, push constants, vertex/index bindings, direct/indexed/instanced
draws, multi-draws, fixed-count indirect draws, buffer/texture copies, buffer-image copies, color and
depth/stencil clears, and explicit barriers. Buffer-image copies whose byte pitch cannot be represented
by Vulkan texel units are rejected rather than rounded. Count-buffer indirect draws are rejected unless
the backend advertises the corresponding Vulkan extension. Unsupported shader stages, vertex formats,
multisample modes, layouts, and resource states fail closed during admission.

For one transaction, an earlier transfer write consumed later as a vertex, index, indirect, transfer, or
AS-build input buffer read receives a submission-local Vulkan visibility dependency automatically. AS input
uses the exact `TRANSFER_WRITE -> ACCELERATION_STRUCTURE_BUILD` edge; this does not create
cross-submission readiness: a resource written by an earlier transaction must still have `GPU_READY`
evidence. `ResourceBarrierCommand` remains necessary whenever the caller requires an explicit stage,
access, layout, or ownership contract beyond that narrow automatic edge.

`BindingType.COMBINED_IMAGE_SAMPLER` represents a single texture-view/sampler pair at one shader
location. It maps directly to `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`; it is not an alias for
adjacent sampled-texture and sampler slots. The Vulkan backend validates the SPIR-V descriptor
shape before pipeline creation, so a binary using `OpTypeSampledImage` must declare this exact
binding type and cannot be accepted through a split descriptor declaration.

`OUTPUT_PRODUCED` is emitted only after the submission fence completes and names the first stored
attachment resource. `RECORDED` is not GPU completion. Device loss changes the generic command lane
to a terminal structured `DEVICE_LOST` state; callers must recreate the renderer according to the
reported recovery action.

`ResourceVersion` identifies storage shape and declared usage, not every content update. A later write
to a GPU-ready generation produces later fence-backed residency evidence. Retirement always names an
exact `ResourceGenerationKey`; the API deliberately has no broad identity-retirement operation that
could destroy a newer in-flight allocation.

`ResourceMutationKey` identifies the command-sequence snapshot of that storage. It is the stable
in-flight token for a completed or pending content write, rather than a `ByteBuffer` wrapper identity.

## Generic ray tracing command model

The command algebra can express BLAS/TLAS declarations and builds, explicit ray-tracing shader groups,
pipeline binding, AS descriptors, and `TraceRaysCommand`. This is distinct from the retained scene path;
it does not reinterpret raster GLSL or infer hit shaders from PBR materials.

On a Vulkan device that advertises these capabilities, the generic backend allocates BLAS/TLAS storage,
uses device-addressable declared AS input buffers, bounds scratch and TLAS-instance allocations to the
submitting fence, validates and compiles the supplied SPIR-V pipeline, packs its explicit groups into an
aligned SBT, writes exact TLAS descriptors, and records `vkCmdTraceRaysKHR`. A trace output becomes
`OUTPUT_PRODUCED` only after that submission fence completes. The result is GPU-completion evidence,
not display visibility; constructing an RT command or observing `RECORDED` never proves either.

An AS `UPDATE` requires the exact existing generation to be fence-idle. A transaction can build a BLAS
and a TLAS that references it, then trace through that TLAS in command order; the backend inserts the
AS-build-to-ray-shader dependency in the same command buffer. Missing exact resources, a non-addressable
AS input, a stale TLAS descriptor, unsupported SPIR-V, invalid SBT layout, or an unresolved prior AS
build fails closed at admission.

`DestroyAccelerationStructureCommand` retires one exact AS generation only after all of its recorded
build and trace uses have completed. It cannot be combined with a build or a descriptor use of that same
AS in one transaction; this preserves bounded native ownership without a broad stable-identity destroy.

## Composition and presentation evidence

`FrameCompositionPlan` expresses ordered project-neutral source mutations and one exact target mutation.
`FramePresentationEvidence` separates `GPU_COMPLETED`, `CONSUMER_ACCEPTED`, and `VISIBLE`; a fence never
proves display visibility. The current Vulkan generic backend does not yet consume composition plans or
publish visible-present evidence. It therefore reports `FRAME_COMPOSITION` and
`FRAME_PRESENTATION_EVIDENCE` as `UNSUPPORTED`; callers must not infer consumer acceptance or visibility
from generic command evidence.

## Explicit RT/raster composition

`RenderWorkload` is the composition discriminator. A combined workload must carry one identical
sequence in both its retained scene frame and its command transaction. The Vulkan provider exposes
`COMBINED_WORKLOADS` only when both lanes are present and routes the retained RT submission first,
followed by the raster transaction through the same frame-queue submission authority. The returned
`WorkloadExecutionEvidence` retains both lane records; a deferred or rejected raster lane is not
reported as a successful combined workload. Providers without a real ordered composition path
return `UNSUPPORTED_COMBINATION`.

## Interop boundary

`renderer-api` defines the project-independent `ExternalFrameConsumer` negotiation and lease
contract. The Vulkan provider exposes a narrow adapter over the existing
`VulkanFrameInterop/GpuFrameLease` ABI, preserving both APIs. The adapter advertises only the
CPU-observed completion contract currently proven by the producer; unsupported acquire signals,
formats, or completion mechanisms fail closed. CPU readback, managed presentation, and external
zero-copy each have distinct ownership and completion rules.

No public type assumes a game, engine, vendor, or window-system-specific material model.
