"""
XeoGo - every icon and logo picture, made from branding/logo.png.

    python branding/make-icons.py          (needs Python 3 with Pillow: pip install pillow)

Run it again whenever logo.png changes; it overwrites:

    web/                  icon-192.png, icon-512.png, icon-maskable-512.png, apple-touch-icon.png,
                          favicon.png, logo.png
    agent/packaging/      xeogo.ico, icon-256.png, the four wizard-*.bmp of the installer
    agent/.../station-ui/ icon-64.png (window and tray icon), logo.png
    android/app and android/verifier
                          res/mipmap-*/ic_launcher_foreground.png (the launcher icon of all three apps)
    android/app           res/drawable-nodpi/logo.png (at the top of the app's screens)

The logo is a full square picture with its own dark blue background. Where a
shape is cut out of it (Android's round icons, "maskable" web icons) the logo
is drawn smaller on a background made from its own edge colours, so nothing of
the drawing is cut off and no border shows.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LOGO = Image.open(os.path.join(HERE, "logo.png")).convert("RGB")
NAVY = (2, 18, 74)


def out(*parts):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def square(size):
    """The whole logo, scaled."""
    return LOGO.resize((size, size), Image.LANCZOS)


def rounded(size, radius=0.2):
    """The logo with round corners (transparent outside): Windows icons, the logo in a page header."""
    big = size * 4
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * radius), fill=255)
    img = square(size).convert("RGBA")
    img.putalpha(mask.resize((size, size), Image.LANCZOS))
    return img


def field(size):
    """A smooth dark blue background in the colours of the logo's own edges."""
    w, h = LOGO.size
    m = 6
    px = lambda x, y: LOGO.getpixel((x, y))
    edge = [px(m, m), px(w // 2, m), px(w - m, m),
            px(m, h // 2), None, px(w - m, h // 2),
            px(m, h - m), px(w // 2, h - m), px(w - m, h - m)]
    mids = [edge[1], edge[3], edge[5], edge[7]]
    edge[4] = tuple(sum(c[i] for c in mids) // 4 for i in range(3))
    grid = Image.new("RGB", (3, 3))
    grid.putdata(edge)
    return grid.resize((size, size), Image.BICUBIC)


def blend(canvas, x, y, inner):
    """Draws the logo, `inner` pixels wide, onto a background; its edges fade into it."""
    logo = square(inner)
    feather = max(2, int(inner * 0.07))
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rectangle([feather, feather, inner - feather - 1, inner - feather - 1], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2.2))
    canvas.paste(logo, (x, y), mask)
    return canvas


def padded(size, scale):
    """The logo at `scale` of the picture, in the middle of a background that continues its own."""
    inner = int(round(size * scale))
    at = (size - inner) // 2
    return blend(field(size), at, at, inner)


def font(name, size):
    for candidate in (name, "segoeui.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts", candidate), size)
        except OSError:
            continue
    return ImageFont.load_default()


def wizard(width, height):
    """The tall picture on the first and last page of the Windows installer."""
    img = field(max(width, height)).resize((width, height), Image.BICUBIC)
    logo_size = int(width * 0.94)
    blend(img, (width - logo_size) // 2, int(height * 0.07), logo_size)
    d = ImageDraw.Draw(img)
    cx = width / 2
    title = font("segoeuib.ttf", int(width * 0.2))
    sub = font("seguisb.ttf", int(width * 0.105))
    tag = font("segoeui.ttf", int(width * 0.07))
    y = int(height * 0.07) + logo_size + int(height * 0.02)
    d.text((cx, y), "XeoGo", font=title, fill=(255, 255, 255), anchor="ma")
    y += int(width * 0.27)
    d.text((cx, y), "Station", font=sub, fill=(150, 205, 255), anchor="ma")
    d.text((cx, height - int(height * 0.06)), "QuICK PrINT", font=tag, fill=(140, 160, 205), anchor="ms")
    return img


def main():
    # ---- website
    square(192).save(out("web", "icon-192.png"), optimize=True)
    square(512).save(out("web", "icon-512.png"), optimize=True)
    padded(512, 0.78).save(out("web", "icon-maskable-512.png"), optimize=True)
    square(180).save(out("web", "apple-touch-icon.png"), optimize=True)
    square(64).save(out("web", "favicon.png"), optimize=True)
    rounded(128).save(out("web", "logo.png"), optimize=True)

    # ---- the Xerox center's Station (Windows)
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    rounded(256).save(out("agent", "packaging", "xeogo.ico"), format="ICO", sizes=[(s, s) for s in sizes])
    rounded(256).save(out("agent", "packaging", "icon-256.png"), optimize=True)
    rounded(64).save(out("agent", "src", "main", "resources", "station-ui", "icon-64.png"), optimize=True)
    rounded(128).save(out("agent", "src", "main", "resources", "station-ui", "logo.png"), optimize=True)
    wizard(164, 314).save(out("agent", "packaging", "wizard-100.bmp"))
    wizard(328, 628).save(out("agent", "packaging", "wizard-200.bmp"))
    square(55).save(out("agent", "packaging", "wizard-small-100.bmp"))
    square(110).save(out("agent", "packaging", "wizard-small-200.bmp"))

    # ---- Android: the launcher icon (student app, staff app, payment verifier). Android cuts a circle or a
    #      rounded square out of the middle two thirds: the logo fills exactly that part.
    for module in ("app", "verifier"):
        for density, px in (("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
            padded(px, 72 / 108).save(out("android", module, "src", "main", "res", "mipmap-" + density,
                                          "ic_launcher_foreground.png"), optimize=True)
    # the logo at the top of the app's screens
    square(192).save(out("android", "app", "src", "main", "res", "drawable-nodpi", "logo.png"), optimize=True)

    if "--sheet" in sys.argv:        # one picture of everything, to look at
        sheet = Image.new("RGB", (1500, 760), (236, 238, 232))
        sheet.paste(square(256), (20, 20))
        sheet.paste(padded(256, 0.78), (296, 20))
        fg = padded(432, 72 / 108)
        circle = Image.new("L", (288, 288), 0)
        ImageDraw.Draw(circle).ellipse([0, 0, 287, 287], fill=255)
        sheet.paste(fg.crop((72, 72, 360, 360)), (572, 20), circle)
        squircle = Image.new("L", (288, 288), 0)
        ImageDraw.Draw(squircle).rounded_rectangle([0, 0, 287, 287], radius=80, fill=255)
        sheet.paste(fg.crop((72, 72, 360, 360)), (880, 20), squircle)
        sheet.paste(fg.resize((216, 216)), (1188, 20))
        r = rounded(128)
        sheet.paste(r, (20, 330), r)
        for i, s in enumerate((64, 48, 32, 24, 16)):
            small = rounded(s)
            sheet.paste(small, (170 + i * 80, 330), small)
        sheet.paste(wizard(328, 628).resize((328, 628)), (600, 330 - 220))
        sheet.paste(wizard(164, 314), (960, 330))
        sheet.paste(square(110), (1150, 330))
        sheet.save(sys.argv[sys.argv.index("--sheet") + 1])
    print("Icons written.")


main()
