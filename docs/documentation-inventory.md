# 文档清单

本页按学习用途索引当前指南、API 参考、实测证据与历史记录，不用固定文件数量代表文档完整性。排除 `build/`、`generated/`、`.gradle/`、`out/` 等生成目录。初次使用按“Demo → Java 教程 → 后端／组合 → 专家接口”阅读；签名和范围以当前源码、API 参考及版本合同为准。

早期 29 份报告合并至[历史质量验证与性能报告](quality/verification-report.md)，之后的 RTXPT、组合和内存报告独立保留。每个记录保留测试日期、制品、场景、失败及未完成边界；历史实验的 JSON／CSV／JSONL 等原始数据和一次性脚本已清理，可从 Git 历史恢复；保留的汇总报告与截图不搬入教程。旧 CUDA／LuxCore 结果不代表当前 RTXPT，已实现接口也不等于所有组合都已实机验证。

## 当前学习与查阅入口

### README 与导航

| 文档 | 摘要 |
| --- | --- |
| [README.md](../README.md) | 项目定位、统一入口、能力范围、使用方法与候选状态。 |
| [Home.md](Home.md) | 阅读路线、模块边界、使用层级和本地验证入口。 |
| [_Sidebar.md](_Sidebar.md) | 开发文档导航索引。 |

### 指南

| 文档 | 摘要 |
| --- | --- |
| [Java.md](Java.md) | Java 场景、帧提交、介质、输出、诊断和生命周期指南。 |
| [performance-budgets.md](performance-budgets.md) | LOW／MEDIUM／HIGH、全部公开预算、实际生效范围与六档 shader 发行。 |
| [backend-routing.md](backend-routing.md) | 全 RTXPT、显式 Vulkan、按介质整场景选择及部署依赖。 |
| [d3d12-vulkan-composition.md](d3d12-vulkan-composition.md) | RTXPT 最终呈现、旧 Vulkan 独立工作／UI、同窗完整 3D 切换、同步与限制。 |
| [Generic-Commands-and-Ray-Tracing.md](Generic-Commands-and-Ray-Tracing.md) | 通用资源、图形命令和硬件 RT 提交流程。 |
| [Vulkan-Interop.md](Vulkan-Interop.md) | GPU frame lease、外部内存、同步及所有权协议。 |
| [runtime-distribution.md](runtime-distribution.md) | Windows 候选包、Maven 依赖和启动方式。 |
| [release-local-scene-preparation.md](release-local-scene-preparation.md) | 当前局部资源准备修复、稳定命名和最终验收入口。 |
| [quality/local-scene-preparation.md](quality/local-scene-preparation.md) | 256／4096 资源、同夹具旧包对比及实际 GPU 局部更新证据。 |
| [Programmable-Scenes.md](Programmable-Scenes.md) | retained-scene GLSL/SPIR-V 替换、资源绑定与 ABI。 |
| [Procedural-Geometry.md](Procedural-Geometry.md) | AABB procedural BLAS 构建、更新、交点 shader 和退休规则。 |
| [Integer-Textures.md](Integer-Textures.md) | 整数纹理格式、上传、采样、清除和原始输出。 |
| [medium-gallery.md](medium-gallery.md) | 当前 RTXPT 六个光学场景、HUD 切换、默认能力及已删除功能。 |
| [Evidence-Retention.md](Evidence-Retention.md) | execution evidence 保留预算、lease、查询和背压语义。 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | major-7 公共契约、兼容策略和迁移边界。 |
| [tools/README.md](../tools/README.md) | 三个 PowerShell 构建工具、SDK 配置、路径检查和有界构建方式。 |

### API 参考

| 文档 | 摘要 |
| --- | --- |
| [Java-API-Reference.md](Java-API-Reference.md) | 面向消费方的稳定公共 Java 类型与调用参考。 |
| [Generic-Rendering-Semantics.md](Generic-Rendering-Semantics.md) | retained / command workload、准入、同步和完成 evidence 语义。 |

### 版本说明

