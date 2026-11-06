# 7.2.0-rc.4：增量区域维护与完整慢帧候选

此版本只发布 Maven Local，供真实宿主验收；尚未证明移动 FPS 或长帧收益，不是正式 7.2.0。保留 rc.1～rc.3 制品与原始证据。

区域索引沿权威脏节点的祖先路径更新，复用 epoch 标记；覆盖格变化只维护新旧集合的差集，保留交集的根链接、列表块和哈希槽位。全量初始化、容量增长、tombstone 压缩以及超预算的精确回退仍保留。光源、可见性、GI、真实 RR、分辨率和计时边界不变。

慢帧协议 v6 独立保留最近四组触发树，每组上限 65,536 个事件。记录不可变 root/native-frame scope，完整组描述包含触发序号、帧序号、revision、预期/保留/丢失事件数；监视器按触发 cursor 读取所有尚未消费组，滚动窗口覆盖和慢帧组覆盖分别计数。旧 `lastSlowFrame` accessor 保留最新一组语义。Snapshot 所需内存在记录锁外分配，再检查容量；不在提交记录锁内扩大分配。

新增 SDK 图刷新、完整表准备、拓扑回退原因、mesh 准备、material/geometry/instance 行与表上传、sparse query 准备 scope。`SCENE_TOPOLOGY_FALLBACK.reason` 位为：1 非 bindless、2 skinning、4 geometry 容量增长、8 instance 容量增长、16 material 容量增长、32 buffer 目标缺失/替换、64 CPU/上传镜像容量不一致、128 显式失效 epoch。缩容本身不会触发容量增长回退；真实宿主尖峰仍须按新日志定位，不延用旧总包归因。

定向构建、CPU/Java ABI、区域 oracle、隐藏 native owner/monitor、raw/GI RR 输出对照及制品身份记录保存在 `build/reports/resource-root-followup/rc4-check/` 与 `rc4-package/`。正确性不等于性能通过，真实宿主同入口 FPS、P95/P99 与长帧仍由 MC 线验收。

四模块已发布 Maven Local，21 项制品身份检查通过。API/core/nvidia 三个主 JAR 与 rc.3 字节一致；NVIDIA 原生 bridge 沿用已验版本，封装闭包检查通过，没有重建。RTXPT 主 JAR SHA256 为 `68b8d73c053c159be4c3f2183383c36df8c0e272406ce070f58feaa5193a1410`，runtime ZIP 为 `9190e0779b1f04e8cc67793539e567e532fdde2fb15a177633e4189320668b96`，DLL 为 `0224b05d1551e0f415073aa8b548948d204da77d1cc88c12d8fd84942cd4ba4e`，匹配 PDB 为 `8ae1b615a7637f71bef5619e5d611c1b2f6efb287d06b4c472fe6ed0b6e91c38`。完整收据为 `build/reports/resource-root-followup/rc4-package/receipt.json`。

本 DLL 的 CPU 诊断/Java v6 ABI、25,000 源/2,198 位置区域 oracle、隐藏 owner 与跨线程 monitor 检查通过。raw/GI 及 192×128 真实 RR 各 54 份 HDR 与 rc.3 参考逐字节一致。检查初始 GPU 为 39%，这些检查只判断正确性，没有据其耗时声称性能收益。首轮 monitor 直接使用开发 bin，因缺打包 shader 失败；改用锁定的完整 runtime 后通过。Maven 初次调用未设齐 NVIDIA 工具链路径而失败，补齐已有路径、复用原生 bridge 后成功；原失败日志保留。
