# CatWeb 原型图完整生成提示

日期：2026-09-22。生成方式：内置 image_gen。仅图片生成，未使用 CLI 或编写界面代码。

第 01 张独立生成；第 02–04 张以 01-reading-home-v1.png 为视觉与设备参考，均重新生成独立页面，没有从总览图裁切。下面保留实际发送的完整提示，用于后续视觉迭代；图中文字、订阅与文章均为示例。

## 01 阅读首页

```text
Use case: ui-mockup. Create a high fidelity Android mobile app screen for CatWeb, a personal RSS and AI reader. This is a product prototype image, not code and not a promotional poster. Render one straight-on complete Android phone mockup per image, matte graphite narrow 6px bezel, small circular centered hole-punch camera, no Apple notch or Dynamic Island, minimal soft shadow, absolutely no tilt or perspective. Vertical canvas about 1024 x 2048. Phone occupies about 86% width and 93% height, centered, fully visible equal outer margins, calm warm-grey #E8E7E2 backdrop. Interior realistic 412dp-wide screen. Shared design language: warm ivory #FAF9F6 background, charcoal #252B28 text, secondary #6C756F, single muted forest green #356A52 accent, pale sage #E8EEE7 selection fill, fine #E4E6DF separators. Clean humanist Chinese sans serif (Noto Sans SC-like), short beautiful editorial titles, body equivalent 18dp, metadata no smaller than 13dp. Rounded rectangles 12dp only where meaningful, no card nesting, no gradient, no purple, no glass, no decorative dashboards, no fake avatars. Native Android top status bar 9:41 and small right cellular/wifi/battery; consistent bottom gesture line and safe areas. 24dp content side padding, 8dp spacing rhythm, 48dp touch zones. Tiny consistent filled/outlined icons. Reference ideas: Folo legible mobile reading, Reeder restrained lists, Unread spacious typography, Inoreader clear subscription groups, NewsBlur structured briefings. Make an original coherent visual design, do not copy logos or exact product screenshots. All UI text crisp and accurately typeset, Chinese strings verbatim. No captions, annotations, giant titles or color swatches outside the phone. Content must stay large enough to read. The only brand wordmark is CatWeb, used sparingly. Avoid irrelevant widgets and features.

SCREEN 01: READING HOME. Keep the layout calm and usable, list-led with three real article rows, not a dashboard.
At top below status bar: small green CatWeb wordmark at left, a subtle circular refresh icon right. Next a bold 30dp title "阅读". Below title the current single feed selector "设计札记" with a small down chevron. It is ONLY this selected feed, not an all-feed timeline.
One horizontal filter row: selected pale-sage "今天 · 9月22日" on left and unobtrusive unselected "仅未读" on right. Small supporting line "已获取 3 篇 · 更新于 20:32".
A quiet pale-sage rectangular action row with a simple document icon: bold "生成日摘要", secondary "基于已加载内容，手动生成", right arrow. Not a promotional banner.
Then three editorial list rows separated by fine dividers, without outer card boxes. Each row title and excerpt at left, a modest square thumbnail on right with 10dp rounded corners.
Row1: an unread green dot, title exactly "A quieter way to read", excerpt "Small changes that make long reads feel effortless.", metadata "08:40 · 未读". Thumbnail: tasteful warm desk photo, open cream paper book, small dark green coffee cup, morning window light.
Row2: title "好的工具，应该安静一点", excerpt "减少干扰，让注意力回到内容本身。", metadata "10:15 · 已读", small outlined bookmark icon. Thumbnail: clean cream and olive abstract architectural corner with soft daylight.
Row3: title "给信息留一点空白", excerpt "从订阅整理开始，建立自己的阅读节奏。", metadata "14:20 · 未读". Thumbnail: sunlit notebook and pencil, neutral green tone.
At bottom of list a very quiet "已显示当前加载内容". Respect generous row spacing, no too-small text.
Fixed bottom main navigation with exactly THREE equal destinations: newspaper icon "阅读" selected muted green, bookmark icon "收藏", settings icon "设置". Selected icon has small pale-sage capsule background, other items charcoal-grey. No fourth tab, no add floating button, no search icon. All safe areas and Android gesture bar visible.
Make the screen feel refined and immediately usable, with coherent aligned type and attractive editorial thumbnail photography.
```

