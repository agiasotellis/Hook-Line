package app.hookline.radar.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.core.radar.Palette
import app.hookline.radar.core.radar.SweepTexture
import app.hookline.radar.core.radar.ThickLines
import app.hookline.radar.core.radar.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Everything needed to draw one radar frame. Immutable; swap the whole thing. */
data class RadarFrame(
    val sweep: SweepTexture,
    val palette: Palette,
    val minValue: Float,
    val maxValue: Float,
    /** Places the radar on the Web Mercator map; positions are metres relative to the site. */
    val projector: MercatorProjector,
)

/** A polyline in Web Mercator metres relative to the site: warning outlines, storm tracks. */
class Outline(val points: FloatArray, val argb: Int, val widthPx: Float, val closed: Boolean = true)

/**
 * Draws radials in native polar form: one wedge per radial, a R32F value
 * texture sampled NEAREST, and a palette LUT. Below-threshold gates are
 * discarded in the shader so the map shows through.
 *
 * All public setters are called from the UI thread and hand data to the GL
 * thread through @Volatile fields; uploads happen at the next frame.
 */
class RadarRenderer : GLSurfaceView.Renderer {

    @Volatile private var pendingFrame: RadarFrame? = null
    @Volatile private var viewport: Viewport = Viewport(1, 1)
    @Volatile private var outlines: List<Outline> = emptyList()
    @Volatile private var ringsArgb: Int = 0x553A4652
    @Volatile private var clearArgb: Int = 0xFF141B22.toInt()
    private var ringCache = FloatArray(0)
    private var ringCacheMpp = -1f
    private var ringProjector: MercatorProjector? = null

    private var radialProgram = 0
    private var lineProgram = 0
    private var valueTex = 0
    private var lutTex = 0
    private var meshVbo = 0
    private var meshVertexCount = 0
    private var current: RadarFrame? = null
    private var uploadedSweep: SweepTexture? = null
    private var uploadedProjector: MercatorProjector? = null
    private var uploadedPalette: Palette? = null
    private var lineVbo = 0

