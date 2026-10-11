import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const commit='6b4075b89b4016c4e1fb7165bc340c734ee466ec';
const rows=[["newspaper","Newspaper","阅读入口","01,07,20,21"],["bookmark","Bookmark","收藏、取消收藏","01,02,06,20,21"],["settings","Settings","设置入口","01,06,07,20,21"],["plus","Plus","添加订阅","03,09,20"],["arrow-left","ArrowLeft","二级页返回","02–05,09–19,22"],["x","X","关闭面板、取消","08"],["chevron-down","ChevronDown","源选择、日期选择","01,08,21"],["chevron-right","ChevronRight","进入下一层","03,07–09,14–18,22"],["refresh-cw","RefreshCw","刷新、重试、重新生成","01,03,04,11,19"],["ellipsis","Ellipsis","文章更多操作","02"],["type","Type","字号、阅读偏好","02,07,16"],["languages","Languages","翻译、原译切换","02"],["share-2","Share2","系统分享","02"],["external-link","ExternalLink","原网页","02,04"],["check","Check","选中项","08,10,13,16"],["square","Square","未选中复选框","10,13"],["square-check","SquareCheck","已选中复选框","10,13"],["circle-check","CircleCheck","保存/连接成功","06,11,16,17,19,20,22"],["circle-alert","CircleAlert","异常、部分失败","03,08,11,19"],["wifi-off","WifiOff","离线提示","21"],["eye","Eye","显示密钥","05,12,17"],["eye-off","EyeOff","隐藏密钥","05,12,17"],["link","Link","实例、收藏链接","07,14,17"],["folder","Folder","订阅分组","03,08,09,22"],["rss","Rss","订阅源通用图标","03,08"],["upload","Upload","导出状态/OPML/链接","03,14"],["download","Download","导入/恢复文件","03,10,14,15"],["trash-2","Trash2","清理、删除、重置","12,14,22"],["database","Database","存储与备份","07,14"],["file-text","FileText","日摘要、文件、项目说明","01,04,13,15,18"],["info","Info","帮助性说明、关于","07,10,13–15,18"],["circle-question-mark","CircleQuestionMark","配置帮助","05,18"],["calendar-days","CalendarDays","日期选择","01,04,13"],["lock","Lock","地址只读、配置保护","22"],["loader-circle","LoaderCircle","请求进行中","05,09–15,17,19"],["cpu","Cpu","AI配置与服务","07,12,13"],["book-open","BookOpen","阅读类订阅","03,08"],["code-xml","CodeXml","开源许可","18"],["image-off","ImageOff","文章图片不可用","02,06,21"]];
const get=async url=>{for(let attempt=0;attempt<3;attempt++){try{const r=await fetch(url,{signal:AbortSignal.timeout(30000)});if(!r.ok)throw Error(url+' '+r.status);return await r.text();}catch(error){if(attempt===2)throw error;}}};
await fs.mkdir(path.join(root,'ui'),{recursive:true});
await fs.mkdir(path.join(root,'licenses'),{recursive:true});
const declarations=await get('https://unpkg.com/lucide-react@0.562.0/dist/lucide-react.d.ts');
const exports=declarations.match(/export \{([^}]+)\}/g)?.join('\n') || '';
const icons=[];
for(let start=0;start<rows.length;start+=6){
 await Promise.all(rows.slice(start,start+6).map(async ([name,component,label,pages])=>{
  if(!new RegExp('(?:[ ,])'+component+'(?:[, ;])').test(exports))throw Error('Missing component export '+component);
  const source='https://raw.githubusercontent.com/lucide-icons/lucide/'+commit+'/icons/'+name+'.svg';
  const svg=await fs.readFile(path.join(root,'ui',name+'.svg'),'utf8').catch(()=>get(source));
  if(!svg.includes('viewBox="0 0 24 24"') || /<script|<image|<foreignObject/i.test(svg))throw Error('Invalid icon '+name);
  const file='ui/'+name+'.svg';
  await fs.writeFile(path.join(root,file),svg);
  icons.push({file,component,label,pages,source,sha256:createHash('sha256').update(svg).digest('hex'),modified:false});
 }));
}
icons.sort((a,b)=>rows.findIndex(r=>r[0]+'.svg'===path.basename(a.file))-rows.findIndex(r=>r[0]+'.svg'===path.basename(b.file)));
const license=await get('https://raw.githubusercontent.com/lucide-icons/lucide/'+commit+'/LICENSE');
await fs.writeFile(path.join(root,'licenses/LUCIDE-LICENSE.txt'),license);
const active=(await fs.readFile(path.join(root,'ui/bookmark.svg'),'utf8')).replace('fill="none"','fill="currentColor"');
await fs.writeFile(path.join(root,'ui/bookmark-active.svg'),active);
icons.push({file:'ui/bookmark-active.svg',component:'Bookmark (fill=currentColor)',label:'已收藏',pages:'02,06',source:icons.find(i=>i.component==='Bookmark').source,sha256:createHash('sha256').update(active).digest('hex'),modified:true,change:'Only root fill changed from none to currentColor'});
await fs.writeFile(path.join(root,'ui-manifest.json'),JSON.stringify({version:'0.562.0',commit,typeDeclarationsSha256:createHash('sha256').update(declarations).digest('hex'),verifiedExports:rows.length,license:'licenses/LUCIDE-LICENSE.txt',icons},null,2)+'\n');
console.log(JSON.stringify({downloaded:icons.length,verifiedComponentExports:rows.length,commit}));
