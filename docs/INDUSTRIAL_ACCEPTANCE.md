# 工业级 10/10 验收合同

本文定义 RTRendererAPI 可以宣称达到工业级成熟状态的最低条件。评分不是已完成条目的平均值：任意阻断项未关闭时，项目都不是
10/10，单机成功也不能替代缺失的硬件或长稳证据。

## 范围

声明支持范围固定为：

- Windows 10 x64 或更高版本
- NVIDIA GeForce RTX 20 系或更新架构
- Vulkan 1.2 或更高版本
- Java 25

AMD、Intel、Linux、macOS、移动平台、D3D12、Metal 和软件后端不属于本合同。

## 状态

| 状态           | 含义                     |
|--------------|------------------------|
| `OPEN`       | 尚未实现，或只有设计/局部测试        |
| `MECHANICAL` | 当前源码、构建或自动测试已经机械验证     |
| `MATRIX`     | 声明支持的硬件、驱动和压力矩阵持续通过    |
| `BLOCKED`    | 实现已具备但缺少外部环境或授权；不等同于通过 |

## 1. 公开 API 与接入

- [x] `MECHANICAL`：普通 `RayTracingRenderer` 不暴露 Vulkan handle、format、layout、queue family 或 semaphore；专家通过
  `VulkanFrameInterop` 显式发现能力，Vulkan 值使用不同强类型。
- [x] `MECHANICAL`：托管 `CpuFrame` 提供非阻塞轮询、有界等待和 caller-executor 异步路径，普通调用方不需要 Vulkan 知识。
- [x] `MECHANICAL`：安全资产入口防御性复制；`wrapImmutableDirect(...)` 明确要求 frozen read-only direct range 和 allocator
  lifetime。
- [x] `MECHANICAL`：`RendererHealth` 发布稳定 failure kind、recovery action 和资源欠账；lease 使用互斥 `LeaseState`
  ，失败保持可重试阶段。
- [x] `MECHANICAL`：README、Java 指南、API 参考、Vulkan 专家协议、支持矩阵和迁移指南覆盖当前 0.1.0 接入面。
- [ ] 隔离 Maven 消费者已编译 README quick start、设备选择、基础资源、事务、提交、托管帧，以及可执行的 interop
  ownership/release/recovery 控制流；仍需一个绑定真实 consumer Vulkan device/queue 的 LWJGL native import 样例与实机门禁。
- [x] `MECHANICAL`：异步帧等待取消只终止 caller-executor 上的 managed polling；契约测试证明不关闭 renderer/executor、不产生 lease，并在最大 250 微秒 backoff 后停止。
- [x] `MECHANICAL`：公开 ABI baseline 与隔离 Maven 消费者作为发布门禁。
- [x] `MECHANICAL`：应用只声明 `renderer-api` 一个 Maven 坐标；其 runtime 元数据传递无反向 POM 依赖的
  `renderer-core`、LWJGL 和 Windows native，隔离消费者验证 ServiceLoader runtime classpath 完整。

## 2. 渲染能力

- [x] `MECHANICAL`：`FrameOutputFormat` 贯通 SDR RGBA8 与 linear scene-referred HDR
  RGBA16F；probe/open、shader、allocation、descriptor、显存估算和 managed tone-map/readback 同步。
- [x] `MECHANICAL`：1/2/4/8 spp 固定 subpixel sequence 的确定性空间抗锯齿贯通 public request、frame ABI、SDR/HDR resolve 和
  pinned SPIR-V。
- [x] `MECHANICAL`：motion vector、RGBA16F color/geometry temporal history 和确定性 invalidation 贯通 camera cut、scene
  topology、resolution、format 与 device recovery；ABI、descriptor、shader 和 GPUScene native session 均有门禁。
- [ ] 支持生产级 denoise，或提供完整、版本化、可插拔的 denoiser input/output contract。
- [ ] 支持 compressed texture、HDR texture、array/cubemap resource，以及 capability 协商后的 filter/aniso 上限。
- [ ] `MECHANICAL`：Java/GLSL shader ABI exact-match、18 个 pinned SPIR-V variant、全部 65,536 种 half-float HDR
  输入的固定 SHA-256 tone-map golden、单调性和异常值数值门禁已纳入 `check`；仍需代表性生产场景 golden image 与完整材质能量边界后
  才能关闭整项。

## 3. 正确性、资源与恢复

