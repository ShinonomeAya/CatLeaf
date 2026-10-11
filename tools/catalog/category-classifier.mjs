const PATH_RULES = [
  [/\/readhub\/technews/, 'dev'],
  [/\/(?:juejin|v2ex|github|toutiao\.io|manong-weekly|frontshow|importnew|linux\.cn|csdnnews|infoq)(?:\/|$)/, 'dev'],
  [/\/(?:oschina|freebuf|coolapk)(?:\/|$)/, 'tech'],
  [/\/(?:readhub|36kr|ifanr|ithome|pingwest|leiphone|expreview|dgtle|cnbeta|mittrchina|qqtech|ai_era|techread)(?:\/|$)/, 'tech'],
  [/\/bing(?:\/|$)/, 'design'],
  [/\/(?:fortunechina|hbrchinese|wallstreetcn|caixinwang|xueqiujinghua|tancaijing|wuxiaobopd|dgjdds|worldofboss|meigushe|newfortune|jjbd21|eeo|forbes_china|jiemian\/(?:business|finance)|caijing|dapenti\/caijing|qqfinance)(?:\/|$)/, 'business'],
  [/\/(?:guokr|squirrelclub|scientificamerican|yixuejiezazhi|dili360|mooc|bbc\/learningenglish|msrasia|dingxiangyisheng)(?:\/|$)/, 'science'],
  [/\/(?:ssapi|appinn|ruancan|aliyunpan)(?:\/|$)/, 'productivity'],
  [/\/(?:jandan|jiandan|qiubai)(?:\/|$)/, 'life'],
  [/\/(?:sports_sina|pentitugua)(?:\/|$)/, 'news'],
  [/\/(?:zhihu|one|douban|book|dushu|meiriyiwen|jianshu|dujinyong6|dandureading|duxiaoku|renwumag|irismagazine|ikanlixiang|xilei)(?:\/|$)/, 'culture'],
  [/\/(?:gcores|bilibili|gamersky|vgtime|indienova|chuapp)(?:\/|$)/, 'gaming'],
  [/\/(?:zaobao|infzm|thepaper|initium|voa|reuters|nytimes|wsj|bbc|rfi|aljazeera|sbs|chinadaily|newscn|newsxinhua|people|people-daily|rmrb|guangmingribao|qstheory|jiefangjunbao|phoenixweekly|banyuetan|bjnews|netease|cctvnewscenter|qq\/news|weibo|chouti|idaily)(?:\/|$)/, 'news'],
  [/(?:^|\/)news(?:\/|$)/, 'news'],
];

const NAME_RULES = [
  [/博客|blog|日志|网志|梦呓/i, 'blog'],
  [/技术|开发|编程|程序员|前端|后端|架构|代码|linux|importnew|csdn/i, 'dev'],
  [/财经|经济|投资|商业|金融|股票|理财|地产|电商|财富|企业|美股|福布斯/i, 'business'],
  [/科技|数码|手机|硬件|智能|互联网|产品|软件|人工智能|云盘/i, 'tech'],
  [/新闻|时事|早报|晚报|日报|周谈|热点|热榜|头条|快讯|广播|时报|通讯社|新华社|人民日报/i, 'news'],
  [/游戏|电竞|主机|任天堂|索尼|独立游戏/i, 'gaming'],
  [/设计|创意|美学|摄影|插画|ui|ux|壁纸/i, 'design'],
  [/科学|科普|教育|学习|知识|物理|天文|医学|心理|地理|自然|果壳|mooc/i, 'science'],
  [/效率|工具|应用|笔记/i, 'productivity'],
  [/文化|读书|文学|历史|艺术|电影|影视|音乐|播客|书单|文摘|故事|人文|简书|读库|有书/i, 'culture'],
  [/生活|美食|旅行|健康|家居|健身|母婴|时尚|糗事|笑话|煎蛋/i, 'life'],
];

export function classifyNameCategory(name) {
  for (const [pattern, categoryId] of NAME_RULES) {
    if (pattern.test(name)) return categoryId;
  }
  return null;
}

export function classifyGeneratedFeedCategory(name, feedUrl) {
  let url;
  try {
    url = new URL(feedUrl);
  } catch {
    return null;
  }
  const host = url.hostname.toLowerCase();
  if (host !== 'rsshub.app' && host !== 'plink.anyfeeder.com' && !host.endsWith('.anyfeeder.com') &&
      host !== 'feeds.feedburner.com' && host !== 'feeds2.feedburner.com') return null;

  const path = url.pathname.toLowerCase();
  for (const [pattern, categoryId] of PATH_RULES) {
    if (pattern.test(path)) return categoryId;
  }
  return classifyNameCategory(name) ?? 'other';
}
