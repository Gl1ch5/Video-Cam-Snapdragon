package com.gl1ch5.stabcam.stab

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Minimal EGL14 wrapper: one GLES3 context, window/pbuffer surfaces, recordable config for MediaCodec. */
class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        config = cfgs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
    }

    fun createWindowSurface(s: Surface): EGLSurface {
        val surf = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
        check(surf != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        return surf
    }

    fun createPbuffer(): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) { check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent" } }
    fun swap(s: EGLSurface) = EGL14.eglSwapBuffers(display, s)
    fun setPresentationTime(s: EGLSurface, ns: Long) { EGLExt.eglPresentationTimeANDROID(display, s, ns) }
    fun noSwapInterval() { EGL14.eglSwapInterval(display, 0) }
    fun destroySurface(s: EGLSurface) { EGL14.eglDestroySurface(display, s) }

    fun size(s: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
    }

    companion object { private const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
