package com.personal.autoclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Tọa độ gốc (0,0) thực tế của các cửa sổ overlay so với màn hình vật lý.
 * Được đo bằng getLocationOnScreen() của lớp phủ toàn màn hình, giúp đặt
 * mục tiêu chính xác ở cả chế độ dọc, ngang và màn hình có tai thỏ.
 */
class OverlayOrigin {
    var x: Int = 0
    var y: Int = 0
}

object OverlayUtils {

    /** Độ mờ tối đa để Android 12+ vẫn cho phép chạm xuyên qua cửa sổ overlay. */
    private const val OVERLAY_ALPHA = 0.8f

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).roundToInt()

    fun dpF(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** Kích thước thật của màn hình theo hướng hiện tại (đã tính xoay ngang/dọc). */
    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Point {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Tạo LayoutParams cho cửa sổ nổi: TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE
     * để không làm gián đoạn game đang chạy bên dưới.
     */
    fun createParams(
        width: Int,
        height: Int,
        touchable: Boolean = true
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = OVERLAY_ALPHA
            }
        }
    }

    fun roundedBackground(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun rippleBackground(color: Int, radiusPx: Float): RippleDrawable {
        val content = roundedBackground(color, radiusPx)
        val mask = roundedBackground(Color.WHITE, radiusPx)
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x66FFFFFF),
            content,
            mask
        )
    }
}
