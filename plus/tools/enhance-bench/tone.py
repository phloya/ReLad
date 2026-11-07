"""Reference of the phone's screentone smoothing (plus/enhance/.../Screentone.kt does the same, step for step).

Real-CUGAN turns screentone it cannot resolve (dots of 2-4 px, blurred by the scan) into a mottled "worm"
texture. Such tone is found by its statistics and smoothed with an edge-preserving filter before the
network, so the network sees flat grey there and keeps the lines sharp.

usage: tone.py <in> <out.png> [key=value ...]   (writes the smoothed page and <out>-mask.png, the half-size mask)
"""
import sys

import numpy as np
from PIL import Image

P = dict(
    # detector: smoothstep ranges of the four features (see Screentone.kt)
    eLo=4.0, eHi=8.0,  # pixel-checker energy (RMS): enough fine texture
    qLo=0.14, qHi=0.22,  # its share of all fine detail: dots, not edges
    cLo=0.35, cHi=0.6,  # gradient coherence: hatching and lines run one way, dots do not
    gLo=40.0, gHi=60.0,  # gradient RMS: low-contrast texture only (crisp tone and ink strokes survive the network)
    minShare=0.015,  # pages with less tone than this are left as they are
    # bilateral filter
    radius=2, sigmaSpace=1.5, sigmaRange=40.0, iterations=2,
)


def shift(a, dy, dx):
    """a[y + dy, x + dx], edges repeated."""
    h, w = a.shape
    ys = np.clip(np.arange(h) + dy, 0, h - 1)
    xs = np.clip(np.arange(w) + dx, 0, w - 1)
    return a[ys][:, xs]


def box(a, r):
    """Mean over a (2r+1)x(2r+1) square, edges repeated."""
    rows = sum(shift(a, 0, d) for d in range(-r, r + 1)) / (2 * r + 1)
    return sum(shift(rows, d, 0) for d in range(-r, r + 1)) / (2 * r + 1)


def smoothstep(lo, hi, x):
    t = np.clip((x - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def half(a):
    """Mean of 2x2 blocks (the last row/column repeated on odd sizes)."""
    h, w = a.shape
    hh, hw = (h + 1) // 2, (w + 1) // 2
    p = np.pad(a, ((0, hh * 2 - h), (0, hw * 2 - w)), mode="edge")
    return p.reshape(hh, 2, hw, 2).mean((1, 3))


def luma(rgb):
    rgb = rgb.astype(np.int32)
    return ((rgb[..., 0] * 77 + rgb[..., 1] * 150 + rgb[..., 2] * 29) >> 8).astype(np.float32)


def mask_half(y):
    """Tone mask (0..1) on a half-size grid."""
    hx = (2 * y - shift(y, 0, -1) - shift(y, 0, 1)) / 4
    hy = (2 * y - shift(y, -1, 0) - shift(y, 1, 0)) / 4
    checker = (2 * hx - shift(hx, -1, 0) - shift(hx, 1, 0)) / 4
    gx = (shift(y, 0, 1) - shift(y, 0, -1)) / 2
    gy = (shift(y, 1, 0) - shift(y, -1, 0)) / 2
    products = (checker * checker, hx * hx + hy * hy, gx * gx, gy * gy, gx * gy)
    ec, eh, jxx, jyy, jxy = (box(box(half(m), 2), 1) for m in products)
    coherence = ((jxx - jyy) ** 2 + 4 * jxy ** 2) / (jxx + jyy + 1) ** 2
    s = (
        smoothstep(P["eLo"], P["eHi"], np.sqrt(ec))
        * smoothstep(P["qLo"], P["qHi"], ec / (eh + 25))
        * (1 - smoothstep(P["cLo"], P["cHi"], coherence))
        * (1 - smoothstep(P["gLo"], P["gHi"], np.sqrt(jxx + jyy)))
    )
    # opening (drops specks), then feathering
    low = np.minimum.reduce([shift(s, dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)])
    s = np.maximum.reduce([shift(low, dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)])
    return box(box(s, 1), 1)


def upsample(mh, h, w):
    """Bilinear: pixel (x, y) reads the half grid at ((x - 0.5) / 2, (y - 0.5) / 2)."""
    hh, hw = mh.shape
    fy = np.clip((np.arange(h) - 0.5) / 2, 0, hh - 1)
    fx = np.clip((np.arange(w) - 0.5) / 2, 0, hw - 1)
    y0, x0 = np.floor(fy).astype(int), np.floor(fx).astype(int)
    y1, x1 = np.minimum(y0 + 1, hh - 1), np.minimum(x0 + 1, hw - 1)
    ty, tx = (fy - y0)[:, None], (fx - x0)[None, :]
    top = mh[y0][:, x0] * (1 - tx) + mh[y0][:, x1] * tx
    bottom = mh[y1][:, x0] * (1 - tx) + mh[y1][:, x1] * tx
    return top * (1 - ty) + bottom * ty


def bilateral(rgb):
    """One pass; range weights from the luma of the (rounded) input."""
    r = int(P["radius"])
    y = luma(np.clip(rgb + 0.5, 0, 255))
    num = np.zeros_like(rgb)
    den = np.zeros(y.shape, np.float32)
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            d = shift(y, dy, dx) - y
            w = np.exp(-(dx * dx + dy * dy) / (2 * P["sigmaSpace"] ** 2)) * np.exp(-(d * d) / (2 * P["sigmaRange"] ** 2))
            num += w[..., None] * np.stack([shift(rgb[..., c], dy, dx) for c in range(3)], -1)
            den += w
    return num / den[..., None]


def process(rgb):
    """rgb: HxWx3 uint8. Returns (smoothed float page, half mask, changed)."""
    mh = mask_half(luma(rgb))
    if (mh > 0.5).mean() < P["minShare"]:
        return rgb.astype(np.float32), mh, False
    m = upsample(mh, *rgb.shape[:2])[..., None]
    b = rgb.astype(np.float32)
    for _ in range(int(P["iterations"])):
        b = np.clip(np.floor(bilateral(b) + 0.5), 0, 255)  # the phone keeps whole 8-bit values between passes
    return m * b + (1 - m) * rgb, mh, True


if __name__ == "__main__":
    src, out = sys.argv[1:3]
    for kv in sys.argv[3:]:
        k, v = kv.split("=")
        P[k] = float(v)
    rgb = np.asarray(Image.open(src).convert("RGB"))
    res, mh, changed = process(rgb)
    Image.fromarray(np.clip(res + 0.5, 0, 255).astype(np.uint8)).save(out)
    Image.fromarray((mh * 255 + 0.5).astype(np.uint8)).save(out.rsplit(".", 1)[0] + "-mask.png")
    print(src.split("/")[-1], "smoothed" if changed else "unchanged", "tone share %.3f" % (mh > 0.5).mean())
