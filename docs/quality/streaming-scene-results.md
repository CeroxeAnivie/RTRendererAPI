# 通用流式场景：分窗性能结果

2026-11-07。本轮最终40帧集中合同通过，完整新包四场、同bytecode短窗旧新控制及fast20前后观测均已完成；移动180目标未通过，尚未覆盖正式Maven Local或消费方画面/FPS验收。

三种数据分开使用：完整新包60tick/540移动/30warmup/90静止覆盖真正区域流入；同class短窗2tick/18移动只含texture/pose/source churn、没有tile跨界；fast20/180移动/8warmup/30静止为同输入语义的前后观测，fixture新增计时、不是相同class。均非交错ABBA，不跨窗口合并收益。旧包长窗185.0100174s后主动停止，不能从部分日志推算吞吐，也不标作PASS或真实崩溃。

全部场景为3662独立mesh、3669实例、45000稀疏源，请求2560×1600/direct1280×800，GI=0、reconstruction=NONE；没有降分辨率、视距、几何或质量换速度。完整matrix每场661/661fresh；medium实际47.9999891692单位、3次tile变化、183mesh入出；fast143.9999675075单位、10次tile变化、610mesh入出。短窗两场139/139fresh、位移4.7990252836、0mesh入出；fast20两场219/219fresh、位移47.9999025225、183mesh入出。

|窗口／case|启动前GPU三次采样|
|---|---|
|完整/current-medium-OFF|0/0/0%|
|完整/current-medium-DEEP|0/0/0%|
|完整/current-fast-OFF|2/0/0%|
|完整/current-fast-DEEP|3/0/0%|
|短窗/old|0/0/0%|
|短窗/new|0/0/3%|
|fast20/old|0/0/0%|
|fast20/new|0/0/0%|

GPU基线低不等于频率锁定。完整模拟率含准备/校验/poll/counter/循环记账，pipeline为apply→fresh；renderer打开、终点截图与关闭另计。旧fast20未采完整模拟率，不补算。

关键结果：完整fast模拟率OFF/DEEP为97.913697/93.845884；fast20移动pipeline 79.982056→106.263426（+32.86%），同一20tick静止188.613646→128.447422（−31.90%）。静止退化不与90样本完整矩阵混算，也不推测原因。

## 静止／移动吞吐

### 完整新包矩阵

|case|静止完整模拟|静止pipeline|移动完整模拟|移动pipeline|旧控制／差值|
|---|---:|---:|---:|---:|---|
|current-medium-OFF|414.306462|425.600333|105.031523|119.675154|旧长窗未完成|
|current-medium-DEEP|446.058405|464.548498|104.484089|119.944716|旧长窗未完成|
|current-fast-OFF|413.928034|424.078019|97.913697|110.362964|旧长窗未完成|
|current-fast-DEEP|416.285842|429.494228|93.845884|106.181967|旧长窗未完成|

### 同bytecode短窗与fast20观测（分别计算）

|数据集／窗口／帧率口径|旧|新|新减旧|变化|
|---|---:|---:|---:|---:|
|matched-short-off/stationary/simulation_frames_per_second|382.607187|402.800087|20.192900|+5.28%|
|matched-short-off/stationary/pipeline_frames_per_second|393.494057|413.856657|20.362600|+5.17%|
|matched-short-off/movement/simulation_frames_per_second|0.537386|81.053917|80.516531|+14982.99%|
|matched-short-off/movement/pipeline_frames_per_second|0.538116|101.057794|100.519678|+18679.92%|
|fast20-observation/stationary/simulation_frames_per_second|未提供|126.567539|未提供|—|
|fast20-observation/stationary/pipeline_frames_per_second|188.613646|128.447422|-60.166224|-31.90%|
|fast20-observation/movement/simulation_frames_per_second|未提供|92.301922|未提供|—|
|fast20-observation/movement/pipeline_frames_per_second|79.982056|106.263426|26.281371|+32.86%|

### OFF→DEEP顺序观测

各一次，不能称严格诊断overhead因果。

|场景／窗口／口径|OFF|DEEP|DEEP减OFF|变化|
|---|---:|---:|---:|---:|
|medium/stationary/simulation_frames_per_second|414.306462|446.058405|31.751942|+7.66%|
|medium/stationary/pipeline_frames_per_second|425.600333|464.548498|38.948165|+9.15%|
|medium/movement/simulation_frames_per_second|105.031523|104.484089|-0.547434|-0.52%|
|medium/movement/pipeline_frames_per_second|119.675154|119.944716|0.269562|+0.23%|
|fast/stationary/simulation_frames_per_second|413.928034|416.285842|2.357808|+0.57%|
|fast/stationary/pipeline_frames_per_second|424.078019|429.494228|5.416209|+1.28%|
|fast/movement/simulation_frames_per_second|97.913697|93.845884|-4.067813|-4.15%|
|fast/movement/pipeline_frames_per_second|110.362964|106.181967|-4.180997|-3.79%|

## 优化命中

代码只调整自有桥接：normal首次启用时才遍历resident tangent；纯纹理删除额外SceneFullRefresh，原版Sample本帧Refresh继续消费；exact no-op Decode不整图复制/重复memcmp；完整相同图像短路Java alpha扫描。上游20仓库4426原文件Unexpected=0，未修改算法或默认质量。不是重启历史失败的选择性拓扑准备、frontier或跨队列方案。

完整移动窗每场实测full upload=0/0B、rectangle=270次/276480B、identical generation=254、sampler-only=16、GPU copy=1132462080B、resident tangent visits=0/skipped=1976940。GPU整图copy仍在，不能把CPU去重称为全部GPU工作消失。

|完整case|mesh入/出|instance入/出|source更新|SceneFullRefresh事件|BlasBuild事件|
|---|---:|---:|---:|---:|---:|
|current-medium-OFF|183/183|4503/183|9000|OFF未采集|OFF未采集|
|current-medium-DEEP|183/183|4503/183|9000|3|183|
|current-fast-OFF|610/610|4930/610|9000|OFF未采集|OFF未采集|
|current-fast-DEEP|610/610|4930/610|9000|10|610|

