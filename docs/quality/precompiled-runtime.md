# 构建期着色器与禁止运行时编译

2026-11-07。本批保持 8.0.0，待源码提交上传后覆盖 Maven Local。此前正式包存在真实 DXC 回退，本批替换这一交付方式。

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
