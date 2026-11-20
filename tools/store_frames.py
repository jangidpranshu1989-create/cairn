"""Builds framed store screenshots + feature graphic from Paparazzi StoreShots renders."""
import glob, math, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SNAP = f"{R}/android/app/src/test/snapshots/images"
FONT = f"{R}/android/app/src/main/res/font"
DEV = "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Bold.ttf"
DEVR = "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf"
SHOTS = [("s1Home", {"en": ("Just say it.", "Cairn gets it done."), "hi": ("बस बोलिए,", "काम कैर्न करेगा।")}),
         ("s2Cart", {"en": ("Compares trusted stores,", "fills your cart."), "hi": ("भरोसेमंद स्टोर्स की तुलना,", "फिर कार्ट में ऐड।")}),
         ("s3Approval", {"en": ("Every payment", "needs your OK."), "hi": ("हर पेमेंट से पहले", "आपकी मंज़ूरी।")}),
         ("s5HomeLight", {"en": ("Light or dark,", "calm and fast."), "hi": ("लाइट या डार्क,", "तेज़ और सुकून भरा।")}),
         ("s6Setup", {"en": ("Set up once.", "Private on your phone."), "hi": ("एक बार सेटअप,", "डेटा आपके फ़ोन पर।")})]
AMBER = (232, 196, 140)

def font(lang, bold=True, size=80):
    if lang == "hi": return ImageFont.truetype(DEV if bold else DEVR, size)
    return ImageFont.truetype(f"{FONT}/inter_display_bold.ttf" if bold else f"{FONT}/inter_medium.ttf", size)

def aurora(w, h):
    bg = Image.new("RGB", (w, h), (16, 15, 19))
    glow = Image.new("RGB", (w, h), (0, 0, 0)); d = ImageDraw.Draw(glow)
    for (cx, cy, r, col) in [(0.15, 0.1, 0.55, (120, 84, 40)), (0.9, 0.35, 0.5, (52, 48, 120)), (0.3, 0.95, 0.5, (20, 90, 90))]:
        d.ellipse([w*cx - w*r, h*cy - w*r, w*cx + w*r, h*cy + w*r], fill=col)
    glow = glow.filter(ImageFilter.GaussianBlur(w // 5))
    return Image.blend(bg, glow, 0.55)

def phone(shot, width):
    s = Image.open(shot).convert("RGB")
    s = s.crop((0, 0, 1080, 1835 if "Home" in shot else 2000))
    sw = width - 36; sh = int(s.height * sw / s.width)
    s = s.resize((sw, sh), Image.LANCZOS)
    fr = Image.new("RGBA", (width, sh + 36), (0, 0, 0, 0)); d = ImageDraw.Draw(fr)
    d.rounded_rectangle([0, 0, width - 1, sh + 35], radius=70, fill=(38, 37, 42), outline=(78, 76, 84), width=3)
    m = Image.new("L", (sw, sh), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, sw - 1, sh - 1], radius=54, fill=255)
    fr.paste(s, (18, 18), m)
    return fr

def frame(key, cap, lang, out):
    W, H = 1080, 1920
    img = aurora(W, H).convert("RGBA"); d = ImageDraw.Draw(img)
    f = font(lang, True, 76 if lang == "en" else 70)
    y = 120
    for i, line in enumerate(cap):
        tw = d.textlength(line, font=f)
        d.text(((W - tw) / 2, y), line, font=f, fill=(245, 242, 236) if i == 0 else AMBER)
        y += 100
    ph = phone(glob.glob(f"{SNAP}/app.cairn.browser_StoreShots_{key}_*.png")[0], 820)
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([130, 400, 130 + 820, 400 + ph.height], radius=70, fill=(0, 0, 0, 160))
    img = Image.alpha_composite(img, sh.filter(ImageFilter.GaussianBlur(30)))
    img.alpha_composite(ph, ((W - 820) // 2, 360))
    img.convert("RGB").save(out, optimize=True)

def feature(out):
    W, H = 1024, 500
    img = aurora(W, H).convert("RGBA"); d = ImageDraw.Draw(img)
    logo = Image.open(f"{R}/fastlane/metadata/android/en-US/images/icon.png").convert("RGBA").resize((72, 72))
    m = Image.new("L", (72, 72), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, 71, 71], radius=18, fill=255)
    img.paste(logo, (56, 60), m)
    d.text((144, 70), "Cairn", font=font("en", True, 46), fill=(245, 242, 236))
    d.text((56, 170), "Just say it.", font=font("en", True, 54), fill=(245, 242, 236))
    d.text((56, 232), "Cairn gets it done.", font=font("en", True, 54), fill=AMBER)
    sub = font("en", False, 22)
    for i, t in enumerate(["The AI browser that compares trusted stores,", "fills your cart and pays only with your OK.", "Hindi, Hinglish or English."]):
        d.text((56, 320 + i * 32), t, font=sub, fill=(200, 196, 190))
    ph = phone(glob.glob(f"{SNAP}/app.cairn.browser_StoreShots_s1Home_*.png")[0], 260)
    ph2 = phone(glob.glob(f"{SNAP}/app.cairn.browser_StoreShots_s2Cart_*.png")[0], 260)
    img.alpha_composite(ph2.crop((0, 0, 260, 460)), (740, 60))
    img.alpha_composite(ph.crop((0, 0, 260, 470)), (560, 30))
    img.convert("RGB").save(out, optimize=True)

if __name__ == "__main__":
    for lang, loc in [("en", "en-US"), ("hi", "hi-IN")]:
        dd = f"{R}/fastlane/metadata/android/{loc}/images/phoneScreenshots"; os.makedirs(dd, exist_ok=True)
        for p in glob.glob(f"{dd}/*.png"): os.remove(p)
        for i, (k, caps) in enumerate(SHOTS, 1): frame(k, caps[lang], lang, f"{dd}/{i}.png")
    en = f"{R}/fastlane/metadata/android/en-US/images"
    feature(f"{en}/featureGraphic.png")
    hi = f"{R}/fastlane/metadata/android/hi-IN/images"
    for n in ["featureGraphic.png", "icon.png"]:
        Image.open(f"{en}/{n}").save(f"{hi}/{n}")
