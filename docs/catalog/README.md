# 目录核验与生成方法（L03 / C03）

- 逐条处置台账：[核验台账.md](核验台账.md)（338 条，含失败原因、同名条目注记、HTTPS 替代记录）
- 应用静态清单：`android-native/app/src/main/assets/catalog/catalog.json`（303 条：可收录 78 + 需配置 225；首选五源固定顺序；版本 `2026-09-27.3`）

## 数据来源与边界

- 候选快照固定为 [top-rss-list@3892c301](https://github.com/weekend-project-space/top-rss-list/blob/3892c30181036eeee6e67f0750fad0457bfb59c7/README.md)（2026-08-18 版 README 表格 340 条），本地存档 `tools/catalog/snapshot-top-rss-list-3892c301.md`。该仓库根目录**没有 LICENSE 文件**，不将其视为任何许可证授权：本项目只把表格中的地址用作发现线索，自写的名称整理/分类/描述与地址数据独立记录，不整段搬用其介绍或图标。
- 首选五源（少数派、爱范儿、IT之家、阮一峰的网络日志、The Verge）来自 ADR 003 的产品决策；快照缺 The Verge，已补齐并标记 `provenance=preferred-five`。

## 核验方法（2026-09-25）

工作机直连（Node fetch，**无代理**），受控限流：并发 4、请求间隔 300ms、超时 10s、响应上限 1 MiB、网络层失败仅 1 次重试、失败不无限重试、不使用公共代理。判据：HTTP 2xx 且文档含 `<rss>/<feed>/<rdf>` 根与 `<item>/<entry>` 条目才记「可收录」；HTTP 200 但解析不出条目（如知乎空文档）记「待复验」，不冒充可用。http 候选逐条实测 https 替代后才改写地址（台账记「原 http 地址」），不做字符串替换。

- 2026-09-25 全量处置：可收录 75（其中 HTTPS 替代若干）、需配置 225（rsshub.app 30、plink.anyfeeder.com 192、feedburner 3——第三方生成源需要可用实例，默认不使用陌生公共代理）、待复验 38（当时不可达/超时/非 XML，不代表永久失效）。
- 2026-09-27 初次重查上述 38 条，原始响应与失败原因见 [复查记录](../validation/2026-09-27-catalog-recheck.json)。CoolShell 的 HTTPS 替代与土木坛子经 XML 解析分别有 15、5 条，并以 App 所用 OkHttp User-Agent 再验；另 36 条随后再次受控重查，只有太隐的 `https://wangyurui.com/feed.xml` 经隔离模拟器 App 网络及 RSS 解码解析出条目，余 35 条仍待复验。续查原始结果见[复查补记](../validation/2026-09-27-catalog-recheck-followup.json)。当前分布为可收录 78、需配置 225、待复验 35；原有 75 条和 225 条未重查。
- 同日分类复核：旧目录 192 条需配置源集中“新闻”，经共享生成源分类规则、名称规则及逐项覆盖修订后，当前应用目录 303 条覆盖全部 12 类；其中 61 条仍归新闻，逐条对应新闻或资讯路由。当前分类断言和 Android 资产解析定向测试通过，分类发现页面的完整键盘、TalkBack、视觉验收仍待 L08。见[分类审查记录](../validation/2026-09-27-c03-classification.md)。
- 35 条余下待复验源再次做受控直连 GET（不使用代理），没有发现可直接升级入目录的 feed-shaped 响应：21 次 fetch failed、3 超时、2×403、2×404、1×202、6×200 但无feed根与条目。HTTP失败/HTML响应不证明源永久失效，处置继续标记 recheck；详情见[续查 JSON](../validation/2026-09-27-catalog-recheck-followup-r2.json)。隔离 AVD 分类页本轮确认全部12类可从“全部分类”打开，选择“新闻时事”后首屏6条的分类标签一致；发现原活动分类 chip 会停留在屏外，现调整为选中类优先显示且横向列表回到开头，[修复前截图](../validation/2026-09-27-c03-news-filter-avd-before.png)与[修复后截图](../validation/2026-09-27-c03-news-filter-avd-after.png)可对照。
- 平衡校验：快照 340 + 补五源 1 = 处置 338 + 快照内同 URL 合并 3，由 `verify-catalog.mjs` 断言，不平即失败。
- 同名不同频道不误并：去重仅按规范化 URL；快照内同名条目（阮一峰 http/https、少数派多频道等）在台账别名列注记。

## 再生成

```bash
node tools/catalog/verify-catalog.mjs   # 重新核验（受控限流）→ dispositions.json
node tools/catalog/reclassify.mjs        # 仅更新已有处置分类，不联网
node tools/catalog/build-outputs.mjs    # dispositions.json → 台账 + assets/catalog/catalog.json
```

分类修订改 `tools/catalog/overrides.mjs` 后依次运行 `reclassify.mjs`、`build-outputs.mjs`，无需重新联网核验。

- 2026-09-28 对35条 `recheck` 源第三轮限流直连复查，0条返回 RSS/Atom 根与条目；本轮网络失败/HTTP 非feed仍保留待复验，不视为永久失效。原始响应见[第三轮复查 JSON](../validation/2026-09-28-catalog-recheck-followup-r3.json)。目录资产仍为303条、78可收录/225需配置，新闻类61条。
