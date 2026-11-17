package dev.herdwatch

import dev.herdwatch.domain.OperationState
import dev.herdwatch.domain.operationRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationRpcTest {
    @Test fun `cancellation returned in Result is rethrown never accepted`() = runTest {
        var accepted = false
        try {
            operationRpc(request = { Result.failure<Unit>(CancellationException("cancelled RPC")) }) {
                accepted = true
            }
            fail("must throw cancellation")
        } catch (_: CancellationException) {
            assertFalse(accepted)
        }
    }

    @Test fun `accepted RPC reconciles draft even when underlying code swallowed cancellation`() = runTest {
        val state = OperationState()
        state.setDraft("a", "submitted")
        val captured = state.draft("a")
        var reachedAfter = false
        val job = launch {
            operationRpc(request = {
                currentCoroutineContext().cancel()
                Result.success(Unit)
            }) { state.clearDraft("a", captured) }
            reachedAfter = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(reachedAfter)
        assertEquals("", state.draft("a").text)
    }

    @Test fun `switching away and back cannot acquire same agent while RPC suspended`() = runTest {
        val state = OperationState()
        val reply = CompletableDeferred<Unit>()
        val record = state.begin("a")!!
        val job = async {
            try {
                operationRpc(request = { reply.await(); Result.success(Unit) }) {}
            } finally {
                state.finish("a", record.id, "done", false)
            }
        }
        runCurrent()
        assertNotNull(state.begin("b"))
        assertNull(state.begin("a"))
        reply.complete(Unit)
        job.await()
        assertNotNull(state.begin("a"))
    }
}
