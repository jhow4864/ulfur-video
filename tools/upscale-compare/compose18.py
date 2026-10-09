"""Ulfur Video 1.8.0 - composes the Off / Balanced / Sharp comparison from render18.py's outputs.
    python3 compose18.py <prefix> <x0,y0,x1,y1 crop in output coords> <title> <subtitle> <sharp label> <credit> <out.png>
"""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

FONT_DIR = '/workspace/ulfur-video/proj/app/src/main/res/font/'
fonts = sorted(glob.glob(FONT_DIR + '*.ttf'))


def pick(sub, size):
    for f in fonts:
        if sub in os.path.basename(f).lower():
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


prefix, box, title, subtitle, sharp_label, credit, out = sys.argv[1:8]
box = tuple(int(v) for v in box.split(','))
panels = [(prefix + '_off.png', 'OFF', "Phone's bilinear stretch"),
          (prefix + '_balanced.png', 'BALANCED', 'FSR 1.0 EASU + RCAS (unchanged)'),
          (prefix + '_sharp.png', 'SHARP · NEW', sharp_label)]
cw, ch = box[2] - box[0], box[3] - box[1]
pad, head, foot = 24, 112, 70
W = pad + len(panels) * (cw + pad)
H = head + ch + 76 + foot
img = Image.new('RGB', (W, H), (14, 14, 18))
d = ImageDraw.Draw(img)
for x in range(W):  # Blood Moon accent bar
    t = x / (W - 1)
    a, b = ((255, 45, 85), (247, 47, 160)) if t < .5 else ((247, 47, 160), (224, 64, 251))
    u = t * 2 if t < .5 else (t - .5) * 2
    d.line([(x, 0), (x, 5)], fill=tuple(int(a[i] + (b[i] - a[i]) * u) for i in range(3)))
hfont, lab, body, small = pick('chakra', 40), pick('chakra', 26), pick('inter_regular', 20), pick('inter_regular', 17)
d.text((pad, 30), title, font=hfont, fill=(242, 242, 247))
d.text((W - pad, 44), subtitle, font=body, fill=(160, 160, 174), anchor='ra')
for k, (f, l1, l2) in enumerate(panels):
    x = pad + k * (cw + pad)
    img.paste(Image.open(f).crop(box), (x, head))
    col = (160, 160, 174) if k == 0 else ((242, 242, 247) if k == 1 else (255, 60, 97))
    d.text((x, head + ch + 14), l1, font=lab, fill=col)
    d.text((x, head + ch + 46), l2, font=body, fill=(160, 160, 174))
d.text((pad, H - 52), "Rendered on the box by running the app's own shaders (assets/shaders, Anime4K ported to GLSL ES 3.00) "
       "through Mesa GL, pass for pass as on the phone.", font=small, fill=(110, 110, 124))
d.text((pad, H - 28), credit, font=small, fill=(110, 110, 124))
img.save(out)
print(out, img.size)
