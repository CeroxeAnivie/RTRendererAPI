# API Contract

RTRendererAPI 7.0.0 starts a new Java 25 major-7 binary/API contract. Use matching API,
backend and NVIDIA modules. Medium data is expressed directly by `Rgb`, `Medium`,
`MediumBoundary` and `MediumTransport` records. The nested 6.x medium type, medium builders
and compatibility wrappers are absent. Recompile custom retained-scene shaders against the
7.0.0 `gpuscene_abi.glsl`; do not embed record strides from an older release.

This version is delivered through GitHub source and local Maven artifacts. No remote release
or Maven Central publication is implied by building the sources. See [7.0.0 migration](7.0.0.md)
for the exact medium API and this iteration's CPU-only verification boundary.

## Migration from 4.x

| Historical surface | Current surface |
| --- | --- |
| RendererBootstrap / RendererPreset | Renderer.open(), Renderer.open(config), Renderer.devices() |
| expertBuilder() | RendererConfig.builder() |
| preset configuration | RendererConfig.recommended() / presentation() |
| CpuFrame and CPU-specific poll/wait names | FrameSnapshot, pollFrame(), awaitFrame() |
| RGBA8-only pixel accessors | pixels(), copyPixelsTo(), format(), rowBytes(), origin() |
| implicit HDR-to-SDR readback | raw RGBA16_FLOAT; application explicitly chooses display conversion |
| private retained-scene shader replacement | SceneShaderProgram and the public SceneShaderBindings ABI |
| application data transported through scene assets | RenderFrameRequest.shaderData(List<ResourceData>) |

`readFrame(FrameConsumer)` provides a confined native pixel view; the callback may not reenter
its renderer. Native views and all derived buffers expire even when the callback throws. Copy to
`FrameSnapshot` when retention or sharing is needed. Consumer exceptions retire the frame and
propagate without failing the renderer; device/provenance failures fail closed.

All four public retained-scene stages can be replaced. Built-in medium transport uses
ray-generation control and dedicated lightweight visibility hit/miss stages instead of the
6.x nested miss computation service. Custom scene stages and explicit command pipelines use
the 7.0.0 shader contracts; no automatic 6.x shader or payload translation is provided.
Arbitrary shader groups, callable/procedural RT, graphics, compute, descriptor sets, textures
and samplers remain available through the explicit command API.

The Java API/provider SPI major is 7. Historical ABI baselines and quality reports describe
their original versions and are not compatibility adapters for the current implementation.

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
vertex indices. Generic color output readback publishes native-format `FrameView` and `FrameSnapshot` values with stable
resource identity and explicit completion/retirement semantics.

## Support

The support matrix and backend-specific execution evidence are maintained in the current README
and the generic rendering documentation. Unsupported or failed operations return typed evidence or
exceptions; callers must not infer success from object construction or a recorded command alone.
