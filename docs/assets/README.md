# CatLeaf（猫页）SVG 素材包 v2.0

日期：2026-09-22。**已交付52个生产用途SVG**：品牌5个、平台图标5个、空态插画2个、操作图标40个。另有3个Logo方向源文件、6张SVG/PNG预览板、来源清单和可重跑的静态校验。本次按用户提供的圆脸猫参考重绘品牌，用户已明确确认采用A“歪头小叶猫”，当前品牌文件统一采用该正式方案；素材制作轮次本身不包含 App 工程验收；后来旧 T02 已完成平台接入与模拟器打包，用户真机仍未验收。历史v1/v2候选压缩包保留，当前定稿交付包为项目docs/catleaf-svg-assets-v2-final.zip。

![CatLeaf素材总览](F:/sokusai/CatWeb/docs/assets/previews/asset-overview.png)

## 设计思路：歪头小叶猫

参考用户提供猫图的圆润比例、短耳、点状眼睛与笑嘴，自行绘制宽圆脸和柔软耳尖。猫脸倾斜7度，像带着好奇在看东西；额头上方独立的小叶子呼应Leaf。相比v1的抽象轮廓，这版让表情成为识别重点。

主标只使用一个前景色，眼睛和笑嘴均为真实透明开口，小叶子为实心。浅背景采用森林绿#356A52，深背景采用#94C9A6；Android前景采用暖白#FAF9F6。单色主题保留完整表情，所有颜色取自已有设计规范。

| 方向 | SVG源文件 | 使用判断 |
| --- | --- | --- |
| A 歪头小叶猫 | [01-curious-cat.svg](concepts/v2/01-curious-cat.svg) | 已定稿主标；双眼与小笑嘴，轻微歪头；品牌和平台文件均由此派生 |
| B 眨眼小猫 | [02-winking-cat.svg](concepts/v2/02-winking-cat.svg) | 未采用的历史方向；未接入平台文件 |
| C 摇尾小猫 | [03-tail-cat.svg](concepts/v2/03-tail-cat.svg) | 未采用的历史方向；未接入平台文件 |

[三个方向对比图](previews/logo-directions.png)。B、C仅保留选型历史，不用于正式界面。主标按比例缩放，不镜像、不单独移动叶子；96px显示尺寸时，画布外至少留8px空白，其他尺寸同比例调整。

英文组合标由原创曲线路径绘制，不依赖安装字体，也没有把图片包进SVG。中文“猫页”和说明性文字仍建议用App系统字体实时排版；本素材不提供中文字体文件。

空态使用书页与叶枝构图，服务于阅读场景。操作图标沿用Lucide原有网格和圆角描边，品牌图形不会混入每个按钮；预览中的布局与文字只用于交接，不是App的整页切片。

## 文件清单

所有下列SVG都独立可打开，可在矢量编辑器中继续修改。52个生产SVG均无脚本、外部字体、外链或嵌入位图；其中背景层为纯色不透明，其余保留透明区域。SVG预览板含说明文字，跨设备阅读优先看同名PNG。

| 文件 | 画布 / 背景 | 用途 |
| --- | --- | --- |
| [catleaf-mark.svg](brand/catleaf-mark.svg) | 96×96 / 透明 | 浅色主题主标；05首次连接、18关于 |
| [catleaf-mark-dark.svg](brand/catleaf-mark-dark.svg) | 96×96 / 透明 | 深色主题同一主标 |
| [catleaf-mark-mono.svg](brand/catleaf-mark-mono.svg) | 96×96 / 透明 | currentColor单色版；未指定颜色时默认为黑色 |
| [catleaf-lockup-light.svg](brand/catleaf-lockup-light.svg) | 392×96 / 透明 | 主标＋CatLeaf英文组合，浅色背景 |
| [catleaf-lockup-dark.svg](brand/catleaf-lockup-dark.svg) | 392×96 / 透明 | 同一英文组合，深色背景 |
| [launcher-foreground.svg](platform/launcher-foreground.svg) | 108×108 / 透明 | Android桌面图标前景，暖白主标 |
| [launcher-background.svg](platform/launcher-background.svg) | 108×108 / 不透明 | Android桌面图标森林绿背景 |
| [launcher-monochrome.svg](platform/launcher-monochrome.svg) | 108×108 / 透明 | Android主题图标单色层，以alpha轮廓着色 |
| [splash-icon-light.svg](platform/splash-icon-light.svg) | 288×288 / 透明 | 浅色系统启动屏图标，背景使用#FAF9F6 |
| [splash-icon-dark.svg](platform/splash-icon-dark.svg) | 288×288 / 透明 | 深色系统启动屏图标，背景使用#181D1A |
| [empty-reading-light.svg](illustrations/empty-reading-light.svg) | 240×200 / 透明 | 20无订阅空态，也可用于收藏空态并替换旁侧文案 |
| [empty-reading-dark.svg](illustrations/empty-reading-dark.svg) | 240×200 / 透明 | 深色空态，同一几何结构 |
| ui目录的40个SVG | 24×24 / 透明 | 操作/状态图标，逐项映射见下表 |

