package com.dustline.game

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * The 3D lobby avatar (#lobby scene of the HTML): the picked character, real rigged mesh + textures, standing in the lobby.
 * Drag left / right to turn it. The Canvas lobby UI (LobbyView) is drawn over this view.
 */
class LobbyAvatarView(ctx: Context) : GLSurfaceView(ctx) {
    private companion object {
        const val FOV_Y = 28f        // vertical field of view
        const val CAM_Y = 1.0f       // camera height (looks straight ahead)
        const val CAM_Z = 6.6f       // camera distance
        const val CHAR_SCALE = 1.18f // character mesh is ~2.15 high -> ~2.54 world units = ~77 % of the screen height
        const val FEET_GAP = 0.105f  // free space under the boots, as a fraction of the screen height (head gap comes out ~12 %)
    }
    @Volatile var charId = "survivor"
    /** STORE / VAULT: the avatar fills the right 30 % of the screen and shows `previewWear` (a top that is selected but not bought yet) instead of the worn one. */
    @Volatile var panel = false
    /** width of the panel as a fraction of the screen (VAULT 30 %, STORE 36 % so the shop list + info box fit on its left). */
    @Volatile var panelFrac = 0.30f
    /** VAULT: the panel sits on the LEFT edge (reference screen), STORE keeps it on the right. */
    @Volatile var panelLeft = false
    @Volatile var usePreview = false
    @Volatile var previewWear: String? = null
    @Volatile var previewPants: String? = null
    /** result screen: the character stands still, facing the camera (null = normal lobby turntable) */
    @Volatile var fixedSpin: Float? = null
    @Volatile private var surfW = 1; @Volatile private var surfH = 1
    fun rotateBy(dx: Float) { spin += dx * 0.012f; idleFrom = System.nanoTime() }
    @Volatile private var spin = 0.35f
    private var lastX = 0f; private var dragging = false
    @Volatile private var idleFrom = 0L

    private val r = object : Renderer {
        private val chars = CharRenderer(ctx)
        private val pose = CharPose()
        private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16); private val model = FloatArray(16)
        private val t0 = System.nanoTime()

        // ---- full-screen lobby background image (assets/ui/lobby_bg.jpg), drawn behind the character ----
        private var bgProg = 0; private var bgTex = 0; private var bgAspect = 16f / 9f
        private fun sh(type: Int, src: String): Int {
            val s = GL.glCreateShader(type); GL.glShaderSource(s, src); GL.glCompileShader(s); return s
        }
        private fun initBg() {
            val vs = "#version 300 es\nuniform vec4 uCrop; out vec2 vUv;\nvoid main(){ vec2 p = vec2(float((gl_VertexID<<1)&2), float(gl_VertexID&2));" +
                " vUv = vec2(p.x, 1.0-p.y) * uCrop.xy + uCrop.zw; gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }"
            val fs = "#version 300 es\nprecision mediump float; uniform sampler2D uTex; in vec2 vUv; out vec4 o;\nvoid main(){ o = vec4(texture(uTex, vUv).rgb, 1.0); }"
            bgProg = GL.glCreateProgram()
            GL.glAttachShader(bgProg, sh(GL.GL_VERTEX_SHADER, vs)); GL.glAttachShader(bgProg, sh(GL.GL_FRAGMENT_SHADER, fs)); GL.glLinkProgram(bgProg)
            try {
                val bmp = ctx.assets.open("ui/lobby_bg.jpg").use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    bgAspect = bmp.width.toFloat() / bmp.height
                    val id = IntArray(1); GL.glGenTextures(1, id, 0); bgTex = id[0]
                    GL.glBindTexture(GL.GL_TEXTURE_2D, bgTex)
                    GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                }
            } catch (_: Exception) { bgTex = 0 }
        }
        private fun drawBg(sw: Int, sh: Int) {
            if (bgTex == 0 || bgProg == 0) return
            // stretch-to-fit: the WHOLE image is shown, edge to edge on every screen (no cropping, no black bars)
            val ux = 1f; val uy = 1f
            GL.glDisable(GL.GL_DEPTH_TEST)
            GL.glBindVertexArray(0)
            GL.glUseProgram(bgProg)
            GL.glUniform4f(GL.glGetUniformLocation(bgProg, "uCrop"), ux, uy, (1f - ux) / 2f, (1f - uy) / 2f)
            GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, bgTex)
            GL.glUniform1i(GL.glGetUniformLocation(bgProg, "uTex"), 0)
            GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
            GL.glEnable(GL.GL_DEPTH_TEST)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            chars.init(); initBg()
            GL.glClearColor(0.075f, 0.055f, 0.105f, 1f)
            GL.glEnable(GL.GL_DEPTH_TEST)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            surfW = w; surfH = h
            GL.glViewport(0, 0, w, h)
            Matrix.perspectiveM(proj, 0, FOV_Y, w.toFloat() / h, 0.1f, 60f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
            GL.glViewport(0, 0, surfW, surfH)
            drawBg(surfW, surfH)
            if (!chars.ready || !chars.has(charId)) return
            val sw = surfW; val sh = surfH
            if (panel) { val pw = (sw * panelFrac).toInt().coerceAtLeast(1); GL.glViewport(if (panelLeft) 0 else sw - pw, 0, pw, sh); Matrix.perspectiveM(proj, 0, FOV_Y, pw.toFloat() / sh, 0.1f, 60f) }
            else { GL.glViewport(0, 0, sw, sh); Matrix.perspectiveM(proj, 0, FOV_Y, sw.toFloat() / sh, 0.1f, 60f) }
            val t = (System.nanoTime() - t0) / 1e9f
            if (!dragging && System.nanoTime() - idleFrom > 2_500_000_000L) spin += 0.004f   // slow turntable when not touched
            fixedSpin?.let { spin = it }
            // Lobby framing (reference style): the whole character, head to boots, ~77 % of the screen height,
            // ~12 % free above the head and ~10 % free under the boots. Camera looks straight ahead (no tilt) at CAM_Y.
            val halfH = Math.tan(Math.toRadians(FOV_Y / 2.0)).toFloat() * CAM_Z          // half of the visible height at the character
            val feetY = CAM_Y - halfH + FEET_GAP * 2f * halfH                              // world height of the boots
            Matrix.setLookAtM(view, 0, 0f, CAM_Y, CAM_Z, 0f, CAM_Y, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, 0.015f * sin(t * 1.6f), 0f)
            Matrix.rotateM(model, 0, Math.toDegrees(spin.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.translateM(model, 0, 0f, feetY, 0f)
            Matrix.scaleM(model, 0, CHAR_SCALE, CHAR_SCALE, CHAR_SCALE)                    // mesh origin = feet, mesh height ~2.15
            pose.mode = CharPose.RELAXED; pose.speed = 0f; pose.walk = 0f; pose.sprint = false; pose.crouch = 0f
            chars.draw(charId, vp, model, 0f, CAM_Y, CAM_Z, 8f, pose, wear = if (usePreview) previewWear else Wear.id, pants = if (usePreview) previewPants else Wear.pants)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(r)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { dragging = true; lastX = e.x }
            MotionEvent.ACTION_MOVE -> { spin += (e.x - lastX) * 0.012f; lastX = e.x }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; idleFrom = System.nanoTime() }
        }
        return true
    }
}
