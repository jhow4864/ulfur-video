#version 100
// =====================================================================================
// Ulfur Video 1.5.0 - FSR 1.0 EASU (Edge Adaptive Spatial Upsampling), GLSL ES 1.00 port.
//
// Port of FsrEasuF() from AMD FidelityFX Super Resolution 1.0 (ffx_fsr1.h), 32-bit path.
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Licensed under the MIT License - see assets/licenses/FSR1_LICENSE.txt.
// Changes from the original: GLSL ES 1.00 (no textureGather - 12 individual texel fetches),
// constants computed from uInSize/uOutSize instead of the packed con0..con3 uint4s, and the
// approximate reciprocal helpers replaced by guarded divisions.
//
// Input: an SDR frame (Media3's default working colour space: BT.709 primaries, SMPTE 170M
// transfer, i.e. perceptual/gamma-encoded, which is what EASU expects).
// Output: the frame at uOutSize, edge-directed Lanczos-like filtered and deringed.
// =====================================================================================
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uInSize;   // input size in pixels
uniform vec2 uOutSize;  // output size in pixels

// Fetches the input texel at integer texel coordinates p (texel centres, so bilinear filtering
// returns the exact texel value; edges are CLAMP_TO_EDGE).
vec3 tex(vec2 p) {
  return texture2D(uTexSampler, (p + 0.5) / uInSize).rgb;
}

// Luma times 2 (FSR's cheap approximation): r*0.5 + g + b*0.5.
float luma2(vec3 c) {
  return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Accumulates direction and edge length for one of the four bilinear quadrants (FsrEasuSetF).
//    a
//  b c d
//    e
void easuSet(inout vec2 dir, inout float len, float w,
             float lA, float lB, float lC, float lD, float lE) {
  // Horizontal.
  float dc = lD - lC;
  float cb = lC - lB;
  float lenX = max(abs(dc), abs(cb));
  lenX = 1.0 / max(lenX, 1.0 / 65536.0);
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
  lenX *= lenX;
  len += lenX * w;
  // Vertical.
  float ec = lE - lC;
  float ca = lC - lA;
  float lenY = max(abs(ec), abs(ca));
  lenY = 1.0 / max(lenY, 1.0 / 65536.0);
  float dirY = lE - lA;
  dir.y += dirY * w;
  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
  lenY *= lenY;
  len += lenY * w;
}

// One filter tap (FsrEasuTapF): rotated, anisotropically scaled, windowed approximation of
// Lanczos-2 (polynomial: (25/16 * (2/5 * x^2 - 1)^2 - (25/16 - 1)) * (lob * x^2 - 1)^2).
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2,
             float lob, float clp, vec3 c) {
  vec2 v;
  v.x = (off.x * dir.x) + (off.y * dir.y);
  v.y = (off.x * (-dir.y)) + (off.y * dir.x);
  v *= len2;
  float d2 = v.x * v.x + v.y * v.y;
  d2 = min(d2, clp);
  float wB = (2.0 / 5.0) * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
  float w = wB * wA;
  aC += c * w;
  aW += w;
}

void main() {
  // Position of this output pixel in input-pixel space (con0 in the original).
  vec2 pp = gl_FragCoord.xy * (uInSize / uOutSize) - 0.5;
  vec2 fp = floor(pp);
  pp -= fp;

  // 12-tap kernel.
  //    b c
  //  e f g h
  //  i j k l
  //    n o
  vec3 b = tex(fp + vec2( 0.0, -1.0));
  vec3 c = tex(fp + vec2( 1.0, -1.0));
  vec3 e = tex(fp + vec2(-1.0,  0.0));
  vec3 f = tex(fp);
  vec3 g = tex(fp + vec2( 1.0,  0.0));
  vec3 h = tex(fp + vec2( 2.0,  0.0));
  vec3 i = tex(fp + vec2(-1.0,  1.0));
  vec3 j = tex(fp + vec2( 0.0,  1.0));
  vec3 k = tex(fp + vec2( 1.0,  1.0));
  vec3 l = tex(fp + vec2( 2.0,  1.0));
  vec3 n = tex(fp + vec2( 0.0,  2.0));
  vec3 o = tex(fp + vec2( 1.0,  2.0));

  float bL = luma2(b); float cL = luma2(c); float eL = luma2(e); float fL = luma2(f);
  float gL = luma2(g); float hL = luma2(h); float iL = luma2(i); float jL = luma2(j);
  float kL = luma2(k); float lL = luma2(l); float nL = luma2(n); float oL = luma2(o);

  // Direction and length, bilinearly weighted over the 4 quadrants f g j k.
  vec2 dir = vec2(0.0);
  float len = 0.0;
  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
  easuSet(dir, len, pp.x * (1.0 - pp.y),         cL, fL, gL, hL, kL);
  easuSet(dir, len, (1.0 - pp.x) * pp.y,         fL, iL, jL, kL, nL);
  easuSet(dir, len, pp.x * pp.y,                 gL, jL, kL, lL, oL);

  // Normalise the direction (falls back to horizontal when there is no gradient).
  vec2 dir2 = dir * dir;
  float dirR = dir2.x + dir2.y;
  bool zro = dirR < (1.0 / 32768.0);
  dirR = zro ? 1.0 : inversesqrt(dirR);
  dir.x = zro ? 1.0 : dir.x;
  dir *= dirR;

  // Edge length: 0 = no edge (isotropic), 1 = strong edge.
  len = len * 0.5;
  len *= len;
  // Stretch kernel along the edge; 1.0 on axis, sqrt(2) on diagonals.
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  // Negative-lobe strength, and clipping of the window.
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;

  // Accumulate the 12 taps.
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
  easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
  easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
  easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
  easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
  easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
  easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
  easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
  easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
  easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
  easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
  easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);

  // Normalise and dering: clamp to the min/max of the 4 nearest texels.
  vec3 min4 = min(min(f, g), min(j, k));
  vec3 max4 = max(max(f, g), max(j, k));
  vec3 pix = aC / max(aW, 1.0 / 65536.0);
  pix = min(max4, max(min4, pix));
  gl_FragColor = vec4(pix, 1.0);
}
