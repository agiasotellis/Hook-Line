package app.hookline.radar.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import app.hookline.radar.core.radar.Viewport

/**
 * GL surface for the radar layer. It sits under the translucent base map,
 * which owns all gestures; the map pushes its camera here via [setViewport]
 * so radar and tiles move as one.
 */
@SuppressLint("ViewConstructor")
class RadarGlView(context: Context) : GLSurfaceView(context) {

    val renderer = RadarRenderer()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setLayers(composite: RasterLayer?, site: RasterLayer?) {
        renderer.setCompositeLayer(composite)
        renderer.setSiteLayer(site)
        requestRender()
    }
    fun setOutlines(list: List<Outline>) { renderer.setOutlines(list); requestRender() }
    fun setViewport(vp: Viewport) { renderer.setViewport(vp); requestRender() }
    fun setRedNight(on: Boolean, clearArgb: Int) { renderer.setRedNight(on); renderer.setClearColor(clearArgb); requestRender() }

    /** The radar layer as currently drawn (transparent where there's no echo is NOT kept: background included). */
    suspend fun capture(): android.graphics.Bitmap = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        renderer.requestCapture { bmp -> post { if (cont.isActive) cont.resumeWith(Result.success(bmp)) } }
        requestRender()
    }
}
