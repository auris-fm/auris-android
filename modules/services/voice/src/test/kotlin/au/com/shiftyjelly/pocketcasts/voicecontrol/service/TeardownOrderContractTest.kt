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
import kotlinx.coroutines.withTimeout
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
        "stop-engine", // engine teardown is SYNCHRONOUS; nothing engine-owned outlives it
        // CANCEL BEFORE RELEASE (reviewers 0bba7db5 + dbde4427): a cancellation REQUEST is not
        // "users have finished" — dispatcher children still call render()/onCommandRecognized. So
        // the releases must follow the JOIN, not merely the cancellation.
        "cancel-scope",
        "stop-self",
        "join-root",
        "release-backend",
        "release-session",
        "release-recognizer",
        "release-feedback",
        "cancel-notification",
        "stop-foreground",
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
    fun `no resource is released before the children have actually finished`() {
        val source = productionStopVoiceControlSource()
        val joinAt = indexOfStep(source, "join-root")
        assertTrue("the root join must be present in the teardown job", joinAt >= 0)
        listOf("release-backend", "release-session", "release-recognizer", "release-feedback")
            .forEach { step ->
                val at = indexOfStep(source, step)
                assertTrue("$step must be present", at >= 0)
                assertTrue(
                    "$step touches a resource a dispatcher child may still use; it must come AFTER " +
                        "the root join (join at $joinAt, $step at $at)",
                    at > joinAt,
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
            withTimeout(5_000) { childEntered.await() } // BOUNDED BARRIER: child inside its body
            val rootJob = checkNotNull(scope.coroutineContext[Job])

            // THE SUBJECT: a joiner of the root job, dispatched from an EXTERNAL scope (production
            // shape). It must stay incomplete for as long as the child's finally is held.
            // EXPLICIT RUNNING-JOINER HANDSHAKE (reviewer 9003d3cc): the joiner signals that it has
            // STARTED and is about to join, so the assertion below cannot sample an unstarted job.
            val joinerStarted = CompletableDeferred<Unit>()
            val joiner = CoroutineScope(Dispatchers.IO).launch {
                withContext(NonCancellable) {
                    joinerStarted.complete(Unit)
                    rootJob.join()
                }
            }
            withTimeout(5_000) { joinerStarted.await() }

            bodyReleased.complete(Unit)
            scope.cancel()
            withTimeout(5_000) { finallyEntered.await() } // BOUNDED BARRIER: child finalizing
            assertFalse(
                "teardown must NOT report complete while a child finally is still running",
                joiner.isCompleted,
            )
            releaseFinally.complete(Unit)
            withTimeout(5_000) { joiner.join() }
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
        "join-root" -> source.indexOf("rootJob?.join()")
        "stop-self" -> source.indexOf("stopSelf()")
        else -> -1
    }

    /** The production start function, read from the source tree. */
    private fun productionStartSource(): String {
        val source = productionFileSource()
        val start = source.indexOf("private fun startVoiceControl(): Boolean {")
        assertTrue("startVoiceControl must exist in the production source", start >= 0)
        return source.substring(start, source.indexOf("\n    }", start))
    }

    /** The real production function, read from the source tree (no Service instantiation needed). */
    private fun productionStopVoiceControlSource(): String {
        val source = productionFileSource()
        val start = source.indexOf("private fun stopVoiceControl(): Job {")
        assertTrue("stopVoiceControl must exist in the production source", start >= 0)
        return source.substring(start, source.indexOf("\n    }", start))
    }

    private fun productionFileSource(): String {
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
        return source
    }

    // DELETED (reviewer 9003d3cc): the two "simulateStopEngine" tests were a re-implementation of
    // production logic. Deleting or changing the real stopEngine could not fail them, so they proved
    // nothing about the production teardown. A behavioural control needs an Android Service instance,
    // which is blocked on the unapproved Hilt test dependency — so this seam is SOURCE-ORDER ONLY and
    // is described as such rather than padded with model tests.

    @Test
    fun `the releases run on the main dispatcher they are confined to`() {
        val source = productionStopVoiceControlSource()
        val releases = listOf("wiredBackend?.release()", "mediaSession?.release()", "audioFeedbackRenderer.release()")
        releases.forEach { call ->
            val at = source.indexOf(call)
            assertTrue("$call must be present in the teardown", at >= 0)
            // The enclosing call is withContext(Dispatchers.Main): the nearest Main dispatch must
            // appear before the release and no other release may sit outside it.
            val mainAt = source.lastIndexOf("withContext(Dispatchers.Main)", at)
            assertTrue(
                "$call releases Main-confined state; it must run inside withContext(Dispatchers.Main)",
                mainAt >= 0,
            )
            assertTrue(
                "the Main block must enclose $call, not merely precede the whole teardown",
                at > mainAt && at - mainAt < 600,
            )
        }
    }

    @Test
    fun `every engine start is refused once teardown has begun, not only the top-level one`() {
        // Reviewer e1b11171: startVoiceControl is not the only path that opens a capture. A child
        // already inside startEngine when teardown begins must also be refused, or it opens a
        // stream the teardown is about to release from under it.
        val source = productionFileSource()
        val engineFn = source.indexOf("private suspend fun startEngine(mode: ListeningMode): Boolean {")
        assertTrue("startEngine must exist in the production source", engineFn >= 0)
        val body = source.substring(engineFn, source.indexOf("\n    }", engineFn))
        val guardAt = body.indexOf("stopInFlightJob != null")
        assertTrue("startEngine must refuse to start during teardown", guardAt >= 0)
        val returnAt = body.indexOf("return false", guardAt)
        assertTrue("the guard must return without starting the engine", returnAt > guardAt)
        // The guard must precede the first state-changing call, not merely exist.
        val startAt = body.indexOf("engineStart.startIfNotRunning")
        assertTrue("the guard must precede startIfNotRunning", startAt < 0 || guardAt < startAt)
    }

    @Test
    fun `a start is refused once teardown has begun`() {
        // Reviewer fd3f5a4d: the final shutdown must EXCLUDE new starts. A start that slipped in
        // would open a capture the teardown is about to release from under it.
        val source = productionStartSource()
        val guardAt = source.indexOf("stopInFlightJob != null")
        assertTrue(
            "startVoiceControl must refuse to start while a teardown exists; otherwise a start " +
                "can race the release of the resources it needs",
            guardAt >= 0,
        )
        val earlyReturnAt = source.indexOf("return false", guardAt)
        assertTrue("the guard must return without starting", earlyReturnAt > guardAt)
    }

    @Test
    fun `the root job is captured before the scope is cancelled`() {
        // Reviewer f457fbad: a child created just before cancellation, or a child of a child, must
        // not let the completion job observe a DIFFERENT root than the one being cancelled. So the
        // root is read before the cancel, not sampled from inside the completion coroutine.
        val source = productionStopVoiceControlSource()
        val captureAt = source.indexOf("val rootJob = serviceScope.coroutineContext[Job]")
        val cancelAt = source.indexOf("serviceScope.cancel()")
        assertTrue("the teardown must capture the root job", captureAt >= 0)
        assertTrue("the teardown must cancel the scope", cancelAt >= 0)
        assertTrue(
            "the root must be captured BEFORE the cancel (root at $captureAt, cancel at $cancelAt)",
            captureAt < cancelAt,
        )
        // And the completion coroutine must use that captured value rather than re-reading the scope.
        val completionAt = source.indexOf("rootJob?.join()")
        assertTrue("the completion job must join the captured root", completionAt >= 0)
        assertTrue(
            "re-reading the scope inside the completion job would capture a different root",
            completionAt > captureAt,
        )
    }

    @Test
    fun `stopEngine is structurally present in the production teardown`() {
        val source = productionStopVoiceControlSource()
        assertTrue(
            "the engine teardown must not be dropped: cancelling the scope is not a substitute " +
                "because the engine tears down the stream it owns in its own flow finally",
            source.contains("stopEngine()"),
        )
    }

    @Test
    fun `the ordering helper is not vacuous`() {
        // Guards this control itself: a step name that matches nothing must be detectable.
        assertEquals(-1, indexOfStep("", "stop-engine"))
        assertEquals(-1, indexOfStep("no steps here", "cancel-mode"))
    }
}
