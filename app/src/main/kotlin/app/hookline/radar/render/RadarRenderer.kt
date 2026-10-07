package app.hookline.radar.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import app.hookline.radar.core.radar.Palette
import app.hookline.radar.core.radar.ThickLines
import app.hookline.radar.core.radar.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * One drawable radar layer: a value texture plus a mesh (x, y, u, v) in Web
 * Mercator metres relative to the map origin. Single-site sweeps use one
 * wedge per radial; composites use a lat/lon grid. Immutable: swap the
 * whole object to change what's drawn.
 */
class RasterLayer(
    val texWidth: Int,
    val texHeight: Int,
    /** Row-major texHeight x texWidth; -1e30 = no echo, +1e30 = range folded. */
    val values: FloatArray,
    val mesh: FloatArray,
    val palette: Palette,
    val minValue: Float,
    val maxValue: Float,
)

/** A polyline in Web Mercator metres relative to the map origin. */
class Outline(val points: FloatArray, val argb: Int, val widthPx: Float, val closed: Boolean = true)

/**
 * Draws up to two radar layers (a composite underneath, a single-site radar
 * on top) and outlines. Values are sampled NEAREST and coloured through a
 * palette LUT in the shader, so data is never smoothed.
 *
 * Setters are called from the UI thread and hand data to the GL thread
 * through @Volatile fields; uploads happen at the next frame.
 */
class RadarRenderer : GLSurfaceView.Renderer {

    private class Slot {
        @Volatile var pending: RasterLayer? = null
        @Volatile var dirty = false
        var current: RasterLayer? = null
        var valueTex = 0
        var lutTex = 0
        var vbo = 0
        var vertexCount = 0
        var uploadedPalette: Palette? = null
        var uploadedRange = Float.NaN to Float.NaN
    }

    private val composite = Slot()
    private val site = Slot()
    @Volatile private var viewport: Viewport = Viewport(1, 1)
    @Volatile private var outlines: List<Outline> = emptyList()
    @Volatile private var clearArgb: Int = 0xFF141B22.toInt()
    @Volatile private var redNight = false
    @Volatile private var captureRequest: ((android.graphics.Bitmap) -> Unit)? = null
    private var surfaceW = 1
    private var surfaceH = 1

    private var program = 0
    private var lineProgram = 0
    private var lineVbo = 0

    fun setCompositeLayer(layer: RasterLayer?) { composite.pending = layer; composite.dirty = true }
    fun setSiteLayer(layer: RasterLayer?) { site.pending = layer; site.dirty = true }
    fun setViewport(vp: Viewport) { viewport = vp }
    fun setOutlines(list: List<Outline>) { outlines = list }
    /** Map background behind the translucent base map. */
    fun setClearColor(argb: Int) { clearArgb = argb }
    /** Night vision: everything drawn in shades of red. */
    fun setRedNight(on: Boolean) { redNight = on }
    /** Reads back the next frame (on the GL thread) as a bitmap. */
    fun requestCapture(onDone: (android.graphics.Bitmap) -> Unit) { captureRequest = onDone }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GlUtil.program(RASTER_VS, RASTER_FS)
        lineProgram = GlUtil.program(LINE_VS, LINE_FS)
        val tex = IntArray(4); GLES30.glGenTextures(4, tex, 0)
        val bufs = IntArray(3); GLES30.glGenBuffers(3, bufs, 0)
        composite.valueTex = tex[0]; composite.lutTex = tex[1]; composite.vbo = bufs[0]
        site.valueTex = tex[2]; site.lutTex = tex[3]; site.vbo = bufs[1]
        lineVbo = bufs[2]
        lineSource = null
        // New context: re-upload whatever was showing.
        for (s in listOf(composite, site)) {
            if (!s.dirty) s.pending = s.current
            s.dirty = true
            s.uploadedPalette = null
            s.current = null
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceW = width; surfaceH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = clearArgb
        GLES30.glClearColor(((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
        val red = if (redNight) 1f else 0f
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "u_red"), red)
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(lineProgram, "u_red"), red)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val vp = viewport
        val sx = (2.0 / (vp.metersPerPixel * vp.widthPx)).toFloat()
        val sy = (2.0 / (vp.metersPerPixel * vp.heightPx)).toFloat()
        val cx = vp.centerEastM.toFloat()
        val cy = vp.centerNorthM.toFloat()

        for (s in listOf(composite, site)) {
            if (s.dirty) {
                s.dirty = false
                upload(s, s.pending)
            }
            s.current?.let { draw(s, it, sx, sy, cx, cy) }
        }
        drawLines(vp, sx, sy, cx, cy)
        captureRequest?.let { cb ->
            captureRequest = null
            cb(readPixels())
        }
    }

    private fun readPixels(): android.graphics.Bitmap {
        val w = surfaceW; val h = surfaceH
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        val raw = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // GL rows run bottom-up.
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(raw, 0, 0, w, h, flip, false).also { raw.recycle() }
    }

