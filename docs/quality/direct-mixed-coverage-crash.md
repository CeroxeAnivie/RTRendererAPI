# 直接光混合覆盖网格崩溃

2026-11-07，正式 8.0.0 原生宿主退出 `0xC0000005`。Windows 事件 1000 给出的 DLL 偏移为 `0x5021ec`。保存转储的异常上下文为零地址读取，`RCX=0`；匹配本轮 PDB 定位到 NVRHI `fillAsInputDescForPreBuildInfo` 调用的 `fillD3dGeometryOMMLinkageDesc`，后者直接解引用 `triangles.opacityMicromap`。栈内返回地址对应 `GetAccelStructPreBuildInfo → createAccelStruct → BlasCompactor::BuildAll → OmmBuildQueue::BuildBLASWithOMM → RunBakeAndBuild → BuildOpacityMicromaps → Sample::Render`。这些是故障定位，非上游运行时诊断接口或性能采样。

同一 BLAS 的部分几何启用 alpha testing，部分为不透明；原版 OMM baker 仅为前者生成附件，而锁定 NVRHI 的 DXR 1.2 路径在发现任一 OMM 后遍历全部几何并无条件解引用附件。该数据组合符合公共 MeshAsset 的逐三角材质语义。不能归因为 JVM 或仅凭内存接近上限推断 OOM。

独立直接光 pass 已自行逐命中读取材质并判定透明覆盖。桥接 `PathTracer::ConfigureLighting` 通过原版 OMM 生命周期取消待烘焙任务，并释放可选 baker，选择原版无 OMM 执行路径；保留原版普通 BLAS/TLAS、裁切材质和直接光的实际 alpha 判断。仅设 `UIData.Enable=false` 或清除一次 TriggerRebuild 不足够：原版 CreateAccelStructs 在每次结构重建时又无条件排队，首个候选已由新增回归证伪并替换。可选 baker 为空本来就是原版对不支持 OMM 设备的合法路径，不实现另一套 BLAS。官方源、shader、采样量和分辨率均不修改。该策略只用于独立直接光消费方；原生 beauty/demo 的 OMM 策略保持原样，不宣称其混合 OMM 数据缺陷已由此修复。

新增通用公共消费者 `RtxptMixedCoverageNativeSelfTest`，覆盖同一网格的不透明／裁切双几何、六个 fresh 帧及中途拓扑替换。回归和完整宿主结果须保留实际制品身份；没有有效快飞窗口前不报告 FPS 收益。

实际回归：最终 DLL 下 MIXED_COVERAGE_NATIVE PASS，6 帧 fresh 身份和拓扑替换通过，退出 0；原生进程树提交峰 3.288 GB，外层含启动器 3.374 GB，总时 7.958 秒。该小场景只验证崩溃与身份，不作为完整 FPS 或透明视觉验收。官方导出身份 20 仓库／4426 文件通过，无新增上游源文件。日志：build/reports/precompiled-runtime/mixed-coverage-native-final.log。