## 02 文章详情与译文

```text
Use case: ui-mockup. Create a high fidelity Android mobile app screen for CatWeb, a personal RSS and AI reader. This is a product prototype image, not code and not a promotional poster. Render one straight-on complete Android phone mockup per image, matte graphite narrow 6px bezel, small circular centered hole-punch camera, no Apple notch or Dynamic Island, minimal soft shadow, absolutely no tilt or perspective. Vertical canvas about 1024 x 2048. Phone occupies about 86% width and 93% height, centered, fully visible equal outer margins, calm warm-grey #E8E7E2 backdrop. Interior realistic 412dp-wide screen. Shared design language: warm ivory #FAF9F6 background, charcoal #252B28 text, secondary #6C756F, single muted forest green #356A52 accent, pale sage #E8EEE7 selection fill, fine #E4E6DF separators. Clean humanist Chinese sans serif (Noto Sans SC-like), short beautiful editorial titles, body equivalent 18dp, metadata no smaller than 13dp. Rounded rectangles 12dp only where meaningful, no card nesting, no gradient, no purple, no glass, no decorative dashboards, no fake avatars. Native Android top status bar 9:41 and small right cellular/wifi/battery; consistent bottom gesture line and safe areas. 24dp content side padding, 8dp spacing rhythm, 48dp touch zones. Tiny consistent filled/outlined icons. Reference ideas: Folo legible mobile reading, Reeder restrained lists, Unread spacious typography, Inoreader clear subscription groups, NewsBlur structured briefings. Make an original coherent visual design, do not copy logos or exact product screenshots. All UI text crisp and accurately typeset, Chinese strings verbatim. No captions, annotations, giant titles or color swatches outside the phone. Content must stay large enough to read. The only brand wordmark is CatWeb, used sparingly. Avoid irrelevant widgets and features.
Reference image 1 is the ALREADY APPROVED VISUAL SYSTEM from the reading home. Use it only as a STYLE AND DEVICE REFERENCE. Preserve exactly the phone proportions, matte graphite frame, outer margins, camera, warm white/forest green palette, Chinese font family, line icon weights, padding, status bar and bottom gesture safe area. Create a NEW DISTINCT SCREEN as specified below, not a crop or a repeat of the home screen.
SCREEN 02: ARTICLE DETAIL, with an already completed cached Chinese translation. Dedicated secondary page, NO root bottom navigation.
Top app bar: back arrow left, small title "设计札记", typography settings "Aa" and more menu right. Below: article English title "A quieter way to read" in elegant large dark type, metadata "9月22日 08:40". A compact segmented control "原文" and "简体中文", with 简体中文 selected pale sage.
A landscape 16:9 editorial photograph showing the SAME scene as home row1: open cream book, dark forest-green ceramic cup, warm natural window light. Beautiful, but avoid dominating the page.
Below photo a quiet small line "AI 译文 · 已缓存", then a Chinese subheading "让阅读，重新变得轻松".
Comfortably readable Chinese paragraphs exactly:
"好的阅读体验，不是装下更多信息，而是让注意力停留得更久。"
"合适的字号、舒展的行距，以及安静的界面，都能让长文更容易读下去。"
"把暂时读不完的文章收藏起来，留给真正有空的时刻。"
Use generous line height with paragraphs aligned left; no fake citations, no highlighted quote card, no dashboard.
At bottom a subtle thin separator and compact action toolbar with exactly three equal tappable actions: filled forest-green bookmark with "已收藏", share icon with "分享", external-open icon with "原网页". Above or near toolbar a very small but legible check and "文本已保存，可离线阅读". No large primary button, no global 阅读/收藏/设置 tabs on this detail page.
This should be an inviting real reading surface, typography as hero. Ensure all body copy is accurately rendered and not tiny. The photo and text layout should fit without cropping controls.
```

