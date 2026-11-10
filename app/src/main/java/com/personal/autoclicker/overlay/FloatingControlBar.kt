package com.personal.autoclicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/**
 * Thanh điều khiển nổi: kéo thả được ở bất kỳ vị trí nào trên thanh,
 * có thể thu gọn thành 1 icon duy nhất.
 */
class FloatingControlBar(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenSize: () -> Point,
    private val actions: Actions
) {

    interface Actions {
        fun onPlayPause()
        fun onAddTap()
        fun onAddSwipe()
        fun onRemoveLast()
        fun onPresets()
        fun onClose()
    }

    private val buttonSize = OverlayUtils.dp(context, 44)
    private val iconSize = OverlayUtils.dp(context, 46)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f

    private val barParams = OverlayUtils.createParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT
    )
    private val iconParams = OverlayUtils.createParams(iconSize, iconSize)

    private var barAdded = false
    private var iconAdded = false
    private var posX = OverlayUtils.dp(context, 16)
    private var posY = OverlayUtils.dp(context, 80)

    var isCollapsed: Boolean = false
        private set

    private val playButton: TextView
    private val barView: LinearLayout
    private val iconView: TextView

    init {
        playButton = makeButton("▶", "Chạy", COLOR_PLAY) { actions.onPlayPause() }

        val handle = TextView(context).apply {
            text = "✥"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(OverlayUtils.dp(context, 28), buttonSize)
        }
        attachDrag(handle, null)

        barView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = OverlayUtils.dp(context, 5)
            setPadding(pad, pad, pad, pad)
            background = OverlayUtils.roundedBackground(
                Color.argb(0xCC, 0x1B, 0x1F, 0x24),
                OverlayUtils.dpF(context, 14f),
                Color.argb(0x66, 255, 255, 255),
                OverlayUtils.dp(context, 1)
            )
            addView(handle)
            addView(playButton)
            addView(makeButton("+", "Chạm/Giữ", COLOR_ADD_TAP) { actions.onAddTap() })
            addView(makeButton("+", "Vuốt", COLOR_ADD_SWIPE) { actions.onAddSwipe() })
            addView(makeButton("⌫", "Xóa bớt", COLOR_REMOVE) { actions.onRemoveLast() })
            addView(makeButton("💾", "Lưu/Tải", COLOR_PRESET) { actions.onPresets() })
            addView(makeButton("▾", "Thu gọn", COLOR_COLLAPSE) { collapse() })
            addView(makeButton("✕", "Đóng", COLOR_CLOSE) { actions.onClose() })
        }

        iconView = TextView(context).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = OverlayUtils.roundedBackground(
                COLOR_ICON_IDLE,
                iconSize / 2f,
                Color.argb(0xAA, 255, 255, 255),
                OverlayUtils.dp(context, 2)
            )
        }
        attachDrag(iconView) { expand() }
    }

    private fun styledLabel(symbol: String, label: String): SpannableString {
        val spannable = SpannableString("$symbol\n$label")
        spannable.setSpan(
            RelativeSizeSpan(1.65f),
            0,
            symbol.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun makeButton(symbol: String, label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = styledLabel(symbol, label)
            setTextColor(Color.WHITE)
            textSize = 9f
            isAllCaps = false
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                marginStart = OverlayUtils.dp(context, 3)
            }
            background = OverlayUtils.rippleBackground(color, OverlayUtils.dpF(context, 10f))
            attachDrag(this, onClick)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, onClick: (() -> Unit)?) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = posX
                    startY = posY
                    dragging = false
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        v.isPressed = false
                    }
                    if (dragging) moveTo(startX + dx.toInt(), startY + dy.toInt())
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!dragging) {
                        v.performClick()
                        onClick?.invoke()
                    }
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun currentView(): View = if (isCollapsed) iconView else barView

    private fun moveTo(x: Int, y: Int) {
        val bounds = screenSize()
        val view = currentView()
        val viewWidth = if (view.width > 0) view.width else if (isCollapsed) iconSize else buttonSize
        val viewHeight = if (view.height > 0) view.height else if (isCollapsed) iconSize else buttonSize
        posX = x.coerceIn(0, max(0, bounds.x - viewWidth))
        posY = y.coerceIn(0, max(0, bounds.y - viewHeight))
        applyPosition()
    }

    private fun applyPosition() {
        barParams.x = posX
        barParams.y = posY
        iconParams.x = posX
        iconParams.y = posY
        if (isCollapsed && iconAdded) {
            try {
                windowManager.updateViewLayout(iconView, iconParams)
            } catch (e: IllegalArgumentException) {
                // View không còn gắn trên WindowManager.
            }
        } else if (!isCollapsed && barAdded) {
            try {
                windowManager.updateViewLayout(barView, barParams)
            } catch (e: IllegalArgumentException) {
                // View không còn gắn trên WindowManager.
            }
        }
    }

    fun show() {
        applyPosition()
        if (isCollapsed) {
            addIcon()
        } else {
            addBar()
        }
    }

    fun hide() {
        removeBar()
        removeIcon()
    }

    fun collapse() {
        if (isCollapsed) return
        removeBar()
        isCollapsed = true
        applyPosition()
        addIcon()
    }

    fun expand() {
        if (!isCollapsed) return
        removeIcon()
        isCollapsed = false
        applyPosition()
        addBar()
        barView.post { moveTo(posX, posY) }
    }

    fun setPlaying(value: Boolean) {
        playButton.text = if (value) styledLabel("❚❚", "Dừng") else styledLabel("▶", "Chạy")
        playButton.background = OverlayUtils.rippleBackground(
            if (value) COLOR_PAUSE else COLOR_PLAY,
            OverlayUtils.dpF(context, 10f)
        )
        iconView.background = OverlayUtils.roundedBackground(
            if (value) COLOR_ICON_PLAYING else COLOR_ICON_IDLE,
            iconSize / 2f,
            Color.argb(0xAA, 255, 255, 255),
            OverlayUtils.dp(context, 2)
        )
        iconView.text = if (value) "▶" else "☰"
    }

    /** Giữ thanh điều khiển nằm trong màn hình sau khi xoay ngang/dọc. */
    fun onScreenChanged() {
        moveTo(posX, posY)
    }

    private fun addBar() {
        if (barAdded) return
        OverlayUtils.addOverlayViewSafely(windowManager, barView, barParams)
        barAdded = true
    }

    private fun addIcon() {
        if (iconAdded) return
        OverlayUtils.addOverlayViewSafely(windowManager, iconView, iconParams)
        iconAdded = true
    }

    private fun removeBar() {
        if (!barAdded) return
        barAdded = false
        try {
            windowManager.removeViewImmediate(barView)
        } catch (e: IllegalArgumentException) {
            barAdded = false
        }
    }

    private fun removeIcon() {
        if (!iconAdded) return
        iconAdded = false
        try {
            windowManager.removeViewImmediate(iconView)
        } catch (e: IllegalArgumentException) {
            iconAdded = false
        }
    }

    companion object {
        private val COLOR_PLAY = Color.argb(0xCC, 0x2E, 0x7D, 0x32)
        private val COLOR_PAUSE = Color.argb(0xCC, 0xEF, 0x6C, 0x00)
        private val COLOR_ADD_TAP = Color.argb(0xCC, 0x15, 0x65, 0xC0)
        private val COLOR_ADD_SWIPE = Color.argb(0xCC, 0x6A, 0x1B, 0x9A)
        private val COLOR_REMOVE = Color.argb(0xCC, 0xC6, 0x28, 0x28)
        private val COLOR_PRESET = Color.argb(0xCC, 0x00, 0x83, 0x8F)
        private val COLOR_COLLAPSE = Color.argb(0xCC, 0x54, 0x6E, 0x7A)
        private val COLOR_CLOSE = Color.argb(0xCC, 0x4E, 0x34, 0x2E)
        private val COLOR_ICON_IDLE = Color.argb(0xCC, 0x1B, 0x1F, 0x24)
        private val COLOR_ICON_PLAYING = Color.argb(0xCC, 0x2E, 0x7D, 0x32)
    }
}
