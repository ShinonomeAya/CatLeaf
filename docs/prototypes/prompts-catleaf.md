# CatLeaf 页面扩展：生成提示记录

日期：2026-09-22。工具：内置 image_gen。所有内容为静态原型，未生成 App 代码。首批四图的原始提示保留在 [prompts.md](./prompts.md)。

执行规则：01-v2 为只改品牌文字的编辑；05–22 的实际请求均为下方公共提示加对应屏幕提示，参考图为 01-reading-home-v1.png（05–07）或 01-reading-home-v2.png（08–22）。01-v2 的参考图为 01-reading-home-v1.png。未逐图重新设计风格。

## 公共提示

```text
Use case: ui-mockup. Create a high fidelity Android mobile app screen for CatLeaf, a personal RSS and AI reader. This is a product prototype image, not code and not a promotional poster. Render one straight-on complete Android phone mockup per image, matte graphite narrow 6px bezel, small circular centered hole-punch camera, no Apple notch or Dynamic Island, minimal soft shadow, absolutely no tilt or perspective. Vertical canvas about 1024 x 2048. Phone occupies about 86% width and 93% height, centered, fully visible equal outer margins, calm warm-grey #E8E7E2 backdrop. Interior realistic 412dp-wide screen. Shared design language: warm ivory #FAF9F6 background, charcoal #252B28 text, secondary #6C756F, single muted forest green #356A52 accent, pale sage #E8EEE7 selection fill, fine #E4E6DF separators. Clean humanist Chinese sans serif (Noto Sans SC-like), short beautiful editorial titles, body equivalent 18dp, metadata no smaller than 13dp. Rounded rectangles 12dp only where meaningful, no card nesting, no gradient, no purple, no glass, no decorative dashboards, no fake avatars. Native Android top status bar 9:41 and small right cellular/wifi/battery; consistent bottom gesture line and safe areas. 24dp content side padding, 8dp spacing rhythm, 48dp touch zones. Tiny consistent filled/outlined icons. Reference ideas: Folo legible mobile reading, Reeder restrained lists, Unread spacious typography, Inoreader clear subscription groups, NewsBlur structured briefings. Make an original coherent visual design, do not copy logos or exact product screenshots. All UI text crisp and accurately typeset, Chinese strings verbatim. No captions, annotations, giant titles or color swatches outside the phone. Content must stay large enough to read. The only brand wordmark is CatLeaf, used sparingly. Avoid irrelevant widgets and features.
Match the attached approved reference closely: same phone frame, canvas, margins, type scale, warm white background and forest green accent. Generate ONE independent phone screen only. Do not reproduce the reference page's content unless specified. All screen names and quantities are illustrative; no extra invented features.
```

## 01v2 阅读首页 · 品牌更新

输出文件：01-reading-home-v2.png

```text
Edit the attached approved reading-home image. Preserve the complete layout, all phone hardware, colors, text, article thumbnails, alignment and spacing exactly as shown. Change ONLY the small wordmark at the top left from CatWeb to CatLeaf. Do not change any other content.
```

## 05 首次连接

输出文件：05-instance-setup-v1.png

```text
SCREEN: FIRST CONNECTION. No bottom app tabs. Top small elegant original leaf/page line icon in forest green, wordmark "CatLeaf", small subtitle "猫页". Plenty of breathing room. Main title "连接你的阅读空间". Supporting two-line copy "连接自己的实例，开始整理订阅与阅读。". Two labeled form fields, "实例地址" value "https://reader.example.com", and "个人访问密钥" showing password dots with eye icon. Helper below key "由你的实例管理员提供". Full width forest-green button "连接并保存". Below in neutral secondary text "连接成功后，可添加订阅或导入 OPML。". Quiet divider then heading "还没有实例？" and "先按部署文档创建个人实例，再填写地址与密钥。" with link "查看配置说明". Small footnote "AI 可稍后配置，不影响阅读。" No fake connected indicator, no sign up, no avatar. Form compact in upper-middle, ample lower whitespace.
```