## 03 订阅管理

```text
Use case: ui-mockup. Create a high fidelity Android mobile app screen for CatWeb, a personal RSS and AI reader. This is a product prototype image, not code and not a promotional poster. Render one straight-on complete Android phone mockup per image, matte graphite narrow 6px bezel, small circular centered hole-punch camera, no Apple notch or Dynamic Island, minimal soft shadow, absolutely no tilt or perspective. Vertical canvas about 1024 x 2048. Phone occupies about 86% width and 93% height, centered, fully visible equal outer margins, calm warm-grey #E8E7E2 backdrop. Interior realistic 412dp-wide screen. Shared design language: warm ivory #FAF9F6 background, charcoal #252B28 text, secondary #6C756F, single muted forest green #356A52 accent, pale sage #E8EEE7 selection fill, fine #E4E6DF separators. Clean humanist Chinese sans serif (Noto Sans SC-like), short beautiful editorial titles, body equivalent 18dp, metadata no smaller than 13dp. Rounded rectangles 12dp only where meaningful, no card nesting, no gradient, no purple, no glass, no decorative dashboards, no fake avatars. Native Android top status bar 9:41 and small right cellular/wifi/battery; consistent bottom gesture line and safe areas. 24dp content side padding, 8dp spacing rhythm, 48dp touch zones. Tiny consistent filled/outlined icons. Reference ideas: Folo legible mobile reading, Reeder restrained lists, Unread spacious typography, Inoreader clear subscription groups, NewsBlur structured briefings. Make an original coherent visual design, do not copy logos or exact product screenshots. All UI text crisp and accurately typeset, Chinese strings verbatim. No captions, annotations, giant titles or color swatches outside the phone. Content must stay large enough to read. The only brand wordmark is CatWeb, used sparingly. Avoid irrelevant widgets and features.
Reference image 1 is the ALREADY APPROVED VISUAL SYSTEM from the reading home. Use it only as a STYLE AND DEVICE REFERENCE. Preserve exactly the phone proportions, matte graphite frame, outer margins, camera, warm white/forest green palette, Chinese font family, line icon weights, padding, status bar and bottom gesture safe area. Create a NEW DISTINCT SCREEN as specified below, not a crop or a repeat of the home screen.
SCREEN 03: SUBSCRIPTION MANAGEMENT. Dedicated secondary page accessed from feed selector; no root bottom navigation. Native top app bar back arrow, title "订阅管理", text + icon button "添加" right.
Below top bar a calm meta line "4 个订阅 · 2 个分组". This is a sober usable management list, not a analytics dashboard. Two well-separated group sections with tiny folder icon and text heading.
First group heading "设计与阅读" and small "2" on right.
Row1: a refined forest-green square favicon containing a white simple book glyph, title "设计札记", secondary "当前阅读 · 刚刚更新", right three-dot action menu.
Row2: cream/slate square favicon with letters RW, title "阅读周刊", secondary "上次更新于昨天", right three-dot menu.
Second group heading "技术与产品" and small "2" right.
Row3: dark slate square favicon with bracket glyph, title "技术手记", secondary "上次更新于 09:20", right three-dot menu.
Row4: muted amber square favicon with a small window glyph, title "产品观察", secondary amber warning "请求超时", a clear small "重试" text action on right plus three-dot menu. Use both icon and words, not color only.
Fine separators between source rows, zero giant enclosing cards, generous comfortable 72dp row heights. Do not display unread counts or toggles. No search field, because search is not MVP.
Below list a separated section "订阅迁移". Two simple full-width action rows with clean file import/export icons: "导入 OPML" and "导出 OPML", trailing chevrons. Supporting single sentence under them: "导出仅包含订阅与分组".
Bottom safe area with Android gesture line, no browser chrome. Preserve the exact device scale and font system of reference. Warm ivory background with greenery as subtle accent. Make it look thoughtfully organized, with strong alignment and usable buttons.
```

