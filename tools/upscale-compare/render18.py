"""Ulfur Video 1.8.0 - renders Off / Balanced / Sharp through the app's real shaders
(app/src/main/assets/shaders) with desktop GL (Mesa via moderngl), mirroring UpscaleShaderProgram +
Anime4kPipeline pass for pass, and times each mode.

    python3 render18.py <frame.png> <outW> <outH> <prefix>      -> <prefix>_{off,balanced,sharp}.png
    python3 render18.py --selftest                               -> GL port == numpy mpv reference

Frames are uploaded bottom-up (GL / Media3 orientation) and flipped back after read-back, so the
vertical-offset mirroring in the Anime4K port is exercised exactly as on a phone.
"""
import re, sys, time, moderngl, numpy as np
from PIL import Image

SH = '/workspace/ulfur-video/proj/app/src/main/assets/shaders/'
MPV = '/workspace/ulfur-video/proj/tools/anime4k-port/mpv/'
MAX_OUTPUT_PIXELS = 1920 * 1088
MAX_SCALE, MIN_SCALE = 4.0, 1.10
BAL = 2 ** -0.8  # Upscaler.BALANCED_STOPS

ctx = moderngl.create_standalone_context(require=330)
quad = ctx.buffer(np.array([-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1], dtype='f4'))
_cache = {}


def es1(name):
    return open(SH + name).read().replace('#version 100', '#version 130', 1)


def prog_es1(frag):
    if frag not in _cache:
        p = ctx.program(vertex_shader=es1('ulfur_upscale_vertex.glsl'), fragment_shader=es1(frag))
        _cache[frag] = (p, ctx.vertex_array(p, [(quad, '4f', 'aFramePosition')]))
    return _cache[frag]


def prog_a4k(frag, n):
    key = (frag, n)
    if key not in _cache:
        vs = open(SH + 'ulfur_a4k_vertex.glsl').read().replace('#version 300 es', '#version 330')
        fs = '#version 330\n#define PASS %d\n' % n + open(SH + frag).read()
        p = ctx.program(vertex_shader=vs, fragment_shader=fs)
        _cache[key] = (p, ctx.vertex_array(p, [(quad, '4f', 'aFramePosition')]))
    return _cache[key]


def tex(w, h, float16=False, data=None):
    t = ctx.texture((w, h), 4, data, dtype='f2' if float16 else 'f1')
    t.filter = (moderngl.LINEAR, moderngl.LINEAR)
    t.repeat_x = t.repeat_y = False
    return t


def upload(img):
    a = np.ascontiguousarray(np.asarray(img.convert('RGBA'), dtype=np.uint8)[::-1])  # bottom-up
    return tex(a.shape[1], a.shape[0], data=a.tobytes())


def readback(fbo, w, h):
    a = np.frombuffer(fbo.read(components=4), dtype=np.uint8).reshape(h, w, 4)[::-1]
    return Image.fromarray(a[:, :, :3].copy())


def setu(p, name, v):
    if name in p:
        p[name].value = v


def draw(prog, fbo, w, h, samplers, **uniforms):
    p, va = prog
    fbo.use()
    ctx.viewport = (0, 0, w, h)
    for unit, (name, t) in enumerate(samplers):
        t.use(unit)
        setu(p, name, unit)
    for k, v in uniforms.items():
        setu(p, k, v)
    va.render(moderngl.TRIANGLE_STRIP)


# ------------------------------------------------------------------ plan (mirrors Upscaler.plan)
def plan(iw, ih, bw, bh, mode):
    scale = min(bw / iw, bh / ih)
    budget = (MAX_OUTPUT_PIXELS / (iw * ih)) ** 0.5
    s = min(scale, budget, MAX_SCALE)
    if mode == 'sharp' and iw * ih > MAX_OUTPUT_PIXELS:
        mode = 'balanced'
    if s >= MIN_SCALE:
        ow, oh = max(2, round(iw * s / 2) * 2), max(2, round(ih * s / 2) * 2)
        return ('a4k_upscale' if mode == 'sharp' else 'upscale'), ow, oh
    if mode == 'sharp':
        return 'a4k_restore', iw, ih
    return 'copy', iw, ih


