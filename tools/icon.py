"""Builds the launcher assets from the source art, the way Daftar's were built.

Usage: python3 tools/icon.py art/icon.png . <preview-dir>

The source is the full rendered icon on a white margin. Out come the adaptive
icon's foreground (the art scaled to the 72dp safe zone), its monochrome
silhouette for themed icons, the background colour sampled from the art, and a
preview of the circle, squircle and themed renderings.
"""
import colorsys, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

src, repo, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
art = Image.open(src).convert("RGBA")
W, H = art.size

# 1. The squircle: everything not connected to the white margin.
mask = Image.new("L", art.size, 255)
rgb = art.convert("RGB")
flood = rgb.copy()
for corner in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
    ImageDraw.floodfill(flood, corner, (255, 0, 255), thresh=40)
px, fx = rgb.load(), flood.load()
m = mask.load()
for y in range(H):
    for x in range(W):
        if fx[x, y] == (255, 0, 255):
            m[x, y] = 0
mask = mask.filter(ImageFilter.GaussianBlur(1.2))
bbox = mask.getbbox()
icon = art.copy(); icon.putalpha(mask)
icon = icon.crop(bbox)
side = max(icon.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(icon, ((side - icon.width) // 2, (side - icon.height) // 2))
print("squircle bbox", bbox, "->", square.size)

# 2. Background colour, sampled from the art's wall just inside the top edge.
cx = side // 2
samples = [square.getpixel((cx + dx, int(side * 0.12))) for dx in range(-40, 41, 10)]
r, g, b = (sum(s[i] for s in samples) // len(samples) for i in range(3))
bg = "#%02X%02X%02X" % (r, g, b)
print("background", bg)

# 3. Monochrome: the silhouette a themed icon is tinted with. The dark fur
#    (ears, eye patches, nose, smile, arms) by colour; the padlock, key and
#    sparks by where they sit in the art, because their gold is the same hue
#    as the desk. The keyhole is cut out, and a thin gap separates the padlock
#    from the arms hugging it, so it still reads as a padlock when flat.
ox, oy = bbox[0], bbox[1]
def region(x0, y0, x1, y1):
    return (x0 - ox, y0 - oy, x1 - ox, y1 - oy)
GOLD_REGIONS = [region(560, 690, 860, 1010), region(890, 560, 1020, 780), region(1005, 460, 1120, 700)]
PADLOCK = GOLD_REGIONS[0]
WALLET = region(150, 880, 540, 1030)
DESK_TOP = 1000 - oy  # below this only the padlock belongs to the silhouette
def inside(x, y, r):
    return r[0] <= x < r[2] and r[1] <= y < r[3]

fur = Image.new("L", square.size, 0); gold = Image.new("L", square.size, 0); hole = Image.new("L", square.size, 0)
sp, fp, gp, hp = square.load(), fur.load(), gold.load(), hole.load()
for y in range(side):
    for x in range(side):
        R, G, B, A = sp[x, y]
        if A < 128:
            continue
        h, s_, v = colorsys.rgb_to_hsv(R / 255, G / 255, B / 255)
        in_gold = any(inside(x, y, r) for r in GOLD_REGIONS)
        if in_gold and 0.05 < h < 0.18 and s_ > 0.4 and v > 0.38:
            gp[x, y] = 255
        elif inside(x, y, PADLOCK) and v < 0.38 and 0.0 < h < 0.15 and s_ > 0.3:
            hp[x, y] = 255  # the keyhole: dark brown inside the padlock
        elif (
            v < 0.30 and s_ < 0.45 and not (h < 0.13 and s_ > 0.33)
            and not inside(x, y, WALLET) and y < DESK_TOP
        ):
            fp[x, y] = 255
gold = gold.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))  # close the shading gaps
hole = hole.filter(ImageFilter.MedianFilter(5))
from PIL import ImageChops
gap = gold.filter(ImageFilter.MaxFilter(int(side * 0.012) | 1))
fur = ImageChops.subtract(fur, gap)
mono = ImageChops.lighter(fur, gold)
mono = ImageChops.subtract(mono, hole)
mono = mono.filter(ImageFilter.MedianFilter(5))

from collections import deque
mp = mono.load(); seen = set(); keep = Image.new("L", mono.size, 0); kp = keep.load()
for y0 in range(side):
    for x0 in range(side):
        if mp[x0, y0] < 128 or (x0, y0) in seen:
            continue
        comp, q = [], deque([(x0, y0)]); seen.add((x0, y0))
        while q:
            x, y = q.popleft(); comp.append((x, y))
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < side and 0 <= ny < side and (nx, ny) not in seen and mp[nx, ny] >= 128:
                    seen.add((nx, ny)); q.append((nx, ny))
        if len(comp) > 400:
            for x, y in comp:
                kp[x, y] = 255
# Smooth the fur's ragged edge into a clean contour: blur, then cut at half.
mono = keep.filter(ImageFilter.GaussianBlur(4)).point(lambda a: 255 if a >= 128 else 0)
mono = mono.filter(ImageFilter.GaussianBlur(1))
mono_rgba = Image.new("RGBA", square.size, (0, 0, 0, 0))
mono_rgba.putalpha(mono)

# 4. Per density: 108dp canvas, art scaled to the 72dp safe zone.
densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
res = repo / "app/src/main/res"
for name, scale in densities.items():
    canvas_px = round(108 * scale)
    art_px = round(72 * scale)
    d = res / f"mipmap-{name}"; d.mkdir(parents=True, exist_ok=True)
    for layer, img in (("foreground", square), ("monochrome", mono_rgba)):
        c = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        small = img.resize((art_px, art_px), Image.LANCZOS)
        c.paste(small, ((canvas_px - art_px) // 2,) * 2, small)
        c.save(d / f"ic_launcher_{layer}.png", optimize=True)

(repo / "art").mkdir(exist_ok=True)
art.convert("RGB").save(repo / "art/icon.png", optimize=True)
square.resize((512, 512), Image.LANCZOS).save(repo / "art/icon-512.png", optimize=True)
(res / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    "    <!-- Sampled from the icon art so the adaptive background matches it. -->\n"
    f'    <color name="ic_launcher_background">{bg}</color>\n</resources>\n'
)

# Previews: what launchers show (circle and squircle masks) and the themed icon.
def masked(shape):
    size = 432
    c = Image.new("RGBA", (size, size), bg)
    fg = Image.open(res / "mipmap-xxxhdpi/ic_launcher_foreground.png")
    c.alpha_composite(fg)
    m = Image.new("L", (size, size), 0)
    inset = size // 6  # launchers show the middle 72dp of 108dp
    box = (inset, inset, size - inset, size - inset)
    dr = ImageDraw.Draw(m)
    (dr.ellipse if shape == "circle" else lambda b, fill: dr.rounded_rectangle(b, radius=70, fill=fill))(box, fill=255)
    c.putalpha(m)
    return c.crop(box)
themed = Image.new("RGBA", (432, 432), (225, 236, 230, 255))
tint = Image.new("RGBA", (432, 432), (40, 90, 75, 255))
mono_xx = Image.open(res / "mipmap-xxxhdpi/ic_launcher_monochrome.png")
themed.paste(tint, (0, 0), mono_xx)
themed = themed.crop((72, 72, 360, 360))
sheet = Image.new("RGBA", (288 * 3 + 80, 328), (250, 250, 250, 255))
for i, im in enumerate([masked("circle"), masked("squircle"), themed]):
    sheet.alpha_composite(im, (20 + i * 308, 20))
sheet.save(out / "preview.png")