## 04 每日简报

```text
Use case: ui-mockup. Create a high fidelity Android mobile app screen for CatWeb, a personal RSS and AI reader. This is a product prototype image, not code and not a promotional poster. Render one straight-on complete Android phone mockup per image, matte graphite narrow 6px bezel, small circular centered hole-punch camera, no Apple notch or Dynamic Island, minimal soft shadow, absolutely no tilt or perspective. Vertical canvas about 1024 x 2048. Phone occupies about 86% width and 93% height, centered, fully visible equal outer margins, calm warm-grey #E8E7E2 backdrop. Interior realistic 412dp-wide screen. Shared design language: warm ivory #FAF9F6 background, charcoal #252B28 text, secondary #6C756F, single muted forest green #356A52 accent, pale sage #E8EEE7 selection fill, fine #E4E6DF separators. Clean humanist Chinese sans serif (Noto Sans SC-like), short beautiful editorial titles, body equivalent 18dp, metadata no smaller than 13dp. Rounded rectangles 12dp only where meaningful, no card nesting, no gradient, no purple, no glass, no decorative dashboards, no fake avatars. Native Android top status bar 9:41 and small right cellular/wifi/battery; consistent bottom gesture line and safe areas. 24dp content side padding, 8dp spacing rhythm, 48dp touch zones. Tiny consistent filled/outlined icons. Reference ideas: Folo legible mobile reading, Reeder restrained lists, Unread spacious typography, Inoreader clear subscription groups, NewsBlur structured briefings. Make an original coherent visual design, do not copy logos or exact product screenshots. All UI text crisp and accurately typeset, Chinese strings verbatim. No captions, annotations, giant titles or color swatches outside the phone. Content must stay large enough to read. The only brand wordmark is CatWeb, used sparingly. Avoid irrelevant widgets and features.
Reference image 1 is the ALREADY APPROVED VISUAL SYSTEM from the reading home. Use it only as a STYLE AND DEVICE REFERENCE. Preserve exactly the phone proportions, matte graphite frame, outer margins, camera, warm white/forest green palette, Chinese font family, line icon weights, padding, status bar and bottom gesture safe area. Create a NEW DISTINCT SCREEN as specified below, not a crop or a repeat of the home screen.
SCREEN 04: DAILY DIGEST RESULT. Dedicated secondary page. No root bottom navigation. Native app bar with back arrow, centered small "日摘要", more menu right.
Near top a small forest-green "设计札记" source label, then a large beautiful date "9月22日", below calm metadata "3 篇文章 · AI 生成 · 20:40".
A small readable single line "基于当前已加载内容" makes the single-feed scope clear.
Editorial digest with spacious calm hierarchy. First section heading "今日概览", body exactly "今天的三篇文章，都在讨论同一件事：如何让阅读更专注，让工具更安静。"
Then section heading "值得记住".
Three numbered takeaway rows. Numbers 01, 02, 03 in muted forest green, text dark, no nested card backgrounds.
01 bold "让阅读轻松一点", supporting "字号与行距，决定长文是否愿意读下去。" with small inline source link "[1]".
02 bold "让工具退后一步", supporting "减少界面干扰，把注意力留给内容。" inline "[2]".
03 bold "给信息留一点空白", supporting "整理订阅，让每天的阅读更有节奏。" inline "[3]".
Below a thin divider and section "原文来源". Three compact link rows with small numbered squares and arrow:
"1  A quieter way to read"
"2  好的工具，应该安静一点"
"3  给信息留一点空白"
At bottom of content small muted "AI 生成内容，请结合原文阅读".
Sticky bottom action bar with two proportionate buttons, light outlined "查看范围" and forest-green filled "重新生成", followed by bottom gesture line. No auto schedule, email, push, charts or AI chat composer.
Keep typography readable, beautiful and original. This is a precise reading brief, not a newsletter marketing cover. Same device/spacing/palette as reference. Fit all three source links; do not omit or invent articles.
```

