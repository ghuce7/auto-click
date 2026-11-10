package com.personal.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.personal.autoclicker.data.AppSettings
import com.personal.autoclicker.data.PresetStore
import com.personal.autoclicker.model.Preset
import com.personal.autoclicker.model.StepData
import com.personal.autoclicker.model.StepDefaults
import com.personal.autoclicker.model.StepType
import com.personal.autoclicker.overlay.ConnectorView
import com.personal.autoclicker.overlay.FloatingControlBar
import com.personal.autoclicker.overlay.OverlayDialog
import com.personal.autoclicker.overlay.OverlayOrigin
import com.personal.autoclicker.overlay.OverlayUtils
import com.personal.autoclicker.overlay.TargetView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Dịch vụ Trợ năng: vẽ giao diện nổi (thanh điều khiển + các mục tiêu) và thực thi
 * kịch bản bằng GestureDescription / dispatchGesture.
 */
class AutoClickService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var uiContext: Context
    private lateinit var appSettings: AppSettings
    private lateinit var presetStore: PresetStore
    private lateinit var dialog: OverlayDialog

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playJob: Job? = null

    private val steps = mutableListOf<ScenarioStep>()
    private val origin = OverlayOrigin()

    private var connectorView: ConnectorView? = null
    private var controlBar: FloatingControlBar? = null

    private var overlayVisible = false
    private var isPlaying = false
    private var lastScreen = Point(0, 0)

    private var snapshotSteps: List<StepData> = emptyList()
    private var snapshotScreen = Point(0, 0)

    private val screenBounds: () -> Point = { OverlayUtils.screenSize(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        uiContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        appSettings = AppSettings(this)
        presetStore = PresetStore(this)
        dialog = OverlayDialog(uiContext, windowManager, screenBounds)
        instance = this
        notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Dịch vụ chỉ dùng để dispatchGesture, không cần xử lý sự kiện trợ năng.
    }

    override fun onInterrupt() {
        stopPlayback()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayVisible) {
            handler.postDelayed({ handleScreenChanged() }, SCREEN_CHANGE_DELAY_MS)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideOverlay()
        instance = null
        notifyStateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        instance = null
        notifyStateChanged()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Hiển thị / ẩn giao diện nổi
    // ------------------------------------------------------------------

    val isOverlayVisible: Boolean
        get() = overlayVisible

    fun toggleOverlay() {
        if (overlayVisible) hideOverlay() else showOverlay()
    }

    fun showOverlay() {
        if (overlayVisible) return
        if (!Settings.canDrawOverlays(this)) {
            toast("Chưa cấp quyền Hiển thị trên ứng dụng khác.")
            return
        }
        try {
            val connector = ConnectorView(uiContext) { connectedPairs() }
            windowManager.addView(
                connector,
                OverlayUtils.createParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    touchable = false
                )
            )
            connectorView = connector

            val bar = FloatingControlBar(uiContext, windowManager, screenBounds, barActions)
            controlBar = bar
            bar.show()
            bar.setPlaying(false)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể hiển thị giao diện nổi", e)
            toast("Không thể hiển thị giao diện nổi.")
            teardownViews()
            return
        }

        overlayVisible = true
        lastScreen = screenBounds()
        calibrateOrigin {
            restoreSnapshot()
        }
        notifyStateChanged()
    }

    fun hideOverlay() {
        if (!overlayVisible && connectorView == null && controlBar == null) return
        stopPlayback()
        if (steps.isNotEmpty()) {
            snapshotSteps = steps.map { it.toData() }
            snapshotScreen = screenBounds()
        }
        teardownViews()
        overlayVisible = false
        notifyStateChanged()
    }

    private fun teardownViews() {
        if (::dialog.isInitialized) dialog.dismiss()
        steps.forEach { step -> step.views().forEach { it.detach() } }
        steps.clear()
        controlBar?.hide()
        controlBar = null
        connectorView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Lớp phủ đã được gỡ trước đó", e)
            }
        }
        connectorView = null
    }

    /**
     * Đo gốc tọa độ thật của cửa sổ overlay bằng getLocationOnScreen() sau khi
     * lớp phủ toàn màn hình được vẽ, rồi mới đặt các mục tiêu.
     */
    private fun calibrateOrigin(onDone: () -> Unit) {
        val connector = connectorView ?: return
        connector.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (connector.viewTreeObserver.isAlive) {
                    connector.viewTreeObserver.removeOnPreDrawListener(this)
                }
                val location = IntArray(2)
                connector.getLocationOnScreen(location)
                origin.x = location[0]
                origin.y = location[1]
                if (overlayVisible) {
                    onDone()
                }
                return true
            }
        })
        connector.invalidate()
    }

    private fun handleScreenChanged() {
        if (!overlayVisible) return
        val connector = connectorView ?: return
        val oldScreen = lastScreen
        connector.requestLayout()
        calibrateOrigin {
            val newScreen = screenBounds()
            val scaleX = if (oldScreen.x > 0) newScreen.x.toFloat() / oldScreen.x else 1f
            val scaleY = if (oldScreen.y > 0) newScreen.y.toFloat() / oldScreen.y else 1f
            steps.forEach { step ->
                step.views().forEach { view ->
                    view.moveCenterTo(
                        (view.centerX * scaleX).roundToInt(),
                        (view.centerY * scaleY).roundToInt()
                    )
                }
            }
            lastScreen = newScreen
            controlBar?.onScreenChanged()
            connectorView?.invalidate()
        }
    }

    private fun restoreSnapshot() {
        if (snapshotSteps.isEmpty()) return
        val screen = screenBounds()
        val scaleX = if (snapshotScreen.x > 0) screen.x.toFloat() / snapshotScreen.x else 1f
        val scaleY = if (snapshotScreen.y > 0) screen.y.toFloat() / snapshotScreen.y else 1f
        buildStepsFromData(snapshotSteps, scaleX, scaleY)
    }

    // ------------------------------------------------------------------
    // Quản lý mục tiêu
    // ------------------------------------------------------------------

    private fun connectedPairs(): List<Pair<TargetView, TargetView>> =
        steps.mapNotNull { step -> step.end?.let { end -> step.start to end } }

    private fun createTarget(role: TargetView.Role, cx: Int, cy: Int): TargetView {
        val view = TargetView(uiContext, windowManager, origin, screenBounds, role)
        view.onMoved = { connectorView?.invalidate() }
        view.onTapped = { tapped -> handleTargetTapped(tapped) }
        view.moveCenterTo(cx, cy)
        view.attach()
        return view
    }

    private fun addStep(data: StepData): ScenarioStep {
        val step = if (data.type == StepType.SWIPE) {
            val start = createTarget(TargetView.Role.SWIPE_START, data.x.roundToInt(), data.y.roundToInt())
            val end = createTarget(TargetView.Role.SWIPE_END, data.endX.roundToInt(), data.endY.roundToInt())
            ScenarioStep(StepType.SWIPE, start, end, data.delayMs, data.durationMs)
        } else {
            val start = createTarget(TargetView.Role.TAP, data.x.roundToInt(), data.y.roundToInt())
            ScenarioStep(StepType.TAP, start, null, data.delayMs, data.durationMs)
        }
        steps.add(step)
        renumber()
        connectorView?.invalidate()
        return step
    }

    private fun buildStepsFromData(list: List<StepData>, scaleX: Float, scaleY: Float) {
        list.forEach { data ->
            addStep(
                data.copy(
                    x = data.x * scaleX,
                    y = data.y * scaleY,
                    endX = data.endX * scaleX,
                    endY = data.endY * scaleY
                )
            )
        }
    }

    private fun renumber() {
        steps.forEachIndexed { index, step ->
            val number = index + 1
            if (step.type == StepType.SWIPE) {
                step.start.label = "${number}A"
                step.end?.label = "${number}B"
            } else {
                step.start.label = number.toString()
            }
        }
    }

    private fun clearSteps() {
        steps.forEach { step -> step.views().forEach { it.detach() } }
        steps.clear()
        connectorView?.invalidate()
    }

    private fun removeStep(step: ScenarioStep) {
        if (!steps.remove(step)) return
        step.views().forEach { it.detach() }
        renumber()
        connectorView?.invalidate()
    }

    private fun staggerOffset(): Int =
        (steps.size % STAGGER_COUNT) * OverlayUtils.dp(this, 20)

    private fun addTapAtCenter() {
        val screen = screenBounds()
        val offset = staggerOffset()
        addStep(
            StepData(
                type = StepType.TAP,
                x = screen.x / 2f + offset,
                y = screen.y / 2f + offset,
                delayMs = StepDefaults.DELAY_MS,
                durationMs = StepDefaults.TAP_DURATION_MS
            )
        )
    }

    private fun addSwipeAtCenter() {
        val screen = screenBounds()
        val offset = staggerOffset()
        val half = OverlayUtils.dp(this, 90).toFloat()
        addStep(
            StepData(
                type = StepType.SWIPE,
                x = screen.x / 2f - half,
                y = screen.y / 2f + offset,
                endX = screen.x / 2f + half,
                endY = screen.y / 2f + offset,
                delayMs = StepDefaults.DELAY_MS,
                durationMs = StepDefaults.SWIPE_DURATION_MS
            )
        )
    }

    private fun handleTargetTapped(view: TargetView) {
        if (isPlaying || dialog.isShowing) return
        val index = steps.indexOfFirst { it.owns(view) }
        if (index < 0) return
        val step = steps[index]
        dialog.showStepConfig(
            index = index + 1,
            type = step.type,
            delayMs = step.delayMs,
            durationMs = step.durationMs,
            onSave = { newDelay, newDuration ->
                step.delayMs = newDelay
                step.durationMs = newDuration
                toast("Đã lưu cấu hình điểm #${index + 1}")
            },
            onDelete = {
                removeStep(step)
                toast("Đã xóa điểm #${index + 1}")
            }
        )
    }

    // ------------------------------------------------------------------
    // Thanh điều khiển nổi
    // ------------------------------------------------------------------

    private val barActions = object : FloatingControlBar.Actions {
        override fun onPlayPause() {
            if (isPlaying) stopPlayback() else startPlayback()
        }

        override fun onAddTap() {
            if (blockedWhilePlaying()) return
            addTapAtCenter()
        }

        override fun onAddSwipe() {
            if (blockedWhilePlaying()) return
            addSwipeAtCenter()
        }

        override fun onRemoveLast() {
            if (blockedWhilePlaying()) return
            val last = steps.lastOrNull()
            if (last == null) {
                toast("Chưa có mục tiêu nào để xóa.")
            } else {
                removeStep(last)
            }
        }

        override fun onPresets() {
            if (blockedWhilePlaying()) return
            showPresetDialog()
        }

        override fun onClose() {
            hideOverlay()
        }
    }

    private fun blockedWhilePlaying(): Boolean {
        if (isPlaying) {
            toast("Hãy bấm Dừng (Pause) trước khi chỉnh sửa.")
            return true
        }
        return false
    }

    private fun showPresetDialog() {
        if (dialog.isShowing) return
        dialog.showPresetDialog(
            presets = presetStore.getAll(),
            onSave = { name -> saveCurrentAsPreset(name) },
            onLoad = { preset -> loadPreset(preset) }
        )
    }

    private fun saveCurrentAsPreset(name: String): Boolean {
        if (steps.isEmpty()) {
            toast("Chưa có mục tiêu nào để lưu.")
            return false
        }
        val screen = screenBounds()
        presetStore.save(
            Preset(
                name = name,
                steps = steps.map { it.toData() },
                screenWidth = screen.x,
                screenHeight = screen.y,
                savedAt = System.currentTimeMillis()
            )
        )
        toast("Đã lưu kịch bản \"$name\"")
        return true
    }

    private fun loadPreset(preset: Preset) {
        val screen = screenBounds()
        val scaleX = if (preset.screenWidth > 0) screen.x.toFloat() / preset.screenWidth else 1f
        val scaleY = if (preset.screenHeight > 0) screen.y.toFloat() / preset.screenHeight else 1f
        clearSteps()
        buildStepsFromData(preset.steps, scaleX, scaleY)
        toast("Đã tải kịch bản \"${preset.name}\" (${preset.steps.size} bước)")
    }

    // ------------------------------------------------------------------
    // Thực thi kịch bản
    // ------------------------------------------------------------------

    private fun startPlayback() {
        if (isPlaying) return
        if (steps.isEmpty()) {
            toast("Hãy thêm ít nhất một mục tiêu trước khi chạy.")
            return
        }
        dialog.dismiss()
        isPlaying = true
        steps.forEach { step -> step.views().forEach { it.setLocked(true) } }
        controlBar?.setPlaying(true)

        playJob = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi chạy kịch bản", e)
                toast("Kịch bản dừng do lỗi: ${e.message}")
                stopPlayback()
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        if (!isPlaying) return
        isPlaying = false
        steps.forEach { step -> step.views().forEach { it.setLocked(false) } }
        controlBar?.setPlaying(false)
    }

    private suspend fun runLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val snapshot = steps.toList()
            if (snapshot.isEmpty()) {
                stopPlayback()
                return
            }
            for (step in snapshot) {
                currentCoroutineContext().ensureActive()
                runStep(step)
            }
        }
    }

    private suspend fun runStep(step: ScenarioStep) {
        val randomize = appSettings.antiDetection

        var waitMs = step.delayMs
        if (randomize) {
            waitMs += Random.nextLong(-JITTER_DELAY_MS, JITTER_DELAY_MS + 1)
        }
        waitMs = waitMs.coerceAtLeast(0L)
        if (waitMs > 0L) delay(waitMs)

        val screen = screenBounds()
        val startPoint = step.start.liveCenter()
        var x1 = startPoint.x
        var y1 = startPoint.y
        var x2 = x1
        var y2 = y1

        if (step.type == StepType.SWIPE) {
            val endPoint = step.end?.liveCenter()
            if (endPoint != null) {
                x2 = endPoint.x
                y2 = endPoint.y
            }
        }

        if (randomize) {
            x1 += jitterPx()
            y1 += jitterPx()
            if (step.type == StepType.SWIPE) {
                x2 += jitterPx()
                y2 += jitterPx()
            }
        }

        x1 = x1.coerceIn(0f, (screen.x - 1).toFloat())
        y1 = y1.coerceIn(0f, (screen.y - 1).toFloat())
        x2 = x2.coerceIn(0f, (screen.x - 1).toFloat())
        y2 = y2.coerceIn(0f, (screen.y - 1).toFloat())

        val maxDuration = GestureDescription.getMaxGestureDuration()
        val duration = step.durationMs.coerceIn(1L, maxDuration)

        val path = Path().apply {
            moveTo(x1, y1)
            if (step.type == StepType.SWIPE) {
                lineTo(x2, y2)
            }
        }

        val success = dispatchStroke(path, duration)
        if (!success) {
            delay(FAILED_GESTURE_BACKOFF_MS)
        }
    }

    private fun jitterPx(): Float =
        Random.nextInt(-JITTER_PX, JITTER_PX + 1).toFloat()

    private suspend fun dispatchStroke(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val accepted = dispatchGesture(gesture, callback, null)
            if (!accepted && continuation.isActive) {
                continuation.resume(false)
            }
        }

    // ------------------------------------------------------------------
    // Tiện ích
    // ------------------------------------------------------------------

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun notifyStateChanged() {
        handler.post { onStateChanged?.invoke() }
    }

    companion object {
        private const val TAG = "AutoClickService"
        private const val JITTER_PX = 6
        private const val JITTER_DELAY_MS = 15L
        private const val FAILED_GESTURE_BACKOFF_MS = 100L
        private const val SCREEN_CHANGE_DELAY_MS = 350L
        private const val STAGGER_COUNT = 6

        @Volatile
        var instance: AutoClickService? = null
            private set

        /** Được MainActivity gán để cập nhật nút Bật/Tắt khi trạng thái giao diện nổi thay đổi. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null
    }
}
