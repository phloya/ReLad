"""Downloads the candidate models of the comparison into <dir> (pinned versions, as in plus/DECISIONS.md).

usage: fetch_models.py <dir>
"""
import io
import sys
import urllib.request
import zipfile

DIR = sys.argv[1]
WAIFU2X = "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/64914665c45893135c9e50c1c296170a121b9f77/models/models-cunet"
CUGAN = "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/models"
ESRGAN_ZIP = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-macos.zip"


def get(url, name):
    with urllib.request.urlopen(url) as r, open(f"{DIR}/{name}", "wb") as f:
        f.write(r.read())


for n in (1, 3):
    for ext in ("param", "bin"):
        get(f"{WAIFU2X}/noise{n}_scale2.0x_model.{ext}", f"noise{n}_scale2.0x_model.{ext}")
for kind, names in (("se", ("conservative", "no-denoise", "denoise1x", "denoise2x", "denoise3x")), ("pro", ("conservative", "denoise3x"))):
    for n in names:
        for ext in ("param", "bin"):
            get(f"{CUGAN}/models-{kind}/up2x-{n}.{ext}", f"{kind}-up2x-{n}.{ext}")
# Real-ESRGAN publishes its ncnn files only inside release archives: take just the models
with urllib.request.urlopen(ESRGAN_ZIP) as r:
    archive = zipfile.ZipFile(io.BytesIO(r.read()))
for name in archive.namelist():
    base = name.split("/")[-1]
    if base.startswith(("realesr-animevideov3-x2.", "realesrgan-x4plus-anime.")):
        with open(f"{DIR}/{base}", "wb") as f:
            f.write(archive.read(name))
print("ok")
