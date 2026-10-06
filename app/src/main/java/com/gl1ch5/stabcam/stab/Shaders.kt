package com.gl1ch5.stabcam.stab

import android.opengl.GLES20

/** GLSL programs shared by the live pipeline ([StabPipeline]) and the offline processor. Needs a current GL context. */
object Shaders {
    fun warp(external: Boolean): Int {
        val vs = """#version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vPos;
            void main() {
                vPos = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
                gl_Position = vec4(aPos, 0.0, 1.0);
            }"""
        val rowsN = Stabilizer.ROWS
        val fs = """#version 300 es
            ${if (external) "#extension GL_OES_EGL_image_external_essl3 : require" else ""}
            precision highp float;
            uniform ${if (external) "samplerExternalOES" else "sampler2D"} uTex;
            uniform vec2 uSize;
            uniform vec4 uK;
            uniform float uZoom;
            uniform float uCrop;
            uniform int uPreview;
            uniform float uSharp;
            uniform int uBicubic;
            uniform int uHdr;
            precision highp sampler3D;
            uniform sampler3D uLut;
            uniform float uLutAmt;
            uniform float uLutN;
            uniform mat3 uR[$rowsN];
            in vec2 vPos;
            out vec4 o;

            // Preview only: HLG signal -> SDR (inverse OETF, BT.2020 -> BT.709, soft tone map, gamma).
            vec3 outc(vec3 e) {
                if (uPreview == 0 || uHdr == 0) return e;
                const float a = 0.17883277; const float b = 0.28466892; const float cc = 0.55991073;
                vec3 lo = e * e / 3.0;
                vec3 hi = (exp((e - cc) / a) + b) / 12.0;
                vec3 l = mix(lo, hi, step(vec3(0.5), e));
                mat3 m = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187);
                l = max(m * l, vec3(0.0));
                l = l * 3.5 / (1.0 + l * 2.5);
                return pow(clamp(l, 0.0, 1.0), vec3(1.0 / 2.2));
            }

            vec3 grade(vec3 c) {
                if (uLutAmt <= 0.0) return c;
                vec3 t = clamp(c, 0.0, 1.0) * ((uLutN - 1.0) / uLutN) + 0.5 / uLutN;
                return mix(c, texture(uLut, t).rgb, uLutAmt);
            }

            vec3 fetch(vec2 q) {
                // Raw buffer coordinates (top row = 0): the camera service pre-rotates the SurfaceTexture
                // transform to portrait, but the gyro/intrinsics maths needs the unrotated sensor frame.
                return texture(uTex, ${if (external) "q" else "vec2(q.x, 1.0 - q.y)"}).rgb;
            }

            // Catmull-Rom with 9 bilinear taps: keeps edges crisp after the warp (bilinear alone softens them).
            vec3 catmull(vec2 q) {
                vec2 pos = q * uSize;
                vec2 c = floor(pos - 0.5) + 0.5;
                vec2 f = pos - c;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 o12 = w2 / w12;
                vec2 p0 = (c - 1.0) / uSize;
                vec2 p3 = (c + 2.0) / uSize;
                vec2 p12 = (c + o12) / uSize;
                vec3 r = fetch(vec2(p0.x, p0.y)) * w0.x * w0.y + fetch(vec2(p12.x, p0.y)) * w12.x * w0.y + fetch(vec2(p3.x, p0.y)) * w3.x * w0.y
                       + fetch(vec2(p0.x, p12.y)) * w0.x * w12.y + fetch(vec2(p12.x, p12.y)) * w12.x * w12.y + fetch(vec2(p3.x, p12.y)) * w3.x * w12.y
                       + fetch(vec2(p0.x, p3.y)) * w0.x * w3.y + fetch(vec2(p12.x, p3.y)) * w12.x * w3.y + fetch(vec2(p3.x, p3.y)) * w3.x * w3.y;
                return max(r, vec3(0.0));
            }
            void main() {
                // Output pixel in the sensor-oriented frame (preview is rotated 90° CW to portrait).
                vec2 pos = vPos;
                if (uPreview == 1) pos = vec2(vPos.y, 1.0 - vPos.x);
                else if (uPreview == 2) pos = vec2(1.0 - vPos.y, vPos.x);
                else if (uPreview == 4) pos = vec2(1.0) - vPos;
                vec3 d = vec3((pos.x * uSize.x - uK.z) / uK.x, (pos.y * uSize.y - uK.w) / uK.y, 1.0);
                d.xy /= uZoom;
                float rp = pos.y * float($rowsN - 1);
                int i0 = int(floor(rp));
                int i1 = min(i0 + 1, $rowsN - 1);
                float f = rp - float(i0);
                mat3 R = uR[i0] * (1.0 - f) + uR[i1] * f;
                vec3 s = R * d;
                vec2 q = vec2(s.x / s.z * uK.x + uK.z, s.y / s.z * uK.y + uK.w) / uSize;
                if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
                if (uPreview > 0 || uBicubic == 0) { o = vec4(outc(grade(fetch(q))), 1.0); return; }
                vec3 c = catmull(q);
                if (uSharp > 0.0) {
                    vec2 px = 1.0 / uSize;
                    vec3 n = fetch(q + vec2(0.0, -px.y));
                    vec3 s2 = fetch(q + vec2(0.0, px.y));
                    vec3 e = fetch(q + vec2(px.x, 0.0));
                    vec3 w = fetch(q + vec2(-px.x, 0.0));
                    vec3 lo = min(min(n, s2), min(e, w));
                    vec3 hi = max(max(n, s2), max(e, w));
                    vec3 sh = c + uSharp * (c - 0.25 * (n + s2 + e + w));
                    // Limited overshoot: no halos around edges.
                    c = clamp(sh, min(lo, c) - 0.02, max(hi, c) + 0.02);
                }
                o = vec4(outc(grade(c)), 1.0);
            }"""
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    fun denoise(): Int {
        val vs = """#version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vPos;
            void main() {
                vPos = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
                gl_Position = vec4(aPos, 0.0, 1.0);
            }"""
        val fs = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uCur;
            uniform sampler2D uHist;
            uniform vec2 uSize;
            uniform vec4 uK;
            uniform mat3 uRel;
            uniform float uStr;
            uniform float uSigma;
            uniform int uHasHist;
            in vec2 vPos;
            out vec4 o;
            void main() {
                vec3 c = texture(uCur, vPos).rgb;
                if (uHasHist == 0) { o = vec4(c, 1.0); return; }
                vec3 d = vec3((vPos.x * uSize.x - uK.z) / uK.x, (vPos.y * uSize.y - uK.w) / uK.y, 1.0);
                vec3 s = uRel * d;
                vec2 q = vec2(s.x / s.z * uK.x + uK.z, s.y / s.z * uK.y + uK.w) / uSize;
                if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { o = vec4(c, 1.0); return; }
                vec3 h = texture(uHist, vec2(q.x, 1.0 - q.y)).rgb;
                float dl = dot(abs(c - h), vec3(0.299, 0.587, 0.114));
                // Similar -> average (noise); different -> keep the new frame (motion, no ghosts).
                float w = uStr * 0.75 * exp(-(dl * dl) / (uSigma * uSigma));
                // Large reprojection shifts blur the history: trust it less.
                float shift = length((q - vPos) * uSize);
                w *= 1.0 / (1.0 + shift * 0.08);
                o = vec4(mix(c, h, w), 1.0);
            }"""
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link dn: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

}