|数据集／窗口／工作量|旧|新|新减旧|变化|
|---|---:|---:|---:|---:|
|matched-short-off/movement/full_upload_bytes|未提供|0|未提供|—|
|matched-short-off/movement/full_upload_count|未提供|0|未提供|—|
|matched-short-off/movement/gpu_copy_bytes|未提供|37748736|未提供|—|
|matched-short-off/movement/identical_generation_count|未提供|9|未提供|—|
|matched-short-off/movement/rectangle_upload_bytes|未提供|9216|未提供|—|
|matched-short-off/movement/rectangle_upload_count|未提供|9|未提供|—|
|matched-short-off/movement/resident_mesh_validation_skipped|未提供|65898|未提供|—|
|matched-short-off/movement/resident_mesh_validation_visits|未提供|0|未提供|—|
|matched-short-off/movement/sampler_only_count|未提供|0|未提供|—|
|matched-short-off/movement/SceneFullRefresh_event_count|未提供|未提供|未提供|—|
|matched-short-off/movement/Import_event_count|未提供|未提供|未提供|—|
|matched-short-off/movement/Resources_event_count|未提供|未提供|未提供|—|
|matched-short-off/movement/ResourceIndex_event_count|未提供|未提供|未提供|—|
|matched-short-off/movement/BlasBuild_event_count|未提供|未提供|未提供|—|
|matched-short-off/movement/Acceleration_event_count|未提供|未提供|未提供|—|
|fast20-observation/movement/full_upload_bytes|0|0|0|—|
|fast20-observation/movement/full_upload_count|0|0|0|—|
|fast20-observation/movement/gpu_copy_bytes|377487360|377487360|0|+0.00%|
|fast20-observation/movement/identical_generation_count|85|85|0|+0.00%|
|fast20-observation/movement/rectangle_upload_bytes|92160|92160|0|+0.00%|
|fast20-observation/movement/rectangle_upload_count|90|90|0|+0.00%|
|fast20-observation/movement/resident_mesh_validation_skipped|未提供|658980|未提供|—|
|fast20-observation/movement/resident_mesh_validation_visits|未提供|0|未提供|—|
|fast20-observation/movement/sampler_only_count|5|5|0|+0.00%|
|fast20-observation/movement/SceneFullRefresh_event_count|97|3|-94|-96.91%|
|fast20-observation/movement/Import_event_count|180|180|0|+0.00%|
|fast20-observation/movement/Resources_event_count|180|180|0|+0.00%|
|fast20-observation/movement/ResourceIndex_event_count|97|97|0|+0.00%|
|fast20-observation/movement/BlasBuild_event_count|183|183|0|+0.00%|
|fast20-observation/movement/Acceleration_event_count|3|3|0|+0.00%|

旧runtime没有resource-work方法，短窗旧counter为未提供，不能当零或按3662mesh推算。fast20前后SceneFullRefresh的事件数、单事件耗时与每帧摊销口径不同，须同时看；单事件可能变慢但事件减少。联合旧新比较没有单项消融，不把总收益分配给某一个优化。

统计组成提醒：fast20的SceneFullRefresh从97次（含廉价纹理refresh与真实拓扑）变为3次（只剩真实拓扑）。原始单事件mean 2.526520→36.758333ms、P95 2.469300→38.496800ms仍保留，但不能把约1354%的单事件mean差写成同分布性能退化。总调用wall为245.072400→110.275000ms，除以同一180帧后摊销mean为1.361513333→0.612638889ms/帧。旧summary不能重建按帧P95，故按帧P95旧新不可比；不拿新按帧P95与旧单事件P95相比较。其它per-event行也标出N旧→新，样本数变化时须考虑事件组成，不能只看百分比。

## 各步骤平均／P95

### 完整新包绝对值

native按帧归组同stage的sequence/revision事件，包含零工作帧；事件分布另存JSON。Frame self含跨线程资源等待，不能当exclusive CPU或GPU忙时，也不能与ResourcePlan相加。刷新低频，按帧P95=0仍保留约35ms事件长尾。