## 06 收藏

输出文件：06-favorites-v1.png

```text
SCREEN: FAVORITES ROOT. Title "收藏", secondary "3 篇收藏 · 2 篇文本已保存". Three spacious editorial article rows and fine dividers, consistent with reference. Row1 "A quieter way to read", source "设计札记", green small status "文本已保存 · 可离线阅读", open cream book and green cup thumbnail, filled bookmark action. Row2 "好的工具，应该安静一点", source "设计札记", status "文本已保存 · 可离线阅读", pale architectural thumbnail, filled bookmark. Row3 "给信息留一点空白", source "原订阅已删除", amber text with small icon "仅书签 · 联网后获取正文", simple text-only article row no broken picture. Brief helper beneath list "收藏会保留，删除订阅不会移除收藏。". Exactly three bottom tabs "阅读" "收藏" "设置", 收藏 selected green with pale green capsule. No search, no folders, no additional filters. Clear differences between saved text and bookmark only.
```

## 07 设置

输出文件：07-settings-v1.png

```text
SCREEN: SETTINGS ROOT. Large title "设置". Below small CatLeaf wordmark with "猫页 · 让阅读安静一点". Native grouped setting rows on warm white background, small monochrome line icons at left, chevron at right; no cards within cards. Group "阅读与内容": row "订阅管理", value "4 个订阅"; row "阅读偏好", value "跟随系统 · 标准字号". Group "连接与服务": row "实例管理", value "reader.example.com"; row "AI 设置", green secondary "已配置". Group "本机数据": row "存储与备份", value "42.6 MB". Group "关于": row "关于猫页", value "0.1.0". These six rows must all fit with generous 72dp minimum height, group headings quiet. Exactly three bottom tabs "阅读" "收藏" "设置", 设置 selected green with pale green capsule. No account profile, no notification setting, no multi-device sync. Show only the named rows.
```

## 08 切换订阅面板

输出文件：08-source-selector-v1.png

```text
SCREEN: SOURCE SELECTOR BOTTOM SHEET. Same reading home as reference dimmed behind a modal sheet; sheet occupies lower two-thirds of phone, warm white surface, 24dp top corners, small grab handle, top "选择订阅", close X right. Two sections "设计与阅读" and "技术与产品". Rows respectively "设计札记" selected pale sage with checkmark and small "当前阅读", "阅读周刊", then "技术手记" and "产品观察" with amber "上次更新失败". Compact simple feed icons left, no article counts. Bottom sheet full width outlined action "管理订阅". Underlay top part CatLeaf and 阅读 visible dimmed, bottom navigation obscured by sheet. Make sheet touch areas spacious, no search, no all-feeds option, no fake multi-select.
```

## 09 添加订阅

输出文件：09-add-subscription-v1.png

```text
SCREEN: ADD SUBSCRIPTION FORM. Secondary top bar back arrow and "添加订阅", no bottom navigation. Intro "从一个你喜欢的 RSS 开始。". Form field label "订阅地址" value "https://design.example.com/feed.xml", helper "支持 HTTPS 的 RSS / Atom 地址". Label "显示名称（选填）", value "设计札记", helper "留空将使用订阅源名称". Label "分组（选填）", selection input "设计与阅读" with chevron, helper "选择已有分组，或输入新分组". Below form green button "添加并验证". Neutral short explanation "添加后会尝试读取订阅内容。验证失败时，可在订阅管理中重试。". Leave comfortable whitespace; no fake validation success. No discovery recommendations, no follow friends.
```

## 10 OPML 导入预览

输出文件：10-opml-preview-v1.png

