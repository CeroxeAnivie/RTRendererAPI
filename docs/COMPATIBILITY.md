# 兼容性与版本策略

RTRendererAPI `3.x` 是稳定 API 线。Maven Central 是唯一二进制事实源；`vMAJOR.MINOR.PATCH` Git tag 只标识构建该 Central 制品的不可变源码，不附带第二套 GitHub 二进制资产。

## SemVer

- `1.0.0` 建立稳定公共 API 与 provider SPI 基线。
- `1.0.1` 恢复 `Renderer.extension(...)` 与 `closeAsync()` 的默认实现，并以
  `1.0.0` Central 制品编译的消费者验证运行时链接兼容性。
- `1.0.2` 收紧 `closeAsync()` 默认实现：只有同步关闭后状态已经为 `CLOSED` 才报告完成；延迟 teardown 的 provider 必须显式覆盖异步关闭契约。
- `1.0.3` 以 Maven Central 上一正式版本 JAR 的完整 `javap` 输出作为 exhaustive ABI 差分输入；仓库版本化 `.abi` 仅负责当前版本人工审查与防止静默漂移。
- `1.1.0` 建立了显式的通用资源与命令语义。
- `2.0.0` 是刻意的主版本断代：一个 `Renderer` 直接承载 retained-scene 与通用 command transaction 两种显式模式，移除了重名入口、旧类型与 1.x provider 兼容层。`COMBINED_IMAGE_SAMPLER` 是原生的单一 binding/value，并由 Vulkan 后端映射到对应 descriptor。通用能力只按 `RenderingSemanticCapabilities` 的逐项 executable 证据声明，静态类型存在不等于 GPU 后端已经执行该语义。
- `3.0.0` 继续采用 retained-scene 与通用 command transaction 的显式工作负载区分，并移除按稳定资源 identity 批量退休的危险语义：资源回收必须指定 `ResourceGenerationKey`。它新增通用 buffer-image copy、clear 与 RT command path；在满足 Vulkan 能力门槛时，generic backend 以独立 AS/SBT/trace/evidence 链执行该路径，不能由静态类型或 `RECORDED` 推断画面可见。
- `3.1.2` 是兼容 patch：composition source layout 改为 submit-success-only 的局部事务，且在 frame-slot publication 前提交；提交意外失败会使 host/session terminal failed，撤销可执行 capability，避免后续命令复用不可信 layout ledger。slot 在 queue submit 前完成 admission reservation；同时修复共享 source 引用计数、early managed lease producer-completion 释放、CPU readback publication、首层 blend 约束与 evidence 不变量。既有 `3.1.0` 公共调用路径保持兼容。
- `3.1.3` 是兼容 patch：host 关闭时先等待 composition frame ring 并释放其 generic-resource pin，再销毁 generic command session 的 descriptor、SBT、pipeline、pipeline layout 与其他 device-child 资源，最后销毁 scene 与共享 Vulkan device。该顺序和 RT pipeline owner 修复 `vkDestroyDevice` 前仍存在子对象的生命周期违规，不改变公共 API/SPI，也不引入宿主、游戏或厂商专属契约。
- `3.1.6` 是兼容 patch：generic Vulkan BLAS recording 为每个 build 使用独立的临时 native descriptor scope，避免大量合法 acceleration-structure build 在一个 command transaction 中累积到共享 `MemoryStack` 并耗尽 native stack；同时保留 3.1.5 的同事务 upload/visibility/read 顺序验证和 readiness 门禁。公共 API/SPI、资源语义和 command ordering 不变。
- `3.1.7` 是兼容 patch：generic Vulkan AS build 固化初次 BUILD 的不可变 shape；UPDATE 必须保持 AS 类型、geometry 数量、每个 geometry 的 primitive capacity 或 TLAS instance capacity，重复 BUILD 不得覆盖 resident destination。size query、scratch query、range 与实际 Vulkan record 统一消费同一 resolved description，避免容量漂移触发 validation/device loss。公共 API/SPI、资源语义和 command ordering 不变。
- `3.1.8` 是兼容 patch：generic Vulkan command recording 按 action 作用域释放 native 临时结构，避免大型合法 command transaction 累积 `MemoryStack` 分配并触发 `OutOfMemoryError: Out of stack space`。公共 API/SPI、资源语义和 command ordering 不变。
- `3.1.9` 是兼容 patch：同一 generic command compilation 内的顺序 BLAS/TLAS build 在显式 AS barrier 后复用一块按最大需求分配的 scratch buffer，避免大量 build 各自持有临时 VMA allocation 直到 submission completion 并造成 allocator block 与进程私有内存膨胀。destination AS、TLAS instance upload、GPU completion 和 abort/device-failure 回收契约不变，公共 API/SPI、资源语义和 command ordering 不变。
- patch 版本只能提供兼容修复；minor 版本可以增加兼容能力；删除或改变既有公共二进制声明必须升级 major。
- 每个主版本都提交当前版本的完整 `javap` ABI baseline。`verifyRendererApiAbi` 阻止在同一主版本内未经审查的二进制漂移；跨 major 的移除必须有对应的版本策略记录，不能伪装成兼容变更。

## 稳定等级

| 边界 | `3.x` 兼容承诺 |
| --- | --- |
| `top.ceroxe.rt.renderer.api` 普通调用面 | 同一 major 内保持二进制兼容；除下述 enum 增量规则外保持源码兼容 |
| `top.ceroxe.rt.renderer.spi` provider SPI | 同一 major 内保持二进制兼容；新增能力使用 default 方法或新类型 |
| `top.ceroxe.rt.renderer.api.interop.vulkan` 专家面 | 同一 major 内保持二进制兼容；Vulkan/Win32 资源协议仍要求消费方按文档履约 |
| `renderer-core`、`renderer-nvidia` 实现包 | 非公共 API；除 Maven 运行时解析外不提供类型兼容承诺 |

公开 enum 可以在 minor 版本增加常量。消费方必须为未来值保留 `default` 分支，不能依赖穷举 `switch` 在整个 `3.x` 中维持源码完整性；需要完全封闭的状态域将通过新类型或下一 major 演进。

公共 API 删除必须先标记 `@Deprecated(forRemoval = false)`，至少保留一个完整 minor 周期；实际移除只能进入后续 major。安全修复若无法兼容实现，将升级 major，不在 patch 或 minor 中静默破坏 ABI。

## 支持与证据

兼容目标表示 capability probe 可以接受的系统和硬件下界，不等于生产稳定性证明。每个版本只对 README 支持表或版本验收报告明确列出的操作系统、GPU、驱动和工作负载负责；其他组合必须由消费方自行验收。旧制品保持不可变；安全与缺陷修复策略以 [Security Policy](../SECURITY.md) 为准。
