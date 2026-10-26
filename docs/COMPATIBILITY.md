# API Contract

RTRendererAPI 5.1.0 adds source- and binary-compatible APIs to the Java 25 major-5 contract.
Use matching 5.1.0 API and backend modules for the extended GPU frame ABI. Delivery includes GitHub
source and signed Maven Central artifacts after local staging and independent consumer checks.

## Migration from 4.x

| 4.x surface | 5.0 surface |
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

All four retained-scene stages can be replaced. The retained scene ABI has one raygen, one miss
and one triangle hit group at depth one. Arbitrary shader groups, callable/procedural RT, recursion,
graphics, compute, descriptor sets, textures and samplers remain available through the explicit
command API. Those capabilities use the same versioned resources, ownership and evidence model.

The Java API/provider SPI major is 5. The removed entry points and pixel methods have no
compatibility aliases. Versioned historical quality reports describe their original candidates.

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
