# CatLeaf 0.1.24 · 界面对齐

**0.1.24（code25）为公开测试版（Pre-release），支持 Android 10 及以上。**

### 本版更新

- 对齐阅读、收藏与设置页的边距，修正窄屏下状态说明与列表位置不一致。
- 统一阅读／收藏列表标题和说明文字的排版。
- 调整底部导航高度，并适配系统大字号。

订阅、收藏、缓存和离线副本沿用现有存储方式，本版没有数据库迁移或新增权限。

### 下载与安装

- 安装文件：`catleaf-0.1.24-code25.apk`，28,641,842 bytes。
- 包名：`dev.local.catleaf.nativeapp`。请使用同签名安装包直接覆盖升级，保留原数据；不要先卸载或清除数据。
- APK SHA-256：`0b79af94227a7387440f614e00fa6182e95d03d63947a6057adac2f63ae72b8e`。
- 签名证书 SHA-256：`5f782c4afaf517286d0f5f2a2bd6d8d9f9e2ef9d1cc27b5d4084c65ad64d3f73`。

0.1.23 及以上版本可在“设置 → 检查更新”查看新版，下载仍由系统浏览器处理。0.1.22 及更早版本请从本页手动下载安装。

### 使用说明与后续事项

RSS、全文和图片受原站及网络限制；离线只保证已成功保存的内容。AI 双语和日摘要是可选的手动请求，可能产生费用。普通阅读无需配置 AI，详见[使用指南](https://github.com/ShinonomeAya/CatLeaf/blob/main/docs/14-本机使用与实例帮助.md)。

完整兼容、性能、无障碍与恢复验证继续在[后续验证 #2](https://github.com/ShinonomeAya/CatLeaf/issues/2)跟踪；本版仍为测试版。

<details>
<summary>本版实际验证范围与限制</summary>

本轮最终源码194项单元测试通过，Debug／Release构建成功，lint0错误／27警告／2提示。Android10隔离模拟器完成360／411dp × 系统字号1.0／1.3 × 深浅八组根页、空收藏及订阅／日期面板核对；本轮前后共80张实际截图，边距与触摸区通过定向验证。

更新五态及重试／关闭、关于文档、目录配置、添加后导航和同页双语滚动在隔离环境定向复测。双语使用本地缓存及假响应，没有请求收费AI。Android16测试手机使用同签名Release保数据升级，浅色常规／深色大字的根页、两类面板、阅读偏好、原收藏正文图片、滚动及返回通过；安装包哈希匹配，系统设置恢复。

本轮未重跑全量40页、规模性能、完整TalkBack／键盘、恢复与完整升级矩阵。测试手机没有双语缓存，不以原文复测代替真机收费翻译验收。完整G7与G8/L09仍未通过；浏览器下载、未知来源授权及系统点击安装整链继续在[更新后续 #1](https://github.com/ShinonomeAya/CatLeaf/issues/1)跟踪。

</details>

维护者：[ShinonomeAya](https://github.com/ShinonomeAya)。[反馈问题](https://github.com/ShinonomeAya/CatLeaf/issues/new/choose)／[私人漏洞报告](https://github.com/ShinonomeAya/CatLeaf/security/advisories/new)，请勿公开密钥或私人数据。

项目来源及MIT许可见[项目来源／开源许可](https://github.com/ShinonomeAya/CatLeaf#项目来源开源许可)。
