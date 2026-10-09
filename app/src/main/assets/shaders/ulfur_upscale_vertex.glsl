#version 100
// Ulfur Video 1.5.0 - full-screen quad for the upscale / sharpen passes.
// Output pixels are addressed with gl_FragCoord in the fragment shaders, so only clip-space
// positions are needed here; vTexCoord is used by the copy pass.
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