class Pipe:
    """Allocates the textures once per input size, like UpscaleShaderProgram.configure()."""

    def __init__(self, iw, ih, ow, oh):
        self.iw, self.ih, self.ow, self.oh = iw, ih, ow, oh
        self.feat = [tex(iw, ih, True) for _ in range(8)]
        self.feat_fbo = [ctx.framebuffer([t]) for t in self.feat]
        self.restored = tex(iw, ih)
        self.restored_fbo = ctx.framebuffer([self.restored])
        self.cnn = tex(iw * 2, ih * 2)
        self.cnn_fbo = ctx.framebuffer([self.cnn])
        self.mid = tex(ow, oh)
        self.mid_fbo = ctx.framebuffer([self.mid])
        self.out = tex(ow, oh)
        self.out_fbo = ctx.framebuffer([self.out])

    def restore(self, src, dst_fbo):
        R = 'ulfur_a4k_restore_cnn_m.glsl'
        names = ['conv2d_tf'] + ['conv2d_%d_tf' % i for i in range(1, 7)]
        sz = (self.iw, self.ih)
        draw(prog_a4k(R, 0), self.feat_fbo[0], *sz, [('MAIN', src)], uSrcSize=sz)
        for n in range(1, 7):
            draw(prog_a4k(R, n), self.feat_fbo[n], *sz, [(names[n - 1], self.feat[n - 1])], uSrcSize=sz)
        draw(prog_a4k(R, 7), dst_fbo, *sz, [('MAIN', src)] + list(zip(names, self.feat[:7])), uSrcSize=sz)

    def cnn_x2(self, src, dst_fbo):
        U = 'ulfur_a4k_upscale_cnn_x2_m.glsl'
        names = ['conv2d_tf'] + ['conv2d_%d_tf' % i for i in range(1, 7)]
        sz = (self.iw, self.ih)
        draw(prog_a4k(U, 0), self.feat_fbo[0], *sz, [('MAIN', src)], uSrcSize=sz)
        for n in range(1, 7):
            draw(prog_a4k(U, n), self.feat_fbo[n], *sz, [(names[n - 1], self.feat[n - 1])], uSrcSize=sz)
        draw(prog_a4k(U, 7), self.feat_fbo[7], *sz, list(zip(names, self.feat[:7])), uSrcSize=sz)
        draw(prog_a4k(U, 8), dst_fbo, self.iw * 2, self.ih * 2,
             [('MAIN', src), ('conv2d_last_tf', self.feat[7])], uSrcSize=sz)

    def run(self, src, mode, lite=False):
        kind, ow, oh = plan(self.iw, self.ih, self.ow_box, self.oh_box, mode)
        if kind == 'upscale':
            draw(prog_es1('ulfur_catmull_rom.glsl' if lite else 'ulfur_fsr_easu.glsl'), self.mid_fbo, ow, oh,
                 [('uTexSampler', src)], uInSize=(self.iw, self.ih), uOutSize=(ow, oh))
            draw(prog_es1('ulfur_rcas.glsl'), self.out_fbo, ow, oh, [('uTexSampler', self.mid)],
                 uSize=(ow, oh), uSharpness=BAL)
            return self.out_fbo
        if kind == 'a4k_restore':
            self.restore(src, self.out_fbo)
            return self.out_fbo
        # a4k_upscale: Restore -> CNN x2 at source res -> EASU (up) / Catmull-Rom (down) to out. No RCAS.
        self.restore(src, self.restored_fbo)
        cw, ch = self.iw * 2, self.ih * 2
        if (cw, ch) == (ow, oh):
            self.cnn_x2(self.restored, self.out_fbo)
            return self.out_fbo
        self.cnn_x2(self.restored, self.cnn_fbo)
        frag = 'ulfur_fsr_easu.glsl' if ow > cw else 'ulfur_catmull_rom.glsl'
        draw(prog_es1(frag), self.out_fbo, ow, oh, [('uTexSampler', self.cnn)], uInSize=(cw, ch), uOutSize=(ow, oh))
        return self.out_fbo