```text
SCREEN: OPML IMPORT PREVIEW. Back arrow "导入预览", no root tabs. Filename "subscriptions.opml", metadata "4 个条目 · 可导入 2 个". Four compact separated rows each with title, readable domain and group on second lines and left checkbox. Row1 checked "技术周报", "weekly.example.com/feed.xml", group "技术 / 前端". Row2 checked "独立博客", "blog.example.com/rss.xml", group "阅读". Row3 unselectable unchecked "设计札记", "design.example.com/feed.xml", grey reason "已存在，将跳过". Row4 unselectable unchecked "无效订阅", "地址缺失，无法导入" in muted error. Pale sage notice "多级目录将合并为一级分组名称。". Footer summary "已选 2 个 · 跳过 2 个", green full width button "开始导入（2）". No success indicator before import. Keep all text large, never cut URLs; line wrap okay.
```

## 11 OPML 导入结果

输出文件：11-opml-result-v1.png

```text
SCREEN: OPML IMPORT PARTIAL RESULT. Back arrow "导入结果", no root tabs. Modest amber circle with exclamation, heading "部分导入完成", summary "已导入 1 个 · 失败 1 个 · 跳过 2 个". Two clear status rows: green check "技术周报", secondary "已添加 · 技术 / 前端"; amber "独立博客", secondary "请求超时，尚未添加", small right "重试". Quiet expandable row "查看跳过项（2）" with chevron. Informative small paragraph "已导入的订阅会保留。重试只处理失败条目。". Fullwidth main forestgreen button "重试失败项（1）", secondary outlined button "返回订阅管理". No misleading full-success badge, no counters inconsistent with 1+1+2=4. Comfortable spacious vertical composition.
```

## 12 AI 设置

输出文件：12-ai-settings-v1.png

```text
SCREEN: AI SETTINGS. Back arrow and "AI 设置", no root tabs. Short explanation "按需翻译与摘要，阅读无需开启 AI。". Fields exactly: "服务地址" value "https://ai.example.com/v1"; "模型 ID" value "model-id"; "API Key" value password dots with eye icon. Under fields note "文章内容将发送至 ai.example.com". Quiet status row clock icon "尚未测试". Outlined fullwidth button "测试连接", helper "测试会发送少量内容，可能产生费用。". Forestgreen fullwidth button "保存配置". Muted red text action lower "删除 AI 配置". No automatic generation toggle, no schedule, no artificial balance or token count. Form uses white surface, clear labels and visible grey-green borders, #879189. Plenty of vertical space but fit without clipping.
```

## 13 日摘要范围

输出文件：13-digest-scope-v1.png

```text
SCREEN: DIGEST SCOPE PREVIEW. Back arrow "摘要范围". Top current source "设计札记", bold date "9月22日", secondary "时区 Asia/Hong_Kong". Quiet notice "基于当前已加载内容，可能不包含该日全部文章。". Heading "已加载 3 篇", followed by 3 checked article rows: "A quieter way to read", metadata "正文 · 1,800 字符"; "好的工具，应该安静一点", "正文 · 1,900 字符"; "给信息留一点空白", "仅摘要 · 1,200 字符". Footer tally "已选 3 篇 · 4,900 字符", helper "最多 30 篇 / 30,000 字符". Then quiet service box "发送至 ai.example.com" and "模型：model-id"; brief "将发送勾选文章的内容，可能产生费用。". Wide forestgreen action "生成摘要". No root tabs. All information readable and hierarchy clear, sparse notice not excessive cards.
```

## 14 存储与备份

输出文件：14-storage-backup-v1.png

```text
SCREEN: STORAGE AND BACKUP. Back arrow "存储与备份", no root tabs. Top neutral total "本机占用 42.6 MB". Two simple aligned stats rows "普通缓存" "32.4 MB" and "收藏文本" "10.2 MB". Outlined fullwidth action "清理普通缓存". Short helper "保留收藏、已读状态和连接配置。". Section "导出与恢复", four large full-width rows with icons and chevrons: "导出 OPML" subtitle "仅订阅与分组"; "导出本地状态" subtitle "订阅、已读与收藏状态"; "恢复本地状态" subtitle "选择文件后预览合并"; "导出收藏链接" subtitle "标题与原文地址". Quiet pale-sage info "状态备份不含正文、AI 结果和密钥。". Separated muted red bottom row "重置此设备数据", helper "不会删除云端订阅". Keep type readable, use spare vertical spacing wisely, no pie charts or graphs. No bottom root nav.
```

