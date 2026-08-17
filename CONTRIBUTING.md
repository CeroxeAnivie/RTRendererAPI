# 贡献指南

## 提交 Pull Request 前

使用 JDK 25、仓库内 Gradle Wrapper 构建 Java 21 公共 ABI。一次改动应保持在一个明确的
ownership boundary 内；不得通过更新 ABI baseline 掩盖尚未解释的二进制差异。

```powershell
.\gradlew.bat clean check assemble --dependency-verification=strict --no-daemon --console=plain
```

native RTX 验收需要文档规定的 SDK 根目录和可信 Windows RTX runner。来自 fork 的 Pull Request 不得在
self-hosted GPU runner 上执行；该门禁仅由 merge queue 或维护者拥有的分支触发。

## 公共 API 变更

- 遵守 `docs/COMPATIBILITY.md` 中的兼容级别与版本策略。
- 在当前 major 内需要二进制兼容时，优先通过 builder、新类型或 interface default method 增加公共能力。
- 为改动覆盖的空输入、非法输入、并发、取消和清理路径补充 contract test。
- 只有审查过当前 major 的公共二进制差分后，才能更新版本化 ABI baseline。
- 没有类型化 execution evidence 时，不得把实现描述为 active。

## Pull Request 内容

说明根因、行为契约、失败/回滚行为与已执行验证。不得提交生成文件、本地 SDK 路径、凭据、下载工具或构建输出。
安全敏感问题遵循 `SECURITY.md`，不进入公开 review 流程。

## 发布流程

1. 选择 SemVer 版本并同步全部版本事实。
2. 通过确定性检查、当前 major ABI 验证、published-consumer 验证和有界 RTX 验收。
3. 构建并验证已签名的 Central Portal bundle。
4. 通过 Maven Central 发布，并确认三个模块均可解析。
5. 创建对应的 annotated `vMAJOR.MINOR.PATCH` 源码 tag。GitHub Releases 不是本项目的二进制分发渠道。