    fun setFrame(frame: RadarFrame?) { pendingFrame = frame }
    fun setViewport(vp: Viewport) { viewport = vp }
    fun setOutlines(list: List<Outline>) { outlines = list }
    fun setRingColor(argb: Int) { ringsArgb = argb }
    /** Map background until the vector base map lands; matches the theme's map void. */
    fun setClearColor(argb: Int) { clearArgb = argb }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        radialProgram = GlUtil.program(RADIAL_VS, RADIAL_FS)
        lineProgram = GlUtil.program(LINE_VS, LINE_FS)
        val ids = IntArray(2); GLES30.glGenTextures(2, ids, 0)
        valueTex = ids[0]; lutTex = ids[1]
        val bufs = IntArray(2); GLES30.glGenBuffers(2, bufs, 0)
        meshVbo = bufs[0]; lineVbo = bufs[1]
        // Context was (re)created: force re-upload of whatever we last had.
        uploadedSweep = null; uploadedPalette = null; uploadedProjector = null
        pendingFrame = pendingFrame ?: current
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = clearArgb
        GLES30.glClearColor(((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        pendingFrame?.let { upload(it); current = it; pendingFrame = null }
        val vp = viewport
        val sx = (2.0 / (vp.metersPerPixel * vp.widthPx)).toFloat()
        val sy = (2.0 / (vp.metersPerPixel * vp.heightPx)).toFloat()
        val cx = vp.centerEastM.toFloat()
        val cy = vp.centerNorthM.toFloat()

        current?.let { f ->
            GLES30.glUseProgram(radialProgram)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(radialProgram, "u_center"), cx, cy)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(radialProgram, "u_scale"), sx, sy)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(radialProgram, "u_range"), f.minValue, f.maxValue)
            val rf = f.palette.rangeFoldedArgb
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(radialProgram, "u_rf"),
                ((rf shr 16) and 0xFF) / 255f, ((rf shr 8) and 0xFF) / 255f, (rf and 0xFF) / 255f, 1f,
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, valueTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(radialProgram, "u_values"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(radialProgram, "u_lut"), 1)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, meshVertexCount)
            GLES30.glDisableVertexAttribArray(1)
        }

        drawLines(vp, sx, sy, cx, cy, current?.projector)
    }

    /** Range rings (every 50 km) and alert outlines, rebuilt each frame at the current zoom (cheap). */
    private fun drawLines(vp: Viewport, sx: Float, sy: Float, cx: Float, cy: Float, projector: MercatorProjector?) {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(lineProgram, "u_center"), cx, cy)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(lineProgram, "u_scale"), sx, sy)
        val colorLoc = GLES30.glGetUniformLocation(lineProgram, "u_color")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)

        fun draw(tris: FloatArray, argb: Int) {
            if (tris.isEmpty()) return
            GLES30.glUniform4f(
                colorLoc,
                ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f,
            )
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tris.size * 4, GlUtil.floats(tris), GLES30.GL_STREAM_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, tris.size / 2)
        }

        val mpp = vp.metersPerPixel.toFloat()
        if (projector != null && (mpp != ringCacheMpp || projector !== ringProjector)) {
            val parts = (1..9).map { k ->
                val pts = FloatArray(RING_SEGMENTS * 2)
                for (i in 0 until RING_SEGMENTS) {
                    val (x, y) = projector.fromPolar(360.0 * i / RING_SEGMENTS, k * 50_000.0)
                    pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
                }
                ThickLines.build(pts, true, 0.75f * mpp)
            }
            ringCache = FloatArray(parts.sumOf { it.size }).also { out ->
                var k = 0
                for (p in parts) { p.copyInto(out, k); k += p.size }
            }
            ringCacheMpp = mpp
            ringProjector = projector
        }
        if (projector != null) draw(ringCache, ringsArgb)
        for (o in outlines) draw(ThickLines.build(o.points, o.closed, o.widthPx / 2f * mpp), o.argb)
    }

    private fun upload(f: RadarFrame) {
        val s = f.sweep
        if (s !== uploadedSweep || f.projector !== uploadedProjector) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, valueTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, s.gateCount, s.radialCount, 0,
                GLES30.GL_RED, GLES30.GL_FLOAT, GlUtil.floats(s.values),
            )
            GlUtil.nearestClamp()
            val mesh = s.wedgeMesh(f.projector)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.size * 4, GlUtil.floats(mesh), GLES30.GL_STATIC_DRAW)
            meshVertexCount = mesh.size / 4
            uploadedSweep = s
            uploadedProjector = f.projector
        }
        if (f.palette !== uploadedPalette) {
            val lut = f.palette.toLut(f.minValue, f.maxValue, LUT_SIZE)
            val bytes = ByteBuffer.allocateDirect(LUT_SIZE * 4).order(ByteOrder.nativeOrder())
            for (c in lut) {
                bytes.put(((c shr 16) and 0xFF).toByte()).put(((c shr 8) and 0xFF).toByte())
                    .put((c and 0xFF).toByte()).put(((c ushr 24) and 0xFF).toByte())
            }
            bytes.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, LUT_SIZE, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            GlUtil.nearestClamp()
            uploadedPalette = f.palette
        }
    }

    companion object {
        private const val LUT_SIZE = 256
        private const val RING_SEGMENTS = 120

        private const val RADIAL_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
uniform vec2 u_center;
uniform vec2 u_scale;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4((a_pos - u_center) * u_scale, 0.0, 1.0);
}"""

        private const val RADIAL_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform highp sampler2D u_values;
uniform sampler2D u_lut;
uniform vec2 u_range;
uniform vec4 u_rf;
out vec4 o_color;
void main() {
    float v = texture(u_values, v_uv).r;
    if (v < -1.0e29) discard;
    vec4 c;
    if (v > 1.0e29) {
        c = u_rf;
    } else {
        float t = clamp((v - u_range.x) / (u_range.y - u_range.x), 0.0, 1.0);
        c = texture(u_lut, vec2(t * (255.0 / 256.0) + 0.5 / 256.0, 0.5));
    }
    if (c.a < 0.01) discard;
    o_color = c;
}"""

        private const val LINE_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform vec2 u_center;
uniform vec2 u_scale;
void main() { gl_Position = vec4((a_pos - u_center) * u_scale, 0.0, 1.0); }"""

        private const val LINE_FS = """#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 o_color;
void main() { o_color = u_color; }"""
    }
}

internal object GlUtil {
    fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    fun nearestClamp() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }
}
