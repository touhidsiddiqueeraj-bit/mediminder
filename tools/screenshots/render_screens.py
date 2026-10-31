#!/usr/bin/env python3
"""Render the README screenshot mockups into docs/screenshots/.

The screens are defined in screens.html (same folder) using the app's real
Material 3 tokens from ui/theme/Theme.kt and strings from res/values/strings.xml.
Requires Python 3.8+, playwright (pip install playwright && playwright install chromium).

The optional Roboto font (Android's system typeface) is fetched once into
assets/Roboto.ttf; without it the renderer falls back to Noto Sans.

See docs/SCREENSHOTS.md for the full workflow, including how to replace the
mockups with real `adb exec-out screencap` captures.
"""
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "docs" / "screenshots"
FONT = HERE / "assets" / "Roboto.ttf"
FONT_URL = (
    "https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/"
    "Roboto%5Bwdth%2Cwght%5D.ttf"
)

TARGETS = [
    ("#shot-today", "today.png"),
    ("#shot-camera", "scan-camera.png"),
    ("#shot-review", "scan-review.png"),
    ("#shot-add", "add-medication.png"),
    ("#shot-history", "history.png"),
    ("#shot-settings", "settings.png"),
    ("#shot-notification", "notification.png"),
    ("#banner", "banner.png"),
    ("#logo", "logo.png"),
]


def ensure_font() -> None:
    if FONT.exists():
        return
    print("Roboto not found — trying to fetch it (fallback: Noto Sans)…")
    try:
        FONT.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(FONT_URL, timeout=30) as r, open(FONT, "wb") as f:
            shutil.copyfileobj(r, f)
        print(f"saved {FONT}")
    except Exception as exc:  # noqa: BLE001
        FONT.unlink(missing_ok=True)
        print(f"  could not fetch ({exc}); rendering with the fallback font")


def main() -> None:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sys.exit("playwright is not installed: pip install playwright && playwright install chromium")

    ensure_font()
    OUT.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1650, "height": 1150}, device_scale_factor=2)
        page.goto((HERE / "screens.html").as_uri())
        page.wait_for_load_state("networkidle")
        page.evaluate("document.fonts.ready")
        page.wait_for_timeout(400)

        # Any positive overflow means a panel is clipped — fix the HTML, not this check.
        overflow = page.evaluate(
            """() => [...document.querySelectorAll('.phone .scr')].map(scr => ({
                id: scr.closest('.phone').id || 'banner-phone',
                overflow: scr.scrollHeight - scr.clientHeight,
            }))"""
        )
        bad = [row for row in overflow if row["overflow"] > 0]
        for row in overflow:
            print(f"{row['id']}: content exceeds by {row['overflow']}px")

        for sel, name in TARGETS:
            el = page.query_selector(sel)
            if el is None:
                raise SystemExit(f"selector not found: {sel}")
            el.scroll_into_view_if_needed()
            el.screenshot(path=str(OUT / name))
            print(f"saved {name}")
        browser.close()

    if bad:
        sys.exit(f"\n{len(bad)} panel(s) overflow — shorten their content in screens.html and re-run.")

    # Optional: shrink the PNGs (needs Pillow). Flat UI quantizes losslessly enough.
    try:
        from PIL import Image
        for _, name in TARGETS:
            path = OUT / name
            Image.open(path).convert("RGB").quantize(
                colors=256, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG
            ).save(path, optimize=True)
        print("optimized PNGs with Pillow")
    except ImportError:
        print("Pillow not installed — PNGs left unoptimized")


if __name__ == "__main__":
    main()