## 15 恢复预览

输出文件：15-restore-preview-v1.png

```text
SCREEN: LOCAL STATE RESTORE PREVIEW. Back arrow "恢复预览", no bottom root tabs. Filename "catleaf-state-20260922.json". Section "来源与目标": small label "备份来源" then "reader.example.com"; small label "恢复到当前实例" then same "reader.example.com". Thin divider. Heading "将合并以下数据", aligned rows "订阅" "4 个", "新增订阅" "2 个", "已存在" "1 个", "无效条目" "1 个 · 查看" in amber, "已读记录" "120 条", "收藏记录" "8 条". Small neutral explanation "已有的已读和收藏会保留。无效条目将跳过。". Pale sage notice "文件不含正文、AI 结果或密钥。". Wide green action near bottom "合并恢复", secondary text "恢复会向当前实例添加缺少的订阅。". Do not show success before action; all numbers consistent: 2 new +1 existing +1 invalid =4.
```

## 16 阅读偏好 · 深色

输出文件：16-reading-preferences-dark-v1.png

```text
SCREEN: READING PREFERENCES IN DARK THEME. Override reference light interior ONLY: screen background #181D1A, surfaces #232A25, main text #E5E9E3, secondary #ABB5AB, accent #94C9A6, selected #2B4032, divider #39443C. Same graphite frame and warm grey exterior, bright Android status and gesture bar. Back arrow, "阅读偏好". Section "主题" with three equal selectable segments "跟随系统", "浅色", "深色", 深色 selected with check and pale green highlight. Section "正文字号", four equal segments "小", "标准", "大", "特大", 标准 selected. Small helper "同时遵循系统字体大小". Spacious preview surface below heading "阅读预览", article title "让阅读，重新变得轻松", two paragraphs of natural readable Chinese exactly "好的阅读体验，往往来自更少的干扰。" and "给文字留一点空间，也给自己留一点时间。". Small bottom check row "已保存，立即生效". No save button needed. No font picker, line-height slider, immersive toggles. No root nav.
```

## 17 实例管理

输出文件：17-instance-management-v1.png

```text
SCREEN: INSTANCE MANAGEMENT. Back arrow "实例管理", no root nav. Section "当前实例", origin "https://reader.example.com", green small check status "上次连接成功 · 20:32". Form "个人访问密钥" showing password dots and eye icon. Outlined full width action "更新密钥", helper "更新密钥会保留当前实例的阅读数据。". Clear divider then heading "切换实例". Fields "新实例地址" placeholder "https://new.example.com" and "新实例访问密钥" placeholder "输入新实例的访问密钥"; fields not falsely prefilled. Pale sage message "阅读数据按实例分别保存，连接失败不会替换当前实例。". Fullwidth green button "连接并切换". No logout, no remove all, no live connection guarantee, no credentials visible.
```

## 18 关于猫页

输出文件：18-about-v1.png

```text
SCREEN: ABOUT. Back arrow and "关于猫页". No root navigation. Upper-middle small original green leaf/page line icon, wordmark "CatLeaf", Chinese "猫页", short tagline "让阅读，安静一点。". Small version "版本 0.1.0". Quiet divider and four roomy navigation rows with chevrons: "项目说明", "开源许可", "数据与隐私", "配置帮助". Below modest acknowledgement "基于 gemini-rss-app 开发" and helper "保留上游及依赖的开源许可". Tiny leaf graphic only, no final app icon badge, no website URL other than optional source title. Calm generous whitespace, consistent forest green, refined typography. No follow-social, no reviews, no subscription payments.
```

## 19 恢复结果 · 部分失败

输出文件：19-restore-result-v1.png

