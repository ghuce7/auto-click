package com.personal.autoclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
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

    /**
     * Độ mờ mặc định cho cửa sổ nổi. Giữ dưới ngưỡng 0.8 của Android 12+ kể cả khi
     * nhiều lớp phủ chồng lên nhau để không bao giờ bị chặn cảm ứng (Untrusted Touch).
     */
    const val OVERLAY_ALPHA_ACTIVE = 0.75f
    const val OVERLAY_ALPHA_PASS_THROUGH = 0.45f

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
     * Tạo LayoutParams cho cửa sổ nổi.
     * Ưu tiên dùng TYPE_ACCESSIBILITY_OVERLAY vì dịch vụ chạy trong AccessibilityService
     * (được hệ thống Android tin cậy, không chặn cảm ứng trên Android 12+), và tự động
     * fallback sang TYPE_APPLICATION_OVERLAY nếu cần.
     */
    fun createParams(
        width: Int,
        height: Int,
        touchable: Boolean = true,
        useAccessibilityOverlay: Boolean = true
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val windowType = if (useAccessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
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
                alpha = if (touchable) OVERLAY_ALPHA_ACTIVE else OVERLAY_ALPHA_PASS_THROUGH
            }
        }
    }

    /**
     * Thêm View vào WindowManager với TYPE_ACCESSIBILITY_OVERLAY, nếu ROM từ chối
     * thì tự động chuyển sang TYPE_APPLICATION_OVERLAY.
     */
    fun addOverlayViewSafely(
        windowManager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            windowManager.addView(view, params)
        }
    }

    fun roundedBackground(
        color: Int,
        radiusPx: Float,
        strokeColor: Int = 0,
        strokePx: Int = 0
    ): GradientDrawable =
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
