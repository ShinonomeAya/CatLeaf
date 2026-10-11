#!/usr/bin/env python3
"""把 docs/assets/ui 的 Lucide SVG 转成 Android VectorDrawable。

用法（在仓库根执行）：
    python docs/assets/tools/svg-to-vectordrawable.py <源.svg> <输出.xml> [--filled] [--color #RRGGBB]

为什么单独留成脚本：这套转换临时重写过三次，每次都漏掉一类元素或属性，
代价是渲染出来缺件（例：circle-alert 的感叹号是两个 <line>，早期版本直接丢了）。
支持的元素与属性按交付素材里实际出现的来，不做通用 SVG 转换器。

覆盖：
- 元素：path / rect(含 rx) / circle / ellipse / line / polyline / polygon
- 属性：fill 继承（Lucide 常把 fill 放在父 <g> 上）、fill-rule="evenodd"
- 组变换：translate/scale（Lucide 图标基本不用，仅告警提示）
- 描边：stroke-width / linecap / linejoin
"""
import argparse
import re
import sys
from pathlib import Path


def num(attrs: str, name: str, default=None):
    m = re.search(rf'{name}="(-?[\d.]+)"', attrs)
    return float(m.group(1)) if m else default


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def rect_path(x, y, w, h, rx):
    if not rx:
        return f"M{x},{y}h{w}v{h}h{-w}z"
    return (
        f"M{x + rx},{y}h{w - 2 * rx}a{rx},{rx} 0 0 1 {rx},{rx}v{h - 2 * rx}"
        f"a{rx},{rx} 0 0 1 {-rx},{rx}h{-(w - 2 * rx)}a{rx},{rx} 0 0 1 {-rx},{-rx}"
        f"v{-(h - 2 * rx)}a{rx},{rx} 0 0 1 {rx},{-rx}z"
    )


def ellipse_path(cx, cy, rx, ry):
    if rx == ry:
        return f"M{cx - rx},{cy}a{rx},{rx} 0 1 0 {2 * rx},0a{rx},{rx} 0 1 0 {-2 * rx},0z"
    # 椭圆用两段弧近似，VectorDrawable 支持 a 命令
    return (
        f"M{cx - rx},{cy}a{rx},{ry} 0 1 0 {2 * rx},0a{rx},{ry} 0 1 0 {-2 * rx},0z"
    )


def convert(src: Path, dst: Path, filled: bool, color: str) -> None:
    svg = src.read_text(encoding="utf-8")
    if 'transform="translate' in svg:
        print(f"警告：{src.name} 含 translate 组变换，本脚本不处理，请人工转换", file=sys.stderr)

    body = []
    # fill 可能写在父 <g> 上（Lucide 的 brand 图标就是这样）
    group_fill = re.search(r'<g[^>]*fill="([^"]+)"', svg)
    has_stroke = "stroke=" in svg

    for m in re.finditer(r"<(path|rect|circle|ellipse|line|polyline|polygon)\b([^>]*?)/>", svg, re.S):
        tag, attrs = m.group(1), m.group(2)
        if tag == "path":
            d = re.search(r'd="([^"]+)"', attrs).group(1)
        elif tag == "rect":
            d = rect_path(
                num(attrs, "x", 0), num(attrs, "y", 0),
                num(attrs, "width"), num(attrs, "height"), num(attrs, "rx", 0),
            )
        elif tag == "circle":
            d = ellipse_path(num(attrs, "cx"), num(attrs, "cy"), num(attrs, "r"), num(attrs, "r"))
        elif tag == "ellipse":
            d = ellipse_path(num(attrs, "cx"), num(attrs, "cy"), num(attrs, "rx"), num(attrs, "ry"))
        elif tag == "line":
            d = (
                f"M{num(attrs, 'x1')},{num(attrs, 'y1')}"
                f"L{num(attrs, 'x2')},{num(attrs, 'y2')}"
            )
        elif tag == "polyline":
            pts = [p for p in re.split(r"[ ,]+", norm(re.search(r'points="([^"]+)"', attrs).group(1))) if p]
            coords = list(zip(pts[0::2], pts[1::2]))
            d = "M" + "L".join(f"{x},{y}" for x, y in coords)
        else:  # polygon
            pts = [p for p in re.split(r"[ ,]+", norm(re.search(r'points="([^"]+)"', attrs).group(1))) if p]
            coords = list(zip(pts[0::2], pts[1::2]))
            d = "M" + "L".join(f"{x},{y}" for x, y in coords) + "z"

        # 该元素自己的 fill 优先，其次父 <g> 的 fill，最后看是否纯描边
        own_fill = re.search(r'fill="([^"]+)"', attrs)
        fill_value = own_fill.group(1) if own_fill else (group_fill.group(1) if group_fill else None)
        wants_fill = filled or (fill_value not in (None, "none"))

        parts = [f'android:pathData="{norm(d)}"']
        if wants_fill:
            parts.append(f'android:fillColor="{color}"')
        else:
            parts.append('android:fillColor="#00000000"')
        if has_stroke:
            parts += [
                f'android:strokeColor="{color}"',
                f'android:strokeWidth="{num(attrs, "stroke-width", 2)}"',
                'android:strokeLineCap="round"',
                'android:strokeLineJoin="round"',
            ]
        if 'fill-rule="evenodd"' in attrs:
            parts.append('android:fillType="evenOdd"')
        body.append("    <path " + "\n        ".join(parts) + " />")

    if not body:
        raise SystemExit(f"{src} 没有解析出任何可绘制元素")

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(
        "\n".join(
            [
                f"<!-- 由 {src.name} 转换（docs/assets/tools/svg-to-vectordrawable.py），见 docs/assets/ui-manifest.json -->",
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                '    android:width="24dp"',
                '    android:height="24dp"',
                '    android:viewportWidth="24"',
                '    android:viewportHeight="24">',
                *body,
                "</vector>",
                "",
            ]
        ),
        encoding="utf-8",
        newline="\n",
    )
    print(f"转换 {src} -> {dst}（{len(body)} 个元素）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--filled", action="store_true", help="强制填充（用于当前色实心图标）")
    ap.add_argument("--color", default="#FF000000", help="填充/描边色，默认黑色由 Compose tint 覆盖")
    args = ap.parse_args()
    convert(Path(args.src), Path(args.dst), args.filled, args.color)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())