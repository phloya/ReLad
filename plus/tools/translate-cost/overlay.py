#!/usr/bin/env python3
"""Draws a live test's translation over the original pages, to check boxes and text by eye.

Not the app's typesetting (that one runs on Android): boxes are filled white and the translation
is fitted inside, sound effects get a yellow frame, the speaker is written above the box.

    python3 -I overlay.py <pages dir> <live test out dir> <result dir>

Needs Pillow. Page N of chapter dir "X" is matched to "<X>-<page name>.json" in the out dir.
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"


def fit(draw, text, box, max_size=40):
    x0, y0, x1, y1 = box
    width, height = max(10, x1 - x0), max(10, y1 - y0)
    for size in range(max_size, 7, -1):
        font = ImageFont.truetype(FONT, size)
        lines, line = [], ""
        for word in text.split():
            trial = f"{line} {word}".strip()
            if draw.textlength(trial, font=font) <= width or not line:
                line = trial
            else:
                lines.append(line)
                line = word
        lines.append(line)
        line_height = size * 1.15
        if len(lines) * line_height <= height and all(draw.textlength(l, font=font) <= width for l in lines):
            return font, lines, line_height
    font = ImageFont.truetype(FONT, 8)
    return font, [text], 9


def main():
    pages_dir, out_dir, result_dir = sys.argv[1:4]
    os.makedirs(result_dir, exist_ok=True)
    chapter = os.path.basename(os.path.normpath(pages_dir))
    for name in sorted(os.listdir(pages_dir)):
        stem, ext = os.path.splitext(name)
        data_path = os.path.join(out_dir, f"{chapter}-{stem}.json")
        if ext.lower() not in (".jpg", ".jpeg", ".png", ".webp") or not os.path.exists(data_path):
            continue
        page = Image.open(os.path.join(pages_dir, name)).convert("RGB")
        draw = ImageDraw.Draw(page)
        for region in json.load(open(data_path, encoding="utf-8"))["regions"]:
            b = region["box"]
            box = (b["left"], b["top"], b["right"], b["bottom"])
            if region["type"] in ("bubble", "caption"):
                draw.rectangle(box, fill="white", outline="red", width=2)
            else:
                draw.rectangle(box, outline="orange", width=3)
            font, lines, line_height = fit(draw, region["translation"], box)
            y = box[1] + (box[3] - box[1] - len(lines) * line_height) / 2
            for line in lines:
                x = box[0] + (box[2] - box[0] - draw.textlength(line, font=font)) / 2
                draw.text((x, y), line, font=font, fill="black", stroke_width=2 if region["type"] == "sfx" else 0,
                          stroke_fill="white")
                y += line_height
            if region.get("speaker"):
                draw.text((box[0], max(0, box[1] - 14)), region["speaker"], font=ImageFont.truetype(FONT, 12),
                          fill="blue")
        page.save(os.path.join(result_dir, f"{stem}.jpg"), quality=88)


if __name__ == "__main__":
    main()
