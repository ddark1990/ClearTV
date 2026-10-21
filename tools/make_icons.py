#!/usr/bin/env python3
"""Render the ClearTV launcher icons from the same TV-and-play mark as the approved banner
(drawable-*/banner.png: grey outline TV, blue play triangle, "ClearTV" wordmark on #111416).
Run from the repo root.

Writes, for every density (mdpi..xxxhdpi):
  mipmap-*/ic_launcher.png             legacy full-bleed square: TV mark on top, wordmark below, even margins
  mipmap-*/ic_launcher_foreground.png  adaptive foreground (108 dp canvas): the same mark+wordmark block filling the
                                       central 72 dp square. Fire OS draws the SQUARE icon centred at full height
                                       on a grey plate for sideloaded apps and ignores android:banner.
and values/ic_launcher.xml (background colour). The banner uses the same mark() and is only rewritten with
--banner (it is user-approved; the default run leaves it alone).
Shapes are drawn at 8x and downsampled with LANCZOS for crisp edges; saved as plain RGBA PNG (no palette
quantising, which caused banding/fringes in earlier icons)."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

RES = 'app/src/main/res'
FONT = 'app/src/main/assets/fonts/oxanium_semibold.ttf'  # same face as the banner wordmark
BG = (17, 20, 22)          # #111416, banner background
GREY = (203, 209, 216)     # #CBD1D8, banner TV outline and wordmark
BLUE = (76, 158, 255)      # #4C9EFF, banner play triangle
SS = 8
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

def mark(d, cx, cy, s):
    """The banner's TV mark centred at (cx, cy); s = screen width. Same geometry as banner.png."""
    w = s; h = s * 0.70; t = s * 0.075; r = s * 0.13
    x0, y0 = cx - w / 2, cy - h / 2 - s * 0.06
    d.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=r, outline=GREY, width=int(round(t)))
    sw = s * 0.36; sy = y0 + h + s * 0.10
    d.rounded_rectangle([cx - sw / 2, sy, cx + sw / 2, sy + t * 0.85], radius=t * 0.42, fill=GREY)
    ph = h * 0.40; pw = ph * 0.88; px = cx - pw * 0.38; py = y0 + h / 2
    d.polygon([(px, py - ph / 2), (px + pw, py), (px, py + ph / 2)], fill=BLUE)


# mark bounding box relative to s: top = cy - 0.41 s, bottom = cy + 0.4038 s (stand), width s
MARK_TOP = 0.41  # 0.06 + 0.35 above cy
MARK_BOT = -0.06 + 0.35 + 0.10 + 0.075 * 0.85  # 0.45375 below cy
MARK_H = MARK_TOP + MARK_BOT


def font(px):
    return ImageFont.truetype(FONT, int(px))


def compose(d, x0, y0, a):
    """TV mark on top, 'ClearTV' wordmark centred below, the pair scaled to fill the a x a square at (x0, y0)."""
    u = 1000.0
    f = font(u * 0.40)
    tb = d.textbbox((0, 0), 'ClearTV', font=f)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    gap = u * 0.12
    bw, bh = max(u, tw), u * MARK_H + gap + th
    k = a / max(bw, bh)
    w = u * k; f = font(u * 0.40 * k)
    tb = d.textbbox((0, 0), 'ClearTV', font=f)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    gap *= k
    top = y0 + (a - (w * MARK_H + gap + th)) / 2
    cx = x0 + a / 2
    mark(d, cx, top + w * MARK_TOP, w)
    d.text((cx - tw / 2 - tb[0], top + w * MARK_H + gap - tb[1]), 'ClearTV', font=f, fill=GREY)


MARGIN = 0.08  # even margin around the mark+wordmark block


def legacy(px):
    """Full-bleed square: mark + wordmark filling it with even margins."""
    n = px * SS
    img = Image.new('RGB', (n, n), BG)
    compose(ImageDraw.Draw(img), n * MARGIN, n * MARGIN, n * (1 - 2 * MARGIN))
    return img.resize((px, px), Image.LANCZOS)


def foreground(px):
    """108 dp adaptive canvas. Fire OS square-masks to the central 72 dp; the same mark+wordmark block as the
    legacy icon fills that 72 dp square with the same even margins, so nothing is cropped on the TV tile."""
    n = px * SS
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    v = n * 72 / 108; o = (n - v) / 2
    compose(ImageDraw.Draw(img), o + v * MARGIN, o + v * MARGIN, v * (1 - 2 * MARGIN))
    return img.resize((px, px), Image.LANCZOS)


def banner(w0, h0):
    """The approved full-bleed 16:9 banner (mark + wordmark inside ~10% padding)."""
    S = 4; W, H = w0 * S, h0 * S; k = w0 / 320
    b = Image.new('RGBA', (W, H), BG + (255,)); d = ImageDraw.Draw(b)
    mark(d, 70 * k * S, 90 * k * S, 76 * k * S)
    f = font(50 * k * S); bb = d.textbbox((0, 0), 'ClearTV', font=f)
    d.text((124 * k * S, 90 * k * S - (bb[1] + bb[3]) / 2), 'ClearTV', font=f, fill=GREY)
    return b.resize((w0, h0), Image.LANCZOS).convert('RGB')


def main():
    for name, k in DENS.items():
        d = os.path.join(RES, 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)
        legacy(round(48 * k)).save(os.path.join(d, 'ic_launcher.png'), optimize=True)
        foreground(round(108 * k)).save(os.path.join(d, 'ic_launcher_foreground.png'), optimize=True)
    with open(os.path.join(RES, 'values', 'ic_launcher.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % BG)
    print('launcher icons written')
    if '--banner' in sys.argv:  # the banner is approved; only rewrite it on purpose
        for name, (w, h) in {'xhdpi': (320, 180), 'xxhdpi': (480, 270), 'xxxhdpi': (640, 360)}.items():
            banner(w, h).save(os.path.join(RES, 'drawable-' + name, 'banner.png'))
        print('banners written')


if __name__ == '__main__':
    main()
