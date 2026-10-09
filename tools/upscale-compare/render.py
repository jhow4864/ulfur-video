"""Runs Ulfur Video's actual upscale shaders (app/src/main/assets/shaders) offline with desktop GL
(Mesa, via moderngl) on a 640x360 frame, mirroring UpscaleShaderProgram's passes."""
import re, sys, moderngl, numpy as np
from PIL import Image

SH = '/workspace/ulfur-video/proj/app/src/main/assets/shaders/'
def src(name):
    s = open(SH + name).read()
    return s.replace('#version 100', '#version 130', 1)   # desktop GL: same code, GLSL 1.30

ctx = moderngl.create_standalone_context()
print('GL:', ctx.info['GL_RENDERER'], ctx.info['GL_VERSION'], file=sys.stderr)
quad = ctx.buffer(np.array([-1,-1,0,1, 1,-1,0,1, -1,1,0,1, 1,1,0,1], dtype='f4'))

def prog(frag):
    p = ctx.program(vertex_shader=src('ulfur_upscale_vertex.glsl'), fragment_shader=src(frag))
    return p, ctx.vertex_array(p, [(quad, '4f', 'aFramePosition')])

def tex_from(img):
    a = np.asarray(img.convert('RGBA'), dtype=np.uint8)
    t = ctx.texture((a.shape[1], a.shape[0]), 4, a.tobytes())
    t.filter = (moderngl.LINEAR, moderngl.LINEAR); t.repeat_x = t.repeat_y = False
    return t

def target(w, h):
    t = ctx.texture((w, h), 4); t.filter = (moderngl.LINEAR, moderngl.LINEAR); t.repeat_x = t.repeat_y = False
    return t, ctx.framebuffer([t])

def read(fbo, w, h):
    return Image.frombytes('RGBA', (w, h), fbo.read(components=4)).convert('RGB')

def setu(p, name, v):
    if name in p: p[name].value = v

def run(img, ow, oh, upscale_frag, sharpness):
    iw, ih = img.size
    src_t = tex_from(img)
    mid, mid_fbo = target(ow, oh)
    out, out_fbo = target(ow, oh)
    p, va = prog(upscale_frag)
    mid_fbo.use(); ctx.viewport = (0, 0, ow, oh); src_t.use(0)
    setu(p, 'uTexSampler', 0); setu(p, 'uInSize', (iw, ih)); setu(p, 'uOutSize', (ow, oh))
    va.render(moderngl.TRIANGLE_STRIP)
    if sharpness is None:
        return read(mid_fbo, ow, oh)
    r, rva = prog('ulfur_rcas.glsl')
    out_fbo.use(); ctx.viewport = (0, 0, ow, oh); mid.use(0)
    setu(r, 'uTexSampler', 0); setu(r, 'uSize', (ow, oh)); setu(r, 'uSharpness', sharpness)
    rva.render(moderngl.TRIANGLE_STRIP)
    return read(out_fbo, ow, oh)

def bilinear(img, ow, oh):
    """What the phone shows with Upscale off: the GPU's bilinear stretch (copy shader, GL_LINEAR)."""
    src_t = tex_from(img); out, fbo = target(ow, oh)
    p, va = prog('ulfur_copy.glsl'); fbo.use(); ctx.viewport = (0, 0, ow, oh); src_t.use(0)
    setu(p, 'uTexSampler', 0); va.render(moderngl.TRIANGLE_STRIP)
    return read(fbo, ow, oh)

if __name__ == '__main__':
    img = Image.open(sys.argv[1]).convert('RGB')
    W, H = 1920, 1080
    bal = 2 ** -0.8; sharp = 2 ** -0.25   # Upscaler.BALANCED_STOPS / SHARP_STOPS
    bilinear(img, W, H).save('out_bilinear.png')
    run(img, W, H, 'ulfur_fsr_easu.glsl', bal).save('out_fsr_balanced.png')
    run(img, W, H, 'ulfur_fsr_easu.glsl', sharp).save('out_fsr_sharp.png')
    run(img, W, H, 'ulfur_catmull_rom.glsl', bal).save('out_lite_balanced.png')
    run(img, W, H, 'ulfur_fsr_easu.glsl', None).save('out_easu_only.png')
    print('ok')
