#!/usr/bin/env python3
"""Regenerates the app's bitmap icons from assets/: python3 assets/make-icons.py (needs Pillow).

- res/mipmap-*/ic_launcher.png: the launcher icon, scaled down from assets/icon.png.
- res/drawable-*/ic_notification.png: the status-bar icon, the ring and clock hands of icon.svg drawn
  white on transparent (Android only uses its alpha). Strokes are a bit heavier than the launcher
  icon's so they stay readable at 24 px.
- fastlane/.../icon.png and featureGraphic.png: the store icon and header image.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
FASTLANE = os.path.join(ROOT, "fastlane/metadata/android/en-US/images")


def path(*parts):
    return os.path.join(ROOT, *parts)


def launcher_icons():
    src = Image.open(path("assets/icon.png")).convert("RGBA")
    for d, scale in DENSITIES.items():
        size = round(48 * scale)
        src.resize((size, size), Image.LANCZOS).save(path(f"res/mipmap-{d}/ic_launcher.png"), optimize=True)
    src.save(os.path.join(FASTLANE, "icon.png"), optimize=True)


def notification_icon(px):
    """The ring (with its gap near the top, as in icon.svg) and the clock hands, in a 24 dp square."""
    ss = 16  # supersampling, for smooth edges after scaling down
    n = px * ss
    u = n / 24.0  # one dp
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    white = (255, 255, 255, 255)
    c = n / 2
    ring = 2.4 * u  # stroke width
    outer = 10.5 * u  # outer radius: 1.5 dp of padding
    box = [c - outer, c - outer, c + outer, c + outer]
    # icon.svg: the dash starts at -88° and the gap is the last 10.8° before it.
    d.arc(box, start=-88, end=-88 + 360 - 10.8, fill=white, width=round(ring))
    # Hands, scaled from icon.svg (centre 256, ring radius 155): 12 o'clock to centre to about 4 o'clock.
    r_mid = outer - ring / 2
    k = r_mid / 155.0
    hands = [(c, c - (256 - 181.6) * k), (c, c), (c + (302.5 - 256) * k, c + (285.45 - 256) * k)]
    w = 2.2 * u
    d.line(hands, fill=white, width=round(w), joint="curve")
    for x, y in (hands[0], hands[2]):
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=white)
    return im.resize((px, px), Image.LANCZOS)


def notification_icons():
    for d, scale in DENSITIES.items():
        out = path(f"res/drawable-{d}/ic_notification.png")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        notification_icon(round(24 * scale)).save(out, optimize=True)


def feature_graphic():
    """The banner at 1024x500, the size stores ask for, trimming a few pixels top and bottom."""
    b = Image.open(path("assets/banner.png")).convert("RGB")
    w, h = b.size
    ch = round(w * 500 / 1024)
    top = (h - ch) // 2
    b.crop((0, top, w, top + ch)).resize((1024, 500), Image.LANCZOS).save(
        os.path.join(FASTLANE, "featureGraphic.png"), optimize=True)


if __name__ == "__main__":
    launcher_icons()
    notification_icons()
    feature_graphic()
