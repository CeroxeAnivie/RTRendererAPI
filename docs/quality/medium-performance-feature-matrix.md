# 介质性能基准覆盖

默认 `MediumGalleryPerformanceBenchmark` 运行完整介质矩阵；`--suite=focused` 单独运行原默认均匀直接散射。180 FPS 目标仍对应后者的原场景、1080p、1 SPP 和原预算，各功能场景分别报告自己的 FPS。

矩阵复用 12 个隔离认证路由、展厅介质与焦散开关场景，并增加金属/密度/边界、部分透射、材质/顶点/纹理 alpha 及其乘积、LIGHTMAP/visibility/fog、两种叠层的匹配与 mask/depth 拒绝、应用数据、首界面策略、SPP 和新增焦散组合。场景 coverage 从实际 SceneTransaction、材质/纹理/顶点、边界和 RenderFrameRequest 推导，新增必需 Feature 若没有真实 fixture，CPU 检查直接失败。

每场景单独记录请求预算、完成帧 FPS、固定 GPU 计时、命名 kernel/query 事件和互斥墙钟原始树。测试源码中的 route probe 在窗口外读取原生提交计数；非零介质场景没有执行原生传输则失败，焦散场景没有生成光子图也失败。只有完整矩阵全部完成才能生成 `complete:true`；显式子集永远不冒充全覆盖。开始即把当前覆盖状态写为未完成，避免误用之前的成功文件。

`--profile=true --wallProfile=true` 开启诊断；关闭诊断不读回图像。FG、降噪、重建、时域和动画关闭。只有各场景明确指定的介质、材质、SPP 与焦散不同，不把轻场景 FPS 套用到重场景。短窗口 smoke 用于验证覆盖和工具，正式性能验收仍应采用充分预热和多轮完成帧，并与同制品关闭诊断窗口比较观测开销。

可复用工具 `tools/RunMediumFeatureMatrix.ps1` 使用冻结 `candidate-core.jar`、`matrix-test-classes.jar` 和运行依赖，保存哈希、初始 GPU0 状态、独立日志和全部报告；标签目录不能覆盖。默认预热 1200 帧、3×240 帧；可显式缩短为 smoke。网络代理仍为进程外本机配置，不写仓库。

新增路径的逐项计时覆盖见 [new-api-timing-coverage.md](new-api-timing-coverage.md)。矩阵实测完成后，另外归档本轮场景数量、覆盖功能、事件有效性、开销及制品哈希；这里不预先宣称实测通过。

## 全矩阵暴露的薄区间边界错误

1080p 的 `route-boundary_density` 在像素 (1439,562) 只留下 t=7.294693 的出界事件，严格栈检查拒绝缺失入界。旧已验收 core 制品也失败。独立射线/盒 slab 计算的入界为 7.294689864、出界为 7.294693295，真实区间约 0.000003432，小于原事件去重阈值 0.00001。

修复仅把同方向同边界的近邻事件视为重复，并完整扫描事件包；方向相反的真实入界/出界必须保留。CUDA 对完整有序交点按相邻端点积分，去掉只适用于重发射线的 epsilon 跳步。栈校验仍严格执行。新增高消光薄盒、镜像 SPP8 与密度薄盒的独立解析 HDR 回归，避免仅仅不报错却漏掉薄段贡献。

成熟实现对照：PBRT v4 固定 `b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f` 的 [media.h](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/media.h) `SampleT_maj` 按 `tMax - tMin` 积分介质区间；LuxCore 固定 `b872186d2fe995a762f73708c606847eed55843e` 的 `PathVolumeInfo_Update` 区分进入/退出并更新栈。引用用于区间和栈语义对照，事件包修复是本项目的 Vulkan/CUDA 适配逻辑，不宣称上游实现了相同互操作架构。原始失败、定位记录及固定参考保存在 `build/medium-boundary-packet-fix-20261031/`。修复与全矩阵实测结果随后单独归档，不以编译通过代替验收。

第二个全矩阵阻断出现在第 25 个 `gallery-inward-nested`：同一条灯光连接在内向薄擦边区间先看到出界，严格栈检查 `error=38`。关闭所有计时仍可复现。另发现表面 NEE 先计算点光源方向，再沿法线移动起点，导致最终射线不指向原光源端点；已改为先按已有误差界与向外舍入生成起点，再计算点/聚光源方向及距离。对照 PBRT 上述固定版本 [ray.h](https://github.com/mmp/pbrt-v4/blob/b0bdf461ba5dd6504b70ebf0932acc0fe7aa744f/src/pbrt/ray.h) 的双 Interaction 端点 `SpawnRayTo` 重算 `pt - pf`；单 Point3f 重载不是该逻辑，不能混称。是否消除完整场景错误由后续实测决定，不预先归因。
