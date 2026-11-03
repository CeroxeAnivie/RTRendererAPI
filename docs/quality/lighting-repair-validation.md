# 光照修复累计验收

交付版本：7.1.1。基线提交 `ad6d265bb87b00c63ef741b7629a05a9dbd3c5b0`。本批遵守已通过且未受影响项目不重跑、FG/MFG 不运行、不开 computer-use、保留外部 GPU 程序进行受干扰对照的要求。

## 变更与证据

| 审核项 | 修复及新增覆盖 | 证据 |
| --- | --- | --- |
| H01 顶点染色 | receiver／bounce、barycentric、缺省白色、材质×纹理×顶点色、vertex alpha 阈值及恢复 | `711-repair-native-test.log`、`lighting-validation.log` 的已通过项、`perf-alpha-correctness.log` |
| H02 统一 OFF | requested／effective、在途排空、统计序列冻结、无新 copy/query/poll、恢复 | 修复原生测试；诊断 OFF 开销矩阵；raw 八变体计数开关 |
| H03 实际 GI RR | 28 次 SDK 调用及 guides；gain 同场景补验；32 帧薄接收面运动／3392 新暴露像素、最大积分误差 5.91885%；八变体实际 evaluate | `native-gi-rr-validation.log`、`lighting-rr-gain-validation.log`、`perf-temporal-correctness.log`、`instrumentation-rr.log` |
| H04 增量成本 | 256 mesh 染色／纹理 alpha／baseline／pose／role／4 B patch／局部几何／删除恢复；4096 mesh CPU 事务 | `711-repair-native-test.log`、资源矩阵原始失败与 tail 补验、`lighting-validation.log` |
| H05 诊断 | CPU recorder v4 thread CPU／wall；DEEP 三 dispatch、独立参数；OFF／SUMMARY／DEEP 动态工作量 | `lighting-timing-validation.log`、大网格 DEEP 补验、诊断成本 result／supplement |
| H06 旧后端 | 实际旧发布 core JAR、Vulkan TLAS 和 rayQuery、6144000 RGB 数值对照 | [后端比较](lighting-backend-comparison.md) |
| H07 边缘与压力 | GL 深度／法线／identity／coverage／薄边引导；目标网格持续压力、10session／50resize 的资源与进程曲线；窗口消息探测 | `GL_GUIDED_RECONSTRUCTION PASS`、pressure／lifecycle JSON 与 memory/process 曲线、window JSONL |
| native 性能 | 通用候选判定、遮挡 early exit、GI 删除无用 dynamic 计算、按实际输入选择八个预编译变体 | [600 帧量化结果](native-lighting-performance.md) |

开发验证与最终 JAR 验证分别记录。新制品测试从仓库外目录加载四个 JAR，严格预编译、没有 checkout native override；最终身份、命令、结果与发布状态以 `build/reports/lighting-release/result.json` 为准，未生成该报告不表示最终发布已完成。

## 已通过项目的影响分析

| 保留的历史覆盖 | 本批影响及补验边界 |
| --- | --- |
| API 协商、普通／专家、worker 分块、原 Vulkan renderer、NVIDIA 基础能力、gallery | 既有功能实现未改；公共 API 仅增加 GI 重建状态及控制，ABI 检查与 Java 编译覆盖新增签名。Vulkan 窗口激活时机有改，单独补验真实窗口启动和状态变化。 |
| 完整 RTXPT beauty／预算／材料／连续性 | 无生产算法变更；native GI RR 是独立执行资源。完整路径共用 `RtrNativeLighting` 的候选／属性接口部分由角色、alpha、投影与相关宿主路径的受影响补验覆盖。1143 个动态 shader 排列按源 hash 严格验证，不无故重编译或重复已通过六预算序列。 |
| retained 事务与资源准备 | 持久化资源／依赖索引有改，不能仅沿用小场景通过；新增 256 mesh、删除恢复、资源矩阵、角色／姿态和持续混合编辑。 |
| 原生源算法、projection、时间与 origin | 数学公式和采样未改；候选读取和静态选择器有改，新增 raw／RR 八变体、角色／GI／alpha 补验以及完整输出 bit-exact 对照。原已通过的其余 projection／time 用例保留。 |
| host surface／augmentation | 共用候选与 alpha helpers，需在最终包对相关受影响路径补验；不重复未改的独立输入协议所有组合。 |
| SDK／扩展 | shader ABI 已改变，不沿用旧扩展成功记录；本批离线重编译、外部 SDK 一致性、错误保留、最终运行期拒绝旧 ABI 和新扩展实际 GPU 验证。 |
| 历史 10session／50resize／60s／120s | 原小网格结果作为历史证据保留；新增目标网格压力及资源曲线，不用前者代替后者。 |

历史 7.1.0 首轮 279 个任务中有九项失败，随后严格补验通过；原始失败始终保留。本批出现的测试夹具错误、诊断阶段参数错误及修复后补验也分列，不宣称从未失败或一次全绿。

## 适用边界

RR 对合法漫反射 GI 在光照网格执行 DLAA，空间重建保持宿主底图。它不是全分辨率 DLSS SR，也不对 gain 或任意自定义 AOV 伪报 RR。GL 引导示例验证通用边界条件，实际宿主仍需提供其引导数据与 GPU 消费计时。

DEEP 改变调度，source/shadow 内部尚未分别展开；生产 dispatch 的性能不能用拆分分位数相加推导。旧后端 fixture 材质读取比 native 通用实现简单，且外部 GPU 占用较高，因此未确认跨后端固定速度倍率。当前游戏世界、真实 DPI／跨屏、物理设备丢失／拔除未注入；这些结论不从普通成功运行推断。
