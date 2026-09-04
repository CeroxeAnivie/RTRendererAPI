# API Contract

RTRendererAPI 4.0.1 is the current contract line. The public API and provider SPI expose one canonical
entry point for each operation. Removed constructors, overloads, legacy wrappers, and implicit
repository fallbacks are not part of this release.

## Versioning

- `4.0.1` establishes the direct graphics, compute, ray-tracing, resource, and frame contracts.
- All published modules use the same `MAJOR.MINOR.PATCH` coordinate and the `vMAJOR.MINOR.PATCH`
  source tag.
- A public signature change is intentional in this major line; consumers must rebuild against the
  4.0.1 API instead of relying on binary or source compatibility shims.
- Maven Central is the only artifact source. Local staging is an explicit publishing task and is
  never selected implicitly during dependency resolution.

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
