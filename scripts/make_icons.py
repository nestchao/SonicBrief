"""Generate the SonicBrief app icons from the brand artwork.

The Tauri icon set (window, taskbar, tray, Start Menu, installer) used to be the
blue four-square placeholder Tauri scaffolds. This takes the purple waveform
artwork and writes every size Tauri and Windows need, plus the web logo and
favicon used by the app itself.

Run: .venv\\Scripts\\python.exe scripts\\make_icons.py
     .venv\\Scripts\\python.exe scripts\\make_icons.py --source "Glossy Purple Waveform App Icon.png"
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "desktop-app" / "src-tauri" / "icons"

SIZE = 1024
RADIUS = 0.23          # of the canvas, measured from the source logo
TOP = (97, 68, 245)    # #6144F5
BOTTOM = (102, 87, 245)  # #6657F5
BAR = (242, 243, 252)  # #F2F3FC

# Measured from public/sonicbrief-logo.png: heights as a fraction of the square,
# bar width and centres as fractions of the canvas.
BAR_HEIGHTS = (0.16, 0.37, 0.60, 0.37, 0.16)
BAR_WIDTH = 0.07
BAR_CENTRES = (0.237, 0.377, 0.518, 0.667, 0.807)

# Transparent breathing room around the artwork, as a fraction of the tile.
MARGIN = 0.04


def load_source(path: Path) -> Image.Image:
    """Trim the transparent border of the artwork and centre it in a square tile."""
    artwork = Image.open(path).convert("RGBA")
    bbox = artwork.getbbox()
    if bbox:
        artwork = artwork.crop(bbox)

    side = max(artwork.size)
    inner = round(side * (1 - 2 * MARGIN))
    scale = inner / max(artwork.size)
    resized = artwork.resize(
        (max(1, round(artwork.width * scale)), max(1, round(artwork.height * scale))),
        Image.LANCZOS,
    )

    tile = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    tile.paste(resized, ((side - resized.width) // 2, (side - resized.height) // 2), resized)
    return tile.resize((SIZE, SIZE), Image.LANCZOS)



def _vertical_gradient(size: int) -> Image.Image:
    gradient = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        # Smooth ease so the middle reads lighter than a linear ramp.
        t = 0.35 * t + 0.65 * (t ** 1.6)
        gradient.putpixel(
            (0, y),
            tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)),
        )
    return gradient.resize((size, size), Image.NEAREST)


def render(size: int = SIZE) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=round(size * RADIUS), fill=255
    )
    canvas.paste(_vertical_gradient(size), (0, 0), mask)

    draw = ImageDraw.Draw(canvas)
    bar_w = size * BAR_WIDTH
    cap = bar_w / 2
    for height, centre in zip(BAR_HEIGHTS, BAR_CENTRES):
        bar_h = size * height
        cx = size * centre
        top = (size - bar_h) / 2
        draw.rounded_rectangle(
            (cx - bar_w / 2, top, cx + bar_w / 2, top + bar_h),
            radius=cap,
            fill=BAR,
        )
    return canvas


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        help="Brand artwork (transparent PNG). Without it the mark is redrawn from the measured proportions.",
    )
    args = parser.parse_args()

    ICON_DIR.mkdir(parents=True, exist_ok=True)
    master = load_source(args.source) if args.source else render()
    master.save(ICON_DIR / "icon.png")

    for name, px in (
        ("32x32.png", 32),
        ("128x128.png", 128),
        ("128x128@2x.png", 256),
    ):
        master.resize((px, px), Image.LANCZOS).save(ICON_DIR / name)
        print(f"wrote {name} ({px}px)")

    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    master.resize((256, 256), Image.LANCZOS).save(
        ICON_DIR / "icon.ico", sizes=ico_sizes
    )
    print(f"wrote icon.ico ({len(ico_sizes)} sizes)")

    # The web UI and the extension popup reuse the same artwork.
    web_logo = ROOT / "public" / "sonicbrief-logo.png"
    master.resize((512, 512), Image.LANCZOS).save(web_logo)
    print(f"wrote {web_logo.relative_to(ROOT)} (512px)")

    if args.source:
        print("skipped favicon.svg (source artwork is raster)")
        return

    # Keep the browser favicon in the same brand when drawing from proportions.
    favicon = ROOT / "public" / "favicon.svg"
    bars = []
    for height, centre in zip(BAR_HEIGHTS, BAR_CENTRES):
        top = (1 - height) / 2
        bars.append(
            f'<rect x="{centre - BAR_WIDTH / 2:.4f}" y="{top:.4f}" '
            f'width="{BAR_WIDTH:.4f}" height="{height:.4f}" rx="{BAR_WIDTH / 2:.4f}" fill="#F2F3FC"/>'
        )
    favicon.write_text(
        '<svg width="24" height="24" viewBox="0 0 1 1" xmlns="http://www.w3.org/2000/svg">\n'
        "  <defs>\n"
        '    <linearGradient id="g" x1="0" y1="0" x2="0" y2="1">\n'
        '      <stop offset="0" stop-color="#6144F5"/>\n'
        '      <stop offset="1" stop-color="#6657F5"/>\n'
        "    </linearGradient>\n"
        "  </defs>\n"
        f'  <rect width="1" height="1" rx="{RADIUS}" fill="url(#g)"/>\n'
        + "".join(f"  {bar}\n" for bar in bars)
        + "</svg>\n",
        encoding="utf-8",
    )
    print("wrote public/favicon.svg")


if __name__ == "__main__":
    main()
