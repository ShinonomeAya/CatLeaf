/**
 * L03 分类修订（人工核对 other 桶后的逐条归类；verify 与 reclassify 共用）。
 * 键为快照名称或域名，值为 12 分类 ID 之一。
 */
export const NAME_OVERRIDES = {
  '虎嗅': 'tech', '虎嗅网': 'tech',
  '胡涂说': 'blog', 'draveness.me': 'dev', '晚晴幽草轩': 'blog',
  'Decohack': 'tech', '卡瓦邦噶！ » Feed': 'dev', '卡瓦邦噶！': 'dev',
  '离别歌': 'dev', '风雪之隅': 'dev', 'HelloGitHub 月刊': 'dev',
  'maxOS': 'tech', 'GeekPlux': 'dev', 'CSS-Tricks': 'dev',
  'Julia Evans': 'dev', 'MacTalk-池建强的随想录': 'blog',
  '反斗限免': 'tech', '扯氮集': 'blog', '二丫讲梵': 'blog',
  'Tony Bai': 'dev', '奔跑中的奶酪': 'productivity',
  '卢昌海的个人主页': 'science', '疯投圈': 'business',
  'Tiny Projects': 'blog', '笨方法学写作': 'culture',
  '今日话题 - 雪球': 'business', '西秦公子': 'dev',
  '小明明s à domicile': 'dev', '小胡子哥的个人网站': 'dev',
  '小球飞鱼': 'blog', 'Anthony Fu': 'dev', 'The Verge': 'tech',
  '刘未鹏   Mind Hacks': 'blog', 'Mind Hacks': 'blog',
  '机核': 'gaming', '极客公园': 'tech', '理想生活实验室': 'life',
  '人人都是产品经理': 'tech', '阮一峰的网络日志': 'dev',
  '钛媒体：引领未来商业与生活新知': 'tech', '知乎日报': 'culture', '太隐': 'blog',
  '酷安 - 新鲜图文': 'tech', 'InfoQ 推荐': 'dev', '参考消息': 'news',
  '罗辑思维': 'culture', 'MacTalk': 'tech', '半月谈': 'news', '猫笔刀': 'business',
  '小道消息': 'dev', '新浪专栏-创事记': 'tech', '喷嚏-70': 'news',
  '长安街知事': 'news', '搬砖小组': 'business', 'keso怎么看': 'tech', '棱镜': 'business', '侠客岛': 'news',
  '少数派': 'productivity', '少数派 -- Matrix': 'productivity', 'Vista看天下': 'news',
  'Knowyourself': 'life', '魔宙': 'culture', '铁血军事': 'news', '德林社': 'business',
  '人民网': 'news', '读小库': 'culture', '唐书房': 'business', '界面': 'news',
  '新世相': 'culture', '猎云网': 'tech', '新浪体育': 'news', '译言': 'culture',
  '槽边往事': 'blog', '物种日历': 'science', 'X博士': 'culture',
  '张佳玮写字的地方': 'culture', '南方周末': 'news', '三节课': 'science',
  '新京报书评周刊': 'culture', 'LinkedIn 领英': 'business', '人物': 'culture',
  '连岳': 'blog', '南都周刊': 'news', '刘润': 'business', '丁香妈妈': 'life',
  '青年文摘': 'culture', '三联生活周刊': 'culture', '香港凤凰周刊': 'news',
  '码农周刊': 'dev', '喷嚏网-财经风云': 'business', '喷嚏网-铂程斋': 'culture',
  '洞见': 'other', '理想岛': 'other', '毛有话说': 'other',
};

/** 首选五源的 curated 元数据（ADR 003；引导与精选顺序引用） */
export const FIVE_META = {
  'https://sspai.com/feed': { categoryId: 'productivity', language: 'zh' },
  'https://www.ifanr.com/feed': { categoryId: 'tech', language: 'zh' },
  'https://www.ithome.com/rss/': { categoryId: 'tech', language: 'zh' },
  'https://www.ruanyifeng.com/blog/atom.xml': { categoryId: 'dev', language: 'zh' },
  'https://www.theverge.com/rss/index.xml': { categoryId: 'tech', language: 'en' },
};

export function applyOverrides(dispositions) {
  for (const d of dispositions) {
    const five = FIVE_META[d.originalUrl] || FIVE_META[d.url];
    if (five) {
      d.categoryId = five.categoryId;
      d.language = five.language;
    } else if (NAME_OVERRIDES[d.name]) {
      d.categoryId = NAME_OVERRIDES[d.name];
    }
  }
  return dispositions;
}
