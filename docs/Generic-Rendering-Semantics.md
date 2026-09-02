# 通用渲染语义参考

本页定义 `3.1.14` command path 的精确契约、当前 Vulkan backend 的支持边界和不可推断的事实。首次
实现通用 RT 提交时，先阅读[通用命令与硬件光线追踪指南](Generic-Commands-and-Ray-Tracing.md)；该指南
提供由资源发布到 `TraceRaysCommand` 的完整最小流程，本页则说明每一步为什么成立。

## 两个明确工作负载

一个 `Renderer` 同时提供 retained-scene 与 command transaction 两条路径。`RenderWorkload.Mode` 是唯一
的组合 discriminator；缺少 scene 字段不会把请求解释成 command transaction，command transaction 也不会被
静默改写为 PBR scene。

| 路径 | 适合的输入 | renderer 承担的职责 |
| --- | --- | --- |
| retained scene | 资产、PBR 材质、实例、灯光与相机 | 资源生命周期、帧节奏、保留式 RT scene |
| command transaction | 已确定的 resource、shader、binding、pass 与顺序 | 精确 admission、Vulkan 映射、fence evidence |

普通调用方应使用 retained-scene。专家路径不尝试分类或转换应用私有的材质、世界或 shader 语义。

## Command transaction 的顺序与同步

专家提交按下列顺序建立：

1. 以 `submitResources(...)` 发布不可变的 `BufferResource`、`TextureResource` storage generation；
2. 创建带严格递增 sequence 的不可变 `RenderCommandTransaction`；
3. 录制 render pass、graphics/compute/RT pipeline、binding、draw/dispatch/trace 及显式资源操作；
4. 通过 `CommandExecutionEvidence` 观察 `GPU_COMPLETED` 或 `OUTPUT_PRODUCED`。

Vulkan backend 可执行动态 rendering pass、attachment load/store/resolve、descriptor set、push constant、
vertex/index binding、direct/indexed/instanced/multi/固定计数 indirect draw、buffer/texture copy、
buffer-image copy、color/depth-stencil clear 与显式 barrier。无法用 Vulkan texel unit 精确表示 row pitch 的
buffer-image copy 会被拒绝，count-buffer indirect draw 需要对应扩展。未支持的 shader stage、format、
multisample、layout 与 resource state 同样在 admission 阶段 fail-closed。

顶点接口允许 normalized UINT/SINT storage（例如 `UNORM8X4`）在匹配 component count 且 storage
宽度不超过 shader 浮点接口宽度时完成规范化转换；未 normalized 的整数和浮点格式仍要求 numeric
domain 与 component width 严格一致。独立的数值 uniform 使用 `ImmediateUniform` 反射并占用显式
push-constant 区间；提交 draw/dispatch/trace 前必须以 `SetPushConstantsCommand` 覆盖其声明的
offset、size 和 stage visibility，越界、重叠、未初始化或 stage 不一致都会被拒绝。

同一 transaction 中，先前 transfer write 被随后 vertex/index/indirect/transfer/AS-build input 读取时，
backend 会建立 submission-local visibility dependency；AS 输入使用精确的
`TRANSFER_WRITE -> ACCELERATION_STRUCTURE_BUILD` edge。这不改变跨 transaction 规则：前一提交的写入
仍须先达到 `GPU_READY`。调用方要求特定 stage/access/layout/queue ownership 时，必须使用
`ResourceBarrierCommand` 明确表达。

## Binding、完成与资源代际

`BindingType.COMBINED_IMAGE_SAMPLER` 表示一个 texture view/sampler pair，对应
`VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`。它不是相邻 `SAMPLED_TEXTURE` 与 `SAMPLER` binding 的
别名。SPIR-V validator 会核对 descriptor shape；使用 `OpTypeSampledImage` 的模块只能使用这一精确 binding
类型，不能通过拆分 declaration 进入 backend。

`RECORDED` 仅表示 command 被记录，不表示 GPU 已完成。`OUTPUT_PRODUCED` 只会在提交 fence 完成后发布，
并标识第一个 stored color attachment（或 trace output）resource。device loss 会将 command lane 置为结构化终态
`DEVICE_LOST`，调用方必须按报告的 recovery action 重建 renderer。

Generic command output can also be consumed through the ordinary `Renderer.pollLatestCpuFrame()`
contract. When CPU readback is enabled, a completed stored RGBA8 2D color output is copied into a
bounded, immutable `CpuFrame`; `CpuFrame.outputResource()` preserves the exact output resource
identity and `width`/`height` describe the copied extent. The snapshot owns its bytes and has no
Vulkan lease. A readback failure is surfaced as renderer failure, while rollback and device loss
discard the pending snapshot. Retained-scene and generic command frame sequences are tracked
independently so one lane cannot cause duplicate publication or suppress the other.

`ResourceVersion` 标识 buffer/texture storage shape 与 declared usage，不标识每次内容写入。对 GPU-ready
generation 的后续写入产生新的 fence-backed mutation evidence。`ResourceMutationKey` 标识该 command sequence
的内容快照，而不是 `ByteBuffer` wrapper identity。buffer/texture 回收只能指定精确
`ResourceGenerationKey`，不存在能误删新 in-flight allocation 的宽泛 identity retirement。AS 不属于 resource
transaction；其精确 generation 由 `DestroyAccelerationStructureCommand` 单独回收。

