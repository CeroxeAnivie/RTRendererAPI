# API Contract

RTRendererAPI 4.1.0 is the current contract line. The public API and provider SPI expose one canonical
entry point for each operation. The additive 4.1.0 update preserves the published 4.0.3 API method
descriptors; the new command extends the sealed command hierarchy.

## Versioning

- `4.1.0` adds `AccelerationStructureAabbGeometry` and
  `BuildProceduralBottomLevelAccelerationStructureCommand` with Vulkan BUILD/UPDATE execution.
  Existing 4.0.3 public binary descriptors remain unchanged. Consumers with exhaustive switches
  over the sealed `RenderCommand` hierarchy must handle the new command when recompiling, and
  must not send it to an older provider. The original triangle command remains unchanged.

- `4.0.3` fixes Vulkan logic-operation mapping, reports logic/geometry device capabilities, adds
  geometry-stage barrier visibility, and adds explicit fence-ordered RT pipeline/SBT retirement.
  Existing public method descriptors remain available; bounded history and explicit query/lease
  semantics are documented in [Evidence Retention](Evidence-Retention.md).

- `4.1.0` continues the direct graphics, compute, ray-tracing, resource, and frame contracts.
- All published modules use the same `MAJOR.MINOR.PATCH` coordinate and the `vMAJOR.MINOR.PATCH`
  source tag.
- New consumers should compile against 4.1.0; rollback to 4.0.3 requires removing procedural
  build commands. Published coordinates are immutable; rollback never overwrites an artifact.
- Maven Central is the release artifact authority. This source checkout also configures a local
  build repository after Central so the demo can consume a staged, unreleased version. That
  maintenance fallback is not distributed as a consumer repository requirement.

## Contract Shape

Each workload declares its complete resource, shader, pipeline, pass, command, and output facts at
admission time. Capability probes report executable support; they do not select an undocumented
alternate implementation. Optional rendering technologies may still report an explicit negotiated
fallback as runtime evidence, but that fallback is a feature policy, not an alternate API channel.

The aggregate `Renderer` facade is composed from focused contracts: `RendererLifecycle` for health
and teardown, `RendererFrameAccess` for CPU frame publication, `RendererSceneAccess` for retained
scene admission, and `RendererCommandAccess` for explicit resource and command transactions.
Consumers should depend on the narrowest contract they actually use.

Graphics pipelines support empty vertex layouts when the shader derives positions from built-in
vertex indices. Generic output readback publishes bounded RGBA8 `CpuFrame` values with stable
resource identity and explicit completion/retirement semantics.

## Support

The support matrix and backend-specific execution evidence are maintained in the current README
and the generic rendering documentation. Unsupported or failed operations return typed evidence or
exceptions; callers must not infer success from object construction or a recorded command alone.
