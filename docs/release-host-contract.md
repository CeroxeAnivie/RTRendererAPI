# 宿主投影、场景预算和缓存生命周期修复

本批是 `7.2.0-rc.9` 上的 Java 候选覆盖层，尚未发布新的 Maven Local 制品。原生 DLL 沿用 rc9，未重编或重新运行已有通过的 GPU／MC 验收。没有新的 FPS 或整场内存峰值成绩，不能据此宣称移动 180 FPS 或进程树提交峰值低于 5 GB。

## 根因与修改

精确投影原来按投影矩阵最后一行的固定形状判定透视。宿主将合法的行走晃动、受伤旋转等仿射视图效果乘入投影矩阵后，仍有共同透视中心，但不再满足该形状，原生光照因此抛异常。`ExactProjectionState` 现从逆投影的齐次中心判断有限透视并计算真实世界原点；CPU 光线和原生参数使用同一原点。`RtxptNativeLightingParameters` 和 `RtxptHostSurfaceView` 都使用此契约。平行投影仍由该原生执行路径明确拒绝，CPU 平行光线保留各像素的近面原点。

资产预算拒绝使用可识别的 `SceneAssetBudgetExceededException`，带上几何、纹理、总有效载荷与限制。检查在 `prepareScene` 的私有候选场景中完成；后续稀疏源校验全部成功后才提交。超预算和晚期校验失败均保留已接受 revision、场景及稀疏源。原有资源预算没有降低，不自动删除资源，不改变已接受场景。这里统计的是活跃资产有效载荷，不是 JVM、驱动或进程总提交内存。

属性分组缓存以前在材质分组不变时保留原 `MeshAsset`，导致同 ID 替换网格后旧载荷仍被强引用。现在复用分组而更新网格身份；删除／重置清理颜色待更新身份。`close()` 同时释放属性缓存、发布缓冲、宿主请求与 resident 场景引用，避免调用方保留已关闭 renderer 时继续保活这些数据。正常在用 GPU 资源的 fence／租约规则未改。

## 本次实测

- 7 个生产源文件、2 个专项源文件直接 Java 编译通过。
- 投影独立仿射 oracle：85,608 条光线，覆盖 4 种深度、3 种 jitter 和 6 类视角／非对称变换；48 组会触发旧形状校验。最大方向分量误差 `2.3002565929797214e-7`，最大世界原点分量误差 `2.289609781769286e-6`；平行投影拒绝通过。
- 场景专项 1,930 项检查通过：500 代独立活跃 ID／有效载荷 oracle、全删除归零、OFF／SUMMARY／DEEP 的预算拒绝原子性、稀疏源晚期拒绝原子性、替换网格身份、删除与关闭缓存释放。
- 专项不创建设备或窗口。MC 的 10 GiB 有界脚本执行，进程树提交峰值 `0.663 GB`（十进制、三位小数），exit 0；这不是整场 MC 峰值。

证据：`build/reports/host-contract/receipt.json`、`compile.log`、`RtxptNativeLightingProjectionSelfTest.log`、`RtxptSceneAdmissionSelfTest.log` 和 `validation-job.log`。`job.log` 保留最初把 API 脚本的超时参数误传给 MC 有界脚本的启动失败；尚未启动 Java，随后按 MC 实际参数重试成功。

可供宿主定向集成的生产 class 覆盖层为 `build/reports/host-contract/host-contract-overlay.jar`，只包含本批生产 class，不含测试。其源码与覆盖层哈希记录在 receipt。必须与 rc9 四模块及原生 runtime 配套使用，不是完整分发包；本批没有 push、部署或覆盖玩家配置／存档。
