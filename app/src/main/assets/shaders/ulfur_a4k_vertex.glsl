#version 300 es
// Ulfur Video 1.8.0 - full-screen quad for the Anime4K (GLSL ES 3.00) passes.
// The fragment shaders address texels with gl_FragCoord, so only clip-space positions are needed.
in vec4 aFramePosition;
void main() {
  gl_Position = aFramePosition;
}
