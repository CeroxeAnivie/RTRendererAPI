# 介质性能基准覆盖

默认 `MediumGalleryPerformanceBenchmark` 运行完整介质矩阵；`--suite=focused` 单独运行原默认均匀直接散射。180 FPS 目标仍对应后者的原场景、1080p、1 SPP 和原预算，各功能场景分别报告自己的 FPS。

矩阵复用 12 个隔离认证路由、展厅介质与焦散开关场景，并增加金属/密度/边界、部分透射、材质/顶点/纹理 alpha 及其乘积、LIGHTMAP/visibility/fog、两种叠层的匹配与 mask/depth 拒绝、应用数据、首界面策略、SPP 和新增焦散组合。场景 coverage 从实际 SceneTransaction、材质/纹理/顶点、边界和 RenderFrameRequest 推导，新增必需 Feature 若没有真实 fixture，CPU 检查直接失败。

每场景单独记录请求预算、完成帧 FPS、固定 GPU 计时、命名 kernel/query 事件和互斥墙钟原始树。测试源码中的 route probe 在窗口外读取原生提交计数；非零介质场景没有执行原生传输则失败，焦散场景没有生成光子图也失败。只有完整矩阵全部完成才能生成 `complete:true`；显式子集永远不冒充全覆盖。开始即把当前覆盖状态写为未完成，避免误用之前的成功文件。

`--profile=true --wallProfile=true` 开启诊断；关闭诊断不读回图像。FG、降噪、重建、时域和动画关闭。只有各场景明确指定的介质、材质、SPP 与焦散不同，不把轻场景 FPS 套用到重场景。短窗口 smoke 用于验证覆盖和工具，正式性能验收仍应采用充分预热和多轮完成帧，并与同制品关闭诊断窗口比较观测开销。

可复用工具 `tools/RunMediumFeatureMatrix.ps1` 使用冻结 `candidate-core.jar`、`matrix-test-classes.jar` 和运行依赖，保存哈希、初始 GPU0 状态、独立日志和全部报告；标签目录不能覆盖。默认预热 1200 帧、3×240 帧；可显式缩短为 smoke。网络代理仍为进程外本机配置，不写仓库。

新增路径的逐项计时覆盖见 [new-api-timing-coverage.md](new-api-timing-coverage.md)。矩阵实测完成后，另外归档本轮场景数量、覆盖功能、事件有效性、开销及制品哈希；这里不预先宣称实测通过。
