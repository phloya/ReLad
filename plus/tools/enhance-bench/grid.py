"""Side-by-side crops of model outputs: grid.py <out dir> <scan> <x> <y> <size> <dest> <names...>"""
import sys

from PIL import Image, ImageDraw, ImageFont

out, scan, x, y, size, dest = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:6]), sys.argv[6]
names = sys.argv[7:]
font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 22)
cols = 4
rows = (len(names) + cols - 1) // cols
cell = size
grid = Image.new("RGB", (cols * cell, rows * (cell + 34)), "white")
d = ImageDraw.Draw(grid)
for i, name in enumerate(names):
    path = f"{out}/../pages/truth.png" if name == "truth" else f"{out}/{scan}--{name}.png"
    img = Image.open(path)
    if name == "truth":
        ref = Image.open(f"{out}/{scan}--bicubic.png")
        img = img.resize(ref.size, Image.LANCZOS)
    cx, cy = (i % cols) * cell, (i // cols) * (cell + 34)
    grid.paste(img.crop((x, y, x + size, y + size)), (cx, cy + 34))
    d.text((cx + 6, cy + 4), name, font=font, fill=(200, 0, 0))
grid.save(dest)