def bilinear(src, ow, oh):
    t = tex(ow, oh); fbo = ctx.framebuffer([t])
    draw(prog_es1('ulfur_copy.glsl'), fbo, ow, oh, [('uTexSampler', src)])
    return fbo


def timed(fn, reps=5):
    fn(); ctx.finish()
    t0 = time.perf_counter()
    for _ in range(reps):
        fn()
    ctx.finish()
    return (time.perf_counter() - t0) / reps * 1000


def render(path, bw, bh, prefix):
    img = Image.open(path).convert('RGB')
    iw, ih = img.size
    src = upload(img)
    _, ow, oh = plan(iw, ih, bw, bh, 'balanced')
    pipe = Pipe(iw, ih, ow, oh); pipe.ow_box, pipe.oh_box = bw, bh
    print('GL:', ctx.info['GL_RENDERER'], '|', path, (iw, ih), '-> box', (bw, bh), '-> out', (ow, oh))
    res = {}
    fb = bilinear(src, ow, oh); readback(fb, ow, oh).save(prefix + '_off.png')
    res['off'] = timed(lambda: bilinear(src, ow, oh))
    for mode in ('balanced', 'sharp'):
        fbo = pipe.run(src, mode); readback(fbo, ow, oh).save('%s_%s.png' % (prefix, mode))
        res[mode] = timed(lambda: pipe.run(src, mode))
    # break the Sharp cost down
    res['sharp_restore'] = timed(lambda: pipe.restore(src, pipe.restored_fbo))
    res['sharp_cnn_x2'] = timed(lambda: pipe.cnn_x2(pipe.restored, pipe.cnn_fbo))
    for k, v in res.items():
        print('  %-14s %8.1f ms/frame (box, %s)' % (k, v, ctx.info['GL_RENDERER']))
    return res


# ------------------------------------------------------------------ numpy reference (mpv semantics)
def parse_mpv(path):
    passes, cur = [], None
    for line in open(path):
        if line.startswith('//!DESC'):
            cur = {'binds': [], 'save': None, 'width': '', 'body': ''}; passes.append(cur)
        elif cur is None: continue
        elif line.startswith('//!BIND'): cur['binds'].append(line.split()[1])
        elif line.startswith('//!SAVE'): cur['save'] = line.split()[1]
        elif line.startswith('//!WIDTH'): cur['width'] = line[8:].strip()
        elif not line.startswith('//!'): cur['body'] += line
    return passes


def ref_net(path, main):
    """Evaluates an Anime4K mpv shader file on a top-down float image (H, W, 4) exactly as mpv would."""
    tex = {'MAIN': main}
    H, W = main.shape[:2]
    def off(a, dx, dy):  # mpv: +y is down; CLAMP_TO_EDGE
        ys = np.clip(np.arange(H) + dy, 0, H - 1); xs = np.clip(np.arange(W) + dx, 0, W - 1)
        return a[ys][:, xs]
    for p in parse_mpv(path):
        if p['width'].endswith('2 *'):
            last = tex['conv2d_last_tf']; m = tex['MAIN']
            out = np.zeros((H * 2, W * 2, 4), np.float32)
            for i in range(2):
                for j in range(2):
                    out[i::2, j::2, :] = last[:, :, i * 2 + j:i * 2 + j + 1]
            # bilinear 2x of MAIN at pixel centres (GL_LINEAR, clamp)
            ys = (np.arange(H * 2) + 0.5) / 2 - 0.5; xs = (np.arange(W * 2) + 0.5) / 2 - 0.5
            y0 = np.floor(ys).astype(int); x0 = np.floor(xs).astype(int); fy = (ys - y0)[:, None, None]; fx = (xs - x0)[None, :, None]
            c = lambda y, x: m[np.clip(y, 0, H - 1)][:, np.clip(x, 0, W - 1)]
            bil = (c(y0, x0) * (1 - fx) + c(y0, x0 + 1) * fx) * (1 - fy) + (c(y0 + 1, x0) * (1 - fx) + c(y0 + 1, x0 + 1) * fx) * fy
            tex['MAIN'] = out + bil
            continue
        defs = {}
        for d in re.finditer(r'#define (g[o]?_\d+)\S* (.*)', p['body']):
            body = d.group(2)
            name = re.search(r'(\w+?)_(?:texOff|tex)\(', body).group(1)
            defs[d.group(1)] = ('max(-(' in body, 'max(' in body, name)
        result = None
        for m in re.finditer(r'(?:vec4 result = |result \+= )(mat4|vec4)\(([^)]*)\)(?: \* (g[o]?_\d+)(?:\(([-\d.]+), ([-\d.]+)\))?)?;', p['body']):
            vals = np.array([float(v) for v in m.group(2).split(',')], np.float32)
            if m.group(1) == 'vec4':
                term = np.broadcast_to(vals, (H, W, 4)); 
            else:
                M = vals.reshape(4, 4)  # column-major: M[col][row]
                neg, relu, name = defs[m.group(3)]
                src = tex[name]
                if m.group(4) is not None:
                    src = off(src, int(float(m.group(4))), int(float(m.group(5))))
                v = np.maximum(-src if neg else src, 0) if relu else src
                term = v @ M  # out[row] = sum_col v[col] * M[col][row]
            result = term.copy() if result is None else result + term
        if 'return result + MAIN_tex(MAIN_pos)' in p['body']:
            result = result + tex['MAIN']
        tex[p['save']] = result.astype(np.float32)
    return tex['MAIN']


def selftest():
    img = Image.open(sys.argv[2] if len(sys.argv) > 2 else '/workspace/ulfur-video/compare18/anime_480p.png').convert('RGB')
    img = img.crop((300, 100, 300 + 96, 100 + 64))
    iw, ih = img.size
    src = upload(img)
    pipe = Pipe(iw, ih, iw * 2, ih * 2)
    pipe.restore(src, pipe.restored_fbo)
    gl_restored = np.asarray(readback(pipe.restored_fbo, iw, ih), np.float32) / 255
    pipe.cnn_x2(pipe.restored, pipe.cnn_fbo)
    gl_up = np.asarray(readback(pipe.cnn_fbo, iw * 2, ih * 2), np.float32) / 255
    a = np.concatenate([np.asarray(img, np.float32) / 255, np.ones((ih, iw, 1), np.float32)], 2)
    r = np.clip(ref_net(MPV + 'Anime4K_Restore_CNN_M.glsl', a), 0, 1)
    r[..., 3] = 1
    u = np.clip(ref_net(MPV + 'Anime4K_Upscale_CNN_x2_M.glsl', np.round(r * 255) / 255), 0, 1)
    e1 = np.abs(gl_restored - r[..., :3]); e2 = np.abs(gl_up - u[..., :3])
    print('restore: max |GL - ref| = %.4f (mean %.5f)   upscale x2: max %.4f (mean %.5f)' % (e1.max(), e1.mean(), e2.max(), e2.mean()))
    # what a port WITHOUT the vertical mirroring would produce (network run on the bottom-up image)
    wrong = np.clip(ref_net(MPV + 'Anime4K_Restore_CNN_M.glsl', a[::-1].copy()), 0, 1)[::-1]
    print('sanity: an un-mirrored port would differ from mpv by max %.4f (mean %.5f) on Restore'
          % (np.abs(wrong[..., :3] - r[..., :3]).max(), np.abs(wrong[..., :3] - r[..., :3]).mean()))
    ok = e1.max() < 3 / 255 and e2.max() < 3 / 255
    print('SELFTEST', 'PASS' if ok else 'FAIL')
    return ok


if __name__ == '__main__':
    if sys.argv[1] == '--selftest':
        sys.exit(0 if selftest() else 1)
    render(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4])
