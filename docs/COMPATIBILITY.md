# 兼容性与版本策略

RTRendererAPI `1.x` 是稳定 API 线。Maven Central 是唯一二进制事实源；`vMAJOR.MINOR.PATCH` Git tag 只标识构建该 Central 制品的不可变源码，不附带第二套 GitHub 二进制资产。

## SemVer

- `1.0.0` 建立稳定公共 API 与 provider SPI 基线。
- `1.0.1` 恢复 `RayTracingRenderer.extension(...)` 与 `closeAsync()` 的默认实现，并以
  `1.0.0` Central 制品编译的消费者验证运行时链接兼容性。
- `1.0.2` 收紧 `closeAsync()` 默认实现：只有同步关闭后状态已经为 `CLOSED` 才报告完成；延迟 teardown 的 provider 必须显式覆盖异步关闭契约。
- `1.0.3` 以 Maven Central 上一正式版本 JAR 的完整 `javap` 输出作为 exhaustive ABI 差分输入；仓库版本化 `.abi` 仅负责当前版本人工审查与防止静默漂移。
- `1.1.0` 新增显式 `Renderer` 通用语义入口、版本化资源与命令事务；旧 `RayTracingRenderer` retained-scene 路径保持独立。通用能力仅按 `RenderingSemanticCapabilities` 的逐项 executable 证据声明，静态类型存在不等于 GPU 后端已经执行该语义。
- patch 版本只能提供兼容修复；minor 版本可以增加兼容能力；删除或改变既有公共二进制声明必须升级 major。
- 仓库的 `previous_api_version` 指向上一正式版本；`verifyRendererApiBackwardCompatibility` 从 Maven Central 解析该版本 JAR 并生成完整 ABI，再与当前构件比较。补丁版本不能通过更新仓库快照绕过兼容检查。

## 稳定等级

| 边界 | `1.x` 兼容承诺 |
| --- | --- |
| `top.ceroxe.rt.renderer.api` 普通调用面 | 同一 major 内保持二进制兼容；除下述 enum 增量规则外保持源码兼容 |
| `top.ceroxe.rt.renderer.spi` provider SPI | 同一 major 内保持二进制兼容；新增能力使用 default 方法或新类型 |
| `top.ceroxe.rt.renderer.api.interop.vulkan` 专家面 | 同一 major 内保持二进制兼容；Vulkan/Win32 资源协议仍要求消费方按文档履约 |
| `renderer-core`、`renderer-nvidia` 实现包 | 非公共 API；除 Maven 运行时解析外不提供类型兼容承诺 |

公开 enum 可以在 minor 版本增加常量。消费方必须为未来值保留 `default` 分支，不能依赖穷举 `switch` 在整个 `1.x` 中维持源码完整性；需要完全封闭的状态域将通过新类型或下一 major 演进。

公共 API 删除必须先标记 `@Deprecated(forRemoval = false)`，至少保留一个完整 minor 周期；实际移除只能进入后续 major。安全修复若无法兼容实现，将升级 major，不在 patch 或 minor 中静默破坏 ABI。

## 支持与证据

兼容目标表示 capability probe 可以接受的系统和硬件下界，不等于生产稳定性证明。每个版本只对 README 支持表或版本验收报告明确列出的操作系统、GPU、驱动和工作负载负责；其他组合必须由消费方自行验收。旧制品保持不可变；安全与缺陷修复策略以 [Security Policy](../SECURITY.md) 为准。
