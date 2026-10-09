#version 100
// =====================================================================================
// Ulfur Video 1.5.0 - "Lite" upscaler: Catmull-Rom bicubic in 9 bilinear fetches.
//
// Cheaper fallback used when the FSR EASU pass is too slow on a device or fails to compile.
// It is followed by the same RCAS sharpening pass. The 9-tap trick (folding the middle two
// weights of each axis into one bilinear fetch) follows the public technique described by
// Matt Pettineo ("TheRealMJP"); this implementation is original to Ulfur Video.
// =====================================================================================
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uInSize;
uniform vec2 uOutSize;

void main() {
  // Input-texel position of this output pixel.
  vec2 samplePos = gl_FragCoord.xy * (uInSize / uOutSize);
  vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
  vec2 f = samplePos - texPos1;

  // Catmull-Rom weights.
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);

  vec2 w12 = w1 + w2;
  vec2 offset12 = w2 / w12;

  vec2 tc0 = (texPos1 - 1.0) / uInSize;
  vec2 tc3 = (texPos1 + 2.0) / uInSize;
  vec2 tc12 = (texPos1 + offset12) / uInSize;

  vec3 r = vec3(0.0);
  r += texture2D(uTexSampler, vec2(tc0.x,  tc0.y)).rgb  * w0.x  * w0.y;
  r += texture2D(uTexSampler, vec2(tc12.x, tc0.y)).rgb  * w12.x * w0.y;
  r += texture2D(uTexSampler, vec2(tc3.x,  tc0.y)).rgb  * w3.x  * w0.y;
  r += texture2D(uTexSampler, vec2(tc0.x,  tc12.y)).rgb * w0.x  * w12.y;
  r += texture2D(uTexSampler, vec2(tc12.x, tc12.y)).rgb * w12.x * w12.y;
  r += texture2D(uTexSampler, vec2(tc3.x,  tc12.y)).rgb * w3.x  * w12.y;
  r += texture2D(uTexSampler, vec2(tc0.x,  tc3.y)).rgb  * w0.x  * w3.y;
  r += texture2D(uTexSampler, vec2(tc12.x, tc3.y)).rgb  * w12.x * w3.y;
  r += texture2D(uTexSampler, vec2(tc3.x,  tc3.y)).rgb  * w3.x  * w3.y;
  gl_FragColor = vec4(clamp(r, 0.0, 1.0), 1.0);
}
