"""Runs candidate upscaling models (ncnn, CPU, whole page at once) on test scans and scores them against the clean page.

usage: bench.py <models dir> <pages dir> <out dir> <scan names...>
       ONLY=cugan-se-cons,cugan-se-d3 bench.py ...   # only some candidates
"""
import os
import sys
import time

import ncnn
import numpy as np
from PIL import Image
from scipy.ndimage import minimum_filter

MODELS, PAGES, OUT = sys.argv[1:4]
SCANS = sys.argv[4:]
ONLY = [x for x in os.environ.get("ONLY", "").split(",") if x]

# name: (files, kind, prepadding, scale)
CANDIDATES = {
    "waifu2x-n1": ("noise1_scale2.0x_model", "w2x", 18, 2),
    "waifu2x-n3": ("noise3_scale2.0x_model", "w2x", 18, 2),
    "cugan-se-cons": ("se-up2x-conservative", "cugan", 18, 2),
    "cugan-se-nod": ("se-up2x-no-denoise", "cugan", 18, 2),
    "cugan-se-d1": ("se-up2x-denoise1x", "cugan", 18, 2),
    "cugan-se-d2": ("se-up2x-denoise2x", "cugan", 18, 2),
    "cugan-se-d3": ("se-up2x-denoise3x", "cugan", 18, 2),
    "cugan-pro-cons": ("pro-up2x-conservative", "cugan", 18, 2),
    "cugan-pro-d3": ("pro-up2x-denoise3x", "cugan", 18, 2),
    "esr-animev3-x2": ("realesr-animevideov3-x2", "esr", 10, 2),
    "esr-anime6b-x4": ("realesrgan-x4plus-anime", "esr", 10, 4),
}
BLOBS = {"w2x": ("Input1", "Eltwise4"), "cugan": ("in0", "out0"), "esr": ("data", "output")}


def run(name, rgb):
    files, kind, pad, scale = CANDIDATES[name]
    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.opt.num_threads = 8
    net.load_param(f"{MODELS}/{files}.param")
    net.load_model(f"{MODELS}/{files}.bin")
    h, w, _ = rgb.shape
    x = rgb.astype(np.float32).transpose(2, 0, 1) / 255.0
    extra_h, extra_w = (h % 2, w % 2) if kind == "cugan" else (0, 0)
    mode = "reflect" if kind == "cugan" else "edge"
    x = np.pad(x, ((0, 0), (pad, pad + extra_h), (pad, pad + extra_w)), mode=mode)
    ex = net.create_extractor()
    ex.input(BLOBS[kind][0], ncnn.Mat(np.ascontiguousarray(x)))
    _, out = ex.extract(BLOBS[kind][1])
    y = np.array(out)
    if kind == "esr":
        o = pad * scale
        y = y[:, o:o + h * scale, o:o + w * scale]
    else:
        y = y[:, :h * scale, :w * scale]
    y = np.clip(y * 255.0 + 0.5, 0, 255).astype(np.uint8).transpose(1, 2, 0)
    img = Image.fromarray(y)
    if scale != 2:
        img = img.resize((w * 2, h * 2), Image.LANCZOS)
    return img


truth = Image.open(f"{PAGES}/truth.png").convert("L")


def score(img):
    """PSNR against the clean page, and noise left in areas that are flat white in the clean page."""
    t = np.asarray(truth.resize(img.size, Image.LANCZOS)).astype(np.float32)
    g = np.asarray(img.convert("L")).astype(np.float32)
    mse = np.mean((t - g) ** 2)
    psnr = 10 * np.log10(255 ** 2 / mse)
    flat = minimum_filter(np.asarray(truth.resize(img.size)), size=9) > 250
    white_noise = float(np.std(g[flat]))
    white_mean = float(np.mean(g[flat]))
    return psnr, white_noise, white_mean


for scan in SCANS:
    rgb = np.asarray(Image.open(f"{PAGES}/{scan}.png").convert("RGB"))
    base = Image.fromarray(rgb).resize((rgb.shape[1] * 2, rgb.shape[0] * 2), Image.BICUBIC)
    base.save(f"{OUT}/{scan}--bicubic.png")
    p, n, m = score(base)
    print(f"{scan:6} {'bicubic':16} psnr={p:5.2f} white_noise={n:5.2f} white={m:5.1f}", flush=True)
    for name in ONLY or CANDIDATES:
        t0 = time.time()
        img = run(name, rgb)
        dt = time.time() - t0
        img.save(f"{OUT}/{scan}--{name}.png")
        p, n, m = score(img)
        print(f"{scan:6} {name:16} psnr={p:5.2f} white_noise={n:5.2f} white={m:5.1f} cpu={dt:5.1f}s", flush=True)
