package com.relay.app.ui.screens.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.relay.app.data.model.Message
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PinLabelOverlay(private val pins: List<Message>) : Overlay() {

    private val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
    }

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = osmv.projection
        val screenPoint = Point()
        for (pin in pins) {
            val label = pin.pinLabel ?: continue
            val lat = pin.lat ?: continue
            val lng = pin.lng ?: continue
            projection.toPixels(GeoPoint(lat, lng), screenPoint)
            val textWidth = paint.measureText(label)
            c.drawText(label, screenPoint.x - textWidth / 2f, screenPoint.y.toFloat() + 44f, paint)
        }
    }
}