| 文档 | 摘要 |
| --- | --- |
| [release-generic-renderer-contract.md](release-generic-renderer-contract.md) | 统一 Renderer、Java 25、可编程阶段和原生格式 frame 的版本说明。 |
| [release-application-clock-and-resource-snapshots.md](release-application-clock-and-resource-snapshots.md) | 应用时间轴与不可变局部资源快照。 |
| [release-arena-diagnostics-snapshot-cache.md](release-arena-diagnostics-snapshot-cache.md) | GPU arena 统计快照缓存改进。 |
| [release-closed-boundary-participating-media.md](release-closed-boundary-participating-media.md) | 初始封闭介质、吸收/发光/散射/折射和嵌套体积契约。 |
| [release-compact-topology-admission.md](release-compact-topology-admission.md) | 介质维护修复和闭合网格拓扑 admission 优化。 |
| [release-opaque-visibility-fast-path.md](release-opaque-visibility-fast-path.md) | 恢复无介质 opaque shadow 快速路径的性能修复。 |
| [release-typed-retained-rendering-contract.md](release-typed-retained-rendering-contract.md) | Java API、provider SPI 和 GPU ABI 新 major 契约。 |
| [release-application-shader-source-cache.md](release-application-shader-source-cache.md) | 内容寻址 GLSL/SPIR-V 缓存和冷启动编译改进。 |
| [release-precompiled-application-shaders.md](release-precompiled-application-shaders.md) | 预编译 SPIR-V 场景阶段入口及完整性验证。 |
| [release-driver-pipeline-cache.md](release-driver-pipeline-cache.md) | Vulkan pipeline cache 身份与持久化修复。 |
| [release-managed-path-tracing-backend.md](release-managed-path-tracing-backend.md) | 当前两字段 Medium、RTXPT 托管范围、删除参数、原生运行时及迁移要求。 |
| [release-transparent-background-composition.md](release-transparent-background-composition.md) | 主射线透明输出、宿主 GPU 背景、线性预乘合成与范围。 |
| [release-incremental-lighting-and-frame-diagnostics.md](release-incremental-lighting-and-frame-diagnostics.md) | RTXPT 资源增量更新、空间灯树和窗口等待修复。 |
| [release-demand-resources-and-lighting-updates.md](release-demand-resources-and-lighting-updates.md) | RTXPT 按需内存分配、通用环境／灯光增量更新与兼容性。 |

### 实测与质量记录

| 文档 | 摘要 |
| --- | --- |
| [原生与宿主光照合同](release-independent-native-and-host-lighting.md) | 独立原生光照、两种宿主职责、普通／专家入口及最终验收范围。 |
| [7.1.0 验收矩阵](rtxpt-acceptance.md) | 三份需求与 CPU／GPU／打包测试的对应关系和实测边界。 |
| [7.1.0 性能量化](quality/rtxpt-performance.md) | 相同算法精确剪枝前后、逐位图像对照、数据传输与 compact 输出收益。 |
| [原生光照发布 SDK](rtxpt-release-sdk.md) | 离仓 GPU 扩展工具、ABI 校验和三个接入示例。 |
| [7.0.4 透明背景验收](quality/rtxpt-transparent.md) | 主射线 coverage、RR 边缘、物理表面及两条 GPU 合成路径与增量成本。 |
| [7.0.3 资源增量／灯树验收](quality/rtxpt-retained-incremental-updates.md) | 通用 retained-scene 更新、十万灯采样、窗口回归与同机版本速度对比。 |
| [质量验证与性能报告](quality/verification-report.md) | 合并 29 份历史记录，包含验证事实、失败、未完成边界和限定条件。 |
| [RTXPT 托管接入](quality/rtxpt-managed-medium-migration.md) | 新后端场景、打包运行时与接入验证；按记录的制品判断。 |
| [高级能力检查](quality/rtxpt-advanced-audit-20261101.md) | RR、采样、重用及高级能力调用链／运行证据。 |
| [新旧弹球性能](quality/hexball-rtxpt-performance-20261101.md) | 相应材质、配置、诊断状态下的链路与耗时；不外推到新玻璃质量档。 |
| [GPU 组合与原始帧复用](quality/rtxpt-raw-reuse-composition-20261101.md) | 组合链和跨 API 工作的验证边界。 |
| [内存与 FPS 联合验收](quality/rtxpt-memory-fps-20261102.md) | 进程／Java／GPU 内存、会话重开与性能对照。 |
| [7.0.1 内存修复验收](quality/rtxpt-demand-memory.md) | 闲置资源根因、提交／RAM／显存分别统计、固定场景画面对照与制品身份。 |
| [7.0.1 通用灯光增量更新](quality/rtxpt-incremental-lighting.md) | 两条光照输入、全部灯光状态、实际执行计数、字段效果与生命周期验收。 |
| [API 发布前检查](quality/api-release-review-20261101.md) | 公开入口、后端差异和检查时点的结论。 |
| [后端依赖大小](quality/backend-dependency-size-20261101.md) | 指定制品的依赖与打包尺寸。 |
| [介质优化规范](quality/medium-performance-optimization.md) | 排名、测量、优化与证据保留约定。 |