    private fun draw(s: Slot, l: RasterLayer, sx: Float, sy: Float, cx: Float, cy: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "u_center"), cx, cy)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "u_scale"), sx, sy)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "u_range"), l.minValue, l.maxValue)
        val rf = l.palette.rangeFoldedArgb
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(program, "u_rf"),
            ((rf shr 16) and 0xFF) / 255f, ((rf shr 8) and 0xFF) / 255f, (rf and 0xFF) / 255f, 1f,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.valueTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "u_values"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "u_lut"), 1)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, s.vertexCount)
        GLES30.glDisableVertexAttribArray(1)
    }

    // Outline triangles are rebuilt only when the list or the zoom changes, not every frame:
    // European warning regions can add thousands of points.
    private var lineSource: List<Outline>? = null
    private var lineMpp = 0.0
    private var lineGroups: List<Triple<Int, Int, Int>> = emptyList() // argb, first vertex, count

    private fun drawLines(vp: Viewport, sx: Float, sy: Float, cx: Float, cy: Float) {
        val list = outlines
        if (list.isEmpty()) return
        val mpp = vp.metersPerPixel
        if (list !== lineSource || mpp / lineMpp !in 0.87..1.15) {
            val byColor = LinkedHashMap<Int, MutableList<FloatArray>>()
            for (o in list) {
                val tris = ThickLines.build(o.points, o.closed, (o.widthPx / 2.0 * mpp).toFloat())
                if (tris.isNotEmpty()) byColor.getOrPut(o.argb) { mutableListOf() } += tris
            }
            val total = byColor.values.sumOf { l -> l.sumOf { it.size } }
            val all = FloatArray(total)
            var at = 0
            val groups = mutableListOf<Triple<Int, Int, Int>>()
            for ((argb, parts) in byColor) {
                val first = at / 2
                for (t in parts) { System.arraycopy(t, 0, all, at, t.size); at += t.size }
                groups += Triple(argb, first, at / 2 - first)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, all.size * 4, GlUtil.floats(all), GLES30.GL_STATIC_DRAW)
            lineGroups = groups
            lineSource = list
            lineMpp = mpp
        }
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(lineProgram, "u_center"), cx, cy)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(lineProgram, "u_scale"), sx, sy)
        val colorLoc = GLES30.glGetUniformLocation(lineProgram, "u_color")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        for ((argb, first, count) in lineGroups) {
            GLES30.glUniform4f(
                colorLoc,
                ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, ((argb ushr 24) and 0xFF) / 255f,
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, first, count)
        }
    }

    private fun upload(s: Slot, l: RasterLayer?) {
        s.current = l
        if (l == null) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.valueTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, l.texWidth, l.texHeight, 0,
            GLES30.GL_RED, GLES30.GL_FLOAT, GlUtil.floats(l.values),
        )
        GlUtil.nearestClamp()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, l.mesh.size * 4, GlUtil.floats(l.mesh), GLES30.GL_STATIC_DRAW)
        s.vertexCount = l.mesh.size / 4
        val range = l.minValue to l.maxValue
        if (l.palette !== s.uploadedPalette || range != s.uploadedRange) {
            val lut = l.palette.toLut(l.minValue, l.maxValue, LUT_SIZE)
            val bytes = ByteBuffer.allocateDirect(LUT_SIZE * 4).order(ByteOrder.nativeOrder())
            for (c in lut) {
                bytes.put(((c shr 16) and 0xFF).toByte()).put(((c shr 8) and 0xFF).toByte())
                    .put((c and 0xFF).toByte()).put(((c ushr 24) and 0xFF).toByte())
            }
            bytes.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.lutTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, LUT_SIZE, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            GlUtil.nearestClamp()
            s.uploadedPalette = l.palette
            s.uploadedRange = range
        }
    }

    companion object {
        private const val LUT_SIZE = 256

        private const val RASTER_VS = """#version 300 es
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_uv;
uniform vec2 u_center;
uniform vec2 u_scale;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4((a_pos - u_center) * u_scale, 0.0, 1.0);
}"""

        private const val RASTER_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform highp sampler2D u_values;
uniform sampler2D u_lut;
uniform vec2 u_range;
uniform vec4 u_rf;
uniform float u_red;
out vec4 o_color;
void main() {
    // Composite meshes extend past the data; nothing is drawn outside it.
    if (v_uv.x < 0.0 || v_uv.x > 1.0 || v_uv.y < 0.0 || v_uv.y > 1.0) discard;
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
    if (u_red > 0.5) {
        float l = dot(c.rgb, vec3(0.30, 0.59, 0.11));
        c = vec4(0.25 + 0.75 * l, 0.03 * l, 0.02 * l, c.a);
    }
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
uniform float u_red;
out vec4 o_color;
void main() {
    vec4 c = u_color;
    if (u_red > 0.5) {
        float l = dot(c.rgb, vec3(0.30, 0.59, 0.11));
        c = vec4(0.2 + 0.8 * l, 0.03 * l, 0.02 * l, c.a);
    }
    o_color = c;
}"""
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