|case／窗口／域／步骤|mean ms|P95 ms|旧／差值|
|---|---:|---:|---|
|current-medium-OFF/stationary/wall/simulation_frame_wall|2.413511|3.178400|旧长窗未完成|
|current-medium-OFF/stationary/wall/apply_to_fresh_wall|2.349622|3.110800|旧长窗未完成|
|current-medium-OFF/stationary/wall/submit_wall|2.020099|2.709900|旧长窗未完成|
|current-medium-OFF/stationary/wall/apply_wall|0.043022|0.069000|旧长窗未完成|
|current-medium-OFF/stationary/wall/attributes_wall|0.001687|0.003000|旧长窗未完成|
|current-medium-OFF/stationary/wall/preparation_outside_timing|0.037798|0.064100|旧长窗未完成|
|current-medium-OFF/stationary/wall/diagnostic_poll_outside_timing|0.012292|0.021400|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/VALIDATION|未提供|未提供|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/TRANSLATION|未提供|未提供|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/RESOURCES|未提供|未提供|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/LIGHTING|未提供|未提供|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/ATTRIBUTES|未提供|未提供|旧长窗未完成|
|current-medium-OFF/stationary/boundary_wall/RTXPT_FRAME|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/wall/simulation_frame_wall|9.520278|12.162800|旧长窗未完成|
|current-medium-OFF/movement/wall/apply_to_fresh_wall|8.355953|10.742500|旧长窗未完成|
|current-medium-OFF/movement/wall/submit_wall|7.512856|9.720900|旧长窗未完成|
|current-medium-OFF/movement/wall/apply_wall|0.058524|0.100000|旧长窗未完成|
|current-medium-OFF/movement/wall/attributes_wall|0.002755|0.004800|旧长窗未完成|
|current-medium-OFF/movement/wall/preparation_outside_timing|1.119797|2.130700|旧长窗未完成|
|current-medium-OFF/movement/wall/diagnostic_poll_outside_timing|0.023809|0.038600|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/VALIDATION|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/TRANSLATION|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/RESOURCES|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/LIGHTING|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/ATTRIBUTES|未提供|未提供|旧长窗未完成|
|current-medium-OFF/movement/boundary_wall/RTXPT_FRAME|未提供|未提供|旧长窗未完成|
|current-medium-OFF/initial/upload|13914.750100|13914.750100|旧长窗未完成|
|current-medium-DEEP/stationary/wall/simulation_frame_wall|2.241724|3.115200|旧长窗未完成|
|current-medium-DEEP/stationary/wall/apply_to_fresh_wall|2.152628|3.013000|旧长窗未完成|
|current-medium-DEEP/stationary/wall/submit_wall|1.805004|2.665700|旧长窗未完成|
|current-medium-DEEP/stationary/wall/apply_wall|0.047249|0.085400|旧长窗未完成|
|current-medium-DEEP/stationary/wall/attributes_wall|0.001937|0.002400|旧长窗未完成|
|current-medium-DEEP/stationary/wall/preparation_outside_timing|0.044186|0.082200|旧长窗未完成|
|current-medium-DEEP/stationary/wall/diagnostic_poll_outside_timing|0.035803|0.069600|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/VALIDATION|0.044641|0.151400|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/TRANSLATION|0.040477|0.081500|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/RESOURCES|0.008741|0.015300|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/LIGHTING|0.000966|0.001400|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/ATTRIBUTES|0.007917|0.011800|旧长窗未完成|
|current-medium-DEEP/stationary/boundary_wall/RTXPT_FRAME|1.672451|2.523300|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_inclusive_wall/Frame|1.665872|2.471700|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_inclusive_wall/Import|0.007477|0.009900|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_inclusive_wall/ResourcePlan|0.244173|0.376200|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_inclusive_wall/Resources|0.013323|0.018900|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_self_wall/Frame|1.665872|2.471700|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_self_wall/Import|0.007477|0.009900|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_self_wall/ResourcePlan|0.230850|0.361700|旧长窗未完成|
|current-medium-DEEP/stationary/native_frame_self_wall/Resources|0.005847|0.011500|旧长窗未完成|
|current-medium-DEEP/movement/wall/simulation_frame_wall|9.569631|11.919000|旧长窗未完成|
|current-medium-DEEP/movement/wall/apply_to_fresh_wall|8.337174|10.336700|旧长窗未完成|
|current-medium-DEEP/movement/wall/submit_wall|7.446435|8.950600|旧长窗未完成|
|current-medium-DEEP/movement/wall/apply_wall|0.058270|0.094300|旧长窗未完成|
|current-medium-DEEP/movement/wall/attributes_wall|0.003120|0.006500|旧长窗未完成|
|current-medium-DEEP/movement/wall/preparation_outside_timing|1.162062|2.475200|旧长窗未完成|
|current-medium-DEEP/movement/wall/diagnostic_poll_outside_timing|0.056953|0.092800|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/VALIDATION|0.073190|0.196600|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/TRANSLATION|1.062436|1.732900|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/RESOURCES|0.577547|1.268100|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/LIGHTING|0.018859|0.130000|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/ATTRIBUTES|0.018036|0.026000|旧长窗未完成|
|current-medium-DEEP/movement/boundary_wall/RTXPT_FRAME|5.620448|7.775200|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/Acceleration|0.094780|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/BlasBuild|0.089400|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/BufferUpload|0.001316|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/Frame|5.613455|7.764900|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/Import|0.848789|1.342900|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/ResourceIndex|0.047756|0.040600|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/ResourcePlan|2.379585|3.515200|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/Resources|1.770024|2.746300|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/SceneFullRefresh|0.193636|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/SparseQueryPreparation|0.010071|0.088800|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/SparseRegions|0.000056|0.000500|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/SparseUpload|0.026107|0.227800|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_inclusive_wall/TlasAllocate|0.002393|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/Acceleration|0.002988|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/BlasBuild|0.089400|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/BufferUpload|0.001316|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/Frame|5.613455|7.764900|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/Import|0.848789|1.342900|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/ResourceIndex|0.046440|0.040600|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/ResourcePlan|0.514781|0.809700|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/Resources|0.727599|1.526000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/SceneFullRefresh|0.193636|0.000000|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/SparseQueryPreparation|0.010071|0.088800|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/SparseRegions|0.000056|0.000500|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/SparseUpload|0.026107|0.227800|旧长窗未完成|
|current-medium-DEEP/movement/native_frame_self_wall/TlasAllocate|0.002393|0.000000|旧长窗未完成|
|current-medium-DEEP/initial/upload|13682.917000|13682.917000|旧长窗未完成|
|current-fast-OFF/stationary/wall/simulation_frame_wall|2.415740|3.076700|旧长窗未完成|
|current-fast-OFF/stationary/wall/apply_to_fresh_wall|2.358057|3.004500|旧长窗未完成|
|current-fast-OFF/stationary/wall/submit_wall|2.053843|2.581300|旧长窗未完成|
|current-fast-OFF/stationary/wall/apply_wall|0.041391|0.052400|旧长窗未完成|
|current-fast-OFF/stationary/wall/attributes_wall|0.001727|0.002100|旧长窗未完成|
|current-fast-OFF/stationary/wall/preparation_outside_timing|0.033711|0.050500|旧长窗未完成|
|current-fast-OFF/stationary/wall/diagnostic_poll_outside_timing|0.012228|0.017200|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/VALIDATION|未提供|未提供|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/TRANSLATION|未提供|未提供|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/RESOURCES|未提供|未提供|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/LIGHTING|未提供|未提供|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/ATTRIBUTES|未提供|未提供|旧长窗未完成|
|current-fast-OFF/stationary/boundary_wall/RTXPT_FRAME|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/wall/simulation_frame_wall|10.212196|11.601500|旧长窗未完成|
|current-fast-OFF/movement/wall/apply_to_fresh_wall|9.061011|10.159800|旧长窗未完成|
|current-fast-OFF/movement/wall/submit_wall|8.136466|9.064400|旧长窗未完成|
|current-fast-OFF/movement/wall/apply_wall|0.063716|0.107500|旧长窗未完成|
|current-fast-OFF/movement/wall/attributes_wall|0.002763|0.006300|旧长窗未完成|
|current-fast-OFF/movement/wall/preparation_outside_timing|1.112110|2.282700|旧长窗未完成|
|current-fast-OFF/movement/wall/diagnostic_poll_outside_timing|0.020000|0.032100|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/VALIDATION|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/TRANSLATION|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/RESOURCES|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/LIGHTING|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/ATTRIBUTES|未提供|未提供|旧长窗未完成|
|current-fast-OFF/movement/boundary_wall/RTXPT_FRAME|未提供|未提供|旧长窗未完成|
|current-fast-OFF/initial/upload|13124.214400|13124.214400|旧长窗未完成|
|current-fast-DEEP/stationary/wall/simulation_frame_wall|2.402096|3.346400|旧长窗未完成|
|current-fast-DEEP/stationary/wall/apply_to_fresh_wall|2.328320|3.281600|旧长窗未完成|
|current-fast-DEEP/stationary/wall/submit_wall|2.038258|2.819300|旧长窗未完成|
|current-fast-DEEP/stationary/wall/apply_wall|0.035970|0.044000|旧长窗未完成|
|current-fast-DEEP/stationary/wall/attributes_wall|0.001562|0.001900|旧长窗未完成|
|current-fast-DEEP/stationary/wall/preparation_outside_timing|0.030277|0.040900|旧长窗未完成|
|current-fast-DEEP/stationary/wall/diagnostic_poll_outside_timing|0.035413|0.069200|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/VALIDATION|0.019949|0.026800|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/TRANSLATION|0.035286|0.049100|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/RESOURCES|0.007510|0.011000|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/LIGHTING|0.001007|0.001800|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/ATTRIBUTES|0.006854|0.010300|旧长窗未完成|
|current-fast-DEEP/stationary/boundary_wall/RTXPT_FRAME|1.940203|2.731200|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_inclusive_wall/Frame|1.935292|2.725800|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_inclusive_wall/Import|0.007420|0.007900|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_inclusive_wall/ResourcePlan|0.221794|0.276100|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_inclusive_wall/Resources|0.012217|0.016300|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_self_wall/Frame|1.935292|2.725800|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_self_wall/Import|0.007420|0.007900|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_self_wall/ResourcePlan|0.209578|0.249600|旧长窗未完成|
|current-fast-DEEP/stationary/native_frame_self_wall/Resources|0.004797|0.009800|旧长窗未完成|
|current-fast-DEEP/movement/wall/simulation_frame_wall|10.654867|12.223100|旧长窗未完成|
|current-fast-DEEP/movement/wall/apply_to_fresh_wall|9.417795|11.023600|旧长窗未完成|
|current-fast-DEEP/movement/wall/submit_wall|8.458076|9.796900|旧长窗未完成|
|current-fast-DEEP/movement/wall/apply_wall|0.066801|0.108200|旧长窗未完成|
|current-fast-DEEP/movement/wall/attributes_wall|0.002719|0.005700|旧长窗未完成|
|current-fast-DEEP/movement/wall/preparation_outside_timing|1.164611|2.664200|旧长窗未完成|
|current-fast-DEEP/movement/wall/diagnostic_poll_outside_timing|0.059448|0.107300|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/VALIDATION|0.043021|0.064600|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/TRANSLATION|1.063443|1.849200|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/RESOURCES|0.590361|1.237100|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/LIGHTING|0.016844|0.130900|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/ATTRIBUTES|0.033390|0.028100|旧长窗未完成|
|current-fast-DEEP/movement/boundary_wall/RTXPT_FRAME|6.632157|8.423200|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/Acceleration|0.292332|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/BlasBuild|0.273391|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/BufferUpload|0.002685|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/Frame|6.623420|8.413400|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/Import|0.873702|1.537400|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/ResourceIndex|0.089747|0.075000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/ResourcePlan|3.059741|4.040900|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/Resources|2.214798|3.168400|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/SceneFullRefresh|0.664086|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/SparseQueryPreparation|0.010496|0.093000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/SparseRegions|0.000060|0.000500|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/SparseUpload|0.026066|0.224000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_inclusive_wall/TlasAllocate|0.008638|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/Acceleration|0.010303|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/BlasBuild|0.273391|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/BufferUpload|0.002685|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/Frame|6.623420|8.413400|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/Import|0.873702|1.537400|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/ResourceIndex|0.087063|0.075000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/ResourcePlan|0.552611|0.890700|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/Resources|0.677010|1.613900|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/SceneFullRefresh|0.664086|0.000000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/SparseQueryPreparation|0.010496|0.093000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/SparseRegions|0.000060|0.000500|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/SparseUpload|0.026066|0.224000|旧长窗未完成|
|current-fast-DEEP/movement/native_frame_self_wall/TlasAllocate|0.008638|0.000000|旧长窗未完成|
|current-fast-DEEP/initial/upload|13404.457600|13404.457600|旧长窗未完成|

