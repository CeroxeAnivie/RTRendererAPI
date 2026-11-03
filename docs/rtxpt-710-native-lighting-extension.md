# Native lighting extension ABI 1

The ordinary entry point is `RtxptRenderers.openNativeLighting(plan)`. Explicit policy, style/data publication and offline DXIL replacement are separate expert controls; derive `RtxptRenderers.nativeLightingConfiguration().copyBuilder()` when a full policy is needed. The offline extension described here executes only in the independent native mode, not host-lit augmentation. See [ordinary and expert use](ordinary-and-expert.md).

The independent native-lighting plan permits one offline receiver implementation:

```hlsl
void RtrEvaluateReceiver(RtrReceiver receiver, out float3 gain, out float3 indirect);
```

It executes after the backend's low-resolution primary receiver query, inside the same `cs_6_6` compute dispatch. `main` and its `8×8×1` threads remain backend-owned. The extension may replace source combination, receiver correction and finite-bounce evaluation; it is not restricted to a final color filter. The default complete RTXPT path and the independent native-lighting path are separate execution modes.

## Compile and activation

`tools/Build-NativeLightingExtension.ps1` snapshots the public SDK and literal extension includes, compiles with the pinned runtime DXC under the 10 GiB process-tree cap, validates the DXIL container/resource reflection and publishes `manifest.json` last. A failed compile leaves the previous manifest/binary intact. Binaries are content-addressed; an old binary is never overwritten by a new identity. The tool does not access a network or create a renderer.

`NativeLightingControl.activateLightingExtension(Path)` validates the bundle and prepares a replacement compute pipeline before switching it at an owner-thread boundary. Missing/incompatible artifacts are rejected while the preceding configuration stays usable. `resetLightingExtension()` restores the built-in evaluator. These operations consume precompiled DXIL; application-data or coefficient changes do not compile HLSL or rebuild the retained scene. New pipeline preparation is a configuration cost, not normal frame work.

The portable `sdk/` exported with each bundle contains the backend entry point, style helpers and the pinned Donut type/material/packing headers. Compile an external extension against this SDK and its matching runtime, rather than copying unpublished C++ layouts. `-SdkDirectory` selects an exported SDK; `-RuntimeDirectory` selects the extracted release runtime's DXC. Includes inside the extension source tree and SDK are supported; macro-generated external includes are rejected so all source dependencies have recorded hashes.

## Receiver and helper functions

|Field|Meaning|
|---|---|
|`position`|World-space primary/bounce hit position, in the submitted world coordinates|
|`normal`, `geometricNormal`|Normalized world-space shading/geometric normals, oriented against the incident ray; material normal maps are applied|
|`albedo`|Nonnegative linear material color after texture sampling; receiver albedo is applied once to indirect contribution|
|`uv`, `barycentrics`|Interpolated UV and triangle barycentrics `(b1,b2)`; `b0=1-b1-b2`|
|`baseline`|Interpolated host coordinates after instance scale/bias and 16×16 lookup, local/sky values in `[0,1]`|
|`opacity`, `diffuseWeight`|Sampled surface opacity and `(1-metalness)*(1-transmission)`|
|`distance`|Distance along the normalized query ray, in world units|
|`visibility`, `fog`|Host visibility/fog weights in `[0,1]`; default influence is `visibility*(1-fog)`|
|`instance`, `geometry`, `primitive`|Current native dense indices; geometry is local to the instance, not a stable public resource ID|