## 通用硬件 RT command model

command algebra 可表达 BLAS/TLAS declaration/build、显式 RT shader group、RT pipeline binding、AS descriptor
和 `TraceRaysCommand`。它与 retained-scene 路径独立：不会把 raster GLSL 重新解释为 RT hit shader，也不会
从 PBR material 猜测任意 shader 的 fog、discard、atlas、lightmap、blend 或屏幕依赖语义。

设备声明对应 capability 为 `EXECUTABLE` 时，Vulkan backend 会：

- 分配 BLAS/TLAS storage，使用 device-addressable 的已声明 AS input buffer；
- 将 scratch 与 TLAS instance allocation 的所有权约束到提交 fence；
- 验证并创建提交的 SPIR-V pipeline，将显式 group 以对齐 SBT 记录；
- 写入精确 TLAS descriptor，录制 `vkCmdTraceRaysKHR`；
- 只在 trace submission fence 完成后发布 `OUTPUT_PRODUCED`。

`UPDATE` 必须引用同一且 fence-idle 的 AS generation。一个 transaction 可以依次 build BLAS、build 引用它
的 TLAS、再 trace；backend 会加入 AS-build 到 ray-shader 的依赖。缺失资源、不可寻址输入、stale TLAS descriptor、
不支持的 SPIR-V、无效 SBT layout 或未解决的先前 build 都会使 admission 失败。

`DestroyAccelerationStructureCommand` 仅在所有相关 build/trace use 已完成且没有驻留 TLAS 通过 device address 引用该 BLAS 后回收一个精确 AS generation；它
不能与同一个 AS 的 build 或 descriptor use 出现在一个 transaction 中。

## Composition、显示与互操作

`FrameCompositionPlan` 与 `FramePresentationEvidence` 保留为兼容的 target-based 表达，但 provider-owned
external output 必须使用 `FrameCompositionProvider` 的 `FrameCompositionRequest`。它由精确 source
`ResourceMutationKey`、有序 `REPLACE`/`ALPHA_OVER`/`ADDITIVE` layer、输出 extent/format、frame sequence 与
scene revision 组成；provider 选择 bounded writable frame slot，并以独立的 `FrameCompositionEvidence` 报告
destination frame identity。它绝不把 generic `RenderResourceId` 猜测为 external frame。

Vulkan 只有在每个 source mutation 的 command evidence 已为 `OUTPUT_PRODUCED`、generation 仍 resident、
format/extent/二维单样本 storage-read usage 完全匹配时才记录 composition。source image 仅读、frame-slot
output 仅写，真实 Vulkan barrier 与 compute submission 连接二者；第一层必须是 `REPLACE`，后续
`ALPHA_OVER` 使用 premultiplied-alpha 输入约定；slot 随后通过既有 external-memory lease
路径发布，直到 consumer release 后才能复用。`SUBMITTED` 和 `GPU_COMPLETED` 都不是 consumer acceptance 或
display visibility；调用方通过 `compositionEvidence(frameSequence)` 查询后续状态，只有 exact external lease
发布 completion 才得到 `CONSUMER_ACCEPTED`。`VISIBLE` 必须携带 presenter-owned 证据；composition provider
不拥有显示系统，因此不会自行发布它。不能执行该完整链路的
backend 必须使 extension 为空且把 `FRAME_COMPOSITION`、`FRAME_PRESENTATION_EVIDENCE` 与
`EXTERNAL_FRAME_CONSUMER` 保持为 `UNSUPPORTED`。

source layout 是 submission-local overlay：仅在 Vulkan queue submission 成功后、frame slot publication 前才写回
persistent layout ledger。slot 也在 submit 前完成 admission reservation，因此失败的录制、pin 或 queue-submit 不会污染
后续 barrier 的 old layout，更不会释放已经开始执行的 submission。若这个已验证 overlay 的提交仍发生运行时异常，provider
会等待该 submission 完成；host 随即转入 terminal failure，撤销可执行 capability，并拒绝后续命令。它不会继续以不可信 ledger 接受新命令。启用 CPU readback 时，composition 在同一 submission 中把 output
copy 到 slot-owned buffer、发布 host-read barrier，再恢复 output 的 `GENERAL` layout。

`RenderWorkload` 的 combined mode 要求 retained RT frame 与 command transaction 使用相同 sequence。Vulkan
provider 只有在两条 lane 都存在时才声明 `COMBINED_WORKLOADS`，并通过同一 frame-queue authority 先提交 retained
RT、再提交 raster transaction。返回的 `WorkloadExecutionEvidence` 保留两条 lane 的独立记录；任一 lane deferred
或 rejected 都不会被包装为成功组合。没有真实有序组合路径的 provider 返回 `UNSUPPORTED_COMBINATION`。

`renderer-api` 还定义了项目无关的 `ExternalFrameConsumer` negotiation/lease contract。Vulkan provider 在现有
`VulkanFrameInterop/GpuFrameLease` ABI 上提供窄适配，只宣称 producer 已实际证明的 CPU-observed completion。
不支持的 acquire signal、format 或 completion mechanism 会 fail-closed。CPU readback、官方 managed presentation
与 external zero-copy 是三条不同的 ownership/completion 路径。

公共类型不包含游戏、引擎、厂商或窗口系统专用的材质模型。
