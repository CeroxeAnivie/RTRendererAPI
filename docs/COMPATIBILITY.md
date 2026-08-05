# 兼容性与版本策略

RTRendererAPI `0.x` 是 Advanced Beta / Technology Preview，不宣称 Production Stable。Maven Central 是唯一二进制发布渠道；`vMAJOR.MINOR.PATCH` Git tag 只标识构建该 Central 制品的不可变源码，不附带第二套 GitHub 二进制资产。

## SemVer

- `0.Y.Z` 的 patch 升级必须与同一 `0.Y` 系列保持源码和二进制向后兼容。
- `0.Y+1.0` 可以调整仍处于预览期的公共面，但必须在版本文档中明确列出，不能伪装成 patch。
- `1.0.0` 之后遵循标准 SemVer：同一 major 内不得删除或改变既有公共二进制声明。
- 仓库的 `previous_api_version` 指向上一正式版本；`verifyRendererApiBackwardCompatibility` 直接比较上一版本 ABI 与当前构件，补丁版本不能通过更新当前快照绕过兼容检查。

## 稳定等级

| 边界 | `0.x` 兼容承诺 |
| --- | --- |
| `top.ceroxe.rt.renderer.api` 普通调用面 | 同一 minor 的 patch 保持源码与二进制兼容 |
| `top.ceroxe.rt.renderer.spi` provider SPI | 同一 minor 的 patch 保持二进制兼容；新增能力使用 default 方法或新类型 |
| `top.ceroxe.rt.renderer.api.interop.vulkan` 专家面 | 同一 minor 的 patch 保持二进制兼容；Vulkan/Win32 资源协议仍要求消费方按文档履约 |
| `renderer-core`、`renderer-nvidia` 实现包 | 非公共 API；除 Maven 运行时解析外不提供类型兼容承诺 |

公共 API 删除必须先标记 `@Deprecated(forRemoval = false)`，至少保留一个完整 minor 周期；移除只能进入允许 breaking change 的后续 minor 或 major。安全修复若无法兼容实现，将升级到相应 breaking 版本，不在 patch 中静默破坏 ABI。

## 支持与证据

兼容目标表示 capability probe 可以接受的系统和硬件下界，不等于生产稳定性证明。每个版本只对 README 支持表或版本验收报告明确列出的操作系统、GPU、驱动和工作负载负责；其他组合必须由消费方自行验收。旧制品保持不可变，但 `0.x` 只对最新 minor 的最新 patch 提供主动维护。
