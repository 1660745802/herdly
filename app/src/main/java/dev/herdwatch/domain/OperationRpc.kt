package dev.herdwatch.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Unwrap repository Result cancellation, and reconcile confirmed acceptance before checking it. */
suspend fun <T> operationRpc(
    request: suspend () -> Result<T>,
    onAccepted: (T) -> Unit,
): T {
    currentCoroutineContext().ensureActive()
    val accepted = request().getOrThrow()
    onAccepted(accepted)
    currentCoroutineContext().ensureActive()
    return accepted
}
