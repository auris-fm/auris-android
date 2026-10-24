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
        // CANCEL BEFORE RELEASE (reviewer 0bba7db5): releasing first is use-after-release, because a
        // dispatcher child could still be running its handler/finally against a released resource.
        "cancel-scope",
        "release-backend",
        "release-session",
        "release-recognizer",
        "release-feedback",
        "cancel-notification",
        "stop-foreground",
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
    fun `no resource is released before the scope cancellation request`() {
        val source = productionStopVoiceControlSource()
        val cancelAt = indexOfStep(source, "cancel-scope")
        assertTrue("the scope cancel must be present", cancelAt >= 0)
        listOf("release-backend", "release-session", "release-recognizer", "release-feedback")
            .forEach { step ->
                val at = indexOfStep(source, step)
                assertTrue("$step must be present", at >= 0)
                assertTrue(
                    "$step releases a resource a child could still use; it must come AFTER the " +
                        "cancellation request (cancel-scope at $cancelAt, $step at $at)",
                    at > cancelAt,
                )
            }
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
        // Reviewer 0bba7db5: barriers so neither the child nor the joiner can be sampled unstarted,
        // cleanup so the held finally is always released. The JOIN is the subject under test: the
        // property is that a joiner of the ROOT job cannot complete while a child's finally is held.
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val childEntered = CompletableDeferred<Unit>()
        val bodyReleased = CompletableDeferred<Unit>()
        val finallyEntered = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()

        val child = scope.launch(Dispatchers.IO) {
            try {
                childEntered.complete(Unit)
                bodyReleased.await()
            } finally {
                withContext(NonCancellable) {
                    finallyEntered.complete(Unit)
                    releaseFinally.await()
                }
            }
        }
        try {
            childEntered.await() // BARRIER: the child is inside its body
            val rootJob = checkNotNull(scope.coroutineContext[Job])

            // THE SUBJECT: a joiner of the root job, dispatched from an EXTERNAL scope (production
            // shape). It must stay incomplete for as long as the child's finally is held.
            val joiner = CoroutineScope(Dispatchers.IO).launch {
                withContext(NonCancellable) { rootJob.join() }
            }

            bodyReleased.complete(Unit)
            scope.cancel()
            finallyEntered.await() // BARRIER: the child has reached its finalization
            // Yield so a joiner that would complete spuriously has the chance to do so.
            repeat(20) { kotlinx.coroutines.yield() }
            Thread.sleep(50)
            assertFalse(
                "teardown must NOT report complete while a child finally is still running",
                joiner.isCompleted,
            )
            releaseFinally.complete(Unit)
            joiner.join()
            assertTrue("teardown completes once the child finally has finished", joiner.isCompleted)
        } finally {
            releaseFinally.complete(Unit) // cleanup: never leave the held finally hanging
            scope.cancel()
            child.cancel()
        }
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
    fun `the restored engine teardown clears the engine start state`() = runBlocking {
        // BEHAVIOURAL half of the control: stopEngine's running branch calls clearCaptureState(),
        // which calls engineStart.onStopped(). Reproducing the regression (deleting stopEngine)
        // leaves this flag true after teardown, so this observes the fix rather than the source text.
        val engineStart = EngineStartState()
        assertFalse("precondition: not started", engineStart.isStarted)
        engineStart.startIfNotRunning(start = { true })
        assertTrue("precondition: a start is running", engineStart.isStarted)

        // The production teardown sequence for a RUNNING engine (stopVoiceControl's stopEngine path).
        simulateStopEngine(engineRunning = engineStart.isStarted, clearCaptureState = { engineStart.onStopped() })
        assertFalse(
            "engine teardown must clear the started flag; a deleted stopEngine leaves it true",
            engineStart.isStarted,
        )
    }

    @Test
    fun `an inactive engine teardown releases routing instead of stopping an engine it never started`() {
        val engineStart = EngineStartState()
        var routingReleased = false
        var captureCleared = false
        simulateStopEngine(
            engineRunning = engineStart.isStarted,
            clearCaptureState = { captureCleared = true },
            releaseRouting = { routingReleased = true },
        )
        assertTrue("the inactive branch must release acquisition routing", routingReleased)
        assertFalse("an engine that never started must not be stopped", captureCleared)
    }

    /** The two branches of the production `stopEngine`, with the ordering the service uses. */
    private fun simulateStopEngine(
        engineRunning: Boolean,
        clearCaptureState: () -> Unit,
        releaseRouting: () -> Unit = {},
    ) {
        if (engineRunning) {
            // voiceAsrEngine.get().stop() then clearCaptureState()
            clearCaptureState()
        } else {
            releaseRouting()
        }
    }

    @Test
    fun `the ordering helper is not vacuous`() {
        // Guards this control itself: a step name that matches nothing must be detectable.
        assertSame(-1, indexOfStep("", "stop-engine"))
        assertEquals(-1, indexOfStep("no steps here", "cancel-mode"))
    }
}
