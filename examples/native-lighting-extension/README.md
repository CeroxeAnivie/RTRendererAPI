# Native receiver extension

This is an expert extension example. Ordinary finite lighting starts with `RtxptRenderers.openNativeLighting(plan)`; explicit policy derives from `nativeLightingConfiguration().copyBuilder()`. See [ordinary and expert use](../../docs/ordinary-and-expert.md).

`ReceiverTint.hlsli` replaces receiver lighting evaluation on the GPU. It reads interpolated host baselines, immutable application data and the application's frame clock; uses the backend's local-source, shadow and one-bounce queries; and returns direct gain plus independent indirect contribution. The retained scene, textures and TLAS stay owned by RTXPT.

Compile offline from the repository root, using the pinned runtime DXC:

```powershell
./tools/Build-NativeLightingExtension.ps1 `
  -ExtensionSource examples/native-lighting-extension/ReceiverTint.hlsli `
  -OutputDirectory build/native-lighting-extension-example `
  -Name receiver-tint-v1 -ApplicationSchema rtr.receiver-tint/v1 -MinimumApplicationBytes 16
./tools/Build-NativeLightingExtension.ps1 -OutputDirectory build/native-lighting-extension-example -VerifyOnly
```

The bundle contains `manifest.json`, its content-addressed DXIL, reflection, the extension source and a portable `sdk/`. A source build and an extracted release runtime use the same format. For an independent build, copy this directory and both PowerShell tools out of the checkout, pass the extracted runtime as `-RuntimeDirectory` and the bundle's `sdk` as `-SdkDirectory`.

`NativeLightingExtensionExample.java` runs with matching `renderer-api`, `renderer-core` and `renderer-rtxpt` artifacts from the same build on the classpath. It activates the manifest, publishes two application generations and restores the default algorithm. It prints atomic HDR packet metadata and deliberately leaves the packets unimported. Use the host GPU-composition example to import/read the planes; do not read the shared memory handles as CPU addresses.

```powershell
javac -encoding UTF-8 -cp $releaseClasspath -d build/extension-example examples/native-lighting-extension/NativeLightingExtensionExample.java
java -cp "build/extension-example;$releaseClasspath" NativeLightingExtensionExample build/native-lighting-extension-example/manifest.json
```

The 16-byte, little-endian application schema is RGB indirect tint at offsets 0, 4, 8 and direct strength at offset 12. All four values must be finite; tint is nonnegative and strength is in `[0,1]`. This schema's validation belongs to the application. The manifest declares its minimum byte size; the backend validates that size and publication alignment before activation/submission. Clock-low makes a smooth periodic pulse with an exact period of `2^32` application nanoseconds, so paused input produces a fixed result. At clock zero, direct strength is unchanged.

See [the public ABI](../../docs/rtxpt-native-lighting-extension.md) for resource ownership, function contracts and bundle checks. This example's metadata success alone does not establish signal image correctness, release-package acceptance or the complete E08/E11 matrix.
