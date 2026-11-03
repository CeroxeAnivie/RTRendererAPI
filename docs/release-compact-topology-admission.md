# RTRendererAPI 5.2.1

5.2.1 是针对 5.2 retained-scene 和 medium transport 契约的 GitHub-only maintenance release。未上传到 Maven Central；Central 仍为 5.1.0。三个 runtime modules 必须从本地 repository 或 GitHub source build 以相同版本使用。

公共 API 仍与 5.x ABI 兼容。本版本保留完整的 32-level medium path、均匀 absorption、emission 和 single scattering、Snell refraction、Fresnel、total internal reflection、nested boundaries、frame-local boundaries 以及运行时 quality controls。`MediumTransportOptions.balanced()` 仍为默认值：12 个 interface events、1 个 optical sample、8 个 scattering intervals 和 512-unit integration budget。Distance budget 限制 optical integration，不会缩短用于查找 receiver 或 blocker 的真实几何 ray。

Generic CPU topology admission path 现在使用 compact edge pairing 和 vertex-star checks。它会 weld 完全重合的 seam positions，拒绝开放或 winding 不一致的 surfaces，拒绝 non-manifold edges 与 pinched vertex links，并缓存不可变结果。记录的 49,152-triangle fixture 上，admission allocation 从 46,823,584 bytes 降至 8,943,632 bytes。

GPUScene medium paths 避免 dynamic local-array indexing，并复用共享的 lighting/transport implementation。Custom retained-scene pipelines 会在创建前检查编译后的 SPIR-V；仅当所有 stages 均能证明使用 miss record zero 且没有 nested trace 时，才会省略 internal shadow-service miss。Dynamic miss indices、nested traces 和实际 medium-service calls 仍保留完整的 depth-two pipeline 和 SBT layout。这是通用 provider 优化，不需要 consumer compatibility layer。

本版本的验证包括 API checks、topology checks、SPIR-V compilation、全部 34 个 pinned shader variants 以及 pipeline-requirement contract。Native GPU 和交互式 FG/MFG 验收仍属于单独的硬件检查；成功创建 pipeline 或产出首帧不构成视觉验收。Minecraft 视觉正确性独立跟踪，不是此 API artifact 的 release gate。

此 release 通过本地 `5.2.1` 使用。不包含 Maven Central publication task。
