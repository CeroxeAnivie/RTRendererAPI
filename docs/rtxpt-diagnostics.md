# RTXPT 外部调用边界诊断

本文介绍 **8.0.0** 的外部调用边界诊断接口。

## 记录什么

RtxptDiagnosticAccess 只提供 diagnosticLevel(Level) 与 diagnosticSnapshot(afterOrdinal)。观测范围是 API 自己执行的验证、转换、资源发布、灯光、属性以及原生帧调用边界。

| Stage | 计时边界 |
| --- | --- |
| VALIDATION | API 输入及帧契约检查 |
| TRANSLATION | 必要的数据转换与增量 packet 准备 |
| RESOURCES | 调用上游资源接入的外部边界 |
| LIGHTING | 外部灯光输入提交 |
| ATTRIBUTES | 独立扩展属性提交 |
| RTXPT_FRAME | JNI 帧请求的墙钟耗时，包含原生等待 |

这些是实际代码边界，不能把 RTXPT_FRAME 改名成 GPU 忙时，也不能将包含阶段与其子调用简单相加。GPU 专用时间仅在真实支持的查询有完成样本时报告；无数据不是 0ms。

**RTXPT 内部未展开。**只有上游现有、实际可调用的诊断接口才能延伸到内部。当前原版 GpuProfiler 没有可供此桥读取数值的 observer；声明、内部 Tracy scope 或 private 状态不作为公开数值接口。禁止为补表格修改上游、注入探针或制造内部阶段。

## 级别与有界存储

Level 为 OFF、SUMMARY、DEEP。OFF 不继续计时或构造诊断事件；消费方应同步关闭自己的事件、CSV、JFR 与诊断 GPU 查询，允许已经采集的样本有界收尾。

适配层使用固定 1024 槽 primitive ring。热路径不做同步文件写出或为每个事件分配对象；只有 owner 线程显式取 snapshot 时才构造返回记录。消费方负责将这些记录送入已有的有界异步 writer，不在后台线程并发调用 owner-only 接口。

## 事件与游标

Event 字段为 ordinal、sequence、sceneRevision、stage、beginNanos、durationNanos、items、bytes、failed。Snapshot 包含 level、nextOrdinal、lostEvents、events、upstreamInternalsExpanded；最后一项在当前实现为 false。

初始 afterOrdinal 为 -1，含义是“最后已消费事件”。处理完成后，以 snapshot.nextOrdinal() - 1 作为下一次游标；不要重复读取整个历史环，也不要把覆盖丢失当作没有事件。lostEvents 明确记录消费者落后造成的覆盖。

    long cursor = -1;
    var snapshot = access.diagnosticSnapshot(cursor);
    for (var event : snapshot.events()) {
        // 投递到消费方已有的有界异步输出。
    }
    cursor = snapshot.nextOrdinal() - 1;

导出诊断时显式标记 schema 3、wall_boundary 和内部未展开；不同 schema 或计时边界的数据不能直接混合计算。

## 性能报告

按相同入口、规模、配置与采样级别比较，分别给阶段平均、P95、P99、最大值与卡顿频率；冷启动、编译和稳态分开。失败／未完成样本及丢失事件明确列出。GPU 忙或缺基线时，FPS 只作观测，不能据绝对帧率判定收益。诊断本身的成本应单列，并使用完整资源提交到新帧输出的负载比较。
