package com.personal.autoclicker.service

import com.personal.autoclicker.model.StepData
import com.personal.autoclicker.model.StepDefaults
import com.personal.autoclicker.model.StepType
import com.personal.autoclicker.overlay.TargetView

/**
 * Một bước trong kịch bản: chạm/giữ (1 mục tiêu) hoặc vuốt (2 mục tiêu A -> B).
 */
class ScenarioStep(
    val type: StepType,
    val start: TargetView,
    val end: TargetView?,
    var delayMs: Long = StepDefaults.DELAY_MS,
    var durationMs: Long = if (type == StepType.SWIPE) {
        StepDefaults.SWIPE_DURATION_MS
    } else {
        StepDefaults.TAP_DURATION_MS
    }
) {

    fun owns(view: TargetView): Boolean = start === view || end === view

    fun views(): List<TargetView> = listOfNotNull(start, end)

    fun toData(): StepData {
        val startPoint = start.liveCenter()
        val endPoint = end?.liveCenter()
        return StepData(
            type = type,
            x = startPoint.x,
            y = startPoint.y,
            endX = endPoint?.x ?: startPoint.x,
            endY = endPoint?.y ?: startPoint.y,
            delayMs = delayMs,
            durationMs = durationMs
        )
    }
}
