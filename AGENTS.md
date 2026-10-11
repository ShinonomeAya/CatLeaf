# CatLeaf（猫页）· 项目约定

上游来源及MIT许可见[README项目来源](README.md#项目来源开源许可)与[LICENSE](LICENSE)。旧上游知识库、网页及Vercel适配已在2026-10-11用户授权公开整理中归档到仓库外，不能按旧说明恢复入口。当前产品路线、目录和安全要求以本文件及下列文档为准。

## 项目与当前路线

同日v3.1增量：文章搜索和单篇图文离线按PRD§11、契约§9、Q01–Q04实施；35–40为新静态原型。收藏/普通缓存/主动离线分开保护，不把单篇保存扩成批量或常驻下载服务，G7增加C11–C15。

2026-09-25已批准增量：[ADR 003](docs/decisions/003-默认本地与可选实例.md)规定默认本机使用、实例可选，五源引导与分类发现见PRD v3。老实例用户升级保留数据与原使用方式；不自动迁移。开发顺序见[增量实施计划](tasks/本机模式与发现源实施计划.md)，当前实现及验收状态以任务摘要和最新验收记录为准，G7/G8尚未完整通过。

Android 优先，2026-09-30 用户明确目标改为公开发布、首发公开下载 APK；验收续工见 [公开发布验收计划](tasks/公开发布验收计划.md)，原 G7/G8 标准不降低，发布/分发仍需明确授权。名称 CatLeaf（猫页），暖白/森林绿，正式 Logo 为 `docs/assets/brand/catleaf-mark.svg`（A“歪头小叶猫”）。

**目标客户端：Kotlin + Jetpack Compose，已建于 android-native/（N01 后持续实施中）；Room、DataStore、Android Keystore 保护凭据、OkHttp；正文采用受控 WebView。** 后端继续二开 `Sallyn0225/gemini-rss-app` @ `36d0fa44aabc8f17c7d2e73ec382124677551262`，Cloudflare Pages Functions/D1/KV；保留 MIT 与 Copyright (c) 2025 su-kaka。依据 [ADR 002](docs/decisions/002-Android原生客户端.md)。

2026-10-11用户授权公开整理：旧React/Vite、Capacitor和未纳入当前鉴权边界的Vercel备选适配已归档。当前客户端只有`android-native/`，包名`dev.local.catleaf.nativeapp`；当前后端为`functions/`＋`server/`，Neon回退仍保留。先核对git status/diff和交接，保护已有未提交修改；不reset、不卸载旧包、不覆盖目录。

## 文档权威顺序

| 问题 | 依据 |
| --- | --- |
| 当前路线/变更理由 | [ADR 003本机默认](docs/decisions/003-默认本地与可选实例.md)、[ADR 002原生技术](docs/decisions/002-Android原生客户端.md)、[复评](docs/09-原生路线复评与开源对比.md) |
| 功能范围、上限 | [PRD](docs/03-PRD.md) |
| 接口、数据结构、AI、安全 | [架构与接口契约](docs/04-架构与接口契约.md) |
| 页面行为与状态 | [页面说明](docs/05-页面与状态说明.md) |
| 颜色/字号/间距、布局 | [设计规范](docs/07-设计规范.md)、[原型](docs/prototypes/README.md) |
| 素材 | [素材索引](docs/assets/README.md) |
| 验证与发布 | [验收与发布](docs/06-验收与发布.md) |
| 任务顺序与状态 | [plan](tasks/plan.md)、[todo](tasks/todo.md) |

发现冲突先记录并修正；功能/安全按 PRD/契约，视觉尺寸按规范，原型只作布局参考。旧 ADR/调研/验收记录保留历史，不覆盖新路线。

## 构建与检查

原生工程已建立（N01 完成；2026-09-24 T22 返修轮校正本段旧表述「尚待建立」）。在 `android-native` 用其 wrapper 运行 `./gradlew.bat :app:assembleDebug`、`:app:lintDebug`、`:app:testDebugUnitTest`，设备测试按任务需要执行 `:app:connectedDebugAndroidTest`（只跑在隔离模拟器；用户物理手机仅安装同签名 release 包做只读验收）；具体配置若变化须回写文档。不能在工程或测试不存在时报告通过。

现有Node基线：`npm ci`、`npm run lint`、`npm run typecheck`、`npm run test:core`、`npm run build`、`npm run preview:cf`。`npm run dev`为Wrangler本地后端服务（默认8788），没有App网页界面；build只编译Pages Functions，不生成客户端。旧Vite/Capacitor构建命令已移除；宣传官网独立开发。

## 硬性约束

- 先保留已完成 D01–D03/T01/T02 和旧 G0 的证据；新 N01/G0-N 独立验收。
- 单 app 模块，使用实际所需组件；不预建多后端、多平台、同步引擎、通用组件平台或注入框架。
- 凭据仅在内存与受 Keystore 加密保护的私有密文文件中，不进源码、日志、Room、DataStore、URL、普通导出或聊天。Keystore 保存加密密钥，API Key 作为被加密数据。
- 不预置作者隐藏订阅、账号、密钥、云资源 ID；不操作作者线上实例。
- `.github/workflows/deploy-cloudflare.yml` 在明确自有目标与授权前保持 `workflow_dispatch`。
- HTTPS/TLS 校验不关闭；实例/AI/公开原站请求严格隔离，带凭据请求不自动跳转；不用公共 CORS 代理。
- 所有正文走同一 jsoup allowlist 净化入口；WebView 关闭 JS、不加原生 JS 桥、限制资源与导航；外链走系统浏览器能力。
- Room 失败不自动删库，不用破坏性迁移掩盖错误；写盘成功后再显示收藏成功。
- UI 使用已交付 SVG 的原生转换，不把 React 组件当 Kotlin 依赖；不将原型整页 PNG 切入 App，不硬编码示例数据。

## 记录

公开验收摘要写入[验收记录](docs/validation/验收记录.md)，任务状态追加到[todo](tasks/todo.md)。原始日志、设备截图、数据库快照及完整流水保存在仓库外`F:\sokusai\CatWeb-local-archive\`；不得重新放入公开源码。历史文档中的原证据链接按归档路径查阅，不当作公开可用链接。只认真实证据；代码已写／待验证用待验收。产出APK后再确认路径、签名与哈希。未经授权不推送、发布或分发。

## 原生工程命令（N01 后实际可用）

```bash
cd android-native
# 需要 JAVA_HOME=<JDK 23>、ANDROID_HOME=F:/AndroidSDK/android-sdk、GRADLE_USER_HOME=F:/AndroidSDK/gradle-home
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:lintDebug
./gradlew.bat :app:testDebugUnitTest
```

不使用 `local.properties`（lint 的 `PropertyEscape` 会报错，且该文件是本机相关）。
锁定版本见 `android-native/gradle/libs.versions.toml`。升级 Compose BOM 前先确认它与当前 AGP 的兼容性：
`2026.09.00` 起需要 AGP 9.1+ 与 compileSdk 37。
