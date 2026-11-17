package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
}