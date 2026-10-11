// Lucide SVG → Android VectorDrawable（与 N01/T14 手工转换同一套规则：
// stroke 2、round cap/join，rect/circle/ellipse 展开为 path，currentColor 由主题 tint 承担）
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const SRC = "F:/sokusai/CatWeb/docs/assets/ui";
const OUT = "F:/sokusai/CatWeb/android-native/app/src/main/res/drawable";
const names = process.argv.slice(2);

const rect = (x, y, w, h, rx = 0) =>
  rx > 0
    ? `M${x + rx} ${y}H${x + w - rx}A${rx} ${rx} 0 0 1 ${x + w} ${y + rx}V${y + h - rx}A${rx} ${rx} 0 0 1 ${x + w - rx} ${y + h}H${x + rx}A${rx} ${rx} 0 0 1 ${x} ${y + h - rx}V${y + rx}A${rx} ${rx} 0 0 1 ${x + rx} ${y}Z`
    : `M${x} ${y}H${x + w}V${y + h}H${x}Z`;
const circle = (cx, cy, r) => `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0Z`;
const ellipse = (cx, cy, rx, ry) => `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0Z`;

for (const n of names) {
  const svg = readFileSync(join(SRC, `${n}.svg`), "utf8");
  const paths = [];
  for (const tag of svg.matchAll(/<(path|rect|circle|ellipse)\b([^>]*?)\/?>/g)) {
    const [, kind, attrs] = tag;
    const a = Object.fromEntries(
      [...attrs.matchAll(/([\w-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]),
    );
    const d =
      kind === "path" ? a.d
      : kind === "rect" ? rect(+a.x ?? 0, +a.y ?? 0, +a.width, +a.height, +(a.rx ?? 0))
      : kind === "circle" ? circle(+a.cx, +a.cy, +a.r)
      : ellipse(+a.cx, +a.cy, +a.rx, +a.ry);
    paths.push(`    <path android:pathData="${d}"
        android:strokeColor="#FF000000"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />`);
  }
  const xml = `<!-- 由 docs/assets/ui 的 Lucide 图标转换（tools/svg2vd.mjs） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
${paths.join("\n")}
</vector>
`;
  writeFileSync(join(OUT, `ic_${n.replace(/-/g, "_")}.xml`), xml);
  console.log(`ic_${n.replace(/-/g, "_")}.xml (${paths.length} paths)`);
}
