package dev.herdwatch.data

/**
 * What the person is actually looking at right now.
 *
 * The alert rule needs one distinction the repository cannot make on its own: an agent that blocks
 * while its page is open and visible has already been seen, so a notification would only repeat
 * what is on screen. Kept as a tiny process-wide latch because [AttentionAlerts] runs in the
 * keep-alive service while the value is written by the ViewModel.
 */
object AppPresence {
    @Volatile
    var foreground: Boolean = false

    /** `hostId:paneId` of the detail page on screen, if any. */
    @Volatile
    var detailKey: String? = null

    /** True when the person is looking at this agent's own page with the app in front. */
    fun watching(agentKey: String): Boolean = foreground && detailKey == agentKey

    fun clear() {
        foreground = false
        detailKey = null
    }
}
