package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide session is what stops one voice service instance from starting over resources
 * another instance is still releasing.
 *
 * Every guard inside the service belongs to that instance — its stop-in-flight job, its start state,
 * its shutdown barrier — while the engine, the ASR backends and the intent router are singletons. A
 * second instance therefore passes all of its own guards and would call `start` on an engine whose
 * previous session has not finished, or use a backend whose native recognizer was already freed.
 * These controls pin the admission rule at the seam the service actually uses.
 */
class VoiceSessionAdmissionControlTest {

    @Test
    fun `a second instance cannot take the session while the first holds it`() = runBlocking {
        val gate = VoiceSessionGate()
        val first = gate.acquire(timeoutMs = 1_000)
        assertTrue("the first instance must be admitted", first is VoiceSessionGate.Admission.Acquired)

        val second = gate.acquire(timeoutMs = 50)

        assertTrue(
            "a second instance must be refused while the first holds the session: its own guards " +
                "cannot see the first instance's state, so nothing else would stop it starting over " +
                "resources still in use",
            second is VoiceSessionGate.Admission.TimedOut,
        )
    }

    @Test
    fun `a teardown holds the session until its releases finish, then the next instance is admitted`() = runBlocking {
        val gate = VoiceSessionGate()
        val held = (gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired).session

        // Teardown marks the window before it cancels anything, and gives the session up only once
        // the completion job has finished -- including the releases dispatched off the service scope.
        gate.beginRelease(held)
        assertTrue(
            "no start may be admitted while the releases are running",
            gate.acquire(timeoutMs = 50) is VoiceSessionGate.Admission.TimedOut,
        )

        gate.finishRelease(held)

        assertTrue(
            "once every release has finished the next instance must be admitted, or voice would " +
                "stay unavailable after a normal stop",
            gate.acquire(timeoutMs = 1_000) is VoiceSessionGate.Admission.Acquired,
        )
    }

    @Test
    fun `a start abandoned before any resource is touched leaves the session available`() = runBlocking {
        val gate = VoiceSessionGate()
        val held = (gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired).session

        // A refusal after admission but before the pipeline runs must not strand the session.
        gate.release(held)

        assertTrue(
            "an abandoned start must leave the session available",
            gate.acquire(timeoutMs = 1_000) is VoiceSessionGate.Admission.Acquired,
        )
    }

    @Test
    fun `the wait for a previous release is bounded, so a stuck teardown cannot block voice forever`() = runBlocking {
        val gate = VoiceSessionGate()
        val held = (gate.acquire(timeoutMs = 1_000) as VoiceSessionGate.Admission.Acquired).session
        gate.beginRelease(held) // never finishes: a release that could not complete

        val startedAt = System.nanoTime()
        val admission = gate.acquire(timeoutMs = 80)
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("a stuck release must be reported, not waited on forever", admission is VoiceSessionGate.Admission.TimedOut)
        assertTrue("the wait must respect its bound, waited ${waitedMs}ms", waitedMs < 2_000)
        assertEquals(
            "a refused start must not disturb the holder",
            true,
            gate.acquire(timeoutMs = 20) is VoiceSessionGate.Admission.TimedOut,
        )
    }
}
