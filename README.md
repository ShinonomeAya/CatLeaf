# CatLeaf（猫页）

猫页是一款暖白／森林绿风格的原生 Android RSS 阅读器。默认在本机使用，无需注册；可按需连接自有实例，配置 AI 翻译与日摘要。

当前候选版本 **0.1.22（code23）**，最低 Android 10。已有定向真机与隔离验证，完整兼容、性能、升级恢复等发布验收仍未完成，G7/G8 未全部通过。源码和[问题反馈](https://github.com/ShinonomeAya/CatLeaf/issues)已公开，测试 APK 正在准备上传；候选信息见[版本材料](docs/releases/0.1.22/release.json)。

## 功能

- 添加／导入订阅、分组与全部订阅阅读、日期和未读筛选、文章搜索。
- 全文提取、收藏和单篇图文离线保存。
- 同页原文与译文对照、按需生成日摘要；无需 AI 配置也能阅读。

部分订阅和图片受原站、登录要求及网络限制；RSS 通常只提供近期文章。离线仅保证成功保存的内容。AI 请求由用户手动发起，可能产生供应商费用。

## 工程目录

| 目录 | 用途 |
| --- | --- |
| `android-native/` | Kotlin／Jetpack Compose 原生 Android 客户端，Room、DataStore、OkHttp、受控 WebView |
| `functions/`、`server/` | 可选自有实例的 Cloudflare Pages Functions 后端及 D1／KV 接入 |
| `tests/` | 后端回归检查 |
| `tools/catalog/` | 订阅目录生成与核验工具 |
| `docs/` | 产品、设计、接口、素材及许可说明 |

根目录不再包含旧 React／Vite 网页或 Capacitor Android 客户端。`npm run dev` 启动后端本地服务，不提供猫页 App 界面；宣传官网是独立工程。

## 构建 Android App

使用 JDK 23 和 Android SDK，依赖版本见 `android-native/gradle/libs.versions.toml`。PowerShell 示例中的 SDK 与缓存目录按本机实际位置修改：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
$env:ANDROID_HOME = 'F:\AndroidSDK\android-sdk'
$env:GRADLE_USER_HOME = 'F:\AndroidSDK\gradle-home'
cd android-native
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

调试 APK 在 `android-native/app/build/outputs/apk/debug/`。可在 Android Studio 中打开 `android-native/` 并运行到模拟器。仪器测试只在隔离模拟器运行，用户手机保留数据。

Release 使用自己的签名材料与本地 `keystore.properties` 配置，再运行 `:app:assembleRelease`。签名私钥和口令文件不得提交；原用户升级须保持签名一致。凭据配置仅通过本地安全文件或 App 完成，不写入源码或反馈内容。

## 可选后端开发

本机模式无需启动后端。开发后端需 Node.js 22 或更高版本：

```powershell
npm ci
npm run test:core
npm run lint
npm run typecheck
npm run build
npm run dev
```

`npm run dev` 使用 Wrangler 的本地 Pages Functions 服务，默认端口 8788。`npm run build` 仅编译后端到 `dist/_worker.js/`；`npm run preview:cf` 运行该构建产物。API 需要配置自己的实例凭据，本地配置不得提交。D1／KV 和部署资源使用自己的配置，详见[接口契约](docs/04-架构与接口契约.md)。

`wrangler.toml`中的D1／KV资源ID为占位符。部署前替换为自己的资源；本机已有资源配置另存于已忽略的`wrangler.local.toml`，需要时使用`--config wrangler.local.toml`。本地密钥放入已忽略的`.dev.vars`，可参考`.env.example`；AI Key只在App配置，不传给后端。

部署工作流只允许手动触发。设置仓库变量`CLOUDFLARE_PAGES_PROJECT`及GitHub Secrets：`CLOUDFLARE_API_TOKEN`、`CLOUDFLARE_ACCOUNT_ID`、`D1_DATABASE_ID`、`KV_NAMESPACE_ID`。运行本地构建不会部署或上传。旧Vercel适配没有经过当前统一鉴权验收，已归档，当前不提供其部署入口；Cloudflare后端内部的Neon数据库回退仍保留。

## 项目资料

[产品需求](docs/03-PRD.md) · [页面说明](docs/05-页面与状态说明.md) · [设计规范](docs/07-设计规范.md) · [原型](docs/prototypes/README.md) · [本机与实例帮助](docs/14-本机使用与实例帮助.md)

开发约定见[AGENTS.md](AGENTS.md)，当前状态见[任务摘要](tasks/todo.md)和[验收摘要](docs/validation/验收记录.md)。原始验收日志、设备截图、数据库快照与旧APK已归档到仓库外，仓库只保留摘要和选定的宣传素材。公开仓库采用审查后的新历史。

## 项目来源／开源许可

后端基于 [Sallyn0225/gemini-rss-app](https://github.com/Sallyn0225/gemini-rss-app) 的提交 `36d0fa44aabc8f17c7d2e73ec382124677551262` 继续开发；当前客户端采用 Kotlin／Jetpack Compose。保留原 MIT 许可和 `Copyright (c) 2025 su-kaka`，见 [LICENSE](LICENSE)。猫页自身的需求、设计与路线说明保留在`docs/`；导入的上游旧文档已在仓库外归档。

维护者：[ShinonomeAya](https://github.com/ShinonomeAya)。源码：[ShinonomeAya/CatLeaf](https://github.com/ShinonomeAya/CatLeaf)；普通问题使用[GitHub Issues](https://github.com/ShinonomeAya/CatLeaf/issues)，安全问题见[SECURITY.md](SECURITY.md)。
