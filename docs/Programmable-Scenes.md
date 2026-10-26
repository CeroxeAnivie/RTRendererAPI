# Programmable retained scenes

5.1.0 exposes retained-scene shader replacement through `SceneShaderProgram`. Ordinary callers
continue to use `Renderer.open()` and the built-in shaders. Applications can replace ray generation,
miss, closest-hit and any-hit independently; omitted stages retain the built-in implementation.

Each replacement is a complete GLSL 460 module with a `main` entry point. Quoted includes resolve
inside the bundled `assets/rtrenderer/shaders/gpuscene/` library. `gpuscene_common.glsl` defines the
scene records and payload helpers. Built-in stage sources are shipped beside it as working examples.
Compilation and descriptor validation happen when the renderer opens, before frame submission.

## Application data

Declare application storage buffers with `dataBindingCount(n)` and supply exactly `n` immutable
`ResourceData` values in `RenderFrameRequest.shaderData(...)`. Buffer `i` is a readonly std430
storage buffer at descriptor set 0, binding `SceneShaderBindings.APPLICATION_DATA + i` (33 + i).
It is visible to all four retained-scene stages. The API imposes no application schema. Each payload
must have a positive size divisible by four and fit the device's `maxStorageBufferRange`.

Reuse the same `ResourceData` instance while its bytes remain unchanged. Each frame slot retains
its own allocation and skips an upload when that slot already contains the same immutable object.
Growth is geometric and participates in memory admission. Updating one slot cannot overwrite
application bytes still used by another in-flight frame.

In 5.1.0, `data.withUpdates(List.of(new ResourceData.Update(byteOffset, replacement)))`
creates a new immutable snapshot while sharing unchanged 16 KiB pages. The backend compares
against each frame slot's actual prior snapshot, including skipped versions and older-snapshot
reuse. Size is fixed; construct a new payload to resize. `bytes()` remains a contiguous readonly
view and materializes lazily for updated snapshots.

Supply `.clock(FrameClock.system())` for live monotonic time, or an explicit
`FrameClock.of(timeNanos, deltaNanos)` for pause, seek and deterministic playback. The default
clock is zero. In GLSL, `gsFrameTimeNanos()` retains the 64-bit timestamp bits;
`gsFrameSecondsSince(start)` subtracts before float conversion and clamps future starts to zero.
`gsFrameDeltaSeconds()` exposes the explicit delta. Request retries preserve the clock snapshot.

```java
var program = SceneShaderProgram.builder()
        .shader(ShaderStage.RAY_GENERATION, raygenGlsl)
        .dataBindingCount(1)
        .build();
var configuration = RendererConfig.builder()
        .sceneShaders(program)
        .temporalRendering(TemporalRenderingOptions.disabled())
        .frameOutputFormat(FrameOutputFormat.LINEAR_HDR_RGBA16F)
        .build();
var bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
bytes.putFloat(4).putFloat(2).putFloat(0.5f).putFloat(1).flip();
var parameters = new ResourceData(bytes);
// Submit through the same renderer used for scene transactions.
var request = RenderFrameRequest.builder(sequence, width, height, camera)
        .minimumSceneRevision(revision)
        .shaderData(List.of(parameters))
        .build();
```

The executable public-API example is `demo.ProgrammableSceneDemo`:

```powershell
.\gradlew.bat :demos:hex-ball:programmableSceneDemo
```

That example writes a constant HDR value through a custom ray-generation shader to demonstrate
data transport and native output. The native acceptance test separately executes all four custom
stages against a traced scene and verifies that changing application data changes the image.

## Output and lifetime

`renderer.readFrame(consumer)` synchronously supplies the newest completed native-format view.
The mapped bytes and every derived buffer are readonly, confined to the callback thread, and
invalidated when the callback returns or throws. Do not reenter the renderer from the callback.
Keep callbacks short: rendering lifecycle operations are serialized while the view is mapped.

Use `FrameSnapshot.copyOf(view)` to retain or share pixels. `pollFrame()` and `awaitFrame(...)`
perform that ownership copy explicitly. Metadata includes the exact sequence, scene revision,
optional generic resource identity, format, origin and row stride. Pixels use little-endian order.
HDR values are not tone-mapped or clamped; integer channels retain their exact bits.

Generic CPU frame output supports the public color formats on single-sample 2D textures with
one mip and one layer. Depth, volumes, arrays and multisampled outputs need an explicit conversion
or resolve into a supported color output before frame consumption. GPU presentation and external
frame ownership remain available without CPU readback.

## Shader contract

The fixed scene descriptor locations are published in `SceneShaderBindings`. Scene buffers and
TLAS are visible in all four stages. Output, history, denoising and reconstruction images are
ray-generation resources. Their numeric type, shape and format must match the selected output
and signal contract; unknown descriptors, push constants and mismatched stages fail before
pipeline creation. History input images must remain readonly.

A replacement ray-generation shader must write every temporal, denoising or reconstruction
signal it enables. Enabling an optional feature cannot synthesize those signals for custom code.
The built-in SER permutation is not reported active when ray generation is replaced.

Retained scenes use one ray-generation group, one miss group and one triangle hit group at
recursion depth one. The explicit command API provides arbitrary groups, procedural intersection,
callable shaders, device-supported recursion, textures, samplers, graphics and compute. Choose
that path when the pipeline topology itself needs to change; see
[generic commands and ray tracing](Generic-Commands-and-Ray-Tracing.md).
