from PIL import Image, ImageDraw, ImageFont
import sys
FONT_DIR = '/workspace/ulfur-video/proj/app/src/main/res/font/'
def font(name, size):
    try: return ImageFont.truetype(FONT_DIR + name, size)
    except Exception: return ImageFont.load_default()
box = tuple(int(v) for v in sys.argv[1].split(','))  # crop in 1080p output coords
panels = [('out_bilinear.png', 'BEFORE', 'Upscale off · phone\'s bilinear stretch'),
          ('out_fsr_balanced.png', 'AFTER · BALANCED', 'FSR 1.0 EASU + RCAS (0.8 stops)'),
          ('out_fsr_sharp.png', 'AFTER · SHARP', 'FSR 1.0 EASU + RCAS (0.25 stops)')]
cw, ch = box[2] - box[0], box[3] - box[1]
pad, head, foot = 24, 112, 64
W = pad + len(panels) * (cw + pad); H = head + ch + 70 + foot
img = Image.new('RGB', (W, H), (14, 14, 18))
d = ImageDraw.Draw(img)
# accent gradient bar
for x in range(W):
    t = x / (W - 1)
    a, b = ((255, 45, 85), (247, 47, 160)) if t < .5 else ((247, 47, 160), (224, 64, 251))
    u = t * 2 if t < .5 else (t - .5) * 2
    d.line([(x, 0), (x, 5)], fill=tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3)))
title = font('heading_chakra_petch_semibold.ttf', 40) if False else None
hfont = None
for cand in ['chakra_petch_semibold.ttf', 'chakrapetch_semibold.ttf']:
    try: hfont = ImageFont.truetype(FONT_DIR + cand, 40); break
    except Exception: pass
import glob, os
fonts = sorted(glob.glob(FONT_DIR + '*.ttf'))
def pick(sub, size):
    for f in fonts:
        if sub in os.path.basename(f).lower(): return ImageFont.truetype(f, size)
    return ImageFont.load_default()
hfont = pick('chakra', 40); lab = pick('chakra', 26); body = pick('inter', 20)
if 'inter' not in str(getattr(body, 'path', '')): body = pick('inter', 20)
d.text((pad, 30), 'Ulfur Video 1.5.0 · Upscale', font=hfont, fill=(242, 242, 247))
d.text((W - pad, 44), '640×360 source → 1920×1080 · 1:1 crop', font=body, fill=(160, 160, 174), anchor='ra')
for k, (f, l1, l2) in enumerate(panels):
    x = pad + k * (cw + pad)
    im = Image.open(f).crop(box)
    img.paste(im, (x, head))
    col = (160, 160, 174) if k == 0 else (255, 60, 97)
    d.text((x, head + ch + 14), l1, font=lab, fill=col)
    d.text((x, head + ch + 46), l2, font=body, fill=(160, 160, 174))
d.text((pad, H - 40), 'Rendered on the box by running the app\'s own GLSL shaders (assets/shaders) through Mesa GL. '
       'Big Buck Bunny © Blender Foundation, CC BY 3.0.', font=pick('inter', 18), fill=(110, 110, 124))
img.save(sys.argv[2])
print(img.size)