### 演示文档

| 文档 | 摘要 |
| --- | --- |
| [三个 Demo](../demos/hex-ball/README.md) | 原 Vulkan 弹球、RTXPT 玻璃弹球、介质六场景、参数／时限、工作台和 FG 证据。 |

### 许可证与维护

| 文档 | 摘要 |
| --- | --- |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献规范、ABI 变更、测试、PR 和发布流程。 |
| [SECURITY.md](../SECURITY.md) | 安全漏洞私密报告、支持版本及依赖风险处置。 |
| [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | 发布运行依赖许可归属和 notices 完整性门禁。 |

### 本清单

| 文档 | 摘要 |
| --- | --- |
| [documentation-inventory.md](documentation-inventory.md) | 当前文档全集、非 Markdown 资源与历史质量章节映射。 |

## 原 29 份质量记录的章节映射

以下条目映射原来的独立记录到新报告 anchor，不再代表独立 Markdown 文件。

| 历史记录主题 | 新报告章节 | 摘要 |
| --- | --- | --- |
| 4.0.2 证据保留候选验证 | [4.0.2](quality/verification-report.md#4-0-2-retention-validation) | 记录 retention 合同及未完成的最终 native smoke/consumer 验收。 |
| 4.1.0 程序化几何验证 | [4.1.0](quality/verification-report.md#4-1-0-procedural-validation) | AABB BLAS BUILD/UPDATE、ABI、硬件和发布证据。 |
| 4.2.1 缓冲可见性验证 | [4.2.1](quality/verification-report.md#4-2-1-buffer-visibility-validation) | Vulkan buffer/AS 可见性修复和帧生成验收边界。 |
| 6.0.4 性能与回归 | [6.0.4 性能](quality/verification-report.md#6-0-4-performance) | 同 hex-ball 负载的版本吞吐、启动及功能回归。 |
| 7.0.0 CUDA 相机吸收准备 | [相机吸收准备](quality/verification-report.md#7-0-0-cuda-camera-segment-foundation) | 相机 ABI、tile 与共享输出测试，不等同最终 framebuffer 接入。 |
| 7.0.0 CUDA 运行时基础 | [运行时基础](quality/verification-report.md#7-0-0-cuda-medium-runtime-foundation) | CUDA/NVRTC runtime、原生资源预算和测试。 |
| 7.0.0 HG 与边界栈组件 | [输运语义组件](quality/verification-report.md#7-0-0-cuda-medium-transport-semantics) | HG 数值合同及 32-entry 边界栈适配验证。 |
| 7.0.0 共享射线服务 | [射线服务](quality/verification-report.md#7-0-0-cuda-shared-ray-service) | Ray ABI、复用 slot 和跨 API 所有权交接。 |
| 7.0.0 介质功能增量 | [介质增量](quality/verification-report.md#7-0-0-integrated-medium-20261029) | PBR 阴影、异质多散射、rough glass 和光学 buffer 切片。 |
| 7.0.0 迁移完整交接 | [迁移交接](quality/verification-report.md#7-0-0-native-medium-full-handoff-20261028) | 早期路线、语义冲突、失败实验和剩余工作。 |
| 7.0.0 生产接入交接 | [生产接入交接](quality/verification-report.md#7-0-0-native-medium-production-handoff-20261028) | 原生生产路径阶段状态、验证和未完成项。 |
| 7.0.0 介质设计与边界 | [设计与边界](quality/verification-report.md#7-0-0-medium-design) | 公共介质契约、输运近似、预算、编译和验收设计。 |
| 7.0.0 执行路径审计 | [执行审计](quality/verification-report.md#7-0-0-medium-execution-audit) | 源码/制品身份、留存性能数据及 shader 变体缺口。 |
| 7.0.0 执行路径计划 | [执行计划](quality/verification-report.md#7-0-0-medium-execution-plan) | primary-hit queue 边界、性能门槛和正确性要求。 |
| 7.0.0 相机生产与性能观测 | [相机生产](quality/verification-report.md#7-0-0-native-camera-production-and-performance-20261028) | public HDR 纯吸收接入和外部 GPU 负载限制。 |
| 7.0.0 相机性能收尾 | [性能优化收尾](quality/verification-report.md#7-0-0-native-medium-performance-final-20261029) | 全帧 dispatch、提交合并、常量和 snapshot 池优化。 |
| 7.0.0 接手性能记录 | [接手性能记录](quality/verification-report.md#7-0-0-native-medium-resume-performance-20261028) | 接手验证、正确性切片和受干扰的性能数据。 |
| 7.0.0 终端辐射优化 | [终端辐射](quality/verification-report.md#7-0-0-native-terminal-radiance-20261029) | terminal radiance 融合、packet缩减和ABBA测量。 |
| 7.0.0 冻结制品验收 | [候选制品](quality/verification-report.md#7-0-0-package-validation-20261029) | 候选通用API/介质矩阵、性能范围和功能缺口。 |
| 7.0.0 性能与回归 | [7.0.0 性能](quality/verification-report.md#7-0-0-performance) | 原始性能回退、被动程序特化修复与部分GPU验证。 |
| primary queue 原生修复 | [queue 修复](quality/verification-report.md#7-0-0-primary-queue-native-repair) | deferred pipeline 崩溃、serial恢复及 queued 阻断。 |
| primary queue 重启恢复 | [queue 恢复](quality/verification-report.md#7-0-0-primary-queue-resume) | 七个逐位匹配帧与 general32 host 内存失败。 |
| primary-hit queue 实验切片 | [queue 实验](quality/verification-report.md#7-0-0-primary-queue-slice) | 默认关闭的实验、资源布局、shader gate 和性能缺口。 |
| general32 数值控制 | [数值控制](quality/verification-report.md#7-0-0-primary-queue-structured-control) | O0 serial 和 retained queue 的单 half-ULP 关系。 |
| 原生介质复杂场景验证 | [复杂场景](quality/verification-report.md#native-medium-complex-scene-20261028) | 闭合/嵌套/重叠边界、密度 HDR 和未覆盖输运能力。 |
| 原生模块提取路线 | [提取路线](quality/verification-report.md#native-medium-module-extraction) | 模块边界、许可证语义及阶段门槛。 |
| 原生介质最小测量 | [最小测量](quality/verification-report.md#native-medium-minimal-probe-20261028) | 段函数驻留成本、CPU搬运与共享内存往返。 |
| Vulkan 射线续接失败探针 | [续接失败](quality/verification-report.md#native-ray-continuation-failure-20261028) | CUDA/Vulkan epoch 不一致、失败复现及未完成清理复验。 |
| 真实介质接入边界核查 | [接入边界](quality/verification-report.md#native-medium-integration-boundary-20261028) | 上游调用依赖及完整介质闭环的剩余工作。 |

## 非 Markdown 资源、许可与机器证据

许可归属和源码原文保持原样；机器数据和日志不搬入报告正文。当前 RTXPT 许可证随实际 runtime 保留。

| 路径 | 用途 |
| --- | --- |
| [根 LICENSE](../LICENSE) | RTRendererAPI Apache License 2.0 完整文本。 |
| [RTXPT NOTICE](../renderer-rtxpt/NOTICE-RTXPT.txt) | 当前上游、适配边界与 native runtime 许可位置；第三方许可并非统一 Apache 授权。 |
| [质量 data 目录](quality/data/) | JSON/CSV 等机器可读性能、制品身份、图像差异与测试摘要。数据边界由报告中的日期/源码/制品/场景说明限定。 |

## 生成的 Javadoc

Javadoc 是构建产物；报告不逐条索引构建日志或 HTML 输出文件。四个发布模块都配置 `javadocJar`，本地生成的 HTML/JAR 随对应构建落在模块 `build/` 和 Maven staging 输出中。

| 模块 | 生成内容 |
| --- | --- |
| `renderer-api` | 公共消费方 API 文档及 `renderer-api-7.0.4-javadoc.jar`。 |
| `renderer-core` | 后端实现文档及 `renderer-core-7.0.4-javadoc.jar`。 |
| `renderer-nvidia` | NVIDIA provider 文档及 `renderer-nvidia-7.0.4-javadoc.jar`。 |
| `renderer-rtxpt` | RTXPT 公共宿主／组合入口文档及 `renderer-rtxpt-7.0.4-javadoc.jar`。 |

Javadoc 制品是否存在及具体版本，以对应 Maven staging 或 runtime distribution 验收为准。

## 需核对的版本与历史表述

| 文档 | 情况 |
| --- | --- |
| [SECURITY.md](../SECURITY.md) | 仍将 `3.x` 写为当前稳定 API 线；这是支持政策事实，应由维护者按实际发布承诺确认，本清单不擅自改写支持版本。 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 当前支持边界与逐后端证据入口；不把单机验证外推为全部硬件验证。 |
| 通用命令与渲染语义、Procedural Geometry、Integer Textures、Evidence Retention | 开篇出现 `4.x/5.x` 初次引入版本；应区分历史功能来源与当前 major-7 消费契约，保留有效历史发布事实。 |
| [介质演示](medium-gallery.md) | 现为 RTXPT 六场景；原密度、散射和光子图展厅只存在于历史记录。 |
| 历史版本说明及本报告旧阶段记录 | 旧冻结候选、失败实验、交接文档中的进度百分比和通过声明均为其原时间/制品的历史记录；不得批量改成当前状态或跨制品套用。 |

历史记录不批量重写为当前结论。新增验证应写明源码／制品身份、配置与实际执行证据，再从本清单链接。

## 全部 Markdown 文件（仓库跟踪文件）

包含项目根目录、`.github/`、指南、历史报告及用户工具说明；生成目录和第三方依赖文档不属于本表。

| 序号 | 路径 |
| --- | --- |
| 1 | [.github/PULL_REQUEST_TEMPLATE.md](../.github/PULL_REQUEST_TEMPLATE.md) |
| 2 | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| 3 | [demos/hex-ball/README.md](../demos/hex-ball/README.md) |
| 4 | [docs/release-generic-renderer-contract.md](../docs/release-generic-renderer-contract.md) |
| 5 | [docs/release-application-clock-and-resource-snapshots.md](../docs/release-application-clock-and-resource-snapshots.md) |
| 6 | [docs/release-arena-diagnostics-snapshot-cache.md](../docs/release-arena-diagnostics-snapshot-cache.md) |
| 7 | [docs/release-closed-boundary-participating-media.md](../docs/release-closed-boundary-participating-media.md) |
| 8 | [docs/release-compact-topology-admission.md](../docs/release-compact-topology-admission.md) |
| 9 | [docs/release-opaque-visibility-fast-path.md](../docs/release-opaque-visibility-fast-path.md) |
| 10 | [docs/release-typed-retained-rendering-contract.md](../docs/release-typed-retained-rendering-contract.md) |
| 11 | [docs/release-application-shader-source-cache.md](../docs/release-application-shader-source-cache.md) |
| 12 | [docs/release-precompiled-application-shaders.md](../docs/release-precompiled-application-shaders.md) |
| 13 | [docs/release-driver-pipeline-cache.md](../docs/release-driver-pipeline-cache.md) |
| 14 | [docs/release-managed-path-tracing-backend.md](../docs/release-managed-path-tracing-backend.md) |
| 15 | [docs/release-demand-resources-and-lighting-updates.md](../docs/release-demand-resources-and-lighting-updates.md) |
| 16 | [docs/_Sidebar.md](../docs/_Sidebar.md) |
| 17 | [docs/backend-routing.md](../docs/backend-routing.md) |
| 18 | [docs/COMPATIBILITY.md](../docs/COMPATIBILITY.md) |
| 19 | [docs/d3d12-vulkan-composition.md](../docs/d3d12-vulkan-composition.md) |
| 20 | [docs/documentation-inventory.md](../docs/documentation-inventory.md) |
| 21 | [docs/Evidence-Retention.md](../docs/Evidence-Retention.md) |
| 22 | [docs/Generic-Commands-and-Ray-Tracing.md](../docs/Generic-Commands-and-Ray-Tracing.md) |
| 23 | [docs/Generic-Rendering-Semantics.md](../docs/Generic-Rendering-Semantics.md) |
| 24 | [docs/Home.md](../docs/Home.md) |
| 25 | [docs/Integer-Textures.md](../docs/Integer-Textures.md) |
| 26 | [docs/Java-API-Reference.md](../docs/Java-API-Reference.md) |
| 27 | [docs/Java.md](../docs/Java.md) |
| 28 | [docs/medium-gallery.md](../docs/medium-gallery.md) |
| 29 | [docs/performance-budgets.md](../docs/performance-budgets.md) |
| 30 | [docs/Procedural-Geometry.md](../docs/Procedural-Geometry.md) |
| 31 | [docs/Programmable-Scenes.md](../docs/Programmable-Scenes.md) |
| 32 | [docs/quality/api-release-review-20261101.md](../docs/quality/api-release-review-20261101.md) |
| 33 | [docs/quality/archive/api-closure-312147f/docs/quality/verification-report.md](../docs/quality/archive/api-closure-312147f/docs/quality/verification-report.md) |
| 34 | [docs/quality/backend-dependency-size-20261101.md](../docs/quality/backend-dependency-size-20261101.md) |
| 35 | [docs/quality/data/medium-demo-20261101/README.md](../docs/quality/data/medium-demo-20261101/README.md) |
| 36 | [docs/quality/game-renderer-comparison-20261101.md](../docs/quality/game-renderer-comparison-20261101.md) |
| 37 | [docs/quality/glass-transport-fix-20261102.md](../docs/quality/glass-transport-fix-20261102.md) |
| 38 | [docs/quality/hexball-noise-fix-20261102.md](../docs/quality/hexball-noise-fix-20261102.md) |
| 39 | [docs/quality/hexball-rtxpt-performance-20261101.md](../docs/quality/hexball-rtxpt-performance-20261101.md) |
| 40 | [docs/quality/luxcore-managed-medium.md](../docs/quality/luxcore-managed-medium.md) |
| 41 | [docs/quality/medium-collected-rejected-20261101.md](../docs/quality/medium-collected-rejected-20261101.md) |
| 42 | [docs/quality/medium-error9-sequence172.md](../docs/quality/medium-error9-sequence172.md) |
| 43 | [docs/quality/medium-performance-feature-matrix.md](../docs/quality/medium-performance-feature-matrix.md) |
| 44 | [docs/quality/medium-performance-optimization.md](../docs/quality/medium-performance-optimization.md) |
| 45 | [docs/quality/native-recommended-renderer-comparison-20261101.md](../docs/quality/native-recommended-renderer-comparison-20261101.md) |
| 46 | [docs/quality/new-api-timing-coverage.md](../docs/quality/new-api-timing-coverage.md) |
| 47 | [docs/quality/optical-redundancy-audit-20261102.md](../docs/quality/optical-redundancy-audit-20261102.md) |
| 48 | [docs/quality/renderer-comparison-20261101.md](../docs/quality/renderer-comparison-20261101.md) |
| 49 | [docs/quality/rtxpt-advanced-audit-20261101.md](../docs/quality/rtxpt-advanced-audit-20261101.md) |
| 50 | [docs/quality/rtxpt-general-lighting-20261101.md](../docs/quality/rtxpt-general-lighting-20261101.md) |
| 51 | [docs/quality/rtxpt-incremental-lighting.md](../docs/quality/rtxpt-incremental-lighting.md) |
| 52 | [docs/quality/rtxpt-managed-medium-migration.md](../docs/quality/rtxpt-managed-medium-migration.md) |
| 53 | [docs/quality/rtxpt-demand-memory.md](../docs/quality/rtxpt-demand-memory.md) |
| 54 | [docs/quality/rtxpt-memory-fps-20261102.md](../docs/quality/rtxpt-memory-fps-20261102.md) |
| 55 | [docs/quality/rtxpt-model-audit-20261102.md](../docs/quality/rtxpt-model-audit-20261102.md) |
| 56 | [docs/quality/rtxpt-raw-reuse-composition-20261101.md](../docs/quality/rtxpt-raw-reuse-composition-20261101.md) |
| 57 | [docs/quality/rtxpt-sr-light-ratio-20261102.md](../docs/quality/rtxpt-sr-light-ratio-20261102.md) |
| 58 | [docs/quality/rtxpt-sr-residual-sampling-20261102.md](../docs/quality/rtxpt-sr-residual-sampling-20261102.md) |
| 59 | [docs/quality/rtxpt-vulkan-signal-comparison-20261102.md](../docs/quality/rtxpt-vulkan-signal-comparison-20261102.md) |
| 60 | [docs/quality/verification-report.md](../docs/quality/verification-report.md) |
| 61 | [docs/quality/vulkan-fg-surface-hook-20261102.md](../docs/quality/vulkan-fg-surface-hook-20261102.md) |
| 62 | [docs/runtime-distribution.md](../docs/runtime-distribution.md) |
| 63 | [docs/Vulkan-Interop.md](../docs/Vulkan-Interop.md) |
| 64 | [README.md](../README.md) |
| 65 | [SECURITY.md](../SECURITY.md) |
| 66 | [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) |
| 67 | [tools/README.md](../tools/README.md) |
