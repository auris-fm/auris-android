package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controls for the process-wide voice session gate.
 *
 * The engine, the ASR backends and the intent router are singletons, while every guard against
 * starting during a teardown belongs to one service instance. A second instance therefore sees
 * clean guards and begins using shared resources the first instance is still releasing, and the
 * backend release frees a native recognizer. The rule these controls state is the one that closes
 * that: ONE voice session at a time over the shared resources, held until every release has
 * finished, including the asynchronous tail.
 *
 * The releases here are real suspending work with an explicit completion, so "still releasing" is a
 * fact about ordering rather than about timing. A release that never finishes is included because a
 * gate that waits forever would trade a use-after-release for a permanently dead feature.
 */
class VoiceSessionGateControlTest {
    @Test
    fun `a session is admitted when nothing is releasing`() = runBlocking {
        val gate = VoiceSessionGate()
        val admission = gate.acquire(timeoutMs = 1_000)
        assertTrue("an idle gate must admit a session", admission is VoiceSessionGate.Admission.Acquired)
        gate.release((admission as VoiceSessionGate.Admission.Acquired).session)
    }

    /**
     * THE DESTRUCTIVE CASE. A teardown has begun its releases and not finished them. A second
     * session must not be admitted into that window: the backend it would use is mid-release.
     */
    @Test
    fun `a session is not admitted while a previous session is still releasing`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        val releaseFinished = CompletableDeferred<Unit>()

        // The teardown takes ownership, then does its releases asynchronously -- the IO tail.
        gate.beginRelease(first.session)
        val releasing = async {
            releaseFinished.await()
            gate.finishRelease(first.session)
        }

        val second = gate.acquire(timeoutMs = 200)
        assertFalse(
            "a session must not be admitted while the previous one is still releasing",
            second is VoiceSessionGate.Admission.Acquired,
        )

        releaseFinished.complete(Unit)
        releasing.await()

