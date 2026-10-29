# 文档清单

本清单覆盖当前源码树中的 **32 份 Markdown**：原有 60 份减去已合并的 29 份质量报告和已移除的 `6.0.4.md`，加上统一质量报告和本清单。排除 `build/`、`generated/`、`.gradle/`、`out/` 等生成目录。正文尽量使用中文；API 符号、命令、路径、许可证名称和实测数值保留原写法。

原 `docs/quality/` 下的 29 份报告现合并至[质量验证与性能报告](quality/verification-report.md)。每个历史记录有稳定章节锚点，保留原测试结果、失败、未完成边界和性能限定；机器可读 `data/` 文件、日志、源码制品和许可证未移动或修改。报告按时间分别记录，不把早期冻结制品结论冒充当前状态。

## 当前文档全集（32 份）

### README 与导航（3 份）

| 文档 | 摘要 |
| --- | --- |
| [README.md](../README.md) | 项目定位、统一入口、能力范围、使用方法与候选状态。 |
| [Home.md](Home.md) | 阅读路线、模块边界、使用层级和本地验证入口。 |
| [_Sidebar.md](_Sidebar.md) | 开发文档导航索引。 |

### 指南（10 份）

| 文档 | 摘要 |
| --- | --- |
| [Java.md](Java.md) | Java 场景、帧提交、介质、输出、诊断和生命周期指南。 |
| [Generic-Commands-and-Ray-Tracing.md](Generic-Commands-and-Ray-Tracing.md) | 通用资源、图形命令和硬件 RT 提交流程。 |
| [Vulkan-Interop.md](Vulkan-Interop.md) | GPU frame lease、外部内存、同步及所有权协议。 |
| [runtime-distribution.md](runtime-distribution.md) | Windows 候选包、Maven 依赖和启动方式。 |
| [Programmable-Scenes.md](Programmable-Scenes.md) | retained-scene GLSL/SPIR-V 替换、资源绑定与 ABI。 |
| [Procedural-Geometry.md](Procedural-Geometry.md) | AABB procedural BLAS 构建、更新、交点 shader 和退休规则。 |
| [Integer-Textures.md](Integer-Textures.md) | 整数纹理格式、上传、采样、清除和原始输出。 |
| [medium-gallery.md](medium-gallery.md) | 介质展厅场景、参数、功能消融和验收入口。 |
| [Evidence-Retention.md](Evidence-Retention.md) | execution evidence 保留预算、lease、查询和背压语义。 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | major-7 公共契约、兼容策略和迁移边界。 |

### API 参考（2 份）

| 文档 | 摘要 |
| --- | --- |
| [Java-API-Reference.md](Java-API-Reference.md) | 面向消费方的稳定公共 Java 类型与调用参考。 |
| [Generic-Rendering-Semantics.md](Generic-Rendering-Semantics.md) | retained / command workload、准入、同步和完成 evidence 语义。 |

### 版本说明（11 份）

| 文档 | 摘要 |
| --- | --- |
| [5.0.0.md](5.0.0.md) | 统一 Renderer、Java 25、可编程阶段和原生格式 frame 的版本说明。 |
| [5.1.0.md](5.1.0.md) | 应用时间轴与不可变局部资源快照。 |
| [5.1.1.md](5.1.1.md) | GPU arena 统计快照缓存改进。 |
| [5.2.0.md](5.2.0.md) | 初始封闭介质、吸收/发光/散射/折射和嵌套体积契约。 |
| [5.2.1.md](5.2.1.md) | 介质维护修复和闭合网格拓扑 admission 优化。 |
| [5.2.2.md](5.2.2.md) | 恢复无介质 opaque shadow 快速路径的性能修复。 |
| [6.0.0.md](6.0.0.md) | Java API、provider SPI 和 GPU ABI 新 major 契约。 |
| [6.0.1.md](6.0.1.md) | 内容寻址 GLSL/SPIR-V 缓存和冷启动编译改进。 |
| [6.0.2.md](6.0.2.md) | 预编译 SPIR-V 场景阶段入口及完整性验证。 |
| [6.0.3.md](6.0.3.md) | Vulkan pipeline cache 身份与持久化修复。 |
| [7.0.0.md](7.0.0.md) | 直接介质 records、统一入口、实现范围、预算和迁移要求。 |

