# 第三方软件声明

本文件覆盖 RTRendererAPI 0.1.1 运行时依赖。最终许可证文本以对应上游项目和 Maven
制品内携带的文本为准；本清单用于发布审查与自动完整性门禁，不改变任何第三方许可。

## Apache License 2.0

- `it.unimi.dsi:fastutil` — fastutil
- `org.jetbrains.kotlin:kotlin-stdlib`
- `org.jetbrains.kotlin:kotlin-stdlib-common`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk7`
- `org.jetbrains.kotlin:kotlin-stdlib-jdk8`
- `org.jetbrains:annotations`
- shaderc 原生工具链中的 Shaderc 与 SPIR-V Tools

许可证：<https://www.apache.org/licenses/LICENSE-2.0>

## MIT License

- `org.joml:joml` — Java OpenGL Math Library
- shaderc 原生工具链中的 SPIR-V Headers

许可证：<https://opensource.org/license/mit>

## BSD 3-Clause License

- `org.lwjgl:lwjgl`
- `org.lwjgl:lwjgl-vulkan`
- `org.lwjgl:lwjgl-vma`
- `org.lwjgl:lwjgl-shaderc`
- 上述 LWJGL 模块的 Windows native classifiers
- shaderc 原生工具链中的 glslang

许可证：<https://opensource.org/license/bsd-3-clause>

## 维护规则

`verifyThirdPartyNotices` 会解析 `renderer-core` 的完整 runtime dependency graph。任何新增 Maven
模块如果没有以 `group:artifact` 形式登记在本文件中，`check` 将失败。版本仍由依赖锁与 Gradle
dependency verification 固定和校验。
