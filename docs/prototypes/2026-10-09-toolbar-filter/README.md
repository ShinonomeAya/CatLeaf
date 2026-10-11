# 阅读筛选 A / B / C 候选稿

2026-10-09，用户已选择 **C · 紧凑标签**。局部继承原型01/08/21/32，暖白/森林绿、系统字体、根页CatLeaf→阅读和三个底部入口保持。下列候选截图保留设计比较记录；Android实施与验收另行记录。

- [交互页面](index.html)：选择360/411、字号1.0/1.3、深浅和断网；可操作日期、仅未读、清除、长源名和返回。示例文章仅用于设计，不触及App数据。
- [三案浅色对比](compare-light.png)：A轻量开关、B阅读状态分段（推荐）、C紧凑标签。
- [三案深色大字](compare-dark-large.png)：360模拟宽度、1.3字号。
- [源码评审与取舍](../../2026-10-09-工具栏与未读筛选评审.md)，[验证证据与24张截图](../../validation/2026-10-09-toolbar-filter-review/README.md)。

本地预览（Python已存在）：在项目docs目录运行`python -m http.server 8793 --bind 127.0.0.1`，打开`http://127.0.0.1:8793/prototypes/2026-10-09-toolbar-filter/index.html`。本轮已启动本地预览；不对公网开放、不属于部署/发布。双击file://可能因本地图标fetch被浏览器限制，优先使用本地地址。

`browser-check.js`为可重放交互/宽度检查，通过Playwright MCP的`browser_run_code_unsafe`提供该文件绝对路径执行。运行器返回矩阵结果，24张原型截图写入独立验证目录；console错误单独检查。该脚本不能检测或证明Android真实布局。

选定C后，阅读页使用日期标签展开日期选项、独立「仅未读」标签、下方数量/清除；PRD、页面说明和设计规范已同步本次选择。复用Compose组件，没有将候选整页切图导入App，没有新增生产依赖。最终Android证据见[本轮实施验收](../../validation/2026-10-09-compact-filter-fix/README.md)。

用户随后要求日期范围面板参考「选择订阅」样式，Android已改为图标块、淡绿选中行和右侧勾选，设计规范§9同步。上方候选HTML保留原比较记录，最新面板实机效果与验证见[日期面板返修](../../validation/2026-10-09-date-sheet-refine/README.md)。
