# 7.0.0 发布前 API 检查

普通和专家模式共用不可变场景、帧和配置模型。普通调用方用 preset、场景事务和帧请求；专家按需发现扩展、规划功能变更或提交通用命令，不需要另一套互不兼容的对象模型。

## 入口怎么选

| 目标 | 入口 | 生命周期和观察 |
|---|---|---|
| 普通表面、离屏 CPU 帧 | `Renderer.open()` | `apply` → `submit` → 有界 `awaitFrame`；用 `FrameView.format()` 解释原始格式 |
| 普通 Vulkan GPU 窗口 | `Renderer.open(RendererConfig.presentation())` + `VulkanFramePresenter` | 主动 poll/present；presenter 先于 renderer 关闭 |
| 吸收玻璃、嵌套折射、混合 PBR | `Renderer.open("rtxpt", RendererConfig.pathTracedPresentation())` | 首次 submit 后用 `RtxptAccess` 控制窗口；全部在创建线程；一个进程一个 session |
| Vulkan 自定义 shader/图形/计算/显式 RT | `SceneShaderProgram` 或资源/命令事务 | 先检查 `renderingSemanticCapabilities()`；显式提交与证据、资源所有权和同步 |
| 高级功能开关 | `RendererConfig`；运行期 `RendererFeatureController` | plan → 检查 boundary → apply；需要 rebuild 时显式重建，不当成已生效 |

普通 preset 是政策请求，不是实际执行证明。功能状态读 `RenderingFeatureCapabilities`，FG/MFG 产出读 `FrameGenerationEvidence`；RTXPT 具体组合读 `RtxptAccess.runtimeStatus()`。原生循环 `renderFrame()` 的 true 表示窗口继续运行，可能因为最小化而没有完成新帧；实际完成帧看 bridge 的 `frames()` 差值或 SDK 证据。

`pathTracedPresentation()` 默认 FG 关闭，用于可比性能测量。应用可通过 `copyBuilder().frameGeneration(FrameGenerationOptions.recommended()).build()` 请求普通 2× FG。Demo 自己的默认政策与 API preset 分开管理。

## 为什么不能删除 Vulkan 后端

| 能力 | Vulkan | 当前 RTXPT 桥接 |
|---|---|---|
| 吸收/IOR/嵌套介电/粗糙透射 | 不代替 RTXPT 托管介质 | 支持收缩合同，算法交给上游 |
| 通用 graphics/compute、render pass、draw、barrier、显式 BLAS/TLAS/SBT | 资源/命令事务及语义能力表 | `submitResources`、`submitCommands` 明确 REJECTED，语义能力表 unsupported |
| 四阶段 GLSL/SPIR-V 替换、应用 shaderData | `SceneShaderProgram` | 拒绝；采用上游 shader |
| 应用精确投影、自定义 screen fog、光子图预算 | 普通帧/专家模型可表达 | 拒绝，不做假映射 |
| 原生格式 CPU 帧、linear HDR RGBA16F、外部 GPU consumer | `FrameView`/`FrameSnapshot`、Vulkan interop | GPU SDR 窗口呈现；显式 PNG 捕获仅供诊断，不是 readback 流 |
| 每帧确定性 AA、lightmap、纹理 footprint 策略 | 普通帧模型 | 拒绝非中性请求；样本数改用 `PathTracingOptions`，灯光/采样交上游 |
| 实例 mask、无阴影实例、surface overlay、自定义渲染状态、剪切变换 | 公共场景/专家模型 | `RtxptScene` 明确拒绝不能表达的组合 |

具体实现依据：`RtxptRenderer.submitResources/submitCommands/renderingSemanticCapabilities`、`RtxptInputContract`、`RtxptScene.validate/validateInstance`。某个弹球场景 FPS 达标不能推出上述通用图形能力已被 RTXPT 覆盖。若只发布 RTXPT 专用游戏光追 API，可以另作产品收缩；当前通用图形定位下不能移除 Vulkan。

## 本次确定修复

1. RTXPT 曾忽略配置中的自定义 scene shader、HDR 输出、GPU 选择和 validation 控制，现在在初始化前给出具体拒绝原因。
2. RTXPT 曾忽略帧中的确定性多采样、非中性 lightmap、纹理 footprint 覆盖和显式 history reset，现在在修改原生状态前拒绝。不把“不支持”标为“已执行”。
3. 场景 revision 曾允许重复，现在按公共合同严格递增，首次 revision 0 仍可发布。首次帧之后也不能重新提交旧 revision 0。
4. 通用命令/证据入口补齐 creating-thread、关闭状态和空参数检查，不再在关闭后伪装正常拒绝结果。
5. `FrameOutputFormat` 的过期 Javadoc 已纠正：HDR 快照保留半浮点格式，不会自动转成 RGBA8。Java 指南去掉旧 CUDA 介质程序选择描述，并标明 Vulkan 专家配置适用后端。
6. RTXPT 专家控制器分开保存“已提交的请求”和“实际执行快照”。临时 FG 暂停/回退后恢复不会丢失请求；观察到新执行状态时旧计划失效、旧结果不会与新快照混装。修复了 `effectiveProfile()` 后再取 `featureControlDiagnostics()` 可能抛出 profile 不一致异常的问题；模拟回归覆盖启用→回退→恢复、计划后状态变化、回退期间主动关闭。
7. 发布供应链任务仍强制从 core JAR 读取已撤下的 NVRTC manifest，会阻塞干净构建或把旧缓存当成依赖。已删除旧下载/打包助手和虚构的 CUDA 清单条目，只从当前 runtime graph 生成 SBOM/许可证清单，并校验不再出现旧 NVRTC 模块。RTXPT 适配模块纳入许可证策略；原生运行时继续携带各上游原始许可。

CPU 证据由 `RtxptAdapterSelfTest`、`RendererApiContractSelfTest` 和 API 边界检查提供；GPU 画面、功能组合和吞吐证据仍分别以已有原生验收与本次 Demo 验收为准。上述修复不表示所有硬件、所有组合与所有公开方法都经过穷尽验证；“收缩后的介质合同闭合”和“RTXPT 可替代整个通用图形 API”是两件事。
