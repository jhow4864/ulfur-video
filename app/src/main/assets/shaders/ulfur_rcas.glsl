#version 100
// =====================================================================================
// Ulfur Video 1.5.0 - FSR 1.0 RCAS (Robust Contrast-Adaptive Sharpening), GLSL ES 1.00 port.
//
// Port of FsrRcasF() from AMD FidelityFX Super Resolution 1.0 (ffx_fsr1.h), 32-bit path,
// with FSR_RCAS_DENOISE enabled.
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Licensed under the MIT License - see assets/licenses/FSR1_LICENSE.txt.
// Changes from the original: GLSL ES 1.00, sharpness passed as a linear float
// (uSharpness = exp2(-stops); 1.0 = maximum), guarded divisions.
// =====================================================================================
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uSize;       // texture size in pixels (input == output for RCAS)
uniform float uSharpness; // exp2(-stops), 0..1

// Limit of the negative lobe; keeps the 'unsharp mask' kernel from blowing out.
const float RCAS_LIMIT = 0.25 - (1.0 / 16.0);

vec3 tex(vec2 p) {
  return texture2D(uTexSampler, (p + 0.5) / uSize).rgb;
}

void main() {
  vec2 sp = floor(gl_FragCoord.xy);
  //    b
  //  d e f
  //    h
  vec3 b = tex(sp + vec2( 0.0, -1.0));
  vec3 d = tex(sp + vec2(-1.0,  0.0));
  vec3 e = tex(sp);
  vec3 f = tex(sp + vec2( 1.0,  0.0));
  vec3 h = tex(sp + vec2( 0.0,  1.0));

  // Luma times 2.
  float bL = b.b * 0.5 + (b.r * 0.5 + b.g);
  float dL = d.b * 0.5 + (d.r * 0.5 + d.g);
  float eL = e.b * 0.5 + (e.r * 0.5 + e.g);
  float fL = f.b * 0.5 + (f.r * 0.5 + f.g);
  float hL = h.b * 0.5 + (h.r * 0.5 + h.g);

  // Noise detection: reduce sharpening where the centre stands out from a flat neighbourhood
  // (film grain, compression noise).
  float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
  float range = max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL);
  nz = clamp(abs(nz) / max(range, 1.0 / 65536.0), 0.0, 1.0);
  nz = -0.5 * nz + 1.0;

  // Min and max of the ring.
  vec3 mn4 = min(min(b, d), min(f, h));
  vec3 mx4 = max(max(b, d), max(f, h));

  // Largest negative lobe that keeps the result inside [0, 1] for every channel.
  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0 / 65536.0));
  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0 / 65536.0));
  vec3 lobeRGB = max(-hitMin, hitMax);
  float lobe = max(-RCAS_LIMIT, min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0)) * uSharpness;
  lobe *= nz;

  // Resolve: weighted 5-tap 'plus' kernel.
  float rcpL = 1.0 / (4.0 * lobe + 1.0);
  vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;
  gl_FragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);
}
