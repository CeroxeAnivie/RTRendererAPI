# 恢复中心方向的局部光可见性

## 默认依赖交付

本批统一交付为 Maven Local 四模块 `7.2.0-rc.10`，宿主只需修改版本依赖，不再需要候选覆盖层或 runtime 目录参数。该版本同时纳入上批 host-contract：合法仿射透视投影、场景预算与晚期校验失败的原子拒绝、替换网格引用更新及关闭后缓存释放。四模块默认版本已同步；旧版制品不覆盖。

发布直接复用下文已通过专项的生产源码和冻结 native runtime，DLL、10 份 compute shader 与 shader ABI 保持同一身份；只重新执行必要的 Java 编译、打包、Maven Local 发布和制品核对，不重复已通过的 CPU／GPU／MC 专项。SDK manifest 更新版本与当前说明，不改变 native／shader 字节。本次没有 push、部署或新的性能结论。交付收据位于 `build/reports/source-visibility-publication/receipt.json`。

本批按用户最新“回到旧版效果”要求，恢复宿主旧 direct shader 的两种显式光源模型。响应表／`HOST_RESPONSE_V1` 提议已从所有生产源码撤回，未编译或进入运行目录。保留原两种 gain 模式及原参数；宿主使用 `DYNAMIC_ONLY_V1`，不把静态直线估计再送入动态增亮。GI、RR、beauty 路线未启用。

## 根因与责任

当前默认 BOX 声明同时使用 AABB 最近点计算衰减与方向，再拒绝背向 receiver 的光。对位于发光支持体同一底面／同一坐标 slab 的 receiver，最近点方向会成为纯切向，导致地面被拒绝。旧宿主 `058e168d` 的 `terrain_shadow.rgen` 则用距单位体素的 L1 距离衰减、朝中心的方向做可见性。这是模型差异。

API 增加可声明的中心可见性，不改变原 BOX 默认。MC 负责单位体素／动态半径、源颜色与强度、原版光照坐标、场景捕获与增益模式接线。本批 API 不修改 MC，不重新设计掉落／手持位置或模型；当前只发布 Maven Local，不 push、不部署。

## 通用 API 契约

`SparseLightSource.Visibility` 和 `source.withVisibility(...)` 提供以下互斥声明。原完整构造器及既有短构造器均保留，默认 `NEAREST_POINT`；`withLayer`、`withComposition`、`withAttenuation` 保留显式 visibility。

- `NEAREST_POINT`：保持原 BOX 最近点声明及原 shadow 规则。
- `CENTER_BOX_SUPPORT_V1`：衰减仍距 AABB 最近点，方向朝中心；每轴 `abs(receiver-center)<=halfExtent+.005` 时先直接可见；其余先检查法线朝向，再将阴影终点设为 `distance-min(halfExtent/abs(direction))-.004`，零方向轴不参与最小值。终点 `<=.005` 时直接可见。单位 cube 半径 `.5` 时，严格化为旧 `distance-.5/max(abs(direction))-.004`。
- `CENTER_SPHERE_SUPPORT_V1`：衰减仍距 radius AABB 最近点，方向朝中心；`distance<=radius+.005` 时先直接可见；否则阴影终点为 `max(.002,distance-radius-.004)`。不可替换为 AABB slab 终点。BOX 三个 half extent 必须相等；POINT 半径为零；SPOT 拒绝这两种新模型。

两种 V1 的固定世界单位规则为法线阈值 `.0001`、shading-normal 起点偏移 `.003`、ray minimum `.001`。内部可见判断在法线拒绝之前。阴影仍遍历所有段内真实遮挡者，没有全局跳过发光实例或其他实体。容差属于显式 V1 声明，原调用者不继承它们。

96 字节 source record 不变：identity flags 的 bit 0 为 dynamic、bit 1 为 L1 attenuation，bit 2 起为 visibility 枚举。所有 composition、tree／region／winner pruning、生产和 DEEP executor 共享同一 leaf evaluation；支持体、衰减上界和上传尺寸未变。