```text
SCREEN: RESTORE RESULT WITH LOCAL WRITE FAILURE. Back arrow "恢复结果", no root tabs. Modest amber warning circle, heading "订阅已处理" and subheading "本地状态尚未恢复". First section "远端订阅" with green small check and summary "新增 2 个 · 已存在 1 个 · 跳过 1 个". Second section "本地状态" with amber warning, summary "120 条已读、8 条收藏未写入"; reason "存储空间不足，请清理后重试。". Neutral reassurance "原有阅读状态未改变。重试会保留已添加的订阅。". Fullwidth green button "重试恢复", outlined secondary "返回存储与备份". Do not claim full success, do not show 120 records actually merged, no confetti. Clear two-phase report and roomy alignment.
```

## 20 阅读首页 · 首次空态

输出文件：20-reading-empty-v1.png

```text
SCREEN: READING ROOT WITH ZERO SUBSCRIPTIONS AFTER CONNECTION. Small CatLeaf wordmark and large "阅读" at same positions as approved home. Below small green check with "实例已连接". Centered upper-middle elegant small leaf overlapping page line illustration in sage green, not a cat cartoon. Heading "还没有订阅". Two-line message "添加你喜欢的 RSS，或导入已有订阅。". Forestgreen wide button "添加订阅", outlined secondary "导入 OPML". Near lower center quiet helper "订阅由你选择，阅读节奏也由你决定。". Exactly three root bottom tabs 阅读 selected, 收藏, 设置; no date filter, no AI digest button before feed exists. No sample articles, no made-up recommended subscriptions.
```

## 21 阅读首页 · 离线缓存

输出文件：21-reading-offline-v1.png

```text
SCREEN: READING ROOT OFFLINE WITH SAVED TEXT. Same CatLeaf wordmark, large 阅读 heading, current feed 设计札记 and chevron. Keep forestgreen theme. Small pale amber notice with wifi-off icon "离线，正在显示已保存内容". Below secondary "上次成功更新：9月22日 20:32". Filter "今天 · 9月22日". Disabled subdued digest row "生成日摘要", helper "联网后可生成". Two spacious text-only article rows, no loaded photos: title "A quieter way to read", short English excerpt "Small changes that make long reads feel effortless.", green icon and "文本已保存 · 可离线阅读"; next "好的工具，应该安静一点", Chinese excerpt "减少干扰，让注意力回到内容本身。", same saved text label. Thin dividers. Small endnote "已显示 2 篇已保存内容". Exactly three root tabs 阅读 selected, 收藏, 设置. No spinning refresh, no fake real-time updated indicator, no retry generating AI while offline.
```

## 22 编辑订阅

输出文件：22-edit-subscription-v1.png

```text
SCREEN: EDIT EXISTING SUBSCRIPTION. Back arrow "编辑订阅", no root tabs. Form "订阅地址" read-only pale grey field containing "https://design.example.com/feed.xml", subtle lock icon; helper "更换地址需添加新的订阅。". Editable field "显示名称" value "设计札记". Selection field "分组" value "设计与阅读" plus chevron. Compact green check status "上次读取成功 · 9月22日 20:32". Main forestgreen button "保存更改". Clear separator then subdued red outlined action "删除订阅". Two-line supporting deletion impact text "将删除云端订阅和历史，保留本机收藏。". Do not show delete confirmation simultaneously. Do not add read-only disabled URL that looks editable, no draggable groups, no icons for advanced settings.
```


## 11-v2 超时文案修订

参考图：11-opml-result-v1.png。当前采用输出：11-opml-result-v2.png。原始版本只作过程留存。

```text
Edit the attached CatLeaf OPML import result image. Preserve every pixel of layout, phone frame, all headings, counts, buttons, icons and spacing as closely as possible. Change ONLY the small secondary text under 独立博客 from 请求超时，尚未添加 to the exact text 请求超时，结果待确认. Do not change any other text or visual content. The new message matters because a request timeout does not prove whether the server added the subscription.
```
