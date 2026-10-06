package app.hookline.radar.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.hookline.radar.core.radar.Viewport

/**
 * GL surface for the radar layer. Owns pan/zoom gestures and reports
 * long-presses (in metres from the site) for the inspector.
 */
@SuppressLint("ViewConstructor")
class RadarGlView(context: Context) : GLSurfaceView(context) {

    val renderer = RadarRenderer()

    var viewport: Viewport = Viewport(1, 1, metersPerPixel = 250.0)
        private set

    /** Called on the UI thread whenever the user pans or zooms. */
    var onViewportChanged: ((Viewport) -> Unit)? = null
    /** Long-press at a map point, metres east/north of the radar site. */
    var onInspect: ((eastM: Double, northM: Double) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        contentDescription = "Radar map. Drag to pan, pinch to zoom, long-press for the value at a point."
    }

    fun setFrame(frame: RadarFrame?) { renderer.setFrame(frame); requestRender() }
    fun setOutlines(list: List<Outline>) { renderer.setOutlines(list); requestRender() }

    /** Re-centre, e.g. when the site changes. */
    fun center(eastM: Double, northM: Double, metersPerPixel: Double = viewport.metersPerPixel) {
        update(viewport.copy(centerEastM = eastM, centerNorthM = northM, metersPerPixel = metersPerPixel))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        update(viewport.copy(widthPx = w, heightPx = h))
    }

    private fun update(vp: Viewport) {
        viewport = vp
        renderer.setViewport(vp)
        requestRender()
        onViewportChanged?.invoke(vp)
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            update(viewport.zoomBy(d.scaleFactor, d.focusX, d.focusY))
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // dx/dy are the distance scrolled since last event (finger movement reversed).
            update(viewport.panByPixels(-dx, -dy))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            update(viewport.zoomBy(2f, e.x, e.y))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (east, north) = viewport.toMeters(e.x, e.y)
            onInspect?.invoke(east, north)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
