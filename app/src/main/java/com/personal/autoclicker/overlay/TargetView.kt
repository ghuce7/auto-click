package com.personal.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Một mục tiêu (tâm ngắm) bán trong suốt. Có thể kéo thả khi đang Pause,
 * chạm nhẹ để mở popup cấu hình, và bị khóa + cho chạm xuyên qua khi đang Play.
 */
@SuppressLint("ViewConstructor")
class TargetView(
    context: Context,
    private val windowManager: WindowManager,
    private val origin: OverlayOrigin,
    private val screenBounds: () -> Point,
    val role: Role
) : View(context) {

    enum class Role(val baseColor: Int) {
        TAP(Color.rgb(33, 150, 243)),
        SWIPE_START(Color.rgb(76, 175, 80)),
        SWIPE_END(Color.rgb(255, 87, 34))
    }

    val sizePx: Int = OverlayUtils.dp(context, 52)
    val windowParams: WindowManager.LayoutParams = OverlayUtils.createParams(sizePx, sizePx)

    /** Tọa độ tâm theo hệ tọa độ màn hình (không bao gồm origin của cửa sổ). */
    var centerX: Int = 0
        private set
    var centerY: Int = 0
        private set

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var onMoved: (() -> Unit)? = null
    var onTapped: ((TargetView) -> Unit)? = null

    private var locked = false
    private var added = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startCenterX = 0
    private var startCenterY = 0
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f

    private val strokeWidth = OverlayUtils.dpF(context, 2.5f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(0x55, Color.red(role.baseColor), Color.green(role.baseColor), Color.blue(role.baseColor))
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@TargetView.strokeWidth
        color = Color.argb(0xE6, Color.red(role.baseColor), Color.green(role.baseColor), Color.blue(role.baseColor))
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OverlayUtils.dpF(context, 1.5f)
        color = Color.argb(0xF0, 255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(0xFF, 255, 235, 59)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = OverlayUtils.dpF(context, 13f)
        setShadowLayer(OverlayUtils.dpF(context, 2f), 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - strokeWidth
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val arm = radius * 0.4f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        canvas.drawCircle(cx, cy, OverlayUtils.dpF(context, 2.5f), dotPaint)
        val metrics = textPaint.fontMetrics
        val textY = cy - radius * 0.52f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, textY, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startCenterX = centerX
                startCenterY = centerY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    moveCenterTo(startCenterX + dx.roundToInt(), startCenterY + dy.roundToInt())
                    onMoved?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    performClick()
                } else {
                    onMoved?.invoke()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTapped?.invoke(this)
        return true
    }

    /** Đặt tâm mục tiêu tại tọa độ màn hình (cx, cy). */
    fun moveCenterTo(cx: Int, cy: Int) {
        val bounds = screenBounds()
        centerX = cx.coerceIn(0, bounds.x)
        centerY = cy.coerceIn(0, bounds.y)
        windowParams.x = centerX - sizePx / 2 - origin.x
        windowParams.y = centerY - sizePx / 2 - origin.y
        if (added) {
            windowManager.updateViewLayout(this, windowParams)
        }
    }

    /**
     * Khóa kéo thả và thêm FLAG_NOT_TOUCHABLE để chạm xuyên qua mục tiêu
     * (dùng khi kịch bản đang chạy).
     */
    fun setLocked(value: Boolean) {
        locked = value
        windowParams.flags = if (value) {
            windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (added) {
            windowManager.updateViewLayout(this, windowParams)
        }
    }

    fun attach() {
        if (added) return
        windowManager.addView(this, windowParams)
        added = true
    }

    fun detach() {
        if (!added) return
        added = false
        try {
            windowManager.removeViewImmediate(this)
        } catch (e: IllegalArgumentException) {
            // View đã bị gỡ trước đó, không cần xử lý thêm.
        }
    }

    /** Tọa độ tâm thực tế trên màn hình, đọc trực tiếp bằng getLocationOnScreen(). */
    fun liveCenter(): PointF {
        if (!added || width == 0 || !isAttachedToWindow) {
            return PointF(centerX.toFloat(), centerY.toFloat())
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        return PointF(location[0] + width / 2f, location[1] + height / 2f)
    }
}