品牌建议显示24px及以上；16px只作为极小尺寸识别检查。空态按比例缩放，常用宽160–200dp；不能因为插画固定高度挤掉“添加订阅”按钮。

## 操作图标映射

39个文件直接来自Lucide 0.562.0，另一个bookmark-active.svg仅将根fill改成currentColor。旧 React 工程可使用既有 lucide-react；**新 Kotlin/Compose 客户端按需将这些 SVG 转为 VectorDrawable/ImageVector**，表中 React 名仅作来源和语义对照，不是新客户端依赖，无需另一套图标库。

以下所有原组件名已在0.562.0类型声明的导出列表核对。上游依赖为版本范围，实施T01仍须核对实际锁文件版本；这次核验没有在App中安装依赖。

| SVG | React组件 | 操作语义 | 对应原型 |
| --- | --- | --- | --- |
| [newspaper.svg](ui/newspaper.svg) | Newspaper | 阅读入口 | 01,07,20,21 |
| [bookmark.svg](ui/bookmark.svg) | Bookmark | 收藏、取消收藏 | 01,02,06,20,21 |
| [settings.svg](ui/settings.svg) | Settings | 设置入口 | 01,06,07,20,21 |
| [plus.svg](ui/plus.svg) | Plus | 添加订阅 | 03,09,20 |
| [arrow-left.svg](ui/arrow-left.svg) | ArrowLeft | 二级页返回 | 02–05,09–19,22 |
| [x.svg](ui/x.svg) | X | 关闭面板、取消 | 08 |
| [chevron-down.svg](ui/chevron-down.svg) | ChevronDown | 源选择、日期选择 | 01,08,21 |
| [chevron-right.svg](ui/chevron-right.svg) | ChevronRight | 进入下一层 | 03,07–09,14–18,22 |
| [refresh-cw.svg](ui/refresh-cw.svg) | RefreshCw | 刷新、重试、重新生成 | 01,03,04,11,19 |
| [ellipsis.svg](ui/ellipsis.svg) | Ellipsis | 文章更多操作 | 02 |
| [type.svg](ui/type.svg) | Type | 字号、阅读偏好 | 02,07,16 |
| [languages.svg](ui/languages.svg) | Languages | 翻译、原译切换 | 02 |
| [share-2.svg](ui/share-2.svg) | Share2 | 系统分享 | 02 |
| [external-link.svg](ui/external-link.svg) | ExternalLink | 原网页 | 02,04 |
| [check.svg](ui/check.svg) | Check | 选中项 | 08,10,13,16 |
| [square.svg](ui/square.svg) | Square | 未选中复选框 | 10,13 |
| [square-check.svg](ui/square-check.svg) | SquareCheck | 已选中复选框 | 10,13 |
| [circle-check.svg](ui/circle-check.svg) | CircleCheck | 保存/连接成功 | 06,11,16,17,19,20,22 |
| [circle-alert.svg](ui/circle-alert.svg) | CircleAlert | 异常、部分失败 | 03,08,11,19 |
| [wifi-off.svg](ui/wifi-off.svg) | WifiOff | 离线提示 | 21 |
| [eye.svg](ui/eye.svg) | Eye | 显示密钥 | 05,12,17 |
| [eye-off.svg](ui/eye-off.svg) | EyeOff | 隐藏密钥 | 05,12,17 |
| [link.svg](ui/link.svg) | Link | 实例、收藏链接 | 07,14,17 |
| [folder.svg](ui/folder.svg) | Folder | 订阅分组 | 03,08,09,22 |
| [rss.svg](ui/rss.svg) | Rss | 订阅源通用图标 | 03,08 |
| [upload.svg](ui/upload.svg) | Upload | 导出状态/OPML/链接 | 03,14 |
| [download.svg](ui/download.svg) | Download | 导入/恢复文件 | 03,10,14,15 |
| [trash-2.svg](ui/trash-2.svg) | Trash2 | 清理、删除、重置 | 12,14,22 |
| [database.svg](ui/database.svg) | Database | 存储与备份 | 07,14 |
| [file-text.svg](ui/file-text.svg) | FileText | 日摘要、文件、项目说明 | 01,04,13,15,18 |
| [info.svg](ui/info.svg) | Info | 帮助性说明、关于 | 07,10,13–15,18 |
| [circle-question-mark.svg](ui/circle-question-mark.svg) | CircleQuestionMark | 配置帮助 | 05,18 |
| [calendar-days.svg](ui/calendar-days.svg) | CalendarDays | 日期选择 | 01,04,13 |
| [lock.svg](ui/lock.svg) | Lock | 地址只读、配置保护 | 22 |
| [loader-circle.svg](ui/loader-circle.svg) | LoaderCircle | 请求进行中 | 05,09–15,17,19 |
| [cpu.svg](ui/cpu.svg) | Cpu | AI配置与服务 | 07,12,13 |
| [book-open.svg](ui/book-open.svg) | BookOpen | 阅读类订阅 | 03,08 |
| [code-xml.svg](ui/code-xml.svg) | CodeXml | 开源许可 | 18 |
| [image-off.svg](ui/image-off.svg) | ImageOff | 文章图片不可用 | 02,06,21 |
| [bookmark-active.svg](ui/bookmark-active.svg) | Bookmark，fill设为currentColor | 已收藏 | 02、06 |

