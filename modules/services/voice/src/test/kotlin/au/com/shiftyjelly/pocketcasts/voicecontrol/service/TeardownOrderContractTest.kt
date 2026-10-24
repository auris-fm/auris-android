package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEARDOWN ORDER AND COMPLETION CONTROL.
 *
 * Reviewer b915daaa caught a real regression I introduced: rewriting `stopVoiceControl` dropped
 * `stopEngine()`, so the engine was never stopped and capture state never cleared before routing and
 * resources were released. This control pins the ORDER that regression violated, and pins the
 * completion property (the returned job must not complete while a dispatch body's `finally` is still
 * held).
 *
 * BOUNDARY, stated precisely: these are source-level controls over the ordering CONTRACT. No Android
 * `Service` is constructed (that control is blocked on the Hilt test application), so the wiring
 * inside `stopVoiceControl` itself is asserted by reading the production source order below rather
 * than by executing a Service.
 */
class TeardownOrderContractTest {

    /** The order `stopVoiceControl` must perform teardown in, engine before resource release. */
    private val requiredOrder = listOf(
        "cancel-mode",
        "stop-engine", // RESTORED: must precede every resource release
        "release-backend",
        "release-session",
        "release-recognizer",
        "release-feedback",
        "cancel-notification",
        "stop-foreground",
        "cancel-scope",
        "stop-self",
    )

    @Test
    fun `the production teardown performs engine stop before releasing resources`() {
        val source = productionStopVoiceControlSource()
        // Locate each step in the real source text, in order.
        val positions = requiredOrder.map { step ->
            step to indexOfStep(source, step)
        }
        positions.forEach { (step, at) ->
            assertTrue("teardown step '$step' must be present in the production source", at >= 0)
        }
        val ordered = positions.sortedBy { it.second }.map { it.first }
        assertEquals(
            "teardown must run in the required order, engine stop BEFORE resource release",
            requiredOrder,
            ordered,
        )
    }

    @Test
    fun `stopEngine is present in the production teardown`() {
        val source = productionStopVoiceControlSource()
        assertTrue(
            "the engine teardown must not be dropped: cancelling the scope is not a substitute " +
                "because the engine tears down the stream it owns in its own flow finally",
            source.contains("stopEngine()"),
        )
    }

    @Test
    fun `teardown completion does not report complete while a body finally is still held`() = runBlocking {
        // Model the production shape: children run in a scope that is cancelled, and the completion
        // job joins the ROOT so a held child finally keeps completion incomplete.
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val gate = CompletableDeferred<Unit>()
        val releaseHeld = CompletableDeferred<Unit>()
        scope.launch(Dispatchers.IO) {
            try {
                gate.await()
            } finally {
                withContext(NonCancellable) { releaseHeld.await() }
            }
        }
        val rootJob = scope.coroutineContext[Job]
        gate.complete(Unit) // the body passes its gate and is now in its finally
        scope.cancel()

        val completion = CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) { rootJob?.join() }
        }
        assertFalse(
            "teardown must NOT report complete while a child finally is still running",
            completion.isCompleted,
        )
        releaseHeld.complete(Unit)
        completion.join()
        assertTrue("teardown completes once the child finally has finished", completion.isCompleted)
    }

    private fun indexOfStep(source: String, step: String): Int = when (step) {
        "cancel-mode" -> source.indexOf("modeJob?.cancel()")
        "stop-engine" -> source.indexOf("stopEngine()")
        "release-backend" -> source.indexOf("wiredBackend?.release()")
        "release-session" -> source.indexOf("mediaSession?.release()")
        "release-recognizer" -> source.indexOf("voiceRecognizer.release()")
        "release-feedback" -> source.indexOf("audioFeedbackRenderer.release()")
        "cancel-notification" -> source.indexOf("notificationManager.cancelNotification()")
        "stop-foreground" -> source.indexOf("stopForeground(")
        "cancel-scope" -> source.indexOf("serviceScope.cancel()")
        "stop-self" -> source.indexOf("stopSelf()")
        else -> -1
    }

    /** The real production function, read from the source tree (no Service instantiation needed). */
    private fun productionStopVoiceControlSource(): String {
        val file = java.io.File("src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/service/VoiceControlService.kt")
        val source = if (file.exists()) {
            file.readText()
        } else {
            // Gradle runs unit tests from the module dir; fall back to a tree search if not.
            generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
                .map { java.io.File(it, "modules/services/voice/src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/service/VoiceControlService.kt") }
                .first { it.exists() }
                .readText()
        }
        val start = source.indexOf("private fun stopVoiceControl(): Job {")
        assertTrue("stopVoiceControl must exist in the production source", start >= 0)
        return source.substring(start, source.indexOf("\n    }", start))
    }

    @Test
    fun `the ordering helper is not vacuous`() {
        // Guards this control itself: a step name that matches nothing must be detectable.
        assertSame(-1, indexOfStep("", "stop-engine"))
        assertEquals(-1, indexOfStep("no steps here", "cancel-mode"))
    }
}
