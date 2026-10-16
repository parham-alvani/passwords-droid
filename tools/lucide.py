#!/usr/bin/env python3
"""Flatten the Lucide icon set into one JSON file the app can ship in assets.

Lucide's own package is 2,112 separate SVG documents, which is 2,112 files in
`assets/` and 2,112 XML parses at runtime. Every one of them is the same 24x24
viewBox with `fill="none"`, `stroke-width="2"` and round caps, so the only thing
that actually differs is the geometry -- and Compose can draw that straight from
an SVG path string via `PathParser`.

So this reduces each icon to one path string (two when the icon also has filled
geometry, which ten of them do), which makes the whole set a single asset and a
single parse. Non-path elements are converted to path commands here rather than
in Kotlin: doing it at build time means the app never carries code for circles,
rects, ellipses or polygons it could not otherwise draw.

Usage: tools/lucide.py [--out app/src/main/assets/lucide.json] [--version latest]
"""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ET

REGISTRY = "https://registry.npmjs.org/lucide-static"
SVG_NS = "{http://www.w3.org/2000/svg}"
NUMBER = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def leading_numbers(text: str) -> tuple[list[str], str]:
    """The run of numbers at the head of `text`, and whatever follows it."""
    numbers: list[str] = []
    at = 0
    while at < len(text):
        if text[at] in " ,\t\r\n":
            at += 1
            continue
        match = NUMBER.match(text, at)
        if match is None:
            break
        numbers.append(match.group())
        at = match.end()
    return numbers, text[at:]


def absolutize_moveto(d: str) -> str:
    """Rewrite a leading relative moveto as an absolute one.

    This is what makes concatenating an icon's sub-paths safe, and it is the
    one place this conversion can go wrong without looking wrong. SVG treats a
    path's *first* moveto as absolute even when written lowercase, so each
    `<path d="m9 11 ...">` in a Lucide icon starts from the origin. Concatenate
    them and that same `m` suddenly means "relative to where the previous
    sub-path left off", which silently displaces it -- 1,095 of the 2,112 icons
    have at least one.

    Uppercasing the `m` alone is not the fix: extra coordinate pairs after a
    moveto are implicit linetos that inherit the moveto's relativeness, so
    `m9 11 3 3` is `M9,11` then `l3,3`, not `L3,3`.
    """
    d = d.strip()
    if not d.startswith("m"):
        return d
    numbers, rest = leading_numbers(d[1:])
    if len(numbers) < 2:
        return "M" + d[1:]
    out = f"M{numbers[0]},{numbers[1]}"
    extra = numbers[2:]
    if extra:
        out += "l" + " ".join(f"{extra[i]},{extra[i + 1]}" for i in range(0, len(extra) - 1, 2))
    return out + rest


def num(value: str | None, default: float = 0.0) -> float:
    if value is None or value == "":
        return default
    return float(value)


def trim(value: float) -> str:
    """Shortest exact spelling of a coordinate. Halves the output size."""
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def rect_to_path(el: ET.Element) -> str:
    x, y = num(el.get("x")), num(el.get("y"))
    w, h = num(el.get("width")), num(el.get("height"))
    rx = num(el.get("rx"), num(el.get("ry")))
    ry = num(el.get("ry"), rx)
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx <= 0 or ry <= 0:
        return f"M{trim(x)},{trim(y)}h{trim(w)}v{trim(h)}h{trim(-w)}z"
    arc = f"a{trim(rx)},{trim(ry)} 0 0 1"
    return (
        f"M{trim(x + rx)},{trim(y)}"
        f"h{trim(w - 2 * rx)}{arc} {trim(rx)},{trim(ry)}"
        f"v{trim(h - 2 * ry)}{arc} {trim(-rx)},{trim(ry)}"
        f"h{trim(-(w - 2 * rx))}{arc} {trim(-rx)},{trim(-ry)}"
        f"v{trim(-(h - 2 * ry))}{arc} {trim(rx)},{trim(-ry)}z"
    )