### 质量记录（1 份）

| 文档 | 摘要 |
| --- | --- |
| [质量验证与性能报告](quality/verification-report.md) | 合并 29 份历史记录，包含验证事实、失败、未完成边界和限定条件。 |

### 演示文档（1 份）

| 文档 | 摘要 |
| --- | --- |
| [Hex Ball README.md](../demos/hex-ball/README.md) | Demo 构建、fat JAR、功能配置、GPU smoke 和 FG 验收要求。 |

### 许可证与维护（3 份）

| 文档 | 摘要 |
| --- | --- |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 贡献规范、ABI 变更、测试、PR 和发布流程。 |
| [SECURITY.md](../SECURITY.md) | 安全漏洞私密报告、支持版本及依赖风险处置。 |
| [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | 发布运行依赖许可归属和 notices 完整性门禁。 |

### 本清单（1 份）

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

这些资源不计入 Markdown 统计。许可归属和源码原文保持原样；机器数据和日志也不搬入报告正文。

| 路径 | 用途 |
| --- | --- |
| [根 LICENSE](../LICENSE) | RTRendererAPI Apache License 2.0 完整文本。 |
| [介质 LICENSE.txt](../renderer-core/src/main/resources/assets/rtrenderer/medium/luxcore/LICENSE.txt) | 随上游介质源码分发的 Apache 2.0 许可证。 |
| [介质 NOTICE.txt](../renderer-core/src/main/resources/assets/rtrenderer/medium/luxcore/NOTICE.txt) | 固定上游来源、版权及原始片段/本项目适配边界。 |
| [介质 AUTHORS.txt](../renderer-core/src/main/resources/assets/rtrenderer/medium/luxcore/AUTHORS.txt) | 上游作者和贡献者归属。 |
| [source-manifest.json](../renderer-core/src/main/resources/assets/rtrenderer/medium/luxcore/source-manifest.json) | 固定提交、范围、文件字节数和 SHA-256；不代表功能完整。 |
| [质量 data 目录](quality/data/) | JSON/CSV 等机器可读性能、制品身份、图像差异与测试摘要。数据边界由报告中的日期/源码/制品/场景说明限定。 |

## 生成的 Javadoc

Javadoc 是构建产物，不纳入源码 Markdown 数量；报告不逐条索引构建日志或 HTML 输出文件。三个发布模块都配置 `javadocJar`，本地生成的 HTML/JAR 随对应构建落在模块 `build/` 和 Maven staging 输出中。

| 模块 | 生成内容 |
| --- | --- |
| `renderer-api` | 公共消费方 API 文档及 `renderer-api-7.0.0-javadoc.jar`。 |
| `renderer-core` | 后端实现文档及 `renderer-core-7.0.0-javadoc.jar`。 |
| `renderer-nvidia` | NVIDIA provider 文档及 `renderer-nvidia-7.0.0-javadoc.jar`。 |

Javadoc 制品是否存在及具体版本，以对应 Maven staging 或 runtime distribution 验收为准。

## 需核对的版本与历史表述

| 文档 | 情况 |
| --- | --- |
| [SECURITY.md](../SECURITY.md) | 仍将 `3.x` 写为当前稳定 API 线；这是支持政策事实，应由维护者按实际发布承诺确认，本清单不擅自改写支持版本。 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 将本轮验证边界称为 CPU-only；之后另有公开 HDR/GPU 记录，应根据当前契约链接适用的新报告章节。 |
| 通用命令与渲染语义、Procedural Geometry、Integer Textures、Evidence Retention | 开篇出现 `4.x/5.x` 初次引入版本；应区分历史功能来源与当前 major-7 消费契约，保留有效历史发布事实。 |
| [介质展厅](medium-gallery.md) | “这些入口尚未运行”的文字对应编写时点，不能外推为之后从未运行；各组合按报告时间和确切制品分别判断。 |
| 历史版本说明及本报告旧阶段记录 | 旧冻结候选、失败实验、交接文档中的进度百分比和通过声明均为其原时间/制品的历史记录；不得批量改成当前状态或跨制品套用。 |

本清单只整理导航和待核对项，不更改当前 API 支持声明、支持版本、历史验收结果或许可来源事实。