        val third = gate.acquire(timeoutMs = 1_000)
        assertTrue(
            "once the release has finished the next session must be admitted",
            third is VoiceSessionGate.Admission.Acquired,
        )
        gate.release((third as VoiceSessionGate.Admission.Acquired).session)
    }

    /** The waiting start proceeds only AFTER the release completed, never alongside it. */
    @Test
    fun `a waiting session starts only after the release completes`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        gate.beginRelease(first.session)

        val order = mutableListOf<String>()
        val waiting = async {
            val admission = gate.acquire(timeoutMs = 5_000)
            order += "acquired"
            admission
        }

        order += "released"
        gate.finishRelease(first.session)
        val admission = withTimeout(5_000) { waiting.await() }

        assertTrue("the wait must succeed once released", admission is VoiceSessionGate.Admission.Acquired)
        assertEquals(
            "the release must be recorded before the next session is acquired",
            listOf("released", "acquired"),
            order,
        )
        gate.release((admission as VoiceSessionGate.Admission.Acquired).session)
    }

    /**
     * A STUCK RELEASE must not block every later start forever. The bound is reached, the refusal is
     * explicit and distinguishable from a plain "busy", and no session is handed out.
     */
    @Test
    fun `a stuck release refuses the next start at the bound instead of hanging`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        gate.beginRelease(first.session)
        // Deliberately never finished.

        val began = System.currentTimeMillis()
        val admission = gate.acquire(timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - began

        assertTrue(
            "the refusal must name the unfinished release, not look like ordinary busy",
            admission is VoiceSessionGate.Admission.TimedOut,
        )
        assertTrue("the wait must be bounded (took ${elapsed}ms)", elapsed < 5_000)
    }

    /** Two concurrent starts cannot both hold the session. */
    @Test
    fun `only one of two concurrent sessions is admitted`() = runBlocking {
        val gate = VoiceSessionGate()
        val a = async { gate.acquire(timeoutMs = 1_000) }
        val b = async { gate.acquire(timeoutMs = 1_000) }
        val admissions = listOf(a.await(), b.await())
        val acquired = admissions.count { it is VoiceSessionGate.Admission.Acquired }
        assertEquals("exactly one session may hold the shared resources", 1, acquired)
        admissions.filterIsInstance<VoiceSessionGate.Admission.Acquired>().forEach { gate.release(it.session) }
    }

    /**
     * A STALE session's late finish must not free the gate for the session that holds it now.
     *
     * This is the case identity exists for: the first teardown's tail can complete after a later
     * session was already admitted. Finishing by identity-free assignment would clear the CURRENT
     * holder and admit a second concurrent session over resources the current one is using.
     */
    @Test
    fun `a stale session finishing late does not release the current holder`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        gate.beginRelease(first.session)
        gate.finishRelease(first.session)

        // A new session now owns the shared resources.
        val second = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired

        // The FIRST session's tail arrives late, after it no longer holds anything.
        gate.finishRelease(first.session)

        val third = gate.acquire(timeoutMs = 200)
        assertFalse(
            "a stale finish must not free the gate while a newer session holds it",
            third is VoiceSessionGate.Admission.Acquired,
        )
        gate.release(second.session)
    }

    /** The same hazard on the abandon path: a stale release must not clear a newer holder. */
    @Test
    fun `a stale session abandoning late does not release the current holder`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        gate.release(first.session)

        val second = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
        gate.release(first.session)

        val third = gate.acquire(timeoutMs = 200)
        assertFalse(
            "a stale abandon must not free the gate while a newer session holds it",
            third is VoiceSessionGate.Admission.Acquired,
        )
        gate.release(second.session)
    }

    /**
     * A waiter cancelled at the moment it claims must not leave the session held by nobody.
     *
     * The claim and the return are separate steps: the claim happens under the lock inside a
     * timeout, and cancellation can land between them. The session would then have an owner that
     * never learns it owns anything, so no release ever comes and every later start is refused.
     */
    @Test
    fun `a waiter cancelled right after claiming leaves the gate free`() = runBlocking {
        val gate = VoiceSessionGate()
        // The claim happens on a DISPATCHER thread while the caller waits, so cancellation has to be
        // able to land after the claim and before the caller resumes. A plain yield lets the whole
        // acquire finish first -- measured: every waiter returned normally that way, so the race was
        // never exercised. Blocking the gate first forces each waiter to be parked in its poll, and
        // freeing it while cancelling makes the claim and the cancellation concurrent.
        repeat(100) {
            val blocker = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
            val waiter = launch(kotlinx.coroutines.Dispatchers.Default) {
                val admission = gate.acquire(timeoutMs = 5_000)
                if (admission is VoiceSessionGate.Admission.Acquired) {
                    gate.release(admission.session)
                }
            }
            // Let the waiter reach its poll, then free the gate and cancel in the same instant.
            delay(15)
            gate.release(blocker.session)
            waiter.cancel()
            waiter.join()
            // Whatever the outcome, nothing may remain held: the waiter either got the session and
            // gave it back, or never received it and must not keep it.
            val probe = gate.acquire(timeoutMs = 300)
            assertTrue(
                "iteration $it left the session stranded: $probe",
                probe is VoiceSessionGate.Admission.Acquired,
            )
            (probe as VoiceSessionGate.Admission.Acquired).let { p -> gate.release(p.session) }
        }

        val next = gate.acquire(timeoutMs = 500)
        assertTrue(
            "a cancelled waiter must not strand the session: got $next",
            next is VoiceSessionGate.Admission.Acquired,
        )
    }

    /** A timed-out waiter must not keep a session it claimed as the bound expired. */
    @Test
    fun `a waiter whose bound expires right after claiming leaves the gate free`() = runBlocking {
        val gate = VoiceSessionGate()
        // Repeat with the release timed near the bound, so some iterations claim just as the timeout
        // fires and others time out first. Both outcomes must leave nothing stranded: a claim that
        // expired is given back, and a waiter that never claimed holds nothing.
        repeat(40) {
            val blocker = gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired
            val waiter = async(kotlinx.coroutines.Dispatchers.Default) { gate.acquire(timeoutMs = 20) }
            delay(15)
            gate.release(blocker.session)
            val outcome = waiter.await()
            // A waiter that WAS handed a session owns it until it gives it back, exactly like any
            // caller; only a refusal must leave nothing behind.
            if (outcome is VoiceSessionGate.Admission.Acquired) gate.release(outcome.session)

            val next = gate.acquire(timeoutMs = 500)
            assertTrue(
                "iteration $it stranded the session (waiter outcome $outcome): $next",
                next is VoiceSessionGate.Admission.Acquired,
            )
            (next as VoiceSessionGate.Admission.Acquired).let { n -> gate.release(n.session) }
        }
    }
}
