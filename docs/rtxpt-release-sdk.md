# Native-lighting release SDK

The extracted `rtxpt-runtime.zip` includes `tools/`, `sdk/`, the extension,
native-lighting OpenGL and host-surface OpenGL examples, and their API documentation. `native-lighting-sdk.json` records each
file's SHA-256 and the public shader ABI identity. The runtime's pinned DXC is at
`ShaderDynamic/Tools/d3d12/x64/dxc.exe`; no checkout or compiler download is needed.

From the extracted runtime root, build the supplied receiver extension:

```powershell
./tools/Build-NativeLightingExtension.ps1 `
  -ExtensionSource examples/native-lighting-extension/ReceiverTint.hlsli `
  -SdkDirectory sdk -RuntimeDirectory . `
  -OutputDirectory receiver-tint `
  -Name receiver-tint-v1 -ApplicationSchema rtr.receiver-tint/v1 -MinimumApplicationBytes 16
./tools/Build-NativeLightingExtension.ps1 -OutputDirectory receiver-tint -VerifyOnly
```

Use the Java artifacts from the same build and the example's `manifest.json` when
activating the evaluator. The extension build is offline; activation consumes
precompiled DXIL. The shader SDK hash must match the native backend. Keep the
compiled bundle's `sdk/`, source inventory and manifest together.

For release preparation in the source repository:

```powershell
./tools/Stage-NativeLightingSdk.ps1
./tools/Stage-NativeLightingSdk.ps1 -VerifyOnly
./tools/Build-RtxptShaderBudgets.ps1 -PlanOnly -RequireReleaseCoverage
./tools/Build-RtxptShaderBudgets.ps1 -VerifyOnly -RequireReleaseCoverage
```

The shader bundle check requires every captured runtime command key, all six
analytic optical budgets, and reference/build/fill branches for host-supplied
primary surfaces, analytic lighting and small-point lighting. Capture a missing
configuration with `RTR_RTXPT_SHADER_MANIFEST_DIR` before building the bundle.
`-PlanOnly` performs no shader compilation or GPU work. Runtime thresholds stay
out of shader identities; legacy exact-light-limit macros are removed from old
captures before deriving keys. Final package tests must disable dynamic shader
compilation to establish that the packaged configurations are self-contained.