### 短窗／fast20旧新：全部wall步骤及原生事件分布

native事件统计按实际事件计，样本数保存在JSON；当前按帧分布不能与旧版事件分布直接算百分比。OFF没有诊断计时，未提供不算零。线程CPU原始值另存JSON，GetThreadTimes观察粒度约15.625ms，不把0当精确零成本。

|数据集／窗口／域／步骤／统计／事件N旧→新|旧 ms|新 ms|新减旧 ms|变化|
|---|---:|---:|---:|---:|
|matched-short-off/stationary/wall/simulation_frame_wall/mean_ms|2.613496|2.482423|-0.131072|-5.02%|
|matched-short-off/stationary/wall/simulation_frame_wall/p95_ms|3.054800|3.185300|0.130500|+4.27%|
|matched-short-off/stationary/wall/apply_to_fresh_wall/mean_ms|2.541334|2.416296|-0.125039|-4.92%|
|matched-short-off/stationary/wall/apply_to_fresh_wall/p95_ms|2.986600|3.131100|0.144500|+4.84%|
|matched-short-off/stationary/wall/submit_wall/mean_ms|2.197104|2.048939|-0.148166|-6.74%|
|matched-short-off/stationary/wall/submit_wall/p95_ms|2.574100|2.454000|-0.120100|-4.67%|
|matched-short-off/stationary/wall/apply_wall/mean_ms|0.048774|0.043410|-0.005364|-11.00%|
|matched-short-off/stationary/wall/apply_wall/p95_ms|0.081800|0.061400|-0.020400|-24.94%|
|matched-short-off/stationary/wall/attributes_wall/mean_ms|0.001923|0.001762|-0.000161|-8.38%|
|matched-short-off/stationary/wall/attributes_wall/p95_ms|0.003000|0.002600|-0.000400|-13.33%|
|matched-short-off/stationary/wall/preparation_outside_timing/mean_ms|0.040532|0.038486|-0.002047|-5.05%|
|matched-short-off/stationary/wall/preparation_outside_timing/p95_ms|0.061700|0.060800|-0.000900|-1.46%|
|matched-short-off/stationary/wall/diagnostic_poll_outside_timing/mean_ms|0.014483|0.013334|-0.001149|-7.93%|
|matched-short-off/stationary/wall/diagnostic_poll_outside_timing/p95_ms|0.022000|0.018700|-0.003300|-15.00%|
|matched-short-off/stationary/boundary_wall/ATTRIBUTES/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/ATTRIBUTES/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/LIGHTING/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/LIGHTING/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/RESOURCES/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/RESOURCES/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/RTXPT_FRAME/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/RTXPT_FRAME/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/TRANSLATION/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/TRANSLATION/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/VALIDATION/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/stationary/boundary_wall/VALIDATION/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/wall/simulation_frame_wall/mean_ms|1860.857933|12.337122|-1848.520811|-99.34%|
|matched-short-off/movement/wall/simulation_frame_wall/p95_ms|2484.122500|15.798900|-2468.323600|-99.36%|
|matched-short-off/movement/wall/apply_to_fresh_wall/mean_ms|1858.334894|9.895328|-1848.439567|-99.47%|
|matched-short-off/movement/wall/apply_to_fresh_wall/p95_ms|2477.880000|13.264700|-2464.615300|-99.46%|
|matched-short-off/movement/wall/submit_wall/mean_ms|1857.421711|8.875300|-1848.546411|-99.52%|
|matched-short-off/movement/wall/submit_wall/p95_ms|2477.353200|12.759900|-2464.593300|-99.48%|
|matched-short-off/movement/wall/apply_wall/mean_ms|0.079011|0.076622|-0.002389|-3.02%|
|matched-short-off/movement/wall/apply_wall/p95_ms|0.140400|0.139900|-0.000500|-0.36%|
|matched-short-off/movement/wall/attributes_wall/mean_ms|0.004956|0.005133|0.000178|+3.59%|
|matched-short-off/movement/wall/attributes_wall/p95_ms|0.009000|0.013400|0.004400|+48.89%|
|matched-short-off/movement/wall/preparation_outside_timing/mean_ms|2.463711|2.358767|-0.104944|-4.26%|
|matched-short-off/movement/wall/preparation_outside_timing/p95_ms|6.195800|6.054700|-0.141100|-2.28%|
|matched-short-off/movement/wall/diagnostic_poll_outside_timing/mean_ms|0.031128|0.041683|0.010556|+33.91%|
|matched-short-off/movement/wall/diagnostic_poll_outside_timing/p95_ms|0.061200|0.190800|0.129600|+211.76%|
|matched-short-off/movement/boundary_wall/ATTRIBUTES/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/ATTRIBUTES/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/LIGHTING/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/LIGHTING/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/RESOURCES/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/RESOURCES/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/RTXPT_FRAME/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/RTXPT_FRAME/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/TRANSLATION/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/TRANSLATION/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/VALIDATION/mean_ms|未提供|未提供|未提供|—|
|matched-short-off/movement/boundary_wall/VALIDATION/p95_ms|未提供|未提供|未提供|—|
|matched-short-off/open/wall/renderer_open_ms/mean_ms|254.134300|255.028500|0.894200|+0.35%|
|matched-short-off/initial/wall/initial_upload/mean_ms|13478.720100|23621.373000|10142.652900|+75.25%|
|matched-short-off/initial/wall/initial_upload/p95_ms|13478.720100|23621.373000|10142.652900|+75.25%|
|fast20-observation/stationary/wall/simulation_frame_wall/mean_ms|未提供|7.900783|未提供|—|
|fast20-observation/stationary/wall/simulation_frame_wall/p95_ms|未提供|18.367500|未提供|—|
|fast20-observation/stationary/wall/apply_to_fresh_wall/mean_ms|5.301843|7.785287|2.483443|+46.84%|
|fast20-observation/stationary/wall/apply_to_fresh_wall/p95_ms|11.951600|18.227300|6.275700|+52.51%|
|fast20-observation/stationary/wall/submit_wall/mean_ms|4.943170|6.469873|1.526703|+30.89%|
|fast20-observation/stationary/wall/submit_wall/p95_ms|11.566500|16.597400|5.030900|+43.50%|
|fast20-observation/stationary/wall/apply_wall/mean_ms|0.056350|0.058380|0.002030|+3.60%|
|fast20-observation/stationary/wall/apply_wall/p95_ms|0.087300|0.111300|0.024000|+27.49%|
|fast20-observation/stationary/wall/attributes_wall/mean_ms|0.001587|0.001877|0.000290|+18.28%|
|fast20-observation/stationary/wall/attributes_wall/p95_ms|0.002000|0.002800|0.000800|+40.00%|
|fast20-observation/stationary/wall/preparation_outside_timing/mean_ms|0.043523|0.043297|-0.000227|-0.52%|
|fast20-observation/stationary/wall/preparation_outside_timing/p95_ms|0.061500|0.065800|0.004300|+6.99%|
|fast20-observation/stationary/wall/diagnostic_poll_outside_timing/mean_ms|0.038820|0.053980|0.015160|+39.05%|
|fast20-observation/stationary/wall/diagnostic_poll_outside_timing/p95_ms|0.082900|0.117000|0.034100|+41.13%|
|fast20-observation/stationary/boundary_wall/ATTRIBUTES/mean_ms|0.008920|0.009607|0.000687|+7.70%|
|fast20-observation/stationary/boundary_wall/ATTRIBUTES/p95_ms|0.014500|0.017000|0.002500|+17.24%|
|fast20-observation/stationary/boundary_wall/LIGHTING/mean_ms|0.001213|0.001240|0.000027|+2.20%|
|fast20-observation/stationary/boundary_wall/LIGHTING/p95_ms|0.001600|0.002100|0.000500|+31.25%|
|fast20-observation/stationary/boundary_wall/RESOURCES/mean_ms|0.010437|0.008947|-0.001490|-14.28%|
|fast20-observation/stationary/boundary_wall/RESOURCES/p95_ms|0.017500|0.014500|-0.003000|-17.14%|
|fast20-observation/stationary/boundary_wall/RTXPT_FRAME/mean_ms|4.815223|6.326940|1.511717|+31.39%|
|fast20-observation/stationary/boundary_wall/RTXPT_FRAME/p95_ms|11.387000|16.424500|5.037500|+44.24%|
|fast20-observation/stationary/boundary_wall/TRANSLATION/mean_ms|0.049317|0.050723|0.001407|+2.85%|
|fast20-observation/stationary/boundary_wall/TRANSLATION/p95_ms|0.089400|0.095200|0.005800|+6.49%|
|fast20-observation/stationary/boundary_wall/VALIDATION/mean_ms|0.027030|0.032487|0.005457|+20.19%|
|fast20-observation/stationary/boundary_wall/VALIDATION/p95_ms|0.048600|0.066700|0.018100|+37.24%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Frame/mean_ms/N30→30|4.806260|6.312727|1.506467|+31.34%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Frame/p95_ms/N30→30|11.371100|16.413200|5.042100|+44.34%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Import/mean_ms/N30→30|0.008153|0.008383|0.000230|+2.82%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Import/p95_ms/N30→30|0.009900|0.015900|0.006000|+60.61%|
|fast20-observation/stationary/native_boundary_inclusive_wall/ResourcePlan/mean_ms/N30→30|0.253403|0.264470|0.011067|+4.37%|
|fast20-observation/stationary/native_boundary_inclusive_wall/ResourcePlan/p95_ms/N30→30|0.325800|0.418100|0.092300|+28.33%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Resources/mean_ms/N30→30|0.013927|0.013997|0.000070|+0.50%|
|fast20-observation/stationary/native_boundary_inclusive_wall/Resources/p95_ms/N30→30|0.021300|0.025900|0.004600|+21.60%|
|fast20-observation/stationary/native_boundary_self_wall/Frame/mean_ms/N30→30|4.806260|6.312727|1.506467|+31.34%|
|fast20-observation/stationary/native_boundary_self_wall/Frame/p95_ms/N30→30|11.371100|16.413200|5.042100|+44.34%|
|fast20-observation/stationary/native_boundary_self_wall/Import/mean_ms/N30→30|0.008153|0.008383|0.000230|+2.82%|
|fast20-observation/stationary/native_boundary_self_wall/Import/p95_ms/N30→30|0.009900|0.015900|0.006000|+60.61%|
|fast20-observation/stationary/native_boundary_self_wall/ResourcePlan/mean_ms/N30→30|0.239477|0.250473|0.010997|+4.59%|
|fast20-observation/stationary/native_boundary_self_wall/ResourcePlan/p95_ms/N30→30|0.310400|0.398700|0.088300|+28.45%|
|fast20-observation/stationary/native_boundary_self_wall/Resources/mean_ms/N30→30|0.005773|0.005613|-0.000160|-2.77%|
|fast20-observation/stationary/native_boundary_self_wall/Resources/p95_ms/N30→30|0.013700|0.010000|-0.003700|-27.01%|
|fast20-observation/movement/wall/simulation_frame_wall/mean_ms|未提供|10.833782|未提供|—|
|fast20-observation/movement/wall/simulation_frame_wall/p95_ms|未提供|11.673100|未提供|—|
|fast20-observation/movement/wall/apply_to_fresh_wall/mean_ms|12.502804|9.410576|-3.092229|-24.73%|
|fast20-observation/movement/wall/apply_to_fresh_wall/p95_ms|16.738600|10.145100|-6.593500|-39.39%|
|fast20-observation/movement/wall/submit_wall/mean_ms|11.302966|8.476038|-2.826928|-25.01%|
|fast20-observation/movement/wall/submit_wall/p95_ms|12.196300|8.878300|-3.318000|-27.20%|
|fast20-observation/movement/wall/apply_wall/mean_ms|0.081405|0.077548|-0.003857|-4.74%|
|fast20-observation/movement/wall/apply_wall/p95_ms|0.124400|0.132300|0.007900|+6.35%|
|fast20-observation/movement/wall/attributes_wall/mean_ms|0.003993|0.003649|-0.000344|-8.61%|
|fast20-observation/movement/wall/attributes_wall/p95_ms|0.008500|0.007800|-0.000700|-8.24%|
|fast20-observation/movement/wall/preparation_outside_timing/mean_ms|1.540141|1.338902|-0.201239|-13.07%|
|fast20-observation/movement/wall/preparation_outside_timing/p95_ms|2.985400|2.644200|-0.341200|-11.43%|
|fast20-observation/movement/wall/diagnostic_poll_outside_timing/mean_ms|0.052932|0.066964|0.014032|+26.51%|
|fast20-observation/movement/wall/diagnostic_poll_outside_timing/p95_ms|0.081400|0.096300|0.014900|+18.30%|
|fast20-observation/movement/boundary_wall/ATTRIBUTES/mean_ms|0.037077|0.035279|-0.001797|-4.85%|
|fast20-observation/movement/boundary_wall/ATTRIBUTES/p95_ms|0.028700|0.025800|-0.002900|-10.10%|
|fast20-observation/movement/boundary_wall/LIGHTING/mean_ms|0.017842|0.018104|0.000263|+1.47%|
|fast20-observation/movement/boundary_wall/LIGHTING/p95_ms|0.129700|0.136900|0.007200|+5.55%|
|fast20-observation/movement/boundary_wall/RESOURCES/mean_ms|0.713761|0.579178|-0.134583|-18.86%|
|fast20-observation/movement/boundary_wall/RESOURCES/p95_ms|1.580100|1.195900|-0.384200|-24.31%|
|fast20-observation/movement/boundary_wall/RTXPT_FRAME/mean_ms|9.028354|6.387789|-2.640565|-29.25%|
|fast20-observation/movement/boundary_wall/RTXPT_FRAME/p95_ms|10.258800|7.502600|-2.756200|-26.87%|
|fast20-observation/movement/boundary_wall/TRANSLATION/mean_ms|1.363203|1.301928|-0.061276|-4.49%|
|fast20-observation/movement/boundary_wall/TRANSLATION/p95_ms|2.166100|2.222400|0.056300|+2.60%|
|fast20-observation/movement/boundary_wall/VALIDATION/mean_ms|0.042468|0.063973|0.021504|+50.64%|
|fast20-observation/movement/boundary_wall/VALIDATION/p95_ms|0.062600|0.178900|0.116300|+185.78%|
|fast20-observation/movement/native_boundary_inclusive_wall/Acceleration/mean_ms/N3→3|16.193300|17.517367|1.324067|+8.18%|
|fast20-observation/movement/native_boundary_inclusive_wall/Acceleration/p95_ms/N3→3|16.367800|21.070900|4.703100|+28.73%|
|fast20-observation/movement/native_boundary_inclusive_wall/BlasBuild/mean_ms/N183→183|0.249164|0.268353|0.019189|+7.70%|
|fast20-observation/movement/native_boundary_inclusive_wall/BlasBuild/p95_ms/N183→183|0.437600|0.533700|0.096100|+21.96%|
|fast20-observation/movement/native_boundary_inclusive_wall/BufferUpload/mean_ms/N17→16|0.018647|0.036319|0.017672|+94.77%|
|fast20-observation/movement/native_boundary_inclusive_wall/BufferUpload/p95_ms/N17→16|0.060700|0.217300|0.156600|+257.99%|
|fast20-observation/movement/native_boundary_inclusive_wall/Frame/mean_ms/N180→180|9.017754|6.379559|-2.638196|-29.26%|
|fast20-observation/movement/native_boundary_inclusive_wall/Frame/p95_ms/N180→180|10.249100|7.489700|-2.759400|-26.92%|
|fast20-observation/movement/native_boundary_inclusive_wall/Import/mean_ms/N180→180|1.423975|0.837933|-0.586042|-41.16%|
|fast20-observation/movement/native_boundary_inclusive_wall/Import/p95_ms/N180→180|1.678900|1.266300|-0.412600|-24.58%|
|fast20-observation/movement/native_boundary_inclusive_wall/ResourceIndex/mean_ms/N97→97|0.137931|0.133895|-0.004036|-2.93%|
|fast20-observation/movement/native_boundary_inclusive_wall/ResourceIndex/p95_ms/N97→97|0.878500|0.785400|-0.093100|-10.60%|
|fast20-observation/movement/native_boundary_inclusive_wall/ResourcePlan/mean_ms/N180→180|5.905886|3.015308|-2.890578|-48.94%|
|fast20-observation/movement/native_boundary_inclusive_wall/ResourcePlan/p95_ms/N180→180|7.107700|3.602900|-3.504800|-49.31%|
|fast20-observation/movement/native_boundary_inclusive_wall/Resources/mean_ms/N180→180|5.109374|2.222044|-2.887330|-56.51%|
|fast20-observation/movement/native_boundary_inclusive_wall/Resources/p95_ms/N180→180|6.478300|2.795300|-3.683000|-56.85%|
|fast20-observation/movement/native_boundary_inclusive_wall/SceneFullRefresh/mean_ms/N97→3|2.526520|36.758333|34.231814|+1354.90%|
|fast20-observation/movement/native_boundary_inclusive_wall/SceneFullRefresh/p95_ms/N97→3|2.469300|38.496800|36.027500|+1459.02%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseQueryPreparation/mean_ms/N20→20|0.090960|0.092100|0.001140|+1.25%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseQueryPreparation/p95_ms/N20→20|0.102300|0.102200|-0.000100|-0.10%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseRegions/mean_ms/N20→20|0.000550|0.000510|-0.000040|-7.27%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseRegions/p95_ms/N20→20|0.000700|0.000600|-0.000100|-14.29%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseUpload/mean_ms/N20→20|0.236735|0.221710|-0.015025|-6.35%|
|fast20-observation/movement/native_boundary_inclusive_wall/SparseUpload/p95_ms/N20→20|0.295200|0.232400|-0.062800|-21.27%|
|fast20-observation/movement/native_boundary_inclusive_wall/TlasAllocate/mean_ms/N3→3|0.418233|0.505200|0.086967|+20.79%|
|fast20-observation/movement/native_boundary_inclusive_wall/TlasAllocate/p95_ms/N3→3|0.435800|0.537200|0.101400|+23.27%|
|fast20-observation/movement/native_boundary_self_wall/Acceleration/mean_ms/N3→3|0.576033|0.642633|0.066600|+11.56%|
|fast20-observation/movement/native_boundary_self_wall/Acceleration/p95_ms/N3→3|0.601700|0.743000|0.141300|+23.48%|
|fast20-observation/movement/native_boundary_self_wall/BlasBuild/mean_ms/N183→183|0.249164|0.268353|0.019189|+7.70%|
|fast20-observation/movement/native_boundary_self_wall/BlasBuild/p95_ms/N183→183|0.437600|0.533700|0.096100|+21.96%|
|fast20-observation/movement/native_boundary_self_wall/BufferUpload/mean_ms/N17→16|0.018647|0.036319|0.017672|+94.77%|
|fast20-observation/movement/native_boundary_self_wall/BufferUpload/p95_ms/N17→16|0.060700|0.217300|0.156600|+257.99%|
|fast20-observation/movement/native_boundary_self_wall/Frame/mean_ms/N180→180|9.017754|6.379559|-2.638196|-29.26%|
|fast20-observation/movement/native_boundary_self_wall/Frame/p95_ms/N180→180|10.249100|7.489700|-2.759400|-26.92%|
|fast20-observation/movement/native_boundary_self_wall/Import/mean_ms/N180→180|1.423975|0.837933|-0.586042|-41.16%|
|fast20-observation/movement/native_boundary_self_wall/Import/p95_ms/N180→180|1.678900|1.266300|-0.412600|-24.58%|
|fast20-observation/movement/native_boundary_self_wall/ResourceIndex/mean_ms/N97→97|0.134663|0.127904|-0.006759|-5.02%|
|fast20-observation/movement/native_boundary_self_wall/ResourceIndex/p95_ms/N97→97|0.868100|0.775900|-0.092200|-10.62%|
|fast20-observation/movement/native_boundary_self_wall/ResourcePlan/mean_ms/N180→180|0.526623|0.501307|-0.025316|-4.81%|
|fast20-observation/movement/native_boundary_self_wall/ResourcePlan/p95_ms/N180→180|0.699700|0.703900|0.004200|+0.60%|
|fast20-observation/movement/native_boundary_self_wall/Resources/mean_ms/N180→180|2.323886|0.771472|-1.552414|-66.80%|
|fast20-observation/movement/native_boundary_self_wall/Resources/p95_ms/N180→180|3.013600|1.459000|-1.554600|-51.59%|
|fast20-observation/movement/native_boundary_self_wall/SceneFullRefresh/mean_ms/N97→3|2.526520|36.758333|34.231814|+1354.90%|
|fast20-observation/movement/native_boundary_self_wall/SceneFullRefresh/p95_ms/N97→3|2.469300|38.496800|36.027500|+1459.02%|
|fast20-observation/movement/native_boundary_self_wall/SparseQueryPreparation/mean_ms/N20→20|0.090960|0.092100|0.001140|+1.25%|
|fast20-observation/movement/native_boundary_self_wall/SparseQueryPreparation/p95_ms/N20→20|0.102300|0.102200|-0.000100|-0.10%|
|fast20-observation/movement/native_boundary_self_wall/SparseRegions/mean_ms/N20→20|0.000550|0.000510|-0.000040|-7.27%|
|fast20-observation/movement/native_boundary_self_wall/SparseRegions/p95_ms/N20→20|0.000700|0.000600|-0.000100|-14.29%|
|fast20-observation/movement/native_boundary_self_wall/SparseUpload/mean_ms/N20→20|0.236735|0.221710|-0.015025|-6.35%|
|fast20-observation/movement/native_boundary_self_wall/SparseUpload/p95_ms/N20→20|0.295200|0.232400|-0.062800|-21.27%|
|fast20-observation/movement/native_boundary_self_wall/TlasAllocate/mean_ms/N3→3|0.418233|0.505200|0.086967|+20.79%|
|fast20-observation/movement/native_boundary_self_wall/TlasAllocate/p95_ms/N3→3|0.435800|0.537200|0.101400|+23.27%|
|fast20-observation/open/wall/renderer_open_ms/mean_ms|259.700700|258.465500|-1.235200|-0.48%|
|fast20-observation/initial/wall/initial_upload/mean_ms|13595.141800|13558.629500|-36.512300|-0.27%|
|fast20-observation/initial/wall/initial_upload/p95_ms|13595.141800|13558.629500|-36.512300|-0.27%|

所有wall退化照列；完整CPU原始分布及对照差值保存在data/streaming-scene-results.json，但量化粒度使短scope的0和细微差值不能当作精确CPU结论。完整新包没有匹配完成的旧长窗，不能用短窗数值填入。fast20静止下降与移动改善同时保留，不归因未经证明的外部负载。

53项审计分类仍为24活跃/29不可达旧glue清理；原图registry/reindex、scene表上传粒度、exact-size TLAS及真正材质definition完整导入仍未等价恢复。正确性通过、原版源码纯度及本报告均不能替代消费方画面/整体性能/无后编译验收。版本保持8.0.0，正式GitHub全量→Maven Local身份仍待实际完成，本文不预签Local通过。

## 最终包与发布状态

最终打包wall120.110s、Job提交峰1.609GB，398项检查通过；150预编译条目／236source条目，JAR内ZIP=独立ZIP、包内DLL=受测DLL，热缓存未入包。完整摘要见data/streaming-scene-results.json的finalPackageVerification和precompiled-runtime.md。此为最终包验证，GitHub推送及Maven Local仍待实际执行，不提前记成功。