- 基准图形24×24、描边2，圆端点与圆连接；页面图形可用20–24dp，触摸区独立保证至少48dp。
- 默认使用正文色，选中使用主题强调色；异常/离线同时提供文字说明，不能只改颜色。
- 根导航选中态用浅绿底＋强调色图标和文字；收藏实心态仅在实际保存成功后出现。
- 原生资源将 currentColor 映射为主题 tint；品牌透明开口不填死。旧 Web 内联 SVG/React 可继承颜色，外部 img 不能自动继承宿主 color；这条 Web 说明不作为 Kotlin 用法。
- 图标应由按钮/状态文本提供可访问名称。纯装饰图标不重复朗读；眼睛按钮分别读“显示密钥”或“隐藏密钥”。
- loader-circle只提供静态形状。实际动画服从减少动态效果设置，不在SVG中内置持续动画。
- circle-question-mark对应CircleQuestionMark；不要按旧别名circle-help猜文件名。表格给出本包已验证的实际路径。

## 平台接入规则

桌面前景、背景、单色层必须保持108×108画布及原坐标，不能自动裁掉透明边距再导出。实际前景绘制范围已检查，距离画布中心的最大半径32.36dp，处于半径33dp安全圆内。眼睛和笑嘴必须保持透明，不能改成固定背景色覆盖。裁切预览使用中央72×72视口，外围18dp保留给平台裁切与动效；不能把108dp画布整体缩进一个最终桌面图标，造成主标过小。[Android自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

启动图标按无独立图标底板的288×288方案准备，最大绘制半径92.05dp，小于96dp安全圆；窗口背景单独配置。不要同时显示系统启动屏与另一个延时网页开屏。[Android启动屏规范](https://developer.android.com/develop/ui/views/launch/splash-screen)

这里的 SVG 是设计源格式；**旧 T02 已完成平台资源转换，见下方历史记录**。新原生 N01 核对复用平台 VectorDrawable/主题配置，各页面按需转换 UI 图标；T24 验证安装、桌面裁切、主题图标和系统版本兼容。SVG中的组旋转、路径描边、evenodd填充和透明表情在转换后必须保持。旧系统兼容资源由同一源文件派生，不能另画不一致图标。

**2026-09-22 T02 转换记录（已完成，真机部分待 T24）**：`launcher-foreground.svg` → `android/app/src/main/res/drawable/ic_launcher_foreground.xml`，
`launcher-monochrome.svg` → `ic_launcher_monochrome.xml`，`splash-icon-light/dark.svg` → `drawable/splash_icon.xml` 与
`drawable-night/splash_icon.xml`。转换保留了 `<g transform="rotate(-7 64 72)">`（映射为 `<group android:rotation="-7" android:pivotX="64" android:pivotY="72">`）
与 `fill-rule="evenodd"`（映射为 `android:fillType="evenOdd"`）。背景层使用颜色资源 `#356A52`，未再烘焙位图。
由于产品 minSdk=29（≥26），自适应图标始终生效，因此没有生成 PNG 兼容密度；模板自带的 Ionic 位图图标与启动图已删除。
模拟器（Android 15/API 35）装机后系统 App info 显示圆形蒙版下的定稿猫标与名称“猫页”。**桌面裁切、主题图标、低版本系统与用户真机仍待 T24。**

品牌几何修改后应同步更新5个品牌文件和5个平台文件，并重新渲染与验证。空态的两种主题同步修改。避免只改其中一张预览图。

## 原型替换关系

| 原型区域 | 采用素材 / 实施规则 |
| --- | --- |
| 01、06、07、20、21的CatLeaf文字 | 短文字可继续由界面字体渲染，不必须每处增加猫标 |
| 05首次连接品牌区 | catleaf-mark＋系统字体名称，或横向组合标 |
| 18关于品牌区 | 同一个catleaf-mark，替换旧稿书页/叶片占位标记 |
| 20首次空态 | empty-reading对应主题；旁侧文案与按钮仍是实时组件 |
| 其余导航、动作、状态 | 使用上表对应Lucide图标；某个形状与生成图细节不同，以本包统一样式为准 |
| 文章配图与站点头像 | 来自真实订阅；图片失效可使用ImageOff。原型咖啡/书本照片不属于素材包 |
| 手机外框、系统状态栏、按钮背景 | 不提取为素材；由系统及实际布局提供 |

原型原PNG保留供页面布局比较，没有裁切、涂改或覆盖历史图。替换上述品牌区域属于本次独立素材交付，不表示22个页面已经重画或在App中接入。

## 预览与验证

| 预览 | 检查内容 |
| --- | --- |
| [Logo三个方向](previews/logo-directions.png) | A歪头已定稿；B眨眼、C摇尾保留历史 |
| [素材总览](previews/asset-overview.png) | 品牌组合、主题、平台、插画和常用图标 |
| [40枚图标全集](previews/ui-icons.png) | 文件名、状态与用途 |
| [小尺寸与深浅背景](previews/small-sizes.png) | 16/24/32/48px |
| [桌面裁切与主题图标](previews/launcher-masks.png) | 圆形、圆角、方形、系统主题示例 |
| [系统启动屏预览](previews/splash-preview.png) | 纯色背景和同源图标 |

每张预览同时有同名SVG可编辑。预览含页面文字、剪裁蒙版和背景，仅用于交接，不能代替上方生产文件。

[verification.json](verification.json)记录52个SVG的SHA-256、画布、解析渲染结果、颜色版本形状一致性、眼睛/笑嘴透明性、叶片实心及安全圆检查。[ui-manifest.json](ui-manifest.json)记录每个图标的源地址、哈希和派生修改。

可重跑检查使用Node和sharp：tools/verify-svg.mjs；预览由tools/render-previews.mjs生成。运行环境可通过CATLEAF_SHARP_PATH指向已有sharp模块，或使用环境已安装的sharp；没有给项目安装依赖。tools/fetch-ui.mjs仅用于显式重新获取已固定版本的图标，会联网，不是正常验证必需步骤。

已查看本次更新的方向图、素材总览、小尺寸、桌面裁切和启动屏5张预览；40枚UI图标及其全集预览沿用v1。52个生产SVG均解析并渲染通过，24/48px的猫脸与表情可辨识，16px仅用于极小尺寸检查，不作为推荐显示尺寸。**这些证据仅证明设计文件的静态质量，不证明Android转换、真机触摸、读屏和运行性能通过。用户视觉定稿依据本轮明确回复“可以，那就采用A”。**

## 来源与保留要求

2026-10-14新增 [AI阅读图标](ui/ai-reading.svg)：CatLeaf原创“书页＋星光”，24×24、2px描边，遵循本仓库MIT许可；原生派生为`ic_ai_reading.xml`。当前生产SVG增至53个、UI图标41个，既有40枚全集预览保留为历史，不包含这枚新增图标。

品牌主标依据用户提供的猫图风格重新绘制路径与表情，未嵌入或直接描摹参考图片；英文路径字标和空态插画沿用此前CatLeaf设计，没有嵌入第三方字体或图片。外部搜索仅用于风格观察，没有下载或转用图库Logo。

操作图标来自 [Lucide 0.562.0](https://github.com/lucide-icons/lucide/tree/0.562.0/icons)，固定提交6b4075b89b4016c4e1fb7165bc340c734ee466ec；来源与本地哈希逐项记录于ui-manifest。使用或再分发这些图标须同时保留 [完整上游许可](licenses/LUCIDE-LICENSE.txt)。bookmark-active为上述书签图标的填充派生版，遵循同一许可保留要求。

文件与资源版本以本索引为准；当前 D01–D03 设计层完成，新原生资源接入与真机验收另计。工程任务状态保存在项目tasks/todo.md，不随纯素材压缩包复制。

本次只修订工作区中的原生接入说明，52 个 SVG、来源哈希与历史 zip 均未重绘/重打包；zip 内说明是素材交付时快照，工程执行以当前 [路线与准入](../08-素材交付与开发准入.md) 为准。
