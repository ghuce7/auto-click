package com.personal.autoclicker.model

enum class StepType {
    TAP,
    SWIPE
}

object StepDefaults {
    const val DELAY_MS: Long = 300L
    const val TAP_DURATION_MS: Long = 50L
    const val SWIPE_DURATION_MS: Long = 300L
    const val MAX_DELAY_MS: Long = 600_000L
    const val MAX_DURATION_MS: Long = 60_000L
}

data class StepData(
    val type: StepType = StepType.TAP,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val delayMs: Long = StepDefaults.DELAY_MS,
    val durationMs: Long = StepDefaults.TAP_DURATION_MS
)

data class Preset(
    val name: String = "",
    val steps: List<StepData> = emptyList(),
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val savedAt: Long = 0L
)
