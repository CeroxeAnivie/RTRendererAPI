# Generic Rendering Semantics

`1.1.0` keeps the 1.0.x retained scene API and adds an explicit command transaction path. The
two paths are selected by their entry points. A missing scene field never turns a scene request
into a command transaction, and a command transaction is never silently rewritten as a PBR scene.

## Ordinary path

Use `Renderer` scene methods when the application wants the renderer to own resource lifetime,
frame pacing, and the retained ray-tracing scene. This path is intentionally small and remains the
backward-compatible default.

## Expert command path

The expert path is:

1. publish immutable `BufferResource` and `TextureResource` generations with `submitResources`;
2. build an immutable `RenderCommandTransaction` with explicit sequence and command order;
3. use `BeginRenderPassCommand`, a validated `GraphicsPipelineState`, explicit bindings and draw
   commands, then `EndRenderPassCommand`;
4. observe `CommandExecutionEvidence` until it reaches `GPU_COMPLETED` or `OUTPUT_PRODUCED`.

The Vulkan backend records dynamic-rendering passes, typed attachment transitions, load/store and
resolve operations, descriptor sets, push constants, vertex/index bindings, direct/indexed/instanced
draws, multi-draws, fixed-count indirect draws, copies, and explicit barriers. Count-buffer indirect
draws are rejected unless the backend advertises the corresponding Vulkan extension. Unsupported
shader stages, vertex formats, multisample modes, layouts, and resource states fail closed during
admission.

`OUTPUT_PRODUCED` is emitted only after the submission fence completes and names the first stored
attachment resource. `RECORDED` is not GPU completion. Device loss changes the generic command lane
to a terminal structured `DEVICE_LOST` state; callers must recreate the renderer according to the
reported recovery action.

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