- [ ] 每个 native 资源具有单一 owner、幂等 close、异常路径释放和机械可验证的状态转换。
- [ ] device lost、OOM、driver error、external handle import failure 和 process shutdown 具有故障注入测试。
- [ ] 无界 queue、无界 cache、静默 fallback 和未声明的全局可变配置为零。
- [x] `MECHANICAL`：API/Core Java 使用 `-Xlint:all -Werror`；API/Core Javadoc 使用 `-Werror`；block-tag layout 和高 warning
  上限阻止截断伪通过。
- [ ] 独立静态分析、覆盖率和依赖漏洞 warning 全部按错误处理。
- [ ] `MECHANICAL`：固定种子 property/fuzz 门禁已覆盖 GPU record 尺寸与 64 位偏移、mip texture 序列化、double light
  坐标、instance transform 及非法 stride/alignment/byte-count；仍需 native handle import/export 与更多 shader input
  变异语料后才能关闭整项。

## 4. 性能与长稳

- [ ] 性能门禁记录 GPU stable ID、driver、Windows、JDK、scene version、平均值、P95/P99、低窗口和最大停顿。
- [ ] 冷启动、稳态、scene streaming、resource churn、VRAM pressure 和 recovery 后性能具有独立预算。
- [ ] 至少 8 小时 soak 无 native leak、handle growth、VRAM drift、deadlock 或未恢复停顿。
- [ ] benchmark history 可比较，并以经过批准的阈值阻断回归。

## 5. 支持矩阵

- [ ] Windows 10 和 Windows 11 均通过完整严格验收。
- [ ] Turing/RTX 20、Ampere/RTX 30、Ada/RTX 40、Blackwell/RTX 50 各至少一张代表设备通过完整严格验收。
- [ ] 每个架构覆盖当前推荐 driver；最低支持架构额外覆盖至少一个仍受支持旧 driver。
- [ ] 覆盖 desktop/mobile GPU、不同 VRAM、低显存压力、多 NVIDIA GPU、device removal 和 sleep/resume。
- [ ] 发布制品中的“声明支持、已验证、未验证”与实际证据完全一致。

## 6. 可维护性与可观测性

- [ ] 核心类职责可审查，不存在依赖隐式阶段顺序的超大 coordinator。
- [ ] 全局 system property 收敛为实例级不可变配置；环境覆盖具有解析、校验和来源追踪。
- [ ] 公开 diagnostics 覆盖有界事件、资源预算、queue latency、failure reason 和 recovery evidence。
- [ ] 日志结构化、限流，且不泄露本机路径、native handle 或敏感环境信息。

## 7. 发布与供应链

- [ ] 普通 CI、严格硬件 CI、ABI、文档、静态分析、覆盖率和发布消费者都是 protected branch required checks。
- [ ] `MECHANICAL`：JAR/source/Javadoc archive 已强制移除时间戳并固定 entry 顺序；本地 staging 的全部 JAR/POM/Gradle
  module metadata 生成并重新计算 `SHA256SUMS`；runtime license inventory 具有默认门禁。仍缺签名、SBOM、build provenance 和跨环境
  reproducible-build 证据。
- [ ] `MECHANICAL`：Gradle 依赖制品使用严格 SHA-256 verification metadata，GitHub Actions 固定完整 commit SHA，Dependabot 每周维护
  Gradle 与 Actions；仍需自动漏洞扫描和高危漏洞响应 SLA 才能关闭整项。
- [ ] 正式版本具有 tag、release notes、migration、rollback 和至少一个独立消费方验证记录。

## 当前证据

- API/Core Javadoc 强制重建为 0 warning。
- ABI、published Maven consumer 和当前公开 API contract 已通过。
- 本轮 Windows 11 + NVIDIA GeForce RTX 5080 Laptop GPU 通过 `check + rendererCoreGpuSceneNativeGate`：覆盖 API/Core 合同、隔离
  Maven consumer、8 个 GPUScene Vulkan RT native 检查与 1920×1080 throughput；两份 Vulkan validation JSONL 共 267 条事件，
  `ERROR=0`、`WARNING=0`。
- Java SCIP 曾完整重建为 `indexedLanguages=[java]`、`skipped=[]`；每次阶段修改后仍必须重新建立索引并通过 semantic diff
  gate。

当前唯一实机组合不能关闭 Windows 10、RTX 20/30/40、旧 driver、低显存、多 GPU、sleep/resume 或 soak 条目。所有未勾选项仍是
10/10 阻断项。
