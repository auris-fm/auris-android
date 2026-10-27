package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.queue

import org.junit.rules.ExternalResource

/**
 * Isolates the PROCESS-WIDE source-slot bound (reviewer 4e8592ba) between tests.
 *
 * `ContributionStore` bounds open audio descriptors across the WHOLE process, not per instance, because
 * every open descriptor anywhere retains device bytes. That is the right production shape - one process,
 * one owner - but it makes any test that holds a descriptor open by design (several do, to prove
 * unlink/retention/localisation behaviour) starve every test that runs after it in the same JVM. Without
 * isolation those later tests fail with "the request started expected:<1> but was:<0>", which points at
 * the victim rather than at the holder.
 *
 * Resetting between tests keeps the bound honest where it matters (production) and stops one test's
 * deliberate hold from failing another's. It deliberately does NOT weaken the bound inside a test: a
 * single test still observes the real limit, which is what `the source-slot bound is PROCESS-WIDE` proves.
 */
class ProcessWideSourceSlotsRule : ExternalResource() {
    override fun before() {
        ContributionStore.resetSourceSlotsForTest()
    }

    override fun after() {
        ContributionStore.resetSourceSlotsForTest()
    }
}
