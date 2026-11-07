#!/usr/bin/env python3
"""Rough price of translating one chapter with different models.

Estimates for planning, not measurements: token counts of a typical page and list prices per
million tokens (PRICES, checked 2026-11-07 on the providers' pricing pages; check again before
relying on them). The app records the real token counts of every request (plus.core.ai.AiUsage);
those replace these guesses once real chapters are translated.

    python3 cost.py                 # 20-page manga chapter, 1000x1500 pages
    python3 cost.py --manhwa        # 70 slices of 800x2000
"""
import argparse
import math


def claude_image(w, h):  # 28-px patches; Claude 4.7+ downscales above 2576 px / 4784 tokens
    return min(math.ceil(w / 28) * math.ceil(h / 28), 4784)


def openai_image(w, h):  # gpt-5.4+: 32-px patches x 1.2
    return math.ceil(math.ceil(w / 32) * math.ceil(h / 32) * 1.2)


def gemini3_image(w, h):  # Gemini 3/4: fixed budget per image (media_resolution high/default)
    return 1120


# name: (input $/MTok, cache read $/MTok or None, output $/MTok, image tokens(w, h), thinking tokens we can't avoid)
PRICES = {
    "claude-opus-5-5": (4.00, 0.20, 20.00, claude_image, 600),  # thinking can't be off: effort low
    "claude-sonnet-5-5": (2.00, 0.20, 10.00, claude_image, 0),  # thinking off ("between_tools")
    "claude-haiku-5-5": (0.10, 0.01, 0.50, claude_image, 0),  # thinking off at effort <= high
    "gemini-3.8-flash": (0.75, 0.075, 3.75, gemini3_image, 300),  # doubles from 2027-01-01
    "gemini-3.1-flash-lite": (0.25, 0.025, 1.50, gemini3_image, 200),
    "gpt-6.1-terra": (1.00, 0.05, 5.00, openai_image, 0),
    "gpt-6-luna": (0.10, 0.01, 0.50, openai_image, 0),
}

CACHE_WRITE = 1.25  # first request of a chapter writes the cache


def chapter_cost(model, pages, size, fixed, page_text, out, cached, batch):
    price_in, price_cache, price_out, image_tokens, thinking = PRICES[model]
    total = 0.0
    for page in range(pages):
        if cached and price_cache is not None:
            fixed_price = price_in * CACHE_WRITE if page == 0 else price_cache
        else:
            fixed_price = price_in
        total += fixed * fixed_price / 1e6
        total += (image_tokens(*size) + page_text) * price_in / 1e6
        total += (out + thinking) * price_out / 1e6
    return total / 2 if batch else total


SCENARIOS = [
    # label, fixed prompt tokens, per-page text tokens, output tokens, cached, batch
    ("now (v1)", 600, 300, 1000, False, False),
    ("new: notes + context", 3000, 500, 900, False, False),
    ("new + cache", 3000, 500, 900, True, False),
    ("new + cache + short JSON", 3000, 500, 550, True, False),
    ("... + batch (whole chapter ahead)", 3000, 500, 550, True, True),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manhwa", action="store_true", help="70 slices of 800x2000 instead of 20 pages of 1000x1500")
    args = parser.parse_args()
    pages, size = (70, (800, 2000)) if args.manhwa else (20, (1000, 1500))
    print(f"US$ per chapter: {pages} images of {size[0]}x{size[1]}\n")
    print(f"{'':<36}" + "".join(f"{m:>22}" for m in PRICES))
    for label, fixed, text, out, cached, batch in SCENARIOS:
        row = "".join(
            f"{chapter_cost(m, pages, size, fixed, text, out, cached, batch):>22.3f}" for m in PRICES
        )
        print(f"{label:<36}{row}")


if __name__ == "__main__":
    main()
