# OpenGL → RTXPT 主表面输入

`GlHostSurfaceWriter` 是输入方向的通用 GPU writer，与输出方向的 GPU consumer 配合使用。在当前 GL 4.5+ 上导入 API 分配的八张（分拆）或十张（底图增强）D3D12 纹理和共享 fence，核对 LUID，完整执行 previousDone wait → markImported → GPU 写 → hostReady signal／flush → publish。每个 writer 对应一个 renderer 生命周期；不关闭借用的 Windows handles。

用 `RtxptRenderers.openHostSurface(new HostSurfacePlan(width,height))` 可直接取得默认优选 RR 的分拆传输配置；增强使用 `openHostSurface(plan, augmentation)`。专家从 `hostSurfaceConfiguration(plan.composition()).copyBuilder()` 派生，再传完整配置。快捷工厂只减少配置准备，不隐藏以下 GPU 所有权责任，见[普通用法与专家控制](../../docs/ordinary-and-expert.md)。

```java
var input = renderer.extension(HostSurfaceControl.class).orElseThrow();
try (var writer = new GlHostSurfaceWriter()) {
    // renderer 已发布常驻场景；此帧 request 随后原样交给 trySubmit。
    var pending = input.acquireHostSurfaceInput(request, resourceGeneration);
    if (pending.isPresent()) {
        writer.writeCard(pending.get(), GlHostSurfaceWriter.Card.unit(
            instanceId, (float) request.width() / request.height()));
        var submission = renderer.trySubmit(request);
        // 若 deferred：保留 request，后续仅重试 trySubmit；不重新申请／发布输入。
    }
}
```

`writeCard` 只示范主表面生成：相机 `(0,0,3)`、FOV 52°、z=0 单位平板、法线 +Z、静止 motion=0。常驻 `MeshAsset` 须为顶点 `(-1,-1,0),(1,-1,0),(1,1,0),(-1,1,0)`，索引 `0,1,2,0,2,3`；公开 `SceneInstance.id` 与 `instanceId` 一致。卡片外显式背景；有效像素写入公开 64 位 instance ID 及原始三角形序号。使用 `HostSurfacePlan(width,height)` 选择默认分拆表面光照。

底图增强使用 `HostSurfacePlan.augmentLitBase(...)`；参考卡片必须显式传入两个附加平面的值，不能用 albedo 代替已照亮底图：

```java
writer.writeCard(lease, card, new GlHostSurfaceWriter.AugmentationInputs(
    localBaseline, skyBaseline, visibility, fog,
    hostLitRed, hostLitGreen, hostLitBlue, hostAlpha));
```

附加值在此 fixture 内作为整张图的常量写入，仅用于隔离验证；底图 RGB 是最终 fog、HUD 等叠加之前的 diffuse 层，alpha 为 straight alpha。B／S／visibility／fog／alpha 在 [0,1]，RGB 有限、非负且不超过 RGBA16F 的 65504。颜色域由 `HostSurfaceAugmentation` 显式声明，输入之间须一致。增强卡片分两次 compute dispatch：基础八张使用 image unit 0…7，附加两张复用 0／1，不额外要求十个同时可写 image unit。

真实宿主用 `writer.write(lease, packet -> { ... })` 替换参考卡片 compute：在回调内按本帧 `packet.frame().planes()`／`packet.textures()` 将实际 raster 结果 GPU 拷贝／计算到全部输入平面。分拆没有附加平面，不能遍历全部枚举假设其总是存在。输入位置与法线均为世界空间；motion 是顶端原点、当前到上一帧的输入像素位移。GL image 单元／program 状态由示例使用，后续宿主 pass 自行恢复。

生产 writer 不读回像素，也不做 CPU fence wait。仅测试 `GlHostSurfaceWriterSelfTest` 做显式诊断读回，分别核对两种布局的四种精确格式、八／十平面值、数值 flags、64 位身份和原始 triangle；增强底图值刻意不同于 albedo。此测试验证 GPU writer shader，D3D12 共享同步由真实集成验收。Gradle 入口 `:renderer-rtxpt:hostSurfaceWriterSelfTest`，窗口始终隐藏。

输入 ABI、取消／重试／退休、预算与诊断契约见 [输入说明](../../docs/rtxpt-710-host-surface-input.md)。writer 测试与真实传输、GPU 回传／合成分别验收；当前证据与尚未完成的最终发布包项目见[验收矩阵](../../docs/rtxpt-710-acceptance.md)。
