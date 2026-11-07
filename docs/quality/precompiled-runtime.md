# 构建期着色器与禁止运行时编译

2026-11-07 最终桥接包补验：新宿主真实子进程门禁再次通过，启动 cmd.exe 返回 Win32 367，childCreationBlocked=true；本轮没有运行时编译回退。该宿主与完整流式矩阵、最终包398项身份核验对应同一字节，未用热缓存替代可分发清单。

2026-11-07。本批保持 8.0.0，构建期着色器、运行时禁编译门禁及下述分发检查已有实现和实测；本轮桥接候选也已统一构建并完成集中合同与通用分窗负载。最终包身份与398项包检查已通过；正式源码／工具／文档161619d0已先上传GitHub，随后覆盖Maven Local，两模块18项身份检查通过，见[data/bridge-publication.json](data/bridge-publication.json)。此前正式包存在真实 DXC 回退，本批替换这一交付方式。

根因是原版缓存键包含源与 include 的绝对路径；直接搬运构建机缓存到消费者机器会失配。材质的非确定性调试名也进入 shader export 名，导致同一 StandardBSDF 数据模型随场景名字生成新编译命令。

桥接通过公开 `GetCustomPTPipelineGlobalMacros` 和 `MaterialsBaker::GetShaderPermutationTable`，仅把唯一标准排列的公开调试名固定为 RtrStandard；检查真实宏和 IndexInTable，不合并未知排列。真实材质名字、数值、纹理、透射与阴影语义不变。上游源码／shader 未修改；仍只有原先授权的非交互进度窗 guard。

`tools/rtxpt-precompiled-commands.json` 保存从实际 DXIL debug metadata 还原且重现原版命令哈希的构建输入。`Build-PrecompiledRuntime.py` 使用固定身份的官方 DXC、真实官方源和原选项串行编译。清单随源码交付，干净构建不依赖玩家／开发者的热缓存。编译缓存键包含全部源内容及编译器身份；输出记录二进制摘要、可迁移命令模板、源摘要和全局配置白名单。

native 会话只校验、复制二进制并用原版 `ShaderCompilerUtils::ComputeSha256Hex` 计算安装路径的别名。ZIP 不打包 ShaderDynamic/Bin。宿主及会话通过公开 Windows mitigation API 禁止创建子进程；实测启动真实 cmd.exe 返回 ERROR_CHILD_PROCESS_BLOCKED=367。原版真正的 DXC 保留完整文件，但不能执行；没有假编译器、成功 stub、核心补丁或链接拦截。缺失／损坏文件明确失败。

当前 150 条命令与 12 组全局宏配置只证明收录组合，不等于任意质量参数笛卡尔积。未收录组合会明确拒绝，新增组合必须离线构建发布；不静默改默认或删材质。整个宿主不能再启动其它子进程，外部进程工作由启动器承担。

本批证据位于 build/reports/precompiled-runtime（本机验证产物，不随 GitHub 上传二进制）：

| 检查 | 结果 |
| --- | --- |
| 真实宿主子进程门禁 | 通过，Win32 367 |
| 原目录弹球 | 4 帧，limit-reached，进程退出 0 |
| 全新第二目录、无 ShaderDynamic/Bin、介质 | 4 帧，原生 RR／SHaRC 路径，退出 0；进程树提交峰值 3.552 GB |
| 第二目录弹球材质循环 | 360 个已接受帧、两次材质切换，覆盖玻璃／不透明／金属，退出 0；峰值 3.535 GB |
| 损坏二进制 | GPU 初始化前 SHA-256 拒绝，退出 1，无编译回退 |
| 安装／ZIP CPU 合同 | 7 类通过，新增清单输入后随最终 Java 构建补验 |
| 官方源身份 | 20 仓库、4426 原文件、无额外源文件，保持原版 |

这些是功能与分发检查，640×400 的短窗不用于宣称完整性能收益。消费方正常地形快移与视觉仍单独验证。旧包发生的编译 OOM 及构建 JVM 崩溃保留原证据，不把本批成功解释为已证明所有闪退根因都消失。

## 本轮候选验证与发布边界

最终桥接 40 帧集中合同、CPU high-framerate 检查及 20 仓库／4426 原文件 Unexpected=0 已通过。通用完整新包 medium/fast × OFF/DEEP 四场各 661/661 fresh；另有同 bytecode 18 移动帧短窗旧新控制与同输入语义、不同统计 class 的 fast20 前后观测，结果分开见[流式场景报告](streaming-scene-results.md)。完整 fast 模拟率 OFF 97.913697／DEEP 93.845884 帧/秒，未达 180；OFF/DEEP 为顺序观测，不能称已证明近零诊断成本。

这些结果证明本轮候选已完成所列验证。最终 JAR／独立 runtime ZIP／受测 DLL 的一致性已通过398项包检查，Maven Local两模块与受测包也已完成18项身份检查。正式交付已按源码／工具／文档先推GitHub、再覆盖8.0.0 Maven Local的顺序完成；消费方受影响画面、整体性能和无运行时后编译仍须单独验收。未收录宏组合仍必须离线扩充并发布，不能借本机热缓存回退运行时编译。

### 最终打包与发布回执

build/reports/bridge-final-package-verification.json：398项通过，150个预编译条目、236个source条目；JAR内ZIP与独立ZIP一致，包内DLL与本轮受测身份一致，运行时热缓存未入包。打包wall120.110s，Job提交峰1.609GB；这不是任务管理器内存。

|制品|SHA-256|
|---|---|
|DLL|151f86b94fcfa8c869d8d6bc2ecdaf937e28ea476ee3d9f126db28a1c50dce09|
|runtime ZIP|118d01b056a3f7e28c76f70257ab56e95d7a18425d2d48e65f1fb3714b78b785|
|renderer-rtxpt JAR|5a9c379e7b41ee6a1b87e265c6de75a8f636688c020d3e7c033dc181c39195b9|
|renderer-api JAR|8f7a6c06ccaa3d08c3e7c70eb44fe72ffc84564d25f10202f5ddf1a06541a62c|

上述最终包已覆盖Maven Local，且确认与受测字节一致。GitHub源提交161619d005667aa26584ba4bc006c5f00a6d9635先于Local发布；正式回执见[data/bridge-publication.json](data/bridge-publication.json)。
