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

    /**
     * Where a teardown step appears in the production source.
     *
     * THE RELEASE STEPS MOVED (reviewer a9271b39): the initial teardown used to release inline, which
     * bypassed the per-kind tracker the retry uses, so a retry repeated releases that had already
     * happened. Releases now live in `releaseResources` and are reached from the teardown. Order is
     * therefore asserted across the TEARDOWN and that helper: the helper must be called from inside
     * the teardown, and its body sits after the join in the same file. `teardownReleaseAt` expresses
     * that so the ordering claims stay meaningful instead of silently matching -1.
     */
    private fun indexOfStep(source: String, step: String): Int = when (step) {
        "cancel-mode" -> source.indexOf("modeJob?.cancel()")

        "stop-engine" -> source.indexOf("stopEngine()")

        "release-backend", "release-session", "release-recognizer", "release-feedback",
        "cancel-notification", "stop-foreground",
        -> teardownReleaseAt(source, step)

        "cancel-scope" -> source.indexOf("serviceScope.cancel()")

        "join-root" -> source.indexOf("rootJob?.join()")

        "stop-self" -> source.indexOf("stopSelf()")

        else -> -1
    }

    /** Release steps are dispatched by kind, so locate the KIND the teardown asks for. */
    private fun teardownReleaseAt(source: String, step: String): Int {
        val kind = when (step) {
            "release-backend" -> "\"backend\""
            "release-session" -> "\"session\""
            "release-recognizer" -> "\"recognizer\""
            "release-feedback" -> "\"feedback\""
            "cancel-notification" -> "\"notification\""
            "stop-foreground" -> "\"foreground\""
            else -> return -1
        }
        // Releases are dispatched by kind through ONE call inside the teardown. Ordering against
        // the join is therefore a property of that call site, not of each kind's definition, which
        // sits further down the file. Returning the call site keeps the ordering claim honest;
        // `releaseOrder` separately asserts that every kind really is dispatched, in order.
        assertTrue("kind $kind must be dispatched by releaseResources", estimatedPresence(kind))
        return source.indexOf("releaseResources(TeardownRetry.pendingReleases(ALL_RELEASES")
    }

    /** The production file, for the helper-based release assertions below. */
    private fun estimatedPresence(kind: String): Boolean = productionFileSource().contains("$kind ->")

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
        val start = source.indexOf("private fun stopVoiceControl(): Deferred<TeardownResult> {")
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
        // Releases are dispatched from releaseResources; the MAIN confinement is the
        // withContext(Dispatchers.Main) around that call, asserted below.
        val releases = listOf("releaseResources(TeardownRetry.pendingReleases(ALL_RELEASES")
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
    fun `engine work is not released before the service children finish`() {
        // The re-sampling join itself is now EngineQuiescence's job and is proven behaviourally in
        // EngineQuiescenceControlTest. Retained here: engine quiescence is consulted only AFTER the
        // service root has been joined, so a child cannot add work behind the decision.
        val source = productionStopVoiceControlSource()
        val rootJoinAt = source.indexOf("rootJob?.join()")
        val settleAt = source.indexOf("EngineQuiescence.settle(")
        assertTrue("the root join must be present", rootJoinAt >= 0)
        assertTrue("engine quiescence must be consulted", settleAt >= 0)
        assertTrue(
            "quiescence must follow the root join (settle at $settleAt, root join at $rootJoinAt)",
            settleAt > rootJoinAt,
        )
    }

    @Test
    fun `shutdown invalidates a pending start before doing anything else`() {
        // Reviewer 543d284b: a start suspended inside ensureReady() can commit after teardown
        // released the backend it needs, so teardown must invalidate it up front.
        val source = productionStopVoiceControlSource()
        val invalidateAt = source.indexOf("engineStart.invalidatePendingStart()")
        assertTrue("teardown must invalidate a pending start", invalidateAt >= 0)
        val cancelAt = source.indexOf("serviceScope.cancel()")
        val rootCaptureAt = source.indexOf("val rootJob =")
        assertTrue(
            "invalidation must happen before the cancel and the root capture " +
                "(invalidate at $invalidateAt, cancel at $cancelAt, root at $rootCaptureAt)",
            invalidateAt < cancelAt && (rootCaptureAt < 0 || invalidateAt < rootCaptureAt),
        )
    }

    @Test
    fun `the production predicate receives the recorded outcome and the expected-cancellation set`() {
        // Reviewer 51baa020: the helper was proven only in isolation. If production never PASSES the
        // recorded cause, the classification it enables is dead code. This pins the wiring.
        val source = productionFileSource()
        assertTrue(
            "recordedOutcome must reach the predicate",
            source.contains("recordedCause = engine.recordedOutcome(job)"),
        )
        assertTrue(
            "expected cancellation must reach the predicate",
            source.contains("expectedCancellation = engine.cancelledByThisTeardown"),
        )
        assertTrue(
            "the pruner must actually be called, not just defined",
            source.contains("pruneConsumedBookkeeping("),
        )
    }

    @Test
    fun `each release is recorded as it confirms, not batched afterwards`() {
        // Reviewer 9f873b94: the retry path added the whole batch AFTER the release loop. A throw
        // halfway through therefore lost the record of kinds that HAD already released, and the
        // retry released them again — a repeated media-session or renderer release is destructive.
        // The record must be written per kind, immediately after that kind confirms.
        val source = productionFileSource()
        val body = source.substringAfter("private fun releaseResources(pending: List<String>) {")
            .substringBefore("\n    }")
        assertTrue(
            "releaseResources must record each kind as it confirms",
            body.contains("releasedResources.add(kind)"),
        )
        assertTrue(
            "the retry must not re-add a whole batch it did not necessarily complete",
            !source.contains("releasedResources.addAll(pending)"),
        )
    }

    @Test
    fun `the stop path decides its retry from the deferred's own record, not a side field`() {
        // Reviewer 9d15bb44 follow-up: reading the separate `teardownFailure` field raced the
        // completion handler that writes it. A re-stop could see isCompleted == true while the field
        // still held its previous value, and treat a HELD teardown as a finished, non-retryable one.
        // The record must come FROM the completed Deferred, so completion and outcome are one
        // observation.
        val source = productionStopVoiceControlSource()
        val reuseAt = source.indexOf("stopInFlightJob?.let { existing ->")
        assertTrue("the stop path must reuse a running teardown", reuseAt >= 0)
        val branch = source.substring(reuseAt, source.indexOf("return retryHeldTeardown(", reuseAt))
        assertTrue(
            "the retry decision must consume the completed Deferred's record",
            branch.contains("settleRecord(existing)"),
        )
        assertFalse(
            "the retry decision must not read the side field, which can lag the completion",
            branch.contains("teardownFailure"),
        )
    }

    @Test
    fun `a retry is created under the lock and only while its source is still current`() {
        // Reviewer 9d15bb44 follow-up: two concurrent stops could each decide to retry and let the
        // SLOWER attempt publish last, so an older attempt's outcome overwrote a newer one and
        // resurrected a hold that had already been resolved. Creation must be serialized (under a
        // lock) and must refuse to start once its source teardown has been replaced.
        val source = productionFileSource()
        val retryStart = source.indexOf("private fun retryHeldTeardown(")
        assertTrue("retryHeldTeardown must exist", retryStart >= 0)
        val retry = source.substring(retryStart, source.indexOf("\n    }\n", retryStart))
        assertTrue(
            "creating a retry must be serialized, not left to concurrent callers",
            retry.contains("synchronized(retryLock)"),
        )
        assertTrue(
            "a retry must refuse to start if its source teardown is no longer the current one",
            retry.contains("stopInFlightJob !== previousSource"),
        )
        // Publication must also be inside the lock, or the check above is merely advisory.
        val guardAt = retry.indexOf("stopInFlightJob !== previousSource")
        val publishAt = retry.indexOf("stopInFlightJob = attempt")
        assertTrue("the retry must publish itself as the current teardown", publishAt >= 0)
        assertTrue(
            "publication must happen after the currency check it depends on",
            guardAt < publishAt,
        )
    }

    @Test
    fun `a start registers its barrier release only AFTER admission succeeds`() {
        // Reviewer 9d15bb44 follow-up: the completion callback was installed BEFORE beginStart, so a
        // job whose parent scope was already cancelled could complete (and release) before it was
        // ever registered; beginStart would then register a job nothing would clear, and the teardown
        // would wait on that stale registration. Release must exist only for an ADMITTED job.
        val source = productionStartSource()
        val admitAt = source.indexOf("shutdownBarrier.beginStart(listOf(startJob))")
        val releaseAt = source.indexOf("shutdownBarrier.release(listOf(startJob))")
        assertTrue("the start must be admitted to the barrier", admitAt >= 0)
        assertTrue("an admitted start must be released when it completes", releaseAt >= 0)
        assertTrue(
            "the release must be installed AFTER admission, or a pre-admission completion " +
                "releases a job that was never registered",
            admitAt < releaseAt,
        )
        // The already-completed case must be handled explicitly, not left to a callback that fired.
        assertTrue(
            "an already-completed job must be released directly",
            source.contains("if (startJob.isCompleted) {"),
        )
    }

    @Test
    fun `an unproven barrier join holds resources instead of releasing them`() {
        // Reviewer 9d15bb44 follow-up: the barrier-owned join used to time out with a log line and
        // then carry on toward the releases, so a job the barrier owned could still be running while
        // the backend was freed underneath it. Fail CLOSED: an unproven join must return Held.
        val source = productionStopVoiceControlSource()
        val joinAt = source.indexOf("withTimeout(BARRIER_JOIN_TIMEOUT_MS) { owned.join() }")
        assertTrue("the barrier-owned join must be bounded", joinAt >= 0)
        // SCOPE THE SEARCH TO THE JOIN'S OWN BLOCK. Searching the whole remainder of the file would
        // find a `Held(` return belonging to a LATER, unrelated block and pass no matter what this
        // one does — a position that cannot be false. The block ends at the closing brace of the
        // `forEach` that contains the join.
        val afterJoin = source.substring(joinAt)
        val blockEnd = afterJoin.indexOf("\n                }\n")
        assertTrue("the join must sit inside a block", blockEnd > 0)
        val block = afterJoin.substring(0, blockEnd)
        assertTrue(
            "an unproven join must return Held from its own block, not merely somewhere later",
            block.contains("return@withContext TeardownResult.Held("),
        )
        assertTrue(
            "the Held return must be the join's failure branch, not a bare log line",
            block.contains("JoinFailed("),
        )
        val heldAt = afterJoin.indexOf("return@withContext TeardownResult.Held(")
        val releaseAt = afterJoin.indexOf("releaseResources(TeardownRetry.pendingReleases(ALL_RELEASES")
        assertTrue(
            "the Held return must come BEFORE any release, or the hold does not fail closed",
            releaseAt < 0 || heldAt < releaseAt,
        )
    }

    @Test
    fun `the start path actually ADMITS its job to the shutdown barrier`() {
        // Reviewer 9f873b94, confirmed by grep: `beginStart` had no production caller at all. The
        // barrier only ever CLOSED, so `close()` always returned an empty set and the owned-job join
        // below looped over nothing. Every control on the barrier itself still passed, because they
        // construct it directly — a class whose admission half nothing calls is not evidence.
        //
        // This pins the production wiring, which is the only thing that can make those controls mean
        // something: the start must be ADMITTED, and a refused admission must abort the start.
        val source = productionFileSource()
        val admitAt = source.indexOf("shutdownBarrier.beginStart(listOf(startJob))")
        assertTrue("the start path must admit its job to the barrier", admitAt > 0)
        assertTrue(
            "the admitted job must be dropped once it finishes, or the set grows forever",
            source.contains("startJob.invokeOnCompletion { shutdownBarrier.release(listOf(startJob)) }"),
        )
        // A refused admission means a shutdown closed the barrier while this start was being built.
        // Proceeding would start work the teardown will never wait for.
        assertTrue(
            "a refused admission must abort the start rather than proceed",
            source.contains("if (!shutdownBarrier.beginStart(listOf(startJob))) {"),
        )
    }

    @Test
    fun `the teardown closes the barrier before cancelling and joins what it owned`() {
        // Reviewers 902e0c1a1/cce1f100/9045dd43: a snapshot-then-cancel leaves a start that lands in
        // between owned by nobody. The barrier must be CLOSED (not sampled) and its returned set
        // joined before any release. Source-order because the barrier's behaviour is controlled
        // separately in ShutdownBarrierControlTest.
        val source = productionStopVoiceControlSource()
        val closeAt = source.indexOf("shutdownBarrier.close()")
        val cancelAt = source.indexOf("serviceScope.cancel()")
        val joinAt = source.indexOf("ownedByShutdown.forEach")
        // The RELEASE call of this teardown, not the helper defined further down the file.
        val releaseAt = source.indexOf("releaseResources(TeardownRetry.pendingReleases(ALL_RELEASES")
        assertTrue("the barrier must be closed, got no close() in the teardown", closeAt >= 0)
        assertTrue("the barrier must close BEFORE the scope is cancelled", closeAt < cancelAt)
        assertTrue("the barrier-owned jobs must be joined", joinAt > 0)
        assertTrue("barrier-owned jobs must be joined BEFORE releases run", joinAt < releaseAt)
    }

    @Test
    fun `the platform stop path observes the teardown outcome instead of discarding it`() {
        // Reviewer 47a3a267: the seam is the ONE stop authority. Calling lifecycle.stop() and
        // dropping the Deferred leaves a Held teardown invisible at the only place that can act on
        // it. (A mutant that discards it survived every other control, which is why this exists.)
        val source = productionFileSource()
        assertTrue(
            "the platform stop path must observe the teardown result",
            source.contains("observeTeardown(lifecycle.stop())"),
        )
        assertTrue(
            "the observation must distinguish Held from Released",
            source.contains("is TeardownResult.Held ->") && source.contains("failure = settled.failure"),
        )
        // ONE OWNER OF THE OUTCOME (reviewer 9f873b94). Two independent launchers each awaited the
        // same Deferred and then wrote `teardownFailure`, so they raced: a re-stop could observe a
        // completed Deferred while the field still held its previous value, and a HELD teardown read
        // as successful and non-retryable. Both entries must route through the one recorder.
        assertEquals(
            "every teardown outcome must be recorded by exactly one owner",
            4,
            Regex("recordTeardownOutcome\\(").findAll(source).count(),
            // declaration + stop path + observeTeardown + retry path
        )
        // Judge CODE, not prose: comments in this file explain the very field being counted.
        val code = source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertEquals(
            "exactly ONE place may ASSIGN teardownFailure — the recorder (the declaration is not a write)",
            1,
            Regex("teardownFailure = [^(]").findAll(code).count(),
        )
    }

    @Test
    fun `a teardown that holds resources reports a Held result and never throws`() {
        // Reviewers 6ce4ad78 + 3104ba44: a resources-held teardown must not look like a clean one,
        // but throwing from this unrouted launch would be an UNCAUGHT exception and could take the
        // process down. The outcome must be RETURNED, and a throwing release must be reported too.
        val source = productionStopVoiceControlSource()
        assertTrue(
            "a non-Settled verdict must return Held rather than throw",
            source.contains("return@withContext TeardownResult.Held(") &&
                !source.contains("throw IllegalStateException(\"voice teardown did not release"),
        )
        assertTrue(
            "the teardown must be a Deferred so its outcome is observable",
            productionFileSource().contains("private fun stopVoiceControl(): Deferred<TeardownResult>"),
        )
        assertTrue(
            "a throwing release must be reported as Held, not escape the launch",
            source.contains("TeardownResult.Held(TeardownFailure(EngineQuiescence.Verdict.Residual(remaining = 0, error = error)))"),
        )
    }

    @Test
    fun `the teardown delegates quiescence to EngineQuiescence after the root join`() {
        // The concrete stop/join/fail-closed behaviour moved into EngineQuiescence and is proven by
        // EngineQuiescenceControlTest (behavioural, with failure and exhaustion mutants). What this
        // source-level control still pins is the ORDER: quiescence runs after the root join, and the
        // service releases only on a Settled verdict.
        val source = productionStopVoiceControlSource()
        val rootJoinAt = source.indexOf("rootJob?.join()")
        val settleAt = source.indexOf("EngineQuiescence.settle(")
        // The releases are dispatched through releaseResources now, so the teardown-side position is
        // that call; the kinds themselves live further down the file.
        val releaseAt = source.indexOf("releaseResources(TeardownRetry.pendingReleases(ALL_RELEASES")
        assertTrue("the root join must be present", rootJoinAt >= 0)
        assertTrue("quiescence must be consulted", settleAt >= 0)
        assertTrue("the releases must be dispatched from the teardown", releaseAt >= 0)
        assertTrue("quiescence must run after the root join", settleAt > rootJoinAt)
        assertTrue("the releases must come after quiescence", releaseAt > settleAt)
        assertTrue(
            "release must be gated on a Settled verdict",
            source.contains("verdict != EngineQuiescence.Verdict.Settled"),
        )
    }

    @Test
    fun `a start rechecks shutdown after the readiness suspension`() {
        // The pre-suspension guard proves nothing about a teardown that begins during ensureReady().
        val source = productionFileSource()
        val engineFn = source.indexOf("private suspend fun startEngine(mode: ListeningMode): Boolean {")
        assertTrue("startEngine must exist", engineFn >= 0)
        val body = source.substring(engineFn, source.indexOf("\n    }", engineFn))
        val readyAt = body.indexOf("ensureReady()")
        val recheckAt = body.indexOf("stopInFlightJob != null", body.indexOf("isCurrent(generation)"))
        assertTrue("the readiness call must be present", readyAt >= 0)
        assertTrue(
            "shutdown must be rechecked AFTER ensureReady() suspends",
            recheckAt > readyAt,
        )
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