def ellipse_to_path(cx: float, cy: float, rx: float, ry: float) -> str:
    # Two half-turn arcs: a single 360-degree arc is degenerate and renders as
    # nothing, which is the classic way this conversion goes silently wrong.
    return (
        f"M{trim(cx - rx)},{trim(cy)}"
        f"a{trim(rx)},{trim(ry)} 0 1 0 {trim(2 * rx)},0"
        f"a{trim(rx)},{trim(ry)} 0 1 0 {trim(-2 * rx)},0"
    )


def points_to_path(el: ET.Element, close: bool) -> str:
    raw = [p for p in re.split(r"[\s,]+", (el.get("points") or "").strip()) if p]
    pairs = [f"{raw[i]},{raw[i + 1]}" for i in range(0, len(raw) - 1, 2)]
    if not pairs:
        return ""
    return "M" + pairs[0] + "".join(f"L{p}" for p in pairs[1:]) + ("z" if close else "")


def element_to_path(el: ET.Element) -> str:
    tag = el.tag.removeprefix(SVG_NS)
    if tag == "path":
        return absolutize_moveto(el.get("d") or "")
    if tag == "circle":
        r = num(el.get("r"))
        return ellipse_to_path(num(el.get("cx")), num(el.get("cy")), r, r)
    if tag == "ellipse":
        return ellipse_to_path(num(el.get("cx")), num(el.get("cy")), num(el.get("rx")), num(el.get("ry")))
    if tag == "rect":
        return rect_to_path(el)
    if tag == "line":
        return f"M{trim(num(el.get('x1')))},{trim(num(el.get('y1')))}L{trim(num(el.get('x2')))},{trim(num(el.get('y2')))}"
    if tag in ("polyline", "polygon"):
        return points_to_path(el, close=tag == "polygon")
    return ""


def convert(svg: bytes, name: str) -> str | list[str]:
    root = ET.fromstring(svg)
    if root.get("viewBox") != "0 0 24 24":
        raise SystemExit(f"{name}: unexpected viewBox {root.get('viewBox')!r}")
    stroke: list[str] = []
    filled: list[str] = []
    for el in root.iter():
        if el is root:
            continue
        data = element_to_path(el)
        if not data:
            tag = el.tag.removeprefix(SVG_NS)
            raise SystemExit(f"{name}: cannot convert <{tag}>")
        # A handful of icons mix a filled dot into an otherwise stroked glyph.
        (filled if (el.get("fill") or "none") != "none" else stroke).append(data)
    joined = "".join(stroke)
    return [joined, "".join(filled)] if filled else joined


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="app/src/main/assets/lucide.json")
    parser.add_argument("--version", default="latest")
    parser.add_argument("--only", help="comma-separated names, for a trimmed build")
    args = parser.parse_args()

    meta = json.load(urllib.request.urlopen(REGISTRY))
    version = meta["dist-tags"][args.version] if args.version in meta["dist-tags"] else args.version
    url = meta["versions"][version]["dist"]["tarball"]
    print(f"lucide-static {version}", file=sys.stderr)

    keep = set(args.only.split(",")) if args.only else None
    icons: dict[str, str | list[str]] = {}
    with tarfile.open(fileobj=io.BytesIO(urllib.request.urlopen(url).read())) as tar:
        for member in tar.getmembers():
            if not member.name.startswith("package/icons/") or not member.name.endswith(".svg"):
                continue
            name = member.name.removeprefix("package/icons/").removesuffix(".svg")
            if keep is not None and name not in keep:
                continue
            handle = tar.extractfile(member)
            if handle is None:
                continue
            icons[name] = convert(handle.read(), name)

    payload = {"version": version, "icons": dict(sorted(icons.items()))}
    with open(args.out, "w", encoding="utf-8") as out:
        json.dump(payload, out, separators=(",", ":"), ensure_ascii=False)
        out.write("\n")
    print(f"{len(icons)} icons -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
