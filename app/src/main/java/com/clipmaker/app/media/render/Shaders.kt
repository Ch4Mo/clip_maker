package com.clipmaker.app.media.render

/** GLSL ES 1.00 fragment shaders for the visual effects, transform and transitions. */
object Shaders {
    private const val HEADER = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uResolution;
uniform float uTime;
varying vec2 vTexSamplingCoord;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec4 tex(vec2 uv) { return texture2D(uTexSampler, clamp(uv, 0.0, 1.0)); }
"""

    val COLOR_GRADE = HEADER + """
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uTemperature;
uniform float uTint;
uniform float uHue;
uniform float uHighlights;
uniform float uShadows;
uniform float uFade;
vec3 hueRotate(vec3 c, float a) {
  vec3 k = vec3(0.57735);
  float ca = cos(a);
  return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb * pow(2.0, uExposure);
  c.r += uTemperature * 0.08;
  c.b -= uTemperature * 0.08;
  c.g -= uTint * 0.06;
  float l = luma(c);
  c += uShadows * 0.25 * (1.0 - smoothstep(0.0, 0.5, l));
  c += uHighlights * 0.25 * smoothstep(0.5, 1.0, l);
  c = (c - 0.5) * (1.0 + uContrast) + 0.5;
  l = luma(c);
  c = mix(vec3(l), c, 1.0 + uSaturation);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  c = mix(vec3(l), c, 1.0 + uVibrance * (1.0 - clamp(mx - mn, 0.0, 1.0)));
  c = hueRotate(c, radians(uHue));
  c = mix(c, vec3(0.08) + c * 0.84, uFade);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

    val VIGNETTE = HEADER + """
uniform float uAmount;
uniform float uSize;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec2 p = vTexSamplingCoord - 0.5;
  p.x *= uResolution.x / uResolution.y;
  float d = length(p) * 1.2;
  float v = smoothstep(uSize * 0.6, uSize * 0.6 + 0.6, d);
  gl_FragColor = vec4(src.rgb * (1.0 - uAmount * v), src.a);
}
"""

    val GRAIN = HEADER + """
uniform float uAmount;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  float n = hash(floor(vTexSamplingCoord * uResolution / 1.5) + fract(uTime * 23.0) * 311.0) - 0.5;
  float weight = 1.0 - abs(luma(src.rgb) - 0.5);
  gl_FragColor = vec4(clamp(src.rgb + n * uAmount * 0.35 * weight, 0.0, 1.0), src.a);
}
"""

    val SHARPEN = HEADER + """
uniform float uAmount;
void main() {
  vec2 t = 1.0 / uResolution;
  vec2 uv = vTexSamplingCoord;
  vec4 src = texture2D(uTexSampler, uv);
  vec3 n = tex(uv + vec2(t.x, 0.0)).rgb + tex(uv - vec2(t.x, 0.0)).rgb + tex(uv + vec2(0.0, t.y)).rgb + tex(uv - vec2(0.0, t.y)).rgb;
  vec3 c = src.rgb * (1.0 + 4.0 * uAmount) - n * uAmount;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

    val BLACK_AND_WHITE = HEADER + """
uniform float uAmount;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  float l = luma(src.rgb);
  gl_FragColor = vec4(mix(src.rgb, vec3(l), uAmount), src.a);
}
"""

    val INVERT = HEADER + """
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  gl_FragColor = vec4(1.0 - src.rgb, src.a);
}
"""

    val GLITCH = HEADER + """
uniform float uAmount;
uniform float uSpeed;
void main() {
  vec2 uv = vTexSamplingCoord;
  float t = floor(uTime * 12.0 * uSpeed);
  float block = floor(uv.y * 24.0);
  float r = hash(vec2(block, t));
  float active = step(1.0 - 0.35 * uAmount, r);
  uv.x += active * (hash(vec2(t, block + 7.0)) - 0.5) * 0.25 * uAmount;
  float split = 0.012 * uAmount * (0.5 + hash(vec2(t, 3.0)));
  vec4 src = tex(uv);
  vec3 c = vec3(tex(uv + vec2(split, 0.0)).r, src.g, tex(uv - vec2(split, 0.0)).b);
  float line = step(0.985, hash(vec2(floor(uv.y * uResolution.y / 3.0), t)));
  c = mix(c, vec3(1.0) - c, line * uAmount);
  gl_FragColor = vec4(c, src.a);
}
"""

    val VHS = HEADER + """
