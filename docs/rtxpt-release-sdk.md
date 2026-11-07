# 外部 shader 与 SDK 边界

本文对应 8.0.0 迁移候选。旧 native-lighting-sdk.json、bundle.json、六档私有 shader ABI 和旧上游 overlay 不是新版本的交付契约。

锁定的官方 RTXPT 源码与核心 shader 保持只读。自有视觉、正确有用的性能适配和诊断留在外部桥；上游有等价实现必须直接调用。独立 shader 即使复用了上游场景或加速结构，也不因此成为 RTXPT 原版光照算法。

## 独立编译

自有 Rtr shader 位于 renderer-rtxpt/src/main/cpp，入口清单位于同目录 AdapterShaders.cfg。tools/Build-AdapterShaders.ps1 只读使用固定上游 ShaderMake、DXC 与 include，输出到运行时 bin/AdapterShaders；不复制到官方源树，不改原版 target、shader 清单或默认实现。

外部 pass 使用自己的 ShaderFactory base、pipeline layout 和必要元数据。纹理过滤直接使用 Donut／NVRHI 硬件 sampler，不维持另一套手工过滤算法；元数据不得塞进原版 MaterialConstants flags 或其他私有字段。

原版扩展入口的实际调用点需核对：有声明不代表会执行。不能因为某个钩子名称合适，就补一个上游本不存在的调用或扩展原 binding layout。官方没有等价支持时，明确列限制和外部适配成本，不静默改变消费者要求的图像语义。

## 验证范围

独立 shader 的路径检查、编译、布局／ABI、实际出帧与最终包验证分别报告。此前 AdapterShaders 120／120 编译及对应 native／JNI 检查保留原制品身份；当前上游 20 仓／4426 原文件保持，生产 29 帧和原版 beauty 24 帧 proof 已通过。新原生控制定向验证、完整动态负载、人工验收与正式包仍未完成，不能把短帧 proof 当作等性能或所有画面通过。各项修复的保留、原生接管与退役范围见[迁移映射](consumer-agent-handoff.md#既有修复的迁移映射)。

通用直接光、稀疏源和共享同步的已实现扩展保留在外桥；宿主专属曲线、画风与最终合成由消费方承担，现有宿主策略迁出尚未完成。旧离线 manifest 激活接口、无数据指标与 retired shader ABI 不以空壳继续暴露。

正式包保留官方 bin 与相邻 Assets，详见[运行时分发](runtime-distribution.md)。历史 7.x 扩展教程保留原版本含义，不可直接拿其 manifest／SDK 与 8.0.0 混用。
