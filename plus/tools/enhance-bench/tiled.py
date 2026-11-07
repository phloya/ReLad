"""Prototype of the phone algorithm: Real-CUGAN 2x in tiles with shared SE statistics ("gap" blobs).

usage: tiled.py <param> <bin> <in.png> <out.png> <tile> [nosync|sample32|sample96|tiles200|exact]
"""
import sys

import ncnn
import numpy as np
from PIL import Image

param, model, src, dst, tile = sys.argv[1:6]
tile = int(tile)
mode = sys.argv[6] if len(sys.argv) > 6 else "sample32"
sync = mode != "nosync"
PAD = 18
GAPS = ["gap0", "gap1", "gap2", "gap3"]

net = ncnn.Net()
net.opt.use_vulkan_compute = False
net.opt.num_threads = 8
net.load_param(param)
net.load_model(model)

img = np.asarray(Image.open(src).convert("RGB")).astype(np.float32).transpose(2, 0, 1) / 255.0
_, h, w = img.shape


def tile_input(x0, y0, x1, y1):
    """Pixels [x0,x1)x[y0,y1) plus PAD around (reflected at page edges) plus one extra row/column to make it even."""
    ew, eh = (x1 - x0) % 2, (y1 - y0) % 2
    big = np.pad(img, ((0, 0), (PAD, PAD + 1), (PAD, PAD + 1)), mode="reflect")
    return np.ascontiguousarray(big[:, y0:y1 + 2 * PAD + eh, x0:x1 + 2 * PAD + ew])


def tiles(size):
    for y0 in range(0, h, size):
        for x0 in range(0, w, size):
            yield x0, y0, min(x0 + size, w), min(y0 + size, h)


def gaps():
    """Page-wide SE features. sample32: realcugan's mode 3; tilesN: mean over the real tiles (area-weighted)."""
    if mode.startswith("sample"):
        S = int(mode[6:])
        boxes = [(x, y, x + S, y + S) for y in range(0, h - S + 1, S * 3) for x in range(0, w - S + 1, S * 3)]
    elif mode.startswith("tiles"):
        boxes = list(tiles(int(mode[5:])))
    if mode == "exact":
        # realcugan's mode 1: one gap at a time, each pass fed the page-wide gaps found so far
        found = []
        for gi, g in enumerate(GAPS):
            total, area = None, 0
            for (x0, y0, x1, y1) in tiles(tile):
                ex = net.create_extractor()
                ex.input("in0", ncnn.Mat(tile_input(x0, y0, x1, y1)))
                for name, v in zip(GAPS, found):
                    ex.input(name, ncnn.Mat(np.ascontiguousarray(v)))
                v = np.array(ex.extract(g)[1]).copy() * (x1 - x0) * (y1 - y0)
                total = v if total is None else total + v
                area += (x1 - x0) * (y1 - y0)
            found.append(total / area)
        return found
    sums, weight = None, 0
    for (x0, y0, x1, y1) in boxes:
        ex = net.create_extractor()
        ex.input("in0", ncnn.Mat(tile_input(x0, y0, x1, y1)))
        a = (x1 - x0) * (y1 - y0)
        vals = [np.array(ex.extract(g)[1]).copy() * a for g in GAPS]
        sums = vals if sums is None else [p + q for p, q in zip(sums, vals)]
        weight += a
    return [s / weight for s in sums]


shared = gaps() if sync else None
out = np.zeros((3, h * 2, w * 2), np.float32)
for y0 in range(0, h, tile):
    for x0 in range(0, w, tile):
        x1, y1 = min(x0 + tile, w), min(y0 + tile, h)
        ex = net.create_extractor()
        ex.input("in0", ncnn.Mat(tile_input(x0, y0, x1, y1)))
        if shared:
            for g, v in zip(GAPS, shared):
                ex.input(g, ncnn.Mat(np.ascontiguousarray(v)))
        y = np.array(ex.extract("out0")[1])
        out[:, y0 * 2:y1 * 2, x0 * 2:x1 * 2] = y[:, :(y1 - y0) * 2, :(x1 - x0) * 2]
Image.fromarray(np.clip(out * 255 + 0.5, 0, 255).astype(np.uint8).transpose(1, 2, 0)).save(dst)
print("ok", w, h)
