# 7.3.1 正式交付回执

2026-11-07。完整源码、正式工具及报告先以 `81be83d92387aa65c5a035e8daaf4039ddd01a7a` 上传 GitHub 原分支 `codex/resource-root-followup`，随后发布四模块 Maven Local。没有切分支、worktree、Central 或生成二进制上传。

最终 Local 字节身份 **70/70** 通过，包含当前源码/class、sources/javadoc、POM/module版本与内部依赖、Local与构建包字节、受测JNI与匹配PDB、1621执行依赖/shader、81文件SDK及shader ABI。详见[机器回执](data/retained-update-release.json)。

|正式模块|SHA256|
|---|---|
|renderer-api|`fcd22ffc4dc3fc6fbeed809e43e3bb596e4b3db8bc769863670e591829bd399c`|
|renderer-core|`a2e59ce9b4f84846827b3b0c255101fda9945e27d1379c38123c8129c55f3a69`|
|renderer-nvidia|`39b9f11445013704465776361dabccadc6a381e35d2923970ecd306266835095`|
|renderer-rtxpt|`87b82c8306843a0cd7d8bdeec6b842ecedf0643bfa419188d24f694ec3927847`|

JNI：`16b82de59926172a15b99b3550234c2e5d531e0eed5e247ecb19aa248a727560`。PDB age 266，GUID bytes `42b75afe1d929142be32e164448828b6`。匹配符号保留于 `build/rtxpt-sdk/bin/rtrenderer_rtxpt.pdb`。

Runtime ZIP：`f3e03ba184bfb342081e48a5c8704b5f129418f95453dfc2a791aec3e7e1c5c1`。

首轮 package-only 的 RTXPT JAR `e24a183d…` 不是最终 Local 包。它在报告回填前打包；发布时 Stage-NativeLightingSdk 按 release-index 再次收进本批最终报告和优化记录，SDK清单/ZIP/JAR随之变化。正式回执重新核对了所有Local交付字节和执行payload；最终三个回填文档也与staged SDK逐字节一致。不能用首轮草稿哈希代签正式包。

定向 JNI sparse：24帧通过，5.778s/Job峰2.156GB；四模块打包75.170s/1.973GB；Local发布65.134s/1.234GB；最终身份15.885s/1.803GB。均无同条件旧步骤延迟，不计算百分比。均为进程树commit口径，不是任务管理器驻留内存。

成本仍按[本批记录](retained-update-maintenance.md)原样报告：query准备均值-72.48%，三阶段均值合计-7.94%；tree均值+19.48%、regions均值+23.46%及P95+58.64%退化保留，没有做额外交错轮次。旧混合漏源失败保留，不作速度对照。真实MC FPS、RAM及闪退稳定性等待主代理唯一一次人工客户端；API子代理没有启动MC。

所有构建、测试和核验进程已退出。重任务槽位已释放；本回执提交仅改文档。
