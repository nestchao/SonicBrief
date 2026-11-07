"""Render the SonicBrief logo (public/favicon.svg) with Pillow.

The logo is four rounded tiles on a 24x24 grid, so it is drawn directly
instead of pulling in an SVG renderer. Run this module to regenerate the
committed desktop .ico and the Chrome extension PNG icons:

    .venv\\Scripts\\python.exe desktop\\icons.py
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ICO_PATH = ROOT / "desktop" / "sonicbrief.ico"
EXTENSION_ICON_DIR = ROOT / "extension" / "icons"

# (x0, y0, x1, y1, radius, colour, corners[tl, tr, br, bl]) on the 24-unit grid.
_TILES = [
    (12, 12, 22, 22, 2.727, "#68C4FF", (False, True, True, True)),
    (15, 2, 22, 9, 2.0, "#0C79D8", (True, True, True, True)),
    (2, 15, 9, 22, 2.0, "#0C79D8", (True, True, True, True)),
    (2, 2, 12, 12, 2.727, "#2E9EFF", (True, True, False, True)),
]


def render_logo(size: int = 64) -> Image.Image:
    """Return an RGBA logo image of ``size`` x ``size`` pixels."""
    scale = 512 / 24  # Draw large, then downsample for anti-aliasing.
    canvas = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    for x0, y0, x1, y1, radius, colour, corners in _TILES:
        draw.rounded_rectangle(
            (x0 * scale, y0 * scale, x1 * scale, y1 * scale),
            radius=radius * scale,
            fill=colour,
            corners=corners,
        )
    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    render_logo(256).save(ICO_PATH, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    EXTENSION_ICON_DIR.mkdir(parents=True, exist_ok=True)
    for size in (16, 32, 48, 128):
        render_logo(size).save(EXTENSION_ICON_DIR / f"icon{size}.png")
    print(f"Wrote {ICO_PATH} and {EXTENSION_ICON_DIR}")


if __name__ == "__main__":
    main()
