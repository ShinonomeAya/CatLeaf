# CatLeaf 0.1.23 发布说明

**0.1.23（code24）为公开测试版（Pre-release），最低Android10。**

### 本版内容

接通“设置→检查更新”：手动读取官方GitHub版本清单，显示新版和更新说明，按“前往下载”在系统浏览器获取APK。不需要配置GitHub账号，不在后台检查，也不自动下载或安装。

0.1.22及更早版本的清单地址为空，首次请从本页手动下载安装0.1.23；以后可通过App检查更新。网络不可达会显示失败，可稍后重试。

### 下载与安装

- 安装文件：`catleaf-0.1.23-code24.apk`，28,641,842bytes。
- 包名：`dev.local.catleaf.nativeapp`。同签名覆盖升级，保留原数据；不要先卸载或清除数据。
- APK SHA-256：`823b99f5c75ed9b97c0888aa0fd6e186f33231335b25984e2adea574d1d77a3a`。
- 签名证书SHA-256：`5f782c4afaf517286d0f5f2a2bd6d8d9f9e2ef9d1cc27b5d4084c65ad64d3f73`。

### 使用说明与后续事项

RSS、全文和图片仍受原站及网络限制；离线只保证已成功保存的内容。AI是可选的手动请求，可能产生费用。[使用指南](https://github.com/ShinonomeAya/CatLeaf/blob/main/docs/14-本机使用与实例帮助.md)。

完整兼容、性能、无障碍与数据恢复验证继续在[后续验证 #2](https://github.com/ShinonomeAya/CatLeaf/issues/2)跟踪，不将公开测试版当作正式验收通过。

<details>
<summary>本版实际验证范围与限制</summary>

最终源码194个单元测试通过，Debug／Release构建成功，lint0错误／27警告／2提示。Android10隔离模拟器7项更新专项通过，包含无凭据读取、包名／发布状态／格式与大小校验、网络失败／超时／跳转阻断、真实官方清单读取，以及弹窗重试／关闭。

Android16手机同签名保数据升级及真实清单成功、断网失败、恢复网络重试、关闭与系统返回已验证；原6个订阅、1篇收藏保持，系统设置已恢复。浏览器下载／未知来源授权／系统点击安装全流程继续在[更新后续 #1](https://github.com/ShinonomeAya/CatLeaf/issues/1)跟踪。本次没有重跑全量文章、AI、性能与恢复矩阵，完整G7与G8/L09仍未通过。

</details>

### 反馈与项目来源

维护者：[ShinonomeAya](https://github.com/ShinonomeAya)。[普通反馈](https://github.com/ShinonomeAya/CatLeaf/issues/new/choose)／[私人漏洞报告](https://github.com/ShinonomeAya/CatLeaf/security/advisories/new)，请勿公开密钥或私人数据。

后端基于[Sallyn0225/gemini-rss-app](https://github.com/Sallyn0225/gemini-rss-app)继续开发，保留MIT与`Copyright (c) 2025 su-kaka`；原生客户端采用Kotlin／Jetpack Compose，见[项目来源／开源许可](https://github.com/ShinonomeAya/CatLeaf#项目来源开源许可)。
