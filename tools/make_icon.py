#!/usr/bin/env python3
"""Bypass Control launcher icon: blue rounded square, white battery outline,
yellow lightning bolt through it (power bypassing the battery).
Drawn at 1024px supersample, exported at all mipmap densities."""
from PIL import Image, ImageDraw
import os

S = 1024
BASE = os.path.expanduser("~/workspace/passcontrol")
RES = os.path.join(BASE, "app/src/main/res")

# vertical blue gradient background
top = (37, 99, 235)      # #2563EB
bottom = (30, 58, 138)   # #1E3A8A
img = Image.new("RGB", (S, S))
px = img.load()
for y in range(S):
    t = y / (S - 1)
    c = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    for x in range(S):
        px[x, y] = c

# round the corners (transparent outside radius)
mask = Image.new("L", (S, S), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
out.paste(img, (0, 0), mask)

d = ImageDraw.Draw(out)
WHITE = (255, 255, 255, 255)
BOLT = (255, 209, 26, 255)   # #FFD11A

# battery outline: body + cap, thick white stroke
bw = int(S * 0.085)
body = [int(S * 0.155), int(S * 0.315), int(S * 0.775), int(S * 0.685)]
d.rounded_rectangle(body, radius=int(S * 0.055), outline=WHITE, width=bw)
cap_x0, cap_x1 = int(S * 0.795), int(S * 0.865)
cap_y0, cap_y1 = int(S * 0.425), int(S * 0.575)
d.rounded_rectangle([cap_x0, cap_y0, cap_x1, cap_y1], radius=int(S * 0.03), fill=WHITE)

# lightning bolt, centred in the battery body, slightly overlapping the outline
bolt = [
    (int(S * 0.520), int(S * 0.235)),
    (int(S * 0.335), int(S * 0.520)),
    (int(S * 0.455), int(S * 0.520)),
    (int(S * 0.405), int(S * 0.765)),
    (int(S * 0.625), int(S * 0.470)),
    (int(S * 0.500), int(S * 0.470)),
]
d.polygon(bolt, fill=BOLT)

sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for folder, size in sizes.items():
    path = os.path.join(RES, folder)
    os.makedirs(path, exist_ok=True)
    out.resize((size, size), Image.LANCZOS).save(os.path.join(path, "ic_launcher.png"))
out.resize((512, 512), Image.LANCZOS).save(os.path.join(BASE, "icon-preview", "ic_launcher_512.png"))
print("icon written")
