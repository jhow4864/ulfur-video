#version 100
// Ulfur Video 1.5.0 - pass-through (used when the video is already as large as the screen,
// and for HDR input, which is never processed).
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexCoord;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexCoord);
}
