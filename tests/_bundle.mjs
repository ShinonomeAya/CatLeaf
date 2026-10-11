/**
 * 把 `server/` 下的 TS 模块打包成可被 Node 直接 import 的 ESM。
 *
 * 源码用 `.js` 说明符引用 `.ts`（Workers 打包约定），Node 解析不了；
 * 这里在 esbuild 解析阶段把存在的 .js 换成 .ts。
 * 多个测试都要用，故集中在此，避免每个测试各抄一份。
 */
import { build } from 'esbuild';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const jsToTsPlugin = {
  name: 'js-to-ts',
  setup(b) {
    b.onResolve({ filter: /\.js$/ }, async (args) => {
      if (!args.importer) return null;
      const candidate = path.resolve(path.dirname(args.importer), args.path);
      const tsPath = candidate.replace(/\.js$/, '.ts');
      try {
        await import('node:fs/promises').then((fs) => fs.access(tsPath));
        return { path: tsPath };
      } catch {
        return null;
      }
    });
  },
};

/**
 * 一次打包多个模块，返回合并后的导出。
 *
 * 必须走**同一个** bundle：分开打包会让共享依赖出现两份，
 * 类身份不一致（`instanceof` 跨包失效）。生产由 wrangler 打成单一图，不存在这个问题。
 *
 * specs: [{ module: 'server/http.ts', names: ['secureFetch', ...] }, ...]
 */
export async function bundleModules(specs) {
  const outDir = await mkdtemp(path.join(tmpdir(), 'catleaf-bundle-'));
  const entry = path.join(outDir, 'entry.ts');
  const outFile = path.join(outDir, 'entry.mjs');

  const lines = specs.flatMap(({ module, names }) => {
    const abs = path.resolve(module);
    return names.map((n) => `export { ${n} } from ${JSON.stringify(abs)};`);
  });
  await writeFile(entry, lines.join(String.fromCharCode(10)) + String.fromCharCode(10));

  await build({
    entryPoints: [entry],
    outfile: outFile,
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2022',
    plugins: [jsToTsPlugin],
    logLevel: 'warning',
  });

  return import(pathToFileURL(outFile).href);
}