|Function|Contract|
|---|---|
|`RtrLocal(receiver,out local,out dynamic)`|Query all supported compact/analytic local sources at this receiver using the retained spatial index and declared composition rules. The second result is the dynamic subset. Shadow visibility precedes source selection.|
|`RtrShadow(receiver,direction,distance)`|Return RGB visibility using native geometry/material opacity. Direction is normalized and distance positive; directional queries use a large finite limit. Uses role 2.|
|`RtrTrace(origin,direction,distance,role,out hit)`|Native finite ray query with real cutout/material handling. Returns false on miss. Roles are receiver=1, shadow=2, bounce=4. Origin/distance must be finite; direction normalized.|
|`RtrIncident(receiver)`|Built-in incident local/directional lighting at a secondary receiver. Does not multiply that receiver's albedo.|
|`RtrOneBounce(receiver)`|Deterministic finite hemisphere sampling with the plan's sample count/distance/strength, actual bounce albedo and local lighting. Returns a final receiver-albedo-weighted indirect contribution and applies visibility/fog once.|
|`RtrStyleGain(...)`|Versioned public baseline correction formula shared with `NativeLightingStyle`. Its caller supplies source contributions and directional visibility.|
|`RtrAccumulate`, `RtrGroupTotal`|Public helpers for declared source group sum/selected max/channel max semantics.|
|`RtrCount(index)`|Optional diagnostic counters; no atomic work when `g.publication.w==0`. Required output/state must not depend on counters being enabled.|

The evaluator writes finite nonnegative direct gain and indirect contribution. Gain `1` and indirect `0` are neutral. The outer entry point performs requested-mask checks and stores the signals, so an extension must respect `g.grid.z` before doing optional work. Indirect output is linear contribution, already multiplied by receiver albedo when using `RtrOneBounce`; a compositor must not apply that albedo again. Alpha/validity remain controlled by the backend receiver, and a primary miss stays neutral. Helpers may be called multiple times, with proportional ray cost; custom bounce loops must have finite bounds. Recursive receiver evaluation, new acceleration structures, extra binding declarations and writes outside the declared outputs are outside this ABI.

## Resource bindings

All resources below belong to RTXPT. Extensions borrow them for one dispatch and must not cache handles or native dense indices across revisions. Application data is the single read-only `t9` publication. All registers use space 0 unless stated.

|Register|Resource/layout|
|---|---|
|`b0`|320-byte `RtrNativeConstants`, offsets below|
|`t0`|Resident scene TLAS, used by backend query helpers|
|`t1`|`InstanceData`, 112-byte pinned Donut instance records|
|`t2`|`GeometryData`, 64-byte pinned Donut geometry records|
|`t3`|`MaterialConstants`, 208-byte pinned Donut material records|
|`t4`|`RtrSource`, 96-byte compact source records plus backend header|
|`t5`|`RtrNode`, 64-byte compact-source spatial-index records|
|`t6`|`RtrInstance`, 32 bytes: `rolesVisibility` and baseline scale/bias|
|`t7`|`float2` baseline coordinates, expanded to triangle vertices|
|`t8`|`uint2` geometry baseline offset/triangle count|
|`t9`|Read-only application `ByteAddressBuffer`; byte count is `g.publication.x`|
|`t10`|256 `float2` baseline lookup entries, x fastest, bilinear interpolation|
|`t11`|64-byte `RtrAnalytic` resident analytic-light/tree records; use backend query functions for traversal|
|`t0, space1`|Unbounded bindless read-only raw geometry buffers|
|`t0, space2`|Unbounded bindless material `Texture2D<float4>` array|
|`s0`|Backend material sampler|
|`u0`|Requested direct gain, `RGBA16_FLOAT`|
|`u1`|Requested indirect contribution, `RGBA16_FLOAT`|
|`u2`|Requested receiver normal/distance, `RGBA32_FLOAT`|
|`u3`|Requested receiver identity/validity, `RGBA32_UINT`|
|`u4`|Optional six `uint` work counters: pixels, receivers, shadows, bounces, spatial nodes, candidates|

An unrequested output may be a one-pixel dummy resource: never write/sample its full-grid coordinates. The backend outer entry point writes only requested outputs. Extensions should return values through `gain`/`indirect` and use the query helpers, leaving resource allocation, states, barriers and synchronization to the backend.

## Constants and application data

