package dev.herdwatch.ui

/** Session-only reading bookmark; offsets are clamped when the snapshot changes. */
data class ReadingPosition(
    val pageOffset: Int = 0,
    val paneOffset: Int = 0,
    val following: Boolean = true,
    val paused: Boolean = false,
)
