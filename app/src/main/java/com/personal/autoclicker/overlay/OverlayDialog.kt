package com.personal.autoclicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.personal.autoclicker.model.Preset
import com.personal.autoclicker.model.StepDefaults
import com.personal.autoclicker.model.StepType
import kotlin.math.min

/**
 * Dialog nổi (TYPE_APPLICATION_OVERLAY). Khác với thanh điều khiển, dialog cần nhận
 * bàn phím để nhập số/tên nên không dùng FLAG_NOT_FOCUSABLE.
 */
class OverlayDialog(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenSize: () -> Point
) {

    private var root: View? = null

    val isShowing: Boolean
        get() = root != null

    fun dismiss() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            root = null
        }
    }

    /** Popup cấu hình riêng cho từng điểm. */
    fun showStepConfig(
        index: Int,
        type: StepType,
        delayMs: Long,
        durationMs: Long,
        onSave: (delayMs: Long, durationMs: Long) -> Unit,
        onDelete: () -> Unit
    ) {
        val card = createCard()
        val maxDuration = android.accessibilityservice.GestureDescription.getMaxGestureDuration()
            .coerceAtMost(StepDefaults.MAX_DURATION_MS)

        val titleText = if (type == StepType.SWIPE) {
            "Điểm #$index · Vuốt (A → B)"
        } else {
            "Điểm #$index · Chạm / Giữ"
        }
        card.addView(createTitle(titleText))

        card.addView(createLabel("Delay trước khi chạy (ms)"))
        val delayInput = createInput(InputType.TYPE_CLASS_NUMBER, delayMs.toString(), "300")
        card.addView(delayInput)

        val durationLabel = if (type == StepType.SWIPE) {
            "Thời gian Vuốt (ms)"
        } else {
            "Thời gian Nhấn Giữ (ms)"
        }
        card.addView(createLabel(durationLabel))
        val durationInput = createInput(
            InputType.TYPE_CLASS_NUMBER,
            durationMs.toString(),
            if (type == StepType.SWIPE) "300" else "50"
        )
        card.addView(durationInput)

        val hint = if (type == StepType.SWIPE) {
            "Mặc định ${StepDefaults.SWIPE_DURATION_MS}ms: tốc độ vuốt từ A sang B."
        } else {
            "Mặc định ${StepDefaults.TAP_DURATION_MS}ms = chạm nhanh. Nhập 1000 - 5000ms để nhấn giữ (Long Press)."
        }
        card.addView(createHint(hint))

        val buttonRow = createButtonRow()
        buttonRow.addView(createButton("Lưu", COLOR_POSITIVE) {
            val newDelay = delayInput.text.toString().trim().toLongOrNull()
            val newDuration = durationInput.text.toString().trim().toLongOrNull()
            var valid = true
            if (newDelay == null || newDelay < 0 || newDelay > StepDefaults.MAX_DELAY_MS) {
                delayInput.error = "0 - ${StepDefaults.MAX_DELAY_MS}"
                valid = false
            }
            if (newDuration == null || newDuration < 1 || newDuration > maxDuration) {
                durationInput.error = "1 - $maxDuration"
                valid = false
            }
            if (valid && newDelay != null && newDuration != null) {
                dismiss()
                onSave(newDelay, newDuration)
            }
        })
        buttonRow.addView(createButton("Xóa điểm này", COLOR_DANGER) {
            dismiss()
            onDelete()
        })
        buttonRow.addView(createButton("Hủy", COLOR_NEUTRAL) { dismiss() })
        card.addView(buttonRow)

        present(card)
    }

    /** Dialog Lưu / Tải kịch bản. */
    fun showPresetDialog(
        presets: List<Preset>,
        onSave: (name: String) -> Boolean,
        onLoad: (Preset) -> Unit
    ) {
        val card = createCard()
        card.addView(createTitle("Lưu / Tải kịch bản"))

        card.addView(createLabel("Tên kịch bản"))
        val nameInput = createInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "", "VD: Farm rừng")
        card.addView(nameInput)

        val saveRow = createButtonRow()
        saveRow.addView(createButton("Lưu cấu hình hiện tại", COLOR_POSITIVE) {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = "Hãy nhập tên"
            } else if (onSave(name)) {
                dismiss()
            }
        })
        card.addView(saveRow)

        card.addView(createLabel("Kịch bản đã lưu (chạm để tải)").apply {
            setPadding(0, OverlayUtils.dp(context, 12), 0, OverlayUtils.dp(context, 4))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (presets.isEmpty()) {
            list.addView(createHint("Chưa có kịch bản nào được lưu."))
        } else {
            presets.forEach { preset ->
                list.addView(createListRow("${preset.name}  ·  ${preset.steps.size} bước") {
                    dismiss()
                    onLoad(preset)
                })
            }
        }
        val scroll = ScrollView(context).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                min(OverlayUtils.dp(context, 150), screenSize().y / 3)
            )
        }
        card.addView(scroll)

        val closeRow = createButtonRow()
        closeRow.addView(createButton("Đóng", COLOR_NEUTRAL) { dismiss() })
        card.addView(closeRow)

        present(card)
    }

    private fun present(card: View) {
        dismiss()
        val bounds = screenSize()
        val width = min(OverlayUtils.dp(context, 340), bounds.x - OverlayUtils.dp(context, 24))

        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        OverlayUtils.addOverlayViewSafely(windowManager, container, params)
        root = container
    }

    private fun createCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = OverlayUtils.dp(context, 16)
        setPadding(pad, pad, pad, pad)
        background = OverlayUtils.roundedBackground(
            Color.argb(0xF2, 0x21, 0x25, 0x2B),
            OverlayUtils.dpF(context, 16f),
            Color.argb(0x55, 255, 255, 255),
            OverlayUtils.dp(context, 1)
        )
    }

    private fun createTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, OverlayUtils.dp(context, 8))
    }

    private fun createLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.argb(0xDD, 255, 255, 255))
        textSize = 13f
        setPadding(0, OverlayUtils.dp(context, 6), 0, OverlayUtils.dp(context, 2))
    }

    private fun createHint(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.argb(0x99, 255, 255, 255))
        textSize = 11.5f
        setPadding(0, OverlayUtils.dp(context, 4), 0, OverlayUtils.dp(context, 4))
    }

    private fun createInput(inputType: Int, value: String, hint: String): EditText =
        EditText(context).apply {
            this.inputType = inputType
            setText(value)
            this.hint = hint
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(0x66, 255, 255, 255))
            textSize = 15f
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
            val pad = OverlayUtils.dp(context, 10)
            setPadding(pad, pad, pad, pad)
            background = OverlayUtils.roundedBackground(
                Color.argb(0x33, 255, 255, 255),
                OverlayUtils.dpF(context, 8f)
            )
        }

    private fun createButtonRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, OverlayUtils.dp(context, 10), 0, 0)
    }

    private fun createButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padV = OverlayUtils.dp(context, 10)
            setPadding(OverlayUtils.dp(context, 6), padV, OverlayUtils.dp(context, 6), padV)
            background = OverlayUtils.rippleBackground(color, OverlayUtils.dpF(context, 8f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = OverlayUtils.dp(context, 3)
                marginEnd = OverlayUtils.dp(context, 3)
            }
            setOnClickListener { onClick() }
        }

    private fun createListRow(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            val pad = OverlayUtils.dp(context, 10)
            setPadding(pad, pad, pad, pad)
            background = OverlayUtils.rippleBackground(
                Color.argb(0x26, 255, 255, 255),
                OverlayUtils.dpF(context, 8f)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = OverlayUtils.dp(context, 4) }
            setOnClickListener { onClick() }
        }

    companion object {
        private val COLOR_POSITIVE = Color.argb(0xEE, 0x2E, 0x7D, 0x32)
        private val COLOR_DANGER = Color.argb(0xEE, 0xC6, 0x28, 0x28)
        private val COLOR_NEUTRAL = Color.argb(0xEE, 0x54, 0x6E, 0x7A)
    }
}