## 已验候选身份（发布取自相同字节）

- Java 统一覆盖层：`D:/Engineering/code/APIs/RTRendererAPI/build/reports/source-visibility/source-visibility-overlay.jar`，SHA256 `85e2fb34a76f0ca79cfb86f5f5f54a611c9c808a056438ff9c324f12e8b52067`。
- 必须前置在 rc9 四模块之前；覆盖层共 36 个 class，包含上批 host-contract 全部 29 个生产 class（投影、预算事务、属性缓存／关闭释放）及本批 source／packer。既有构造器调用仍可用，不要求重编其他 API 用户。
- native runtime：`D:/Engineering/code/APIs/RTRendererAPI/build/reports/source-visibility/runtime`。明确使用 JVM 参数 `-Dtop.ceroxe.rt.rtxpt.runtime=D:/Engineering/code/APIs/RTRendererAPI/build/reports/source-visibility/runtime`。不使用未经实现的目录环境变量。
- DLL SHA256 `78742bac78d3837525949485337ab2c31f9d5f6961d8b68f5637fcf8f5b5b692`；native／SDK ABI `de8d5dfe63e71f40d823ed947b2ad594b4e3247a8748b943037138394e001300`。runtime 沿用 rc9 的依赖，替换本批 DLL/PDB、相关 compute shader、对应 SDK source 和身份清单。不混用旧 DLL 与新 flags。
- 上述为发布前的候选接入和验收身份；正式 `7.2.0-rc.10` 四模块直接包含这些修复及 native runtime，正常接入使用版本依赖即可。

MC 静态源使用单位 AABB、`range=level`、`intensity=level/15`、L1 衰减和 `CENTER_BOX_SUPPORT_V1`；这样贡献恰为 `max(level-L1(nearestDelta),0)/15`。动态源使用真实声明 radius AABB 和 `CENTER_SPHERE_SUPPORT_V1`，同样保持旧强度。候选不修改 gain 公式或 12 倍旧参数。

## 验证与边界

本批 Java 编译、29 项 CPU source ABI／兼容性、原生及必要 shader 构建已过。native 重编 154.648 秒，10 GiB Job 的进程树提交峰值 0.898 GB（十进制）。上批 7 个生产源文件与原收据哈希全部精确相同，未重跑无关旧专项。

16 张真实 GPU 增益图、合计 813 项检查通过，覆盖最近点负对照、中心共面恢复、发光 BOX 自身网格、体外遮挡、AABB 内但球外的遮挡，以及 RGB 三种 composition 在 OFF／SUMMARY／DEEP 的输出。解析期望值直接写出旧衰减与 gain 公式；中心区域最大绝对误差 `0.0017359990835190686`，断言界限 `.008` 对应本夹具 FP16 输出精度。测试禁用信号复用，GI／RR／beauty 为零。复验 112.490 秒，进程树提交峰值 2.358 GB；这不是 MC 整场峰值或 FPS。此处小图只验证声明场景，不能代替 MC 实际世界验收，region 路由未单列新增 GPU 窗口。

GPU 初次负对照把 receiver 放在 AABB 精确边界；RT 重建的正负浮点尾差使部分 tangent dot 变成极小正数，原最近点模式并非全暗，断言失败。生产未改、断言未放宽；fixture 将 receiver 放到同一 z slab 内部 `.001`，让最近点 z 精确取 receiver z，再验证切向拒绝和中心模式。失败日志与 PFM 保留为 `nearest-boundary-fixture-failure.*`。

此前加载未完成且没有任何有效静态 candidate 时，旧 MC 会回退已烘焙 B；API 默认无 source 时 local 为零。本批按主代理明确范围只恢复正常完整捕获后的效果，不新增加载中 fallback 能力。这一边界须与完整场景图像验收分开。

证据目录 `build/reports/source-visibility/` 内保留 Java／native 源码身份、shader／DLL 身份、构建日志与小图。没有跑完整 API 性能矩阵，也未把图形初始化耗时当作 FPS；世界内视觉和性能由 MC 主代理执行相同入口的定向验收。
