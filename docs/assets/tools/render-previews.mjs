import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require=createRequire(import.meta.url);
const sharp=require(process.env.CATLEAF_SHARP_PATH || 'sharp');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const read=async f=>fs.readFile(path.join(root,f),'utf8');
const svgRoot=async f=>(await read(f)).replace(/<svg([^>]*)>/,(_,a)=>'<svg'+a.replace(/ role="[^"]*"/g,'').replace(/ aria-label="[^"]*"/g,'')+'>');
const place=async(f,x,y,w,h,color)=>'<g'+(color?' color="'+color+'"':'')+'>'+ (await svgRoot(f)).replace(/<svg[^>]*>/,s=>s.replace(/ width="[^"]*"/,' width="'+w+'"').replace(/ height="[^"]*"/,' height="'+h+'"').replace(' xmlns=',' x="'+x+'" y="'+y+'" xmlns='))+'</g>';
// Show the central 72dp viewport; the 108dp layers include 18dp overscan on each side.
const launcher=async(f,x,y,size)=>(await place(f,x,y,size,size)).replace('viewBox="0 0 108 108"','viewBox="18 18 72 72"');
const text=(x,y,t,size=18,color='#252B28',weight=400)=>'<text x="'+x+'" y="'+y+'" font-family="Segoe UI, Microsoft YaHei, sans-serif" font-size="'+size+'" font-weight="'+weight+'" fill="'+color+'">'+t+'</text>';
const line=(y)=>'<path d="M64 '+y+'H1376" stroke="#E4E6DF"/>';
const header=(eyebrow,title,sub)=>text(64,52,eyebrow,14,'#356A52',600)+text(64,113,title,44,'#252B28',600)+text(64,151,sub,17,'#6C756F');
const svg=(w,h,body)=>'<svg xmlns="http://www.w3.org/2000/svg" width="'+w+'" height="'+h+'" viewBox="0 0 '+w+' '+h+'">'+body+'</svg>\n';
const save=async(name,w,h,body)=>{
 const content=svg(w,h,body);
 await fs.writeFile(path.join(root,'previews',name+'.svg'),content);
 await sharp(Buffer.from(content)).png().toFile(path.join(root,'previews',name+'.png'));
};
let board='<rect width="1440" height="1120" fill="#FAF9F6"/>'+header('CATLEAF / ASSET LIBRARY 01','猫页 · SVG 素材','品牌标记 / Android 适配 / 空态插画 / 操作图标');
board+=line(185);
board+=await place('brand/catleaf-lockup-light.svg',74,216,510,125);
board+=text(78,376,'圆圆的脸，带一点好奇。',22)+text(78,412,'歪头小叶猫 · 可编辑路径 · 无位图与字体依赖',16,'#6C756F');
board+='<rect x="736" y="219" width="640" height="226" rx="20" fill="#181D1A"/>';
board+=await place('brand/catleaf-lockup-dark.svg',804,265,490,120);
board+=line(485);
board+=text(64,531,'01  平台图标',22,'#252B28',600);
board+=text(64,563,'同一主标，适配不同桌面形状。',16,'#6C756F');
board+='<defs><clipPath id="round"><circle cx="128" cy="650" r="52"/></clipPath><clipPath id="squircle"><rect x="226" y="598" width="104" height="104" rx="26"/></clipPath></defs>';
for(const [x,clip] of [[76,'round'],[226,'squircle']]){
 board+='<g clip-path="url(#'+clip+')">'+await launcher('platform/launcher-background.svg',x,598,104)+await launcher('platform/launcher-foreground.svg',x,598,104)+'</g>';
}
board+='<rect x="376" y="598" width="104" height="104" rx="26" fill="#E8EEE7"/>';
board+=(await launcher('platform/launcher-monochrome.svg',376,598,104)).replace(/#000000/g,'#356A52');
board+=text(92,737,'圆形',15,'#6C756F')+text(237,737,'圆角方形',15,'#6C756F')+text(393,737,'主题图标',15,'#6C756F');
board+=text(737,531,'02  空态插画',22,'#252B28',600)+text(737,563,'同一几何结构，深浅主题分别交付。',16,'#6C756F');
board+=await place('illustrations/empty-reading-light.svg',750,582,222,185);
board+='<rect x="1070" y="575" width="290" height="202" rx="16" fill="#181D1A"/>';
board+=await place('illustrations/empty-reading-dark.svg',1104,582,222,185);
board+=line(807);
board+=text(64,855,'03  界面操作图标',22,'#252B28',600)+text(64,887,'Lucide 0.562.0 · 24 × 24 · 统一圆角描边',16,'#6C756F');
const main=['newspaper','bookmark','settings','plus','arrow-left','refresh-cw','languages','share-2','download','trash-2','wifi-off','circle-check'];
for(let i=0;i<main.length;i++)board+=await place('ui/'+main[i]+'.svg',76+i*109,933,32,32,'#356A52');
board+=text(64,1062,'设计交付 v2.0   /   2026.09.22',14,'#6C756F')+text(932,1062,'完整源文件与使用规则见素材目录',14,'#6C756F');
await save('asset-overview',1440,1120,board);

const manifest=JSON.parse(await read('ui-manifest.json'));
let ui='<rect width="1440" height="1100" fill="#FAF9F6"/>'+header('CATLEAF / ASSET LIBRARY 02','界面图标 · 全集','39 个 Lucide 原图标 + 1 个已收藏派生状态');
for(let i=0;i<manifest.icons.length;i++){
 const item=manifest.icons[i], col=i%8,row=Math.floor(i/8),x=64+col*165,y=218+row*161;
 ui+='<rect x="'+x+'" y="'+y+'" width="150" height="139" rx="12" fill="#FFFFFF" stroke="#E4E6DF"/>';
 ui+=await place(item.file,x+58,y+19,32,32,'#356A52');
 ui+=text(x+12,y+83,path.basename(item.file,'.svg'),13,'#252B28',600);
 ui+=text(x+12,y+111,item.label.split('、')[0],13,'#6C756F');
}
await save('ui-icons',1440,1100,ui);

let scales='<rect width="1200" height="640" fill="#FAF9F6"/><rect x="600" width="600" height="640" fill="#181D1A"/>';
scales+=text(48,56,'浅色背景 / LIGHT',19,'#356A52',600)+text(648,56,'深色背景 / DARK',19,'#94C9A6',600);
for(const [offset,theme,ink]of[[0,'',' #6C756F'],[600,'-dark','#ABB5AB']]){
 scales+=await place('brand/catleaf-mark'+theme+'.svg',offset+185,88,230,230);
 for(const [i,size]of[16,24,32,48].entries()){
  const x=offset+78+i*126;
  scales+=await place('brand/catleaf-mark'+theme+'.svg',x+24-size/2,397-size/2,size,size);
  scales+=text(x+1,458,size+' px',15,ink.trim());
 }
 scales+=text(offset+48,553,'实际输出尺寸展示；放大页面不等于小尺寸检查。',16,ink.trim());
}
await save('small-sizes',1200,640,scales);

let masks='<rect width="1440" height="680" fill="#FAF9F6"/>'+header('CATLEAF / ASSET LIBRARY 03','Android 图标检查','108 × 108 图层 / 中央 72 × 72 视口 · 圆形 / 圆角 / 方形 / 主题单色');
for(const [i,shape]of['circle','rounded','square','mono'].entries()){
 const x=80+i*340,y=236;
 const mask=shape==='circle'?'<circle cx="'+(x+126)+'" cy="'+(y+126)+'" r="126"/>':'<rect x="'+x+'" y="'+y+'" width="252" height="252" rx="'+(shape==='square'?0:64)+'"/>';
 masks+='<defs><clipPath id="mask'+i+'">'+mask+'</clipPath></defs><g clip-path="url(#mask'+i+')">';
 if(shape==='mono'){
  masks+='<rect x="'+x+'" y="'+y+'" width="252" height="252" fill="#DBE5ED"/>';
  masks+=(await launcher('platform/launcher-monochrome.svg',x,y,252)).replace(/#000000/g,'#335D78');
 }else masks+=await launcher('platform/launcher-background.svg',x,y,252)+await launcher('platform/launcher-foreground.svg',x,y,252);
 masks+='</g>'+text(x,y+299,['圆形裁切','圆角方形裁切','方形预览','系统主题示例'][i],20,'#252B28',600);
}
masks+=text(64,625,'以上仅为设计裁切预览，Android 资源转换与真机显示在 T02 / T24 验证。',17,'#6C756F');
await save('launcher-masks',1440,680,masks);

let splash='<rect width="1000" height="930" fill="#E8E7E2"/>';
for(const [i,theme]of['light','dark'].entries()){
 const x=58+i*476,bg=i?'#181D1A':'#FAF9F6',ink=i?'#ABB5AB':'#6C756F';
 splash+='<rect x="'+x+'" y="56" width="408" height="776" rx="28" fill="'+bg+'"/>';
 splash+=await place('platform/splash-icon-'+theme+'.svg',x+60,280,288,288);
 splash+=text(x,880,i?'深色系统启动屏':'浅色系统启动屏',20,'#252B28',600);
}
await save('splash-preview',1000,930,splash);
let concepts='<rect width="1440" height="1010" fill="#FAF9F6"/>'+header('CATLEAF / LOGO STUDY 02','一只爱看世界的小猫。','圆一点，软一点，带一点好奇。');
concepts+='<path d="M64 190H1376 M499 224V742 M943 224V742" stroke="#DDE4DA"/>';
const directions=[['01-curious-cat','A / 歪头小叶猫','已定稿 · 安静又好奇'],['02-winking-cat','B / 眨眼小猫','未采用 · 保留选型历史'],['03-tail-cat','C / 摇尾小猫','未采用 · 保留选型历史']];
for(const [i,[file,title,caption]]of directions.entries()){
 const x=76+i*444;
 concepts+=text(x,241,title,23,'#356A52',600);
 concepts+=await place('concepts/v2/'+file+'.svg',x+60,290,260,260);
 concepts+=text(x,597,caption,18,'#6C756F');
 concepts+='<rect x="'+x+'" y="634" width="84" height="84" rx="23" fill="#356A52"/>';
 concepts+=(await place('concepts/v2/'+file+'.svg',x+4.667,638.667,74.666,74.666)).replace(/#356A52/g,'#FAF9F6');
 concepts+=text(x+105,683,'桌面图标效果',16,'#6C756F');
}
concepts+='<rect x="64" y="791" width="1312" height="159" rx="20" fill="#E8EEE7"/>';
concepts+=text(96,834,'正式采用 A',20,'#356A52',600)+text(96,876,'保留你参考图的圆脸与笑嘴，用微微歪头和一片小叶子形成猫页的记忆点。',21,'#252B28');
concepts+=text(96,916,'用户已确认 A「歪头小叶猫」；品牌与 Android 图标资源统一采用 A，B / C 留作历史。',16,'#6C756F');
await save('logo-directions',1440,1010,concepts);
console.log(JSON.stringify({previewBoards:6,format:'SVG + PNG'}));
