# 7.2.0-rc.6：有界诊断快照与资源等待取证候选

rc.5 的区域整池重包与超大 sparse staging 请求已经收敛。真实宿主仍观察到新的等待位置：合法几何表增长时首次上传前77.302ms、刷新后的38.0115ms尾部，以及另一次材料首行上传前23.0341ms。现有JFR排除了I1邻近GC暂停，但还不能把等待归因到分配、诊断锁或调度，也不能据此断言rc.5引入新bug。

本候选把默认buffer创建分为整体、committed API调用和postCreate；CPU三表及OMM数组resize记录旧/新行数、表种类和总字节；材料行准备记录常量buffer新建/复用，并以相同名称hash关联默认GPU分配。SCENE_CPU_RESIZE的items/tasks分别是旧/新行数，reason1/2/3/4分别为geometry/material/instance/OMM。

诊断ring和完整slow树快照每次持锁复制不超过64个事件（13.5KiB），块间让出线程；元数据/active/submission快照另有固定上界。覆盖时丢弃失效前缀并核对session、ordinal和trigger身份，不拼接不同慢树。慢树触发时的既有留存复制仍存在，其锁持有时间现在可测，不能宣称所有诊断锁内复制都已消除。

DIAGNOSTIC_MUTEX_WAIT/HOLD记录超过100µs的争用等待及重点锁持有，reason1–8为scope进入、scope退出、独立记录、上下文更新、快照元数据、ring复制、slow复制、快照结束；items是相关stage编号，bytes是复制片段上界。它们是与父阶段重叠的归因记录，不能再加到总耗时，也没有从原计时边界删除等待。OFF不继续测时或写事件。

独立native lighting路径没有beauty OMM debug表消费者，停止随每次拓扑增长扩容它；若之后进入beauty路径，先恢复该表。射线、阴影、稀疏光源语义、GI、真实RR、输出分辨率和同帧要求保持原样。

诊断CPU/Java、受控锁归因、快照块间并发覆盖与实际DLL monitor通过；24帧源增删移动、同帧身份、1MiB写入上限通过，观察到默认GPU创建38次/CPU resize6次。唯一OMM refresh是step0的空场景（geometry0/oldRows0），没有扩容；初次夹具误把这条空刷新当作分配，失败日志保留，修正夹具后通过，生产未为此修改。raw/GI与真实RR各54份HDR均与rc.5逐字节一致，RR为192×128。GPU初始3%，这些是正确性验证。

诊断三scope小夹具DEEP P50为历史875.025→本次1046.065ns，每submit增加171.040ns（+19.55%）；非同期配对，不是宿主FPS。native/Java/GPU正确性Job峰值分别2.919/1.078/2.923GiB。只作为Maven Local候选，rc.5仍保留；真实宿主FPS和长尾收益随后由MC线验证。
