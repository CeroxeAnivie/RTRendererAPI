# 外部 shader 与 SDK 边界

本文面向从源码构建或扩展适配层的开发者。直接使用发布 JAR 的应用不需要安装 RTXPT SDK 或运行 shader 构建工具。

## 扩展方式

官方 RTXPT 源码导出与核心 shader 保持只读。独立视觉扩展位于外部桥接层，使用实际可调用的公开入口；独立输出与原版输出分别说明。

自有 shader 位于 `renderer-rtxpt/src/main/cpp`，入口清单为同目录 `AdapterShaders.cfg`。`tools/Build-AdapterShaders.ps1` 使用固定上游 ShaderMake、DXC 和 include，输出到运行时 `bin/AdapterShaders`。不把扩展复制到官方源树或改写原版 shader 清单。

外部 pass 使用独立 ShaderFactory base、pipeline layout 和必要元数据。纹理过滤使用 Donut／NVRHI sampler；不占用上游私有字段表达自定义元数据。没有可执行扩展入口的能力不能仅凭声明使用。

## 构建与分发

- 路径、工具与构建入口见[源码工具](../tools/README.md)。
- 扩展编译与布局/ABI 校验在构建期完成；发布包不通过首次启动或配置变化触发源码编译。
- 运行时保留官方 `bin` 与相邻 `Assets` 布局，独立 shader 放在 `AdapterShaders`。
- 扩充预编译组合需要重新构建与分发；清单未覆盖的配置明确失败，不回退运行时编译。

旧 native-lighting-sdk.json、bundle.json、六档私有 shader ABI 与旧上游 overlay 不是 8.0.0 扩展契约。运行时启动和目录说明见[运行时分发](runtime-distribution.md)。
