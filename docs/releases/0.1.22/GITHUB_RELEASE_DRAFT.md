# CatLeaf 0.1.22 发布说明

本文件与[已发布的 Pre-release](https://github.com/ShinonomeAya/CatLeaf/releases/tag/v0.1.22)说明同步。

CatLeaf（猫页）是一款原生Android RSS阅读器，默认在本机使用，无需注册。

**0.1.22（code23）为公开测试版（Pre-release），最低Android10。完整发布验证仍在继续。**

### 本版内容

支持订阅与分组、全部订阅阅读和继续加载、日期／未读筛选、搜索、全文提取、收藏、单篇图文离线，以及手动发起的AI双语对照和日摘要。

本版修正底部选中Tab文字残留的默认紫色。标题和正文支持同页双语对照，顶部功能区及原文／双语按钮保持固定，译文状态与重新翻译说明随文章滚动。

### 下载与安装

- 安装文件：`catleaf-0.1.22-code23.apk`，28,641,758bytes。
- 包名：`dev.local.catleaf.nativeapp`。已有同签名版本可覆盖安装，保留数据。
- APK SHA-256：`2c66894c1c5a3298b268f42e5af52f07f66944be37fc0591c9fe902bf3d592ea`。
- 签名证书SHA-256：`5f782c4afaf517286d0f5f2a2bd6d8d9f9e2ef9d1cc27b5d4084c65ad64d3f73`。

### 已知限制与后续事项

- App在线检查更新服务尚未接通，目前请从Releases获取版本，进度见[更新服务接入 #1](https://github.com/ShinonomeAya/CatLeaf/issues/1)。
- RSS通常只提供近期文章；部分订阅、全文和图片受原站、登录与网络限制。离线只保证成功保存的内容。
- AI请求由用户手动发起，会发送所选文章文字给配置的供应商，可能产生费用。
- 完整兼容、性能、无障碍与数据恢复验证仍在继续，跟踪见[测试版后续验证 #2](https://github.com/ShinonomeAya/CatLeaf/issues/2)。这表示证据尚未补齐，不等于已经发现故障。

<details>
<summary>本版实际验证范围与待验证项</summary>

最终源码的194个单元测试通过，Debug／Release构建成功；lint为0错误、27警告、2提示。

Android16测试手机完成同签名保数据升级、浅／深主题大字定向检查、全部订阅200→400→600继续加载、国家地理图文及返回、旧离线包断网强停重开读取正文与抽查图片；原订阅、收藏和离线包保留。

本次定向验证不代表完整验收。Android10完整矩阵、大规模实体性能／图片内存、完整TalkBack／键盘、凭据升级／系统文件导入导出／恢复故障，以及最终主动离线预算失败保护等仍待验证。完整G7未通过，G8/L09未正式交付；详细记录见[验收摘要](https://github.com/ShinonomeAya/CatLeaf/blob/main/docs/validation/验收记录.md)。

</details>

### 反馈与项目来源

维护者：[ShinonomeAya](https://github.com/ShinonomeAya)。普通问题使用[Issues](https://github.com/ShinonomeAya/CatLeaf/issues/new/choose)，安全问题使用[私人漏洞报告](https://github.com/ShinonomeAya/CatLeaf/security/advisories/new)，不要公开密钥或私人数据。

后端基于[Sallyn0225/gemini-rss-app](https://github.com/Sallyn0225/gemini-rss-app)继续开发，保留MIT许可及`Copyright (c) 2025 su-kaka`；原生客户端采用Kotlin／Jetpack Compose，准确说明见[项目来源／开源许可](https://github.com/ShinonomeAya/CatLeaf#项目来源开源许可)。