uniform float uAmount;
void main() {
  vec2 uv = vTexSamplingCoord;
  uv.x += sin(uv.y * 40.0 + uTime * 8.0) * 0.0025 * uAmount;
  float band = 1.0 - smoothstep(0.0, 0.03, abs(uv.y - fract(uTime * 0.13)));
  uv.x += band * 0.02 * uAmount * (hash(vec2(uTime, uv.y)) - 0.5);
  float off = 0.005 * uAmount;
  vec4 src = tex(uv);
  vec3 c = vec3(tex(uv + vec2(off, 0.0)).r, src.g, tex(uv - vec2(off, 0.0)).b);
  c = mix(c, vec3(luma(c)), 0.25 * uAmount);
  c *= 1.0 - 0.12 * uAmount * (0.5 + 0.5 * sin(uv.y * uResolution.y * 1.2));
  c += (hash(uv * uResolution + uTime) - 0.5) * 0.12 * uAmount;
  c = c * vec3(1.02, 0.98, 1.05);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

    val RGB_SPLIT = HEADER + """
uniform float uAmount;
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 off = vec2(0.015 * uAmount, 0.004 * uAmount);
  vec4 src = tex(uv);
  gl_FragColor = vec4(tex(uv + off).r, src.g, tex(uv - off).b, src.a);
}
"""

    val PIXELATE = HEADER + """
uniform float uSize;
void main() {
  vec2 cell = vec2(max(1.0, uSize * 64.0)) / uResolution;
  vec2 uv = (floor(vTexSamplingCoord / cell) + 0.5) * cell;
  gl_FragColor = tex(uv);
}
"""

    val MIRROR = HEADER + """
uniform float uMode;
void main() {
  vec2 uv = vTexSamplingCoord;
  if (uMode < 0.5) { if (uv.x > 0.5) uv.x = 1.0 - uv.x; }
  else if (uMode < 1.5) { if (uv.y < 0.5) uv.y = 1.0 - uv.y; }
  else if (uMode < 2.5) { uv = abs(uv - 0.5) * -1.0 + 0.5; }
  else { if (uv.x < 0.5) uv.x = 1.0 - uv.x; }
  gl_FragColor = tex(uv);
}
"""

    val KALEIDOSCOPE = HEADER + """
uniform float uSegments;
void main() {
  float aspect = uResolution.x / uResolution.y;
  vec2 p = vTexSamplingCoord - 0.5;
  p.x *= aspect;
  float r = length(p);
  float a = atan(p.y, p.x) + uTime * 0.2;
  float seg = 6.28318 / uSegments;
  a = mod(a, seg);
  a = abs(a - seg * 0.5);
  p = vec2(cos(a), sin(a)) * r;
  p.x /= aspect;
  gl_FragColor = tex(p + 0.5);
}
"""

    val WAVE = HEADER + """
uniform float uAmount;
uniform float uSpeed;
void main() {
  vec2 uv = vTexSamplingCoord;
  uv.x += sin(uv.y * 18.0 + uTime * 6.0 * uSpeed) * 0.02 * uAmount;
  uv.y += cos(uv.x * 14.0 + uTime * 4.0 * uSpeed) * 0.012 * uAmount;
  gl_FragColor = tex(uv);
}
"""

    /** [uPulse] (0..1) is computed on the CPU from the beat grid. */
    val ZOOM_PULSE = HEADER + """
uniform float uAmount;
uniform float uPulse;
void main() {
  float s = 1.0 + uAmount * 0.12 * uPulse;
  vec2 uv = (vTexSamplingCoord - 0.5) / s + 0.5;
  vec4 src = tex(uv);
  gl_FragColor = vec4(clamp(src.rgb * (1.0 + 0.25 * uAmount * uPulse), 0.0, 1.0), src.a);
}
"""

    val SHAKE = HEADER + """
uniform float uAmount;
uniform float uSpeed;
void main() {
  float t = floor(uTime * 24.0 * uSpeed);
  vec2 off = vec2(hash(vec2(t, 1.0)) - 0.5, hash(vec2(t, 2.0)) - 0.5) * 0.05 * uAmount;
  float s = 1.0 + 0.06 * uAmount;
  vec2 uv = (vTexSamplingCoord - 0.5) / s + 0.5 + off;
  gl_FragColor = tex(uv);
}
"""

    val STROBE = HEADER + """
uniform float uRate;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  float on = step(0.5, fract(uTime * uRate));
  gl_FragColor = vec4(mix(src.rgb * 0.12, src.rgb, on), src.a);
}
"""

    val LETTERBOX = HEADER + """
uniform float uRatio;
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  float frame = uResolution.x / uResolution.y;
  float bar = max(0.0, (1.0 - frame / uRatio) * 0.5);
  float inside = step(bar, vTexSamplingCoord.y) * step(vTexSamplingCoord.y, 1.0 - bar);
  gl_FragColor = vec4(src.rgb * inside, mix(1.0, src.a, inside));
}
"""

    val CHROMA_KEY = HEADER + """
uniform float uKeyHue;
uniform float uTolerance;
uniform float uSoftness;
vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 hsv = rgb2hsv(src.rgb);
  float dh = abs(hsv.x - uKeyHue / 360.0);
  dh = min(dh, 1.0 - dh) * 2.0;
  float keyness = (1.0 - dh) * smoothstep(0.12, 0.35, hsv.y) * smoothstep(0.08, 0.25, hsv.z);
  float alpha = 1.0 - smoothstep(1.0 - uTolerance - uSoftness, 1.0 - uTolerance + 0.001, keyness);
  vec3 c = src.rgb;
  // Spill suppression: pull the key colour out of semi-transparent edges.
  float spill = clamp(keyness - 0.4, 0.0, 1.0);
  c = mix(c, vec3(luma(c)), spill);
  gl_FragColor = vec4(c, src.a * alpha);
}
"""

    /** Position/scale/rotation/opacity of a clip inside the frame (keyframeable). */
    val TRANSFORM = HEADER + """
uniform vec2 uOffset;
uniform float uScale;
uniform float uRotation;
uniform vec2 uFlip;
uniform float uOpacity;
uniform float uOpaque;
vec2 rotate(vec2 p, float a) { return vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a)); }
void main() {
  float aspect = uResolution.x / uResolution.y;
  vec2 p = vTexSamplingCoord * 2.0 - 1.0;
  p.x *= aspect;
  p -= uOffset;
  p = rotate(p, -uRotation);
  p /= max(uScale, 0.001);
  p.x /= aspect;
  p *= uFlip;
  vec2 uv = p * 0.5 + 0.5;
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  vec4 src = texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
  if (uOpaque > 0.5) {
    gl_FragColor = vec4(src.rgb * inside * uOpacity, 1.0);
  } else {
    gl_FragColor = vec4(src.rgb, src.a * inside * uOpacity);
  }
}
"""

    /**
     * Clip-edge transition. uProgress: 0 = fully transitioned (edge of the clip), 1 = normal.
     * uDir: +1 for the incoming transition, -1 for the outgoing one.
     */
    val TRANSITION = HEADER + """
uniform int uType;
uniform float uProgress;
uniform float uDir;
uniform float uOpaque;
vec2 rotate(vec2 p, float a) { return vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a)); }
vec4 outside(vec2 uv, vec4 c) {
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  if (uOpaque > 0.5) return vec4(c.rgb * inside, 1.0);
  return vec4(c.rgb, c.a * inside);
}
void main() {
  vec2 uv = vTexSamplingCoord;
  float p = clamp(uProgress, 0.0, 1.0);
  float e = 1.0 - p;
  float es = e * e * (3.0 - 2.0 * e);
  float aspect = uResolution.x / uResolution.y;
  vec4 c;
  if (uType == 0) { // fade black
    c = tex(uv); c.rgb *= p; if (uOpaque < 0.5) c.a *= p;
  } else if (uType == 1) { // fade white
    c = tex(uv); c.rgb = mix(vec3(1.0), c.rgb, p);
  } else if (uType == 2) { // flash
    c = tex(uv); c.rgb = clamp(c.rgb + vec3(e * e * 1.5), 0.0, 1.0);
  } else if (uType == 3) { // zoom in
    float s = 1.0 + es * 1.5;
    vec2 d = (uv - 0.5) / s;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 6; i++) { acc += tex(d * (1.0 - float(i) * 0.03 * es) + 0.5); }
    c = acc / 6.0;
  } else if (uType == 4) { // zoom out
    float s = 1.0 - es * 0.7;
    vec2 q = (uv - 0.5) / s + 0.5;
    c = outside(q, tex(q));
  } else if (uType == 5) { // spin
    vec2 q = uv - 0.5; q.x *= aspect;
    q = rotate(q, es * 3.14159 * uDir) / (1.0 + es);
    q.x /= aspect;
    c = tex(q + 0.5);
  } else if (uType == 6) { // blur
    vec4 acc = vec4(0.0);
    float r = es * 0.03;
    for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) acc += tex(uv + vec2(float(x), float(y)) * r);
    c = acc / 25.0;
  } else if (uType == 7) { // glitch
    float t = floor(uTime * 30.0);
    float block = floor(uv.y * 16.0);
    uv.x += step(1.0 - e, hash(vec2(block, t))) * (hash(vec2(t, block)) - 0.5) * 0.4 * e;
    float s = 0.04 * e;
    vec4 src = tex(uv);
    c = vec4(tex(uv + vec2(s, 0.0)).r, src.g, tex(uv - vec2(s, 0.0)).b, src.a);
  } else if (uType >= 8 && uType <= 11) { // slides
    vec2 dir = uType == 8 ? vec2(-1.0, 0.0) : uType == 9 ? vec2(1.0, 0.0) : uType == 10 ? vec2(0.0, 1.0) : vec2(0.0, -1.0);
    vec2 off = uDir > 0.0 ? -dir * es : dir * es;
    vec2 q = uv - off;
    c = outside(q, tex(q));
  } else if (uType == 12) { // whip pan
    vec2 off = vec2(uDir > 0.0 ? es : -es, 0.0);
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 10; i++) acc += tex(uv - off + vec2(float(i) * 0.012 * e, 0.0));
    c = acc / 10.0;
  } else if (uType == 13) { // shake
    float t = floor(uTime * 30.0);
    vec2 off = vec2(hash(vec2(t, 1.0)) - 0.5, hash(vec2(t, 2.0)) - 0.5) * 0.12 * e;
    c = tex((uv - 0.5) / (1.0 + 0.1 * e) + 0.5 + off);
  } else { // rgb split
    vec2 s = vec2(0.06 * es, 0.0);
    vec4 src = tex(uv);
    c = vec4(tex(uv + s).r, src.g, tex(uv - s).b, src.a);
  }
  gl_FragColor = c;
}
"""
}
