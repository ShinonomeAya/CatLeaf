# CatLeaf 0.1.22 测试候选发布草稿

本文件保留发布准备说明。用户已授权公开源码和测试候选APK，2026-10-11已发布[0.1.22 Pre-release](https://github.com/ShinonomeAya/CatLeaf/releases/tag/v0.1.22)。公开源码从审查后的独立快照建立新历史，原工作区的旧Git历史留在本机。

## 发布说明

CatLeaf（猫页）是原生 Android RSS 阅读器，默认本机使用，无需注册。支持订阅与分组、全部订阅阅读、日期／未读筛选、搜索、全文提取、收藏、单篇图文离线，以及用户手动发起的 AI 双语对照和日摘要。

此版本为 **0.1.22（code23）测试候选**，最低 Android 10。完整发布验收仍在进行，不作为 G7/G8 正式交付。GitHub Release 已标记为 Pre-release。

底部选中Tab文字统一为森林绿。标题和正文可在同页双语对照，顶部功能区和原文／双语按钮保持固定，译文状态与重新翻译说明随文章滚动。旧译文缓存保留，不自动补译或发起 AI 请求。

## 安装文件

- 文件：`catleaf-0.1.22-code23.apk`，28,641,758 bytes；2026-10-11 重新构建。
- APK SHA-256：`2c66894c1c5a3298b268f42e5af52f07f66944be37fc0591c9fe902bf3d592ea`。
- 签名证书 SHA-256：`5f782c4afaf517286d0f5f2a2bd6d8d9f9e2ef9d1cc27b5d4084c65ad64d3f73`。
- 包名：`dev.local.catleaf.nativeapp`。已有用户使用同签名 APK 覆盖安装，保留数据。

实际测试范围及未通过的完整验收项目见[公开验收摘要](../../validation/验收记录.md)。RSS 通常只提供近期文章；图片、全文和部分订阅受原站、登录及网络限制。离线只保证成功保存的内容。AI 请求可能产生供应商费用。

## 已完成的公开操作

1. 核对独立快照的逐文件清单和暂存差异；保留 MIT 及上游版权，排除私钥、真实云配置、私人记录和旧 Git 对象。
2. 按用户明确授权创建[公开仓库](https://github.com/ShinonomeAya/CatLeaf)并推送502文件的新历史，标签`v0.1.22`对应源码提交`203b075ebc5c2d483c5f31c9ecd05e3779719a32`。
3. Issues和私人漏洞报告已启用；README、SECURITY及版本清单已登记实际入口，敏感信息使用私人报告渠道。
4. 实际APK和SHA-256文件已上传Pre-release。线上APK匿名HTTP200，长度及重新下载的SHA-256一致。官网和App更新清单仍未接通。

维护者：[ShinonomeAya](https://github.com/ShinonomeAya)。后端来源及 MIT 版权见[项目说明](../../../README.md#项目来源开源许可)和[LICENSE](../../../LICENSE)。
