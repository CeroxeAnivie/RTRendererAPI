# 动态资源与光照属性更新

版本：7.1.3。日期：2026-11-04。

## 审查结论

审核现有实现后确认：局部 mesh 替换触发全属性清空、重排和逐 geometry JNI，布局失效时校验重复扫描全场景；动态纹理与几何仍经过 PNG/glTF 文件往返，普通资源事务无条件清空设备队列。两项均为通用 API 能力缺口。消费方 JFR 的比例属于采样，资源阶段总耗时不能全部算作 device idle，180 FPS 需要最终场景实测。

## 修复范围

保留未变属性，primitive 分组与批量 JNI，常驻身份直接校验，稳定 GPU 属性区间与 GPU 扩容拷贝；内存资源事务、纹理 mip/矩形 patch、版本化资源与完成事件退休；原 SceneTransaction 入口直接使用增量路径，保留 RR、角色、baseline 与颜色语义。

资源提交沿用 `SceneTransaction`，消费方无需更换普通入口。细节、性能边界和验收证据见[仓库动态资源报告](https://github.com/CeroxeAnivie/RTRendererAPI/blob/main/docs/quality/dynamic-resources.md)。

## 输入与事务

```java
TexturePatch patch = new TexturePatch(texture.id(), 1, 2, 3, 4, 2, 20, pixels);
TextureAsset next = texture.patched(patch);
renderer.apply(SceneTransaction.builder(nextRevision).upsert(next).build());
texture = next;
```

`pixels` 的当前位置到 limit 在 `TexturePatch` 构造时复制，调用后可复用。row pitch 单位为字节；最后一行无需包含行尾 padding。patch 只覆盖指定 mip 的矩形，其他 mip、色彩空间、寻址和过滤设置保持原值。

`patched` 生成不可变全纹理快照，并记录其直接父 generation。每次提交的 patch 必须以当前已接受的纹理为基础。旧 generation、已删除 ID、越界和行距不合法会被拒绝。显式全量 TextureAsset upsert 用于尺寸／格式更换和完整快照恢复。

所有 renderer 写操作在创建它的 owner 线程调用。普通复制资产遵守原有不可变快照契约；选择 immutable-direct 输入时，仍须放弃全部可写别名，并使其外部内存在资产完整生命周期内有效。私有 JNI packet 的数据在 native 调用内复制或上传录制，native 不保留 JVM 地址。

资源／输出容量不足时，`trySubmit` 返回 `SCENE_UPDATE_BACKLOG`／`FRAME_RING_FULL`，保留 sequence，消费方在归还 lease、GPU 完成后重试同一请求。容量不足不能通过循环调用 `awaitIdle` 绕过。显式 awaitIdle、resize、reset 和关闭的必要等待与普通事务分开。

Java 在接受 apply 前验证引用、格式和资产预算，失败后可以更正并重试。进入 native 提交后若设备或上传失败，普通 renderer 关闭 session，避免继续呈现半个 generation；需重建 renderer 并重新提交完整快照。

## GPU 生命周期与工作量

稳定材质分组缓存、primitive 批量 JNI 和身份直接索引替代逐角点装箱及全场景查找。未变 GPU 属性区间保留；扩容通过 GPU 拷贝迁移，普通替换不清空全表。

内存 mesh 采用可转换读写状态的顶点／索引 buffer，并复用已完成的同容量分配；相同 BLAS 构建布局复用已完成的 AS 分配，仍以 `PreferFastTrace` 重建，保持相交质量。兼容布局保留 geometry 索引、实例绑定和 TLAS 分配，GPU TLAS 元数据只上传变化条目。真实拓扑变化仍允许重新组织完整表，并由诊断单列。

graphics 和 compute 完成事件均满足后才能复用旧 generation。命令对象也随退休队列保留，因为其拥有上传和 scratch 存储。资源退休最多 16 代，命令池最多 8 个；mesh buffer／BLAS 备用池各最多 64 项、各 64 MiB；纹理备用池最多 128 项、32 MiB。单个私有输入 packet 上限 512 MiB。这里列的是各池容量，不是总显存实测峰值。

重新绑定内容未变时缓存 native lighting／RR resolve／紧凑输出的 binding set；绑定 handle、layout 或 subresource 变化即重建。RGB-only 动画不重建 coverage BLAS，alpha 变化只处理真实依赖，相同像素不重复上传。

完整 beauty 路径在内存拓扑变化后同步 OMM 调试表的容量；该路径仍保留自己的光照、降噪和重建配置。缺少切线且法线为零的输入采用有限值回退，避免导入时产生 NaN。

空场景的 TLAS 用一个不可命中的空实例完成初始化，避免向驱动提交零条实例构建时挂起。逻辑场景仍为零实例，背景、DI／GI 配置与实例恢复语义保持原值。

shader SDK ABI 已变化，离线扩展必须使用新包 SDK 重编译。旧 manifest 不应通过手改 hash 绕过校验。
