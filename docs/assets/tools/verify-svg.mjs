import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
const require=createRequire(import.meta.url);
const sharp=require(process.env.CATLEAF_SHARP_PATH || 'sharp');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dirs=['brand','platform','illustrations','ui'];
const files=(await Promise.all(dirs.map(async d=>(await fs.readdir(path.join(root,d))).filter(f=>f.endsWith('.svg')).map(f=>d+'/'+f)))).flat().sort();
const checks=[];
for(const file of files){
 const bytes=await fs.readFile(path.join(root,file));
 const source=bytes.toString();
 assert(!/<(?:script|image|foreignObject|text|filter|style|use)\b|<!DOCTYPE|\bon\w+\s*=|(?:href|url)\s*=/i.test(source),'Nonportable or embedded content: '+file);
 const view=source.match(/viewBox="([^"]+)"/)?.[1].split(/\s+/).map(Number);
 assert(view?.length===4 && view.every(Number.isFinite) && view[2]>0 && view[3]>0,'Invalid viewBox '+file);
 const {data,info}=await sharp(bytes).resize(96,96,{fit:'contain',background:'#00000000'}).ensureAlpha().raw().toBuffer({resolveWithObject:true});
 let painted=0,transparent=0;
 for(let p=3;p<data.length;p+=4){if(data[p]>0)painted++;if(data[p]===0)transparent++;}
 assert(painted>0,'Empty image '+file);
 if(file!=='platform/launcher-background.svg')assert(transparent>0,'Missing transparency '+file);
 checks.push({file,viewBox:view.join(' '),sha256:createHash('sha256').update(bytes).digest('hex'),rasterized:true});
}
const raster=async(f,size)=>sharp(path.join(root,f)).resize(size,size,{fit:'fill'}).ensureAlpha().raw().toBuffer({resolveWithObject:true});
const sameAlpha=async(a,b)=>{const x=await raster(a,432),y=await raster(b,432);for(let i=3;i<x.data.length;i+=4)assert.equal(x.data[i],y.data[i],a+' geometry differs');};
await sameAlpha('brand/catleaf-mark.svg','brand/catleaf-mark-dark.svg');
await sameAlpha('brand/catleaf-mark.svg','brand/catleaf-mark-mono.svg');
await sameAlpha('brand/catleaf-lockup-light.svg','brand/catleaf-lockup-dark.svg');
await sameAlpha('illustrations/empty-reading-light.svg','illustrations/empty-reading-dark.svg');
await sameAlpha('platform/launcher-foreground.svg','platform/launcher-monochrome.svg');
await sameAlpha('platform/splash-icon-light.svg','platform/splash-icon-dark.svg');
const checkCircle=async(f,canvas,safeRadius)=>{
 const {data,info}=await raster(f,canvas*5);let maximum=0;
 for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++){
  if(data[(y*info.width+x)*4+3]<128)continue;
  maximum=Math.max(maximum,Math.hypot((x+.5)/5-canvas/2,(y+.5)/5-canvas/2));
 }
 assert(maximum<=safeRadius,f+' outside safe circle: '+maximum);
 return {file:f,paintedRadiusDp:Number(maximum.toFixed(2)),safeRadiusDp:safeRadius};
};
const safeAreas=[await checkCircle('platform/launcher-foreground.svg',108,33),await checkCircle('platform/splash-icon-light.svg',288,96)];
const mark=await raster('brand/catleaf-mark.svg',384);
const faceAlpha=(x,y)=>{
 const a=-7*Math.PI/180,dx=x-64,dy=y-72;
 const px=Math.floor((64+dx*Math.cos(a)-dy*Math.sin(a))*3);
 const py=Math.floor((72+dx*Math.sin(a)+dy*Math.cos(a))*3);
 return mark.data[(py*384+px)*4+3];
};
for(const [x,y] of [[44,64],[83,64],[57,84],[72,84]])assert.equal(faceAlpha(x,y),0,'Eyes and smile must remain transparent');
assert.equal(faceAlpha(32,85),255,'Cat silhouette must be opaque');
assert.equal(mark.data[(48*384+234)*4+3],255,'Leaf accent must be opaque');
const manifest=JSON.parse(await fs.readFile(path.join(root,'ui-manifest.json'),'utf8'));
for(const icon of manifest.icons)assert.equal(checks.find(f=>f.file===icon.file)?.sha256,icon.sha256,'UI source changed: '+icon.file);
assert.equal(manifest.verifiedExports,39);
assert.equal(manifest.icons.length,40);
assert.equal(files.length,52);
const report={checkedAt:new Date().toISOString(),assetVersion:'2.0',selectedConcept:'A / curious cat',scope:'SVG source and raster verification only; no Android runtime test; user design selection is recorded in README.md',productionSvgCount:files.length,uiIconCount:manifest.icons.length,verifiedReactExports:manifest.verifiedExports,externalImagesFontsScripts:0,themeGeometryConsistent:true,transparentFaceVerified:true,solidLeafVerified:true,safeAreas,files:checks};
await fs.writeFile(path.join(root,'verification.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({...report,files:undefined},null,2));
