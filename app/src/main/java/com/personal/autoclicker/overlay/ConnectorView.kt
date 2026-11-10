package com.personal.autoclicker.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lớp phủ toàn màn hình (không nhận chạm) dùng để vẽ đường nối Điểm A -> Điểm B
 * cho các cặp mục tiêu Vuốt.
 */
class ConnectorView(
    context: Context,
    private val pairsProvider: () -> List<Pair<TargetView, TargetView>>
) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OverlayUtils.dpF(context, 3f)
        color = Color.argb(0xCC, 255, 193, 7)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(
            floatArrayOf(OverlayUtils.dpF(context, 10f), OverlayUtils.dpF(context, 6f)),
            0f
        )
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(0xDD, 255, 193, 7)
    }
    private val arrowSize = OverlayUtils.dpF(context, 14f)
    private val arrowPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val location = IntArray(2)
        getLocationOnScreen(location)
        for ((start, end) in pairsProvider()) {
            val sx = (start.centerX - location[0]).toFloat()
            val sy = (start.centerY - location[1]).toFloat()
            val ex = (end.centerX - location[0]).toFloat()
            val ey = (end.centerY - location[1]).toFloat()
            canvas.drawLine(sx, sy, ex, ey, linePaint)

            val angle = atan2(ey - sy, ex - sx)
            val tipX = ex - cos(angle) * OverlayUtils.dpF(context, 26f)
            val tipY = ey - sin(angle) * OverlayUtils.dpF(context, 26f)
            arrowPath.reset()
            arrowPath.moveTo(tipX, tipY)
            arrowPath.lineTo(
                tipX - arrowSize * cos(angle - ARROW_SPREAD),
                tipY - arrowSize * sin(angle - ARROW_SPREAD)
            )
            arrowPath.lineTo(
                tipX - arrowSize * cos(angle + ARROW_SPREAD),
                tipY - arrowSize * sin(angle + ARROW_SPREAD)
            )
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
        }
    }

    companion object {
        private const val ARROW_SPREAD = 0.5f
    }
}
