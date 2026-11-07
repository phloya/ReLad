"""Objective check on real pages: spoil a clean page the way bad scans are spoiled, enhance it like the
phone does (paper whitening, strength from the JPEG quality, Real-CUGAN in 200 px tiles), with and
without the screentone handling of tone.py (smoothing before the network, texture back after it),
and score both against the clean page.

usage: real_bench.py <models dir> <out dir> <clean page>...
       DAMAGE=fine,fine60 real_bench.py ...   # only some kinds of damage

Scores (luma, against the clean page scaled to the result's size):
  psnr, ssim      whole page
  white_noise     std left on areas that are flat white paper in the clean page (lower is cleaner)
  tone_psnr       PSNR after a blur (sigma 2) inside the found tone, i.e. is the tone's grey kept
                  without mottling ("worms" score badly)
"""
import io
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import gaussian_filter, minimum_filter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # python -I does not add the script's folder
import tone  # noqa: E402

MODELS, OUT = sys.argv[1:3]
PAGES = sys.argv[3:]

# The spoiling steps of make_page.py: scale, blur, grain, JPEG quality, paper colour, resampling.
# A 2:1 bilinear shrink averages screentone into grey; the "fine" ones shrink with Lanczos, which
# keeps the dots at 2-3 px like on real scans (Beck): the case the network turns into "worms".
DAMAGE = {
    "small": (0.5, 0.0, 2, 80, (255, 255, 255), Image.BILINEAR),
    "yellow": (0.5, 0.5, 3, 60, (228, 222, 205), Image.BILINEAR),
    "bad": (0.5, 0.7, 6, 30, (255, 255, 255), Image.BILINEAR),
    "worst": (0.45, 1.1, 10, 12, (237, 237, 237), Image.BILINEAR),
    "fine": (0.35, 0.3, 2, 87, (255, 255, 255), Image.LANCZOS),
    "fine60": (0.35, 0.4, 3, 60, (240, 236, 225), Image.LANCZOS),
}


def spoil(page, scale, blur, noise, quality, paper, resample):
    small = page.resize((int(page.width * scale), int(page.height * scale)), resample)
    if blur:
        small = small.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(small.convert("RGB")).astype(np.float32) / 255 * np.array(paper, np.float32)
    a += np.random.default_rng(1).normal(0, noise, a.shape[:2])[..., None]
    buf = io.BytesIO()
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(buf, "JPEG", quality=quality)
    return Image.open(io.BytesIO(buf.getvalue())).convert("RGB")


def model_for(quality):
    """ScanQuality.strengthForJpeg"""
    return "se-up2x-denoise1x" if quality < 50 else "se-up2x-conservative"


def whiten(rgb):
    """PaperWhitening.kt: stretch each channel so the most common light tone becomes white (grey pages only)."""
    p = rgb.reshape(-1, 3).astype(np.int32)
    lum = (p[:, 0] * 77 + p[:, 1] * 150 + p[:, 2] * 29) >> 8
    hist = np.bincount(lum, minlength=256)
    smooth = np.convolve(hist, np.ones(5), "same")
    paper = 128 + int(np.argmax(smooth[128:]))
    if paper < 150 or paper >= 250:
        return rgb
    near = np.abs(lum - paper) <= 6
    gains = 255.0 / np.maximum(np.percentile(p[near], 90, axis=0, method="lower"), 150)
    out = p * gains
    if np.mean(out.max(1) - out.min(1) > 40) > 0.03:
        return rgb  # a colour page
    return np.clip(np.round(out), 0, 255).astype(np.uint8).reshape(rgb.shape)


def cugan(rgb, model, name):
    src, dst = f"{OUT}/{name}-in.png", f"{OUT}/{name}.png"
    Image.fromarray(rgb).save(src)
    m = f"{MODELS}/{model}"
    subprocess.run([sys.executable, "-I", f"{HERE}/tiled.py", f"{m}.param", f"{m}.bin", src, dst, "200", "tiles200"],
                   check=True, capture_output=True)
    return np.asarray(Image.open(dst).convert("RGB"))


def grey(rgb):
    return np.asarray(Image.fromarray(rgb).convert("L")).astype(np.float32)


def ssim(a, b):
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mu_a, mu_b = gaussian_filter(a, 1.5), gaussian_filter(b, 1.5)
    va = gaussian_filter(a * a, 1.5) - mu_a ** 2
    vb = gaussian_filter(b * b, 1.5) - mu_b ** 2
    cov = gaussian_filter(a * b, 1.5) - mu_a * mu_b
    return float(np.mean((2 * mu_a * mu_b + c1) * (2 * cov + c2) / ((mu_a ** 2 + mu_b ** 2 + c1) * (va + vb + c2))))


def psnr(a, b):
    return 10 * np.log10(255 ** 2 / max(float(np.mean((a - b) ** 2)), 1e-6))


os.makedirs(OUT, exist_ok=True)
print(f"{'page':14} {'damage':7} {'model':22} {'tone%':>5}  {'psnr':>11} {'ssim':>13} {'white_noise':>11} {'tone_psnr':>11}")
print(f"{'':14} {'':7} {'':22} {'':>5}  {'old   new':>11} {'old    new':>13} {'old   new':>11} {'old   new':>11}")
for path in PAGES:
    clean = Image.open(path).convert("L")
    base = os.path.splitext(os.path.basename(path))[0]
    for damage, steps in DAMAGE.items():
        if os.environ.get("DAMAGE") and damage not in os.environ["DAMAGE"].split(","):
            continue
        name = f"{base}-{damage}"
        scan = np.asarray(spoil(clean, *steps))
        model = model_for(steps[3])
        page = whiten(scan)
        smoothed, mask, changed = tone.process(page)
        old = cugan(page, model, f"{name}-old")
        if changed:
            smoothed = np.clip(np.floor(smoothed + 0.5), 0, 255).astype(np.uint8)
            new = tone.restore(cugan(smoothed, model, f"{name}-new"), page, smoothed)
            Image.fromarray(new).save(f"{OUT}/{name}-new-restored.png")
        else:
            new = old
        old, new = grey(old), grey(new)
        h, w = old.shape
        truth = np.asarray(clean.resize((w, h), Image.LANCZOS)).astype(np.float32)
        flat = minimum_filter(truth, size=9) > 250
        m2 = tone.upsample(mask, *scan.shape[:2])
        m2 = np.asarray(Image.fromarray((m2 * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)) > 127

        def tone_psnr(img):
            if not m2.any():
                return float("nan")
            return psnr(gaussian_filter(img, 2)[m2], gaussian_filter(truth, 2)[m2])

        row = [(psnr(x, truth), ssim(x, truth), float(np.std(x[flat])), tone_psnr(x)) for x in (old, new)]
        print(f"{base:14} {damage:7} {model:22} {100 * (mask > 0.5).mean():5.1f}  "
              f"{row[0][0]:5.2f} {row[1][0]:5.2f} {row[0][1]:6.4f} {row[1][1]:6.4f} "
              f"{row[0][2]:5.2f} {row[1][2]:5.2f} {row[0][3]:5.2f} {row[1][3]:5.2f}", flush=True)
