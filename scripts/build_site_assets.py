#!/usr/bin/env python3
"""Build the web assets for the marketing site in ``site/``.

Sources live outside the site so the originals stay untouched:

  docs/desktop/*.jpg   desktop app captures (Windows title bar included)
  docs/android/*.png   phone captures (900x1600)
  docs/brand/*.png     master artwork
  public/favicon.svg   vector mark
  android/app/src/main/res/font/manrope_*.ttf

Outputs land in ``site/assets/img`` and ``site/assets/fonts``. Everything is
deterministic: the crop box is a constant, so re-shooting a screenshot only
needs a file swap and a re-run.

Usage:
    python scripts/build_site_assets.py [--only desktop|android|icons|og|fonts]
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parent.parent
DESKTOP_SRC = ROOT / "docs" / "desktop"
ANDROID_SRC = ROOT / "docs" / "android"
EXTENSION_SRC = ROOT / "docs" / "extension"
BRAND_SRC = ROOT / "docs" / "brand"
PUBLIC_SRC = ROOT / "public"
FONT_SRC = ROOT / "android" / "app" / "src" / "main" / "res" / "font"

IMG_OUT = ROOT / "site" / "assets" / "img"
FONT_OUT = ROOT / "site" / "assets" / "fonts"

# Captures are 1920x1020 windows. The only chrome is the Windows 11 title bar in
# the first 29 px; the app window fills the rest edge to edge, so nothing else
# is trimmed and the sidebar (all Completed jobs) stays in frame.
DESKTOP_CROP = (0, 29, 0, 0)
DESKTOP_WIDTHS = (1920, 960)
ANDROID_WIDTH = 720
# 30 / 292 — the screen radius at the size the site renders it.
ANDROID_RADIUS_RATIO = 30 / 292

WEBP_QUALITY = 82

# Desktop captures: stem -> (source file, crop override or None, description).
# The tour re-uses summary.jpg with a lower crop so the same capture reads as a
# different screen (timestamp chips in the body) without a second screenshot.
DESKTOP_SHOTS = {
    "desktop-hero": ("summary.png", None, "Summary view with the sidebar and recent briefs"),
    "desktop-transcript": ("transcript.png", None, "Transcript with 408 timestamped segments"),
    "desktop-details": ("details.png", None, "Details panel: model, engine, duration"),
    "desktop-new-brief": ("new-brief.png", None, "New transcription dialog, media URL"),
    "desktop-settings": ("settings-extension.png", None, "Browser extension install steps"),
}

# Phone captures already committed under docs/android.
ANDROID_SHOTS = {
    "android-home": "home.png",
    "android-progress": "progress.png",
    "android-summary": "summary.png",
    "android-settings": "settings.png",
    "android-transcript": "transcript.png",
    "android-dark": "dark.png",
}

BRAND = {
    "light": "#5B46FB",
    "dark": "#7C6CFF",
    "ink": "#09090B",
    "paper": "#FCFCFD",
}


def log(message: str) -> None:
    print(f"  {message}")


def ensure_dirs() -> None:
    IMG_OUT.mkdir(parents=True, exist_ok=True)
    FONT_OUT.mkdir(parents=True, exist_ok=True)


def webp(image: Image.Image, path: Path) -> None:
    image.save(path, "WEBP", quality=WEBP_QUALITY, method=6)
    log(f"{path.relative_to(ROOT)}  {image.width}x{image.height}  {path.stat().st_size / 1024:.0f} KB")


def build_desktop() -> None:
    print("desktop captures")
    for stem, (filename, override, _) in DESKTOP_SHOTS.items():
        source = DESKTOP_SRC / filename
        if not source.exists():
            print(f"  ! missing {source.relative_to(ROOT)}", file=sys.stderr)
            continue
        with Image.open(source) as raw:
            shot = raw.convert("RGB")
        left, top, right, bottom = override or DESKTOP_CROP
        shot = shot.crop((left, top, shot.width + right, shot.height + bottom))
        for width in DESKTOP_WIDTHS:
            suffix = "" if width == DESKTOP_WIDTHS[0] else f"-{width}"
            height = round(shot.height * width / shot.width)
            resized = shot.resize((width, height), Image.LANCZOS)
            webp(resized, IMG_OUT / f"{stem}{suffix}.webp")


def build_android() -> None:
    print("phone captures")
    for stem, filename in ANDROID_SHOTS.items():
        source = ANDROID_SRC / filename
        if not source.exists():
            print(f"  ! missing {source.relative_to(ROOT)}", file=sys.stderr)
            continue
        with Image.open(source) as raw:
            shot = raw.convert("RGB")
        height = round(shot.height * ANDROID_WIDTH / shot.width)
        resized = shot.resize((ANDROID_WIDTH, height), Image.LANCZOS).convert("RGBA")
        radius = round(ANDROID_WIDTH * ANDROID_RADIUS_RATIO)
        resized.putalpha(corner_mask(resized.size, radius))
        out = IMG_OUT / f"{stem}.webp"
        resized.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
        log(f"{out.relative_to(ROOT)}  {resized.width}x{resized.height}  radius {radius}px  {out.stat().st_size / 1024:.0f} KB")


def corner_mask(size: tuple[int, int], radius: int, scale: int = 4) -> Image.Image:
    """Antialiased rounded-rectangle alpha mask (supersampled 4x)."""
    width, height = size
    big = Image.new("L", (width * scale, height * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, width * scale - 1, height * scale - 1), radius * scale, fill=255
    )
    return big.resize(size, Image.LANCZOS)


def rounded(image: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, image.width - 1, image.height - 1), radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def build_icons() -> None:
    print("icons")
    logo_path = PUBLIC_SRC / "sonicbrief-logo.png"
    master_path = BRAND_SRC / "sonicbrief-icon-1024.png"
    if not logo_path.exists() and not master_path.exists():
        print("  ! no brand artwork found", file=sys.stderr)
        return
    master = Image.open(master_path if master_path.exists() else logo_path).convert("RGBA")

    # Full-bleed square for iOS: flatten transparency onto the brand colour so
    # the home-screen icon is a solid tile rather than a black-cornered mask.
    tile = Image.new("RGB", master.size, BRAND["light"])
    tile.paste(master, (0, 0), master)

    for size, name in ((512, "icon-512.png"), (180, "apple-touch-icon.png")):
        tile.resize((size, size), Image.LANCZOS).save(IMG_OUT / name, "PNG", optimize=True)
        log(f"site/assets/img/{name}  {size}x{size}")
    tile.resize((32, 32), Image.LANCZOS).save(IMG_OUT / "favicon-32.png", "PNG", optimize=True)
    log("site/assets/img/favicon-32.png  32x32")

    svg = PUBLIC_SRC / "favicon.svg"
    if svg.exists():
        shutil.copyfile(svg, IMG_OUT / "favicon.svg")
        log("site/assets/img/favicon.svg")


def load_font(size: int, weight: str = "extrabold") -> ImageFont.FreeTypeFont:
    candidates = [FONT_SRC / f"manrope_{weight}.ttf", FONT_SRC / "manrope_bold.ttf"]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default(size)


def text_width(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont) -> int:
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0]


def wrap(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, limit: int) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in text.split():
        candidate = f"{current} {word}".strip()
        if text_width(draw, candidate, font) <= limit or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def build_og() -> None:
    print("social card")
    width, height = 1200, 630
    card = Image.new("RGB", (width, height), "#0A0A12")
    draw = ImageDraw.Draw(card)

    # Vertical wash, then a soft indigo bloom behind the wordmark.
    for y in range(height):
        t = y / height
        draw.line(
            [(0, y), (width, y)],
            fill=(int(10 + 8 * t), int(10 + 6 * t), int(18 + 24 * t)),
        )
    glow = Image.new("RGB", (width, height), "#000000")
    gdraw = ImageDraw.Draw(glow)
    gdraw.ellipse((60, -320, 1000, 340), fill=(52, 38, 128))
    glow = glow.filter(ImageFilter.GaussianBlur(150))
    card = Image.blend(card, glow, 0.75)
    draw = ImageDraw.Draw(card, "RGBA")

    # Faint rule grid to keep the gradient from banding.
    for x in range(0, width, 48):
        draw.line([(x, 0), (x, height)], fill=(255, 255, 255, 8))
    for y in range(0, height, 48):
        draw.line([(0, y), (width, y)], fill=(255, 255, 255, 8))

    # Product shot on the right, inside a translucent card.
    shot_path = IMG_OUT / "desktop-hero.webp"
    if shot_path.exists():
        with Image.open(shot_path) as raw:
            shot = raw.convert("RGB")
        card_x, card_y, card_w, card_h = 690, 150, 460, 330
        panel = Image.new("RGBA", (card_w, card_h), (255, 255, 255, 18))
        ImageDraw.Draw(panel).rounded_rectangle((0, 0, card_w - 1, card_h - 1), 20, outline=(255, 255, 255, 40), width=2)
        # Contain (never crop) inside the card with a 12 px inset.
        inner_w, inner_h = card_w - 24, card_h - 24
        scale = min(inner_w / shot.width, inner_h / shot.height)
        shot_w, shot_h = round(shot.width * scale), round(shot.height * scale)
        preview = rounded(shot.resize((shot_w, shot_h), Image.LANCZOS), 12)
        panel.alpha_composite(preview, ((card_w - shot_w) // 2, (card_h - shot_h) // 2))
        card.paste(panel, (card_x, card_y), panel)

    # Mark + wordmark.
    logo_path = PUBLIC_SRC / "sonicbrief-logo.png"
    if logo_path.exists():
        with Image.open(logo_path) as raw:
            mark = raw.convert("RGBA").resize((64, 64), Image.LANCZOS)
        card.paste(mark, (80, 78), mark)
    title_font = load_font(40, "extrabold")
    draw.text((160, 92), "SonicBrief", font=title_font, fill="#FFFFFF")

    eyebrow_font = load_font(20, "semibold")
    draw.text((80, 190), "LOCAL-FIRST TRANSCRIPTION", font=eyebrow_font, fill=(167, 155, 255, 255))

    head_font = load_font(58, "extrabold")
    lines = wrap(draw, "Turn any video into a transcript you can search.", head_font, 580)
    y = 236
    for index, line in enumerate(lines):
        colour = "#FFFFFF" if index < len(lines) - 1 else "#A79BFF"
        draw.text((80, y), line, font=head_font, fill=colour)
        y += 68

    body_font = load_font(22, "medium")
    draw.text(
        (80, y + 16),
        "Local Whisper  ·  Windows  ·  Android  ·  Chrome  ·  MCP",
        font=body_font,
        fill=(161, 161, 170, 255),
    )

    # Three proof chips along the bottom.
    chip_font = load_font(20, "semibold")
    x = 80
    for label in ("Free, unlimited", "No account", "No per-minute bill"):
        tw = text_width(draw, label, chip_font)
        w = tw + 36
        draw.rounded_rectangle((x, 520, x + w, 566), 23, fill=(255, 255, 255, 16), outline=(255, 255, 255, 45), width=1)
        draw.text((x + 18, 532), label, font=chip_font, fill="#E7E5F5")
        x += w + 12

    out = IMG_OUT / "og.png"
    card.save(out, "PNG", optimize=True)
    log(f"site/assets/img/og.png  {width}x{height}  {out.stat().st_size / 1024:.0f} KB")


def build_fonts() -> None:
    print("fonts")
    mapping = {
        "manrope_regular.ttf": "manrope-400.ttf",
        "manrope_semibold.ttf": "manrope-600.ttf",
        "manrope_bold.ttf": "manrope-700.ttf",
        "manrope_extrabold.ttf": "manrope-800.ttf",
    }
    for source_name, out_name in mapping.items():
        source = FONT_SRC / source_name
        if not source.exists():
            print(f"  ! missing {source.relative_to(ROOT)}", file=sys.stderr)
            continue
        destination = FONT_OUT / out_name
        shutil.copyfile(source, destination)
        log(f"site/assets/fonts/{out_name}  {destination.stat().st_size / 1024:.0f} KB")


def build_extension() -> None:
    """The Chrome popup is already a 1x capture; keep it at native size."""
    print("extension capture")
    source = EXTENSION_SRC / "popup.png"
    if not source.exists():
        print(f"  ! missing {source.relative_to(ROOT)}", file=sys.stderr)
        return
    with Image.open(source) as raw:
        shot = raw.convert("RGB")
    shot.save(IMG_OUT / "chrome-popup.webp", "WEBP", quality=92, method=6)
    log(
        f"site/assets/img/chrome-popup.webp  {shot.width}x{shot.height}  "
        f"{(IMG_OUT / 'chrome-popup.webp').stat().st_size / 1024:.0f} KB"
    )


STEPS = {
    "desktop": build_desktop,
    "android": build_android,
    "extension": build_extension,
    "icons": build_icons,
    "og": build_og,
    "fonts": build_fonts,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", choices=sorted(STEPS), action="append", help="run a single step (repeatable)")
    args = parser.parse_args()

    ensure_dirs()
    selected = args.only or list(STEPS)
    # og.png embeds the hero capture, so build it after the desktop shots.
    if "og" in selected and "desktop" not in selected and (IMG_OUT / "desktop-hero.webp").exists():
        selected.remove("og")
        selected.append("og")
    for name in selected:
        STEPS[name]()
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