All offsets are bytes. Matrices and compile packing are row-major. `uint` fields contain integer bits, not numeric float encodings.

|Offset|Field|
|---|---|
|0|`inverseClip`, 64-byte inverse projection from clip coordinates to view coordinates|
|64|`cameraToWorld`, 64-byte camera/world transform|
|128|`cameraNear`: camera world xyz and near clip-space depth|
|144|`farJitter`: far clip depth, jitter NDC xy; w holds analytic count bits|
|160|`grid`: width, height, signal mask, GI sample count|
|176|`sun`: normalized direction toward source xyz, daylight weight|
|192|`sunColor`: RGB, w holds analytic root bits|
|208|`moon`: normalized direction toward source xyz, moonlight weight|
|224|`gi`: finite distance, intensity, ray epsilon; w holds analytic tree-base bits|
|240|`style.correction`: local dim/boost, daylight/moonlight dim|
|256|`style.moonAndMode`: moon RGB, addition-mode numeric value|
|272|`style.limits`: minimum/maximum gain, maximum GI, reserved|
|288|`publication`: app bytes, generation low/high, diagnostic-count enable|
|304|`clock`: application time nanos low/high, delta nanos low/high|

Read analytic metadata in reserved float slots with `asuint`, not casts. The clock uses the submitted `FrameClock` bit pattern; time may have a negative application-defined origin and delta is nonnegative. Pause/seek is host policy. Reusing the same request preserves the same clock across admission retries.

`publishLightingAttributes(NativeLightingPublication)` copies the remaining byte range of its application `ByteBuffer` into an immutable snapshot. Generations are positive and strictly increase; payload length is a multiple of four and at most 16 MiB. A null payload in an attribute delta retains the previous application publication. Use `ByteOrder.LITTLE_ENDIAN` when writing scalar values. Bounds-check every `Load`: the underlying dummy allocation can be larger than the declared valid byte range. The manifest's application minimum is validated before using the extension; the application owns semantic field validation within its declared schema. Data-only publication reuses the compute pipeline and retained geometry; GPU upload/retirement follows frame ownership.

## Manifest identity and validation

Format identity is `rtr-native-lighting-extension`, formatVersion/abiVersion `1`. Required fields are `name`, `entryPoint`, `shaderModel`, `threadGroup`, `constantsBytes`, `signalsMask`, `application`, `dxil`, `shaderAbiSha256`, `compilerSha256`, `abiSources`, `extensionSources` and the reflected `resources` table. The application object declares minimum bytes, four-byte alignment, 16 MiB maximum and a schema identity. `signalsMask` declares the supported plan outputs; it is not a promise that an absent output was produced.

`dxil.file` is relative to the manifest; `bytes` and `sha256` identify its complete DXIL container. Source entries carry portable logical paths plus byte hashes. For `shaderAbiSha256`, sort ABI entries by ordinal logical path, concatenate UTF-8 without BOM as `path=sha256\n` for each entry, then SHA-256 the result. Source line endings are included in each file's byte hash. The initial public SDK consists of `backend/RtrNativeLighting.hlsl`, `backend/RtrNativeLightingStyle.hlsli` and `donut/shaders/{bindless.h,material_cb.h,packing.hlsli}`; recursively added literal ABI includes join the identity.

The loader must reject a missing/corrupt DXIL, changed ABI, unsupported stage/binding, incompatible requested signal set or insufficient application payload before activation. Hashes provide content identity and corruption detection; they are not a signature or a claim of independent shader correctness. Review and validate extension code as application GPU code. Runtime activation never compiles HLSL. Resize and mode switches must preserve the existing frame-lease/fence contract; switching evaluator does not permit reusing an in-flight output slot.

The standalone example and verification commands are in [examples/native-lighting-extension](../examples/native-lighting-extension/README.md). Offline compilation proves artifact and ABI preparation; pixel-level execution, failed activation recovery and final published-package operation require the separate native acceptance tests.
