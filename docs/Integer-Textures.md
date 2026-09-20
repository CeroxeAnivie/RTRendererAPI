# Integer textures in 4.2.0

`TextureFormat` supports signed and unsigned integer storage for R, RG, and RGBA at 8, 16,
and 32 bits per component. Existing format ordinals remain unchanged. `numericClass()` identifies
the shader-visible numeric domain; `bytesPerTexel()` returns the exact packed storage size.
Normalized and floating-point formats use floating-point shader values. Integer formats retain
integer values, including uint32 values above the exact range of float32.

## Uploads and shaders

Supply native-order integer texels through `WriteTextureCommand`. Row pitches, image pitches,
offsets, mip levels, and array layers remain explicit and validated. For 3D uploads the z origin
selects a depth slice; it does not change the Vulkan base array layer. Version 4.2.0 corrects that
distinction for nonzero-z writes.

Use `isampler*` with SINT storage and `usampler*` with UINT storage. The Vulkan admission path
checks SPIR-V image scalar signedness and width, dimension, array shape, and multisampling
against every bound texture view. This applies to graphics, compute, and explicit RT programs.
A numeric or dimensional mismatch rejects the transaction before native submission.

Combined integer image/sampler bindings require NEAREST minification, magnification, and mip
selection, anisotropy 1, and no comparison operation. Comparison sampling requires a depth view.
Separate image/sampler descriptors remain subject to Vulkan's instruction-level sampling rules;
the API does not infer an association between independent descriptor slots.

## Rendering and clears

Color clears have three distinct variants:

| Storage class | Clear value | Fragment output |
| --- | --- | --- |
| Normalized, sRGB, floating point | `ClearValue.Color` | float or vec |
| SINT | `ClearValue.SignedColor` | int or ivec |
| UINT | `ClearValue.UnsignedColor` | uint or uvec |

`UnsignedColor` uses Java `int` components as exact uint32 bit patterns. For example, `-1`
represents 4294967295, not a negative clear. Clear values must match the attachment's numeric
class. Fragment output numeric classes must match their target attachments. Integer targets
require blending disabled; supported integer logic operations retain their bitwise meaning.
Multisample integer color attachments resolve from sample zero; normalized and floating-point
color attachments use average resolve. Device format, usage, and sample-count support still apply.

## Output and compatibility

Integer attachments can produce fence-backed GPU output evidence and can subsequently be read
by shaders. `CpuFrame` continues to represent RGBA8 pixels only. When its selected GPU output is
an integer texture, the command can complete with `OUTPUT_PRODUCED`, while querying a CPU frame
for that output throws `UnsupportedOperationException`. There is no implicit normalization,
reinterpretation, or color conversion. Use an explicitly authored conversion pass into an RGBA8
target when display pixels are needed. The window composition source contract is unchanged.

Existing method descriptors remain available. The `ClearValue` sealed hierarchy and
`TextureFormat` enum gain variants: exhaustive switches must handle them when recompiling, and
new values must not be sent to a 4.1.0 provider. Rollback requires removing integer formats and
typed clears and avoiding nonzero-z 3D writes, or using a fully witnessed whole-volume upload.

The native regression task is `./gradlew :renderer-core:vulkanGenericIntegerTextureNativeSelfTest`.
It compares integer values in shaders before display conversion, covers all 18 formats, tests
clear and fragment writes, and preserves the untouched slice during a nonzero-z 3D upload.

## 4.2.0 release evidence

The Windows Java 21 release gate completed successfully on an NVIDIA GeForce RTX 5080 Laptop GPU:

```text
gradlew.bat --no-daemon --console=plain strictAcceptanceTest assemble :renderer-core:vulkanGenericIntegerTextureNativeSelfTest :renderer-core:vulkanGenericCommandNativeSelfTest :renderer-core:vulkanGenericGeometryBarrierNativeSelfTest :renderer-core:vulkanGenericRayTracingNativeSelfTest
gradlew.bat --no-daemon --console=plain verifyCentralPortalBundle
```

The integer regression reported 18 formats, 54 exact frames, and 18 nonzero-z upload cases.
The geometry barrier regression reported 24 exact frames. Generic command execution, procedural
geometry, and ray-tracing residency checks passed. Native GPU gates now share a cross-module
Gradle build service so independent test processes do not contend for the adapter; compilation
and CPU-only work retain parallelism. Geometry uniform barriers use `UNIFORM_READ`, and the
API regression rejects substituting `SHADER_READ` for uniform-buffer usage.

The DLSS FG 2x gate observed 72 application presents, 84 actual presents, and 12 generated
frames, with 12 consecutive generated samples. The separate MFG 3x configuration gate reported
zero generated frames; that result establishes configuration acceptance, not sustained 3x
presentation. This release does not claim otherwise.

All 15 detached publication signatures were verified locally. Maven Central publication was
confirmed on 2026-09-20 by downloading the POM, Gradle module metadata, and JAR for all three
modules and matching their SHA-256 hashes to the signed upload bundle. These results cover
the stated device and driver environment; additional devices require their own native gates.
