"""Draws a manga-like test page (line art, screentone, hatching, speed lines, text) and bad scans of it."""
import math
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
W, H = 1400, 2000
rnd = random.Random(7)

page = Image.new("L", (W, H), 255)
d = ImageDraw.Draw(page)
jp = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 46)
en = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 34)


def tone(box, pitch, radius_fn):
    """Screentone: a grid of black dots rotated 45 degrees, radius from radius_fn(x, y)."""
    x0, y0, x1, y1 = box
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rectangle(box, fill=255)
    layer = Image.new("L", (W, H), 255)
    ld = ImageDraw.Draw(layer)
    c, s = math.cos(math.pi / 4), math.sin(math.pi / 4)
    span = int(max(x1 - x0, y1 - y0) * 1.5)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    for i in range(-span // pitch, span // pitch):
        for j in range(-span // pitch, span // pitch):
            u, v = i * pitch, j * pitch
            x, y = cx + u * c - v * s, cy + u * s + v * c
            if x0 - pitch < x < x1 + pitch and y0 - pitch < y < y1 + pitch:
                r = radius_fn(x, y)
                if r > 0.3:
                    ld.ellipse((x - r, y - r, x + r, y + r), fill=0)
    page.paste(layer, (0, 0), mask)


def panel(box):
    d.rectangle(box, outline=0, width=7)


# Panel 1: sky gradient tone, a face, a speech bubble
p1 = (40, 40, W - 40, 760)
tone((p1[0], p1[1], p1[2], p1[3]), 9, lambda x, y: 3.6 * (1 - (y - 40) / 720))
d.ellipse((380, 160, 760, 620), fill=255, outline=0, width=6)  # head
for k in range(14):  # hair strands
    a = math.pi * (0.95 + k * 0.08)
    d.arc((330 - k * 4, 110 - k * 3, 810 + k * 4, 560 + k * 2), math.degrees(a), math.degrees(a) + 40, fill=0, width=5)
for ex in (470, 610):  # eyes
    d.ellipse((ex, 350, ex + 70, 430), outline=0, width=5)
    d.ellipse((ex + 18, 368, ex + 52, 420), fill=0)
    d.ellipse((ex + 26, 374, ex + 38, 388), fill=255)
d.arc((520, 470, 620, 540), 20, 160, fill=0, width=5)  # mouth
for k in range(18):  # cheek hatching
    d.line((420 + k * 6, 470, 440 + k * 6, 450), fill=0, width=2)
d.ellipse((820, 120, 1300, 460), fill=255, outline=0, width=6)
d.polygon([(900, 430), (860, 520), (960, 445)], fill=255, outline=0)
d.line((900, 430, 860, 520, 960, 445), fill=0, width=6)
d.rectangle((905, 425, 955, 450), fill=255)
d.text((900, 200), "こんにちは！", font=jp, fill=0)
d.text((900, 270), "今日は晴れだね。", font=jp, fill=0)
d.text((900, 340), "どこへ行く？", font=jp, fill=0)
panel(p1)

# Panel 2: speed lines and a black fill with white text
p2 = (40, 800, 700, 1440)
cx, cy = 370, 1120
for k in range(160):
    a = rnd.random() * 2 * math.pi
    r0 = 120 + rnd.random() * 80
    w = rnd.choice((1, 2, 2, 3, 4))
    d.line((cx + math.cos(a) * r0, cy + math.sin(a) * r0, cx + math.cos(a) * 700, cy + math.sin(a) * 700), fill=0, width=w)
d.rectangle((40, 1360, 700, 1440), fill=0)
d.text((120, 1375), "BOOM!", font=en, fill=255)
d.text((300, 1060), "ドン", font=ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 110), fill=0)
panel(p2)

# Panel 3: fine cross hatching and a caption box
p3 = (740, 800, W - 40, 1440)
for k in range(0, 900, 7):
    d.line((740 + k, 800, 740 + k - 640, 1440), fill=0, width=1)
for k in range(0, 900, 11):
    d.line((740 + k - 640, 800, 740 + k, 1440), fill=0, width=1)
d.rectangle((800, 860, 1300, 990), fill=255, outline=0, width=4)
d.text((830, 880), "Meanwhile, at the", font=en, fill=0)
d.text((830, 930), "old station...", font=en, fill=0)
panel(p3)

# Panel 4: flat light tone floor, character body outlines, small text
p4 = (40, 1480, W - 40, H - 40)
tone((40, 1700, W - 40, H - 40), 7, lambda x, y: 1.6)
d.rectangle((40, 1480, W - 40, 1700), fill=255)
for k in range(3):
    x = 250 + k * 380
    d.ellipse((x, 1520, x + 120, 1640), fill=255, outline=0, width=5)
    d.line((x + 60, 1640, x + 60, 1840), fill=0, width=6)
    d.line((x + 60, 1700, x - 20, 1790), fill=0, width=5)
    d.line((x + 60, 1700, x + 140, 1790), fill=0, width=5)
small = ImageFont.truetype("/System/Library/Fonts/Hiragino Sans GB.ttc", 26)
d.text((80, 1500), "小さな文字も読めるか？ 細部のテスト。", font=small, fill=0)
panel(p4)

page = page.filter(ImageFilter.GaussianBlur(0.6))  # soft ink like a real print
page.convert("RGB").save(f"{OUT}/truth.png")


def degrade(scale, blur, noise, quality, name, paper=(255, 255, 255)):
    """Shrinks, blurs, prints on [paper] (RGB) and adds grain, then saves as JPEG [quality] (and PNG of it)."""
    small = page.resize((int(W * scale), int(H * scale)), Image.BILINEAR)
    if blur:
        small = small.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(small.convert("RGB")).astype(np.float32) / 255 * np.array(paper, np.float32)
    a += np.random.default_rng(1).normal(0, noise, a.shape[:2])[..., None]
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    img.save(f"{OUT}/{name}.jpg", "JPEG", quality=quality)
    Image.open(f"{OUT}/{name}.jpg").save(f"{OUT}/{name}.png")


degrade(0.5, 0.0, 2, 80, "small")  # clean but low resolution
degrade(0.5, 0.7, 6, 30, "bad")  # typical bad scan
degrade(0.45, 1.1, 10, 12, "worst", paper=(237, 237, 237))  # very bad: grey paper, grain, heavy JPEG
degrade(0.5, 0.5, 3, 60, "yellow", paper=(228, 222, 205))  # old yellowed paper, medium JPEG
print("ok")
