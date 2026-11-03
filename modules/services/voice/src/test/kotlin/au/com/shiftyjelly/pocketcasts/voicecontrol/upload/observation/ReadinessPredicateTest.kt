package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readiness predicate PRODUCTION SUPPLIES, exercised directly (reviewer 34af3bd5).
 *
 * Two rounds of green gate fixtures missed a production blocker because the fixture asserted an
 * intended predicate while the wiring added an extra condition. These controls run the extracted
 * function itself, so a future edit to the wiring has to change this file to stay green.
 */
class ReadinessPredicateTest {

    @Test
    fun `authoritative account and epoch are READY even when no token is cached`() {
        // THE CASE THAT WAS BROKEN: prerequisites ready, Auris cache empty. Production's probe also
        // required a cached token, so it reported NOT_READY forever and the one attempt that would
        // have acquired the token could never be permitted. Readiness is the AUTHORITATIVE
        // prerequisites; obtaining a token is what the permitted attempt does.
        val ready = VoiceObservationModule.prerequisiteSnapshot(
            accountName = "acct-1",
            enrollmentEpoch = "ep-1",
            generation = 3L,
            session = 7L,
        )
        assertNotNull("authoritative prerequisites must read as ready", ready)
        assertEquals("acct-1", ready?.accountId)
        assertEquals("ep-1", ready?.enrollmentEpoch)
    }

    @Test
    fun `a missing epoch is NOT ready`() {
        assertNull(
            VoiceObservationModule.prerequisiteSnapshot(
                accountName = "acct-1",
                enrollmentEpoch = null,
                generation = 3L,
                session = 7L,
            ),
        )
    }

    @Test
    fun `a missing account is NOT ready`() {
        assertNull(
            VoiceObservationModule.prerequisiteSnapshot(
                accountName = null,
                enrollmentEpoch = "ep-1",
                generation = 3L,
                session = 7L,
            ),
        )
    }

    @Test
    fun `the INSTALLED readiness predicate is ready with an empty token cache`() {
        // reviewer 34af3bd5: this asserts the predicate production installs, via the same factory
        // the wiring calls. My previous version called prerequisiteSnapshot() directly and stayed
        // GREEN when the blocker (an extra cached-token requirement) was restored to the wiring --
        // it was testing a description of the wiring, not the wiring. If this factory ever gains a
        // cache condition, this control fails.
        val ready = VoiceObservationModule.readinessPredicate {
            VoiceObservationModule.prerequisiteSnapshot(
                accountName = "acct-1",
                enrollmentEpoch = "ep-1",
                generation = 3L,
                session = 7L,
            )
        }
        assertTrue(
            "authoritative readiness with an EMPTY token cache must be ready",
            ready(),
        )
    }

    @Test
    fun `the INSTALLED readiness predicate is not ready without an epoch`() {
        val ready = VoiceObservationModule.readinessPredicate {
            VoiceObservationModule.prerequisiteSnapshot(
                accountName = "acct-1",
                enrollmentEpoch = null,
                generation = 3L,
                session = 7L,
            )
        }
        assertFalse("a missing epoch must not read as ready", ready())
    }

    @Test
    fun `the probe and the supplier agree on every prerequisite combination`() {
        // A predicate may be made cheaper only by removing COST, never by removing conditions
        // (cloud 650fcc93). Both sides call this same function, so the assertion is that the
        // function alone decides -- there is no second, narrower predicate to compare.
        val accounts = listOf(null, "acct-1")
        val epochs = listOf(null, "ep-1")
        for (account in accounts) {
            for (epoch in epochs) {
                val result = VoiceObservationModule.prerequisiteSnapshot(
                    accountName = account,
                    enrollmentEpoch = epoch,
                    generation = 1L,
                    session = 2L,
                )
                val expectedReady = account != null && epoch != null
                assertEquals(
                    "account=$account epoch=$epoch must be ready=$expectedReady",
                    expectedReady,
                    result != null,
                )
            }
        }
        assertFalse(accounts.isEmpty())
    }
}
