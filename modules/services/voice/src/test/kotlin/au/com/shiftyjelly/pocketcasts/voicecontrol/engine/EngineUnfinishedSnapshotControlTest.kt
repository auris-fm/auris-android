package au.com.shiftyjelly.pocketcasts.voicecontrol.engine

import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.audio.VoiceAudioProcessor
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.SpokenTemplateResolver
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal
import au.com.shiftyjelly.pocketcasts.voicecontrol.service.EngineQuiescence
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.AuthorityDecision
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CollectionGate
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.CompletionHandoff
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.ContributionRetentionSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.IssuedAuthority
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.NoopSegmentSink
import au.com.shiftyjelly.pocketcasts.voicecontrol.upload.observation.SegmentObserver
import au.com.shiftyjelly.pocketcasts.voicecontrol.wakeword.WakeWordDetector
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * THE QUIESCENCE SNAPSHOT MUST BE ATOMIC.
 *
 * `unfinishedProcessingJobs` is read inside `stopVoiceControl` and handed to
 * [EngineQuiescence.settle], whose contract is fail-closed: only `Settled` permits releasing the
 * ASR backend. A throw while taking that snapshot is therefore not a cosmetic flake — it is a
 * failed stop, and a job finishing at the wrong microsecond could hold a session that stopped
 * cleanly.
 *
 * The defect: `CopyOnWriteArrayList.toList()` reads `size` and then indexes, and a completion
 * callback removes the job from that list the moment it finishes. Between the two reads the
 * element can be gone, so the index read throws `ArrayIndexOutOfBoundsException`.
 *
 * The interleaving is forced deterministically rather than raced: the list drains itself inside
 * `get`, which is exactly where the second read lands. `toArray()` captures the array under the
 * list's own lock, so the same interference cannot split it.
 */
class EngineUnfinishedSnapshotControlTest {
    /**
     * A copy-on-write list whose element disappears between the size read and the index read.
     * This is the real interleaving, made reproducible: no threads, no sleeps, no timing.
     */
    private class RacingList<T> : CopyOnWriteArrayList<T>() {
        override fun get(index: Int): T {
            clear()
            return super.get(index)
        }
    }

    private fun engine(scope: CoroutineScope): VoiceAsrEngine {
        val processor = mock(VoiceAudioProcessor::class.java)
        val issued = IssuedAuthority("a@b.c", "epoch-1", "disclosure-1")
        val authority: () -> AuthorityDecision = {
            AuthorityDecision(collectionPermitted = false, fullRevocation = true)
        }
        val observer = SegmentObserver(
            CollectionGate { _ -> false },
            NoopSegmentSink,
            authority,
            issueAuthority = { issued },
            handoff = CompletionHandoff { ctx, onset, end, wake, provider, s, live, measurement ->
                s.completeUnderOwner(ctx, onset, end, wake, provider, wakeMeasurement = measurement)
            },
        )
        return VoiceAsrEngine(
            voiceAudioProcessor = processor,
            utteranceFilter = mock(UtteranceFilter::class.java),
            intentRecognizer = mock(au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer::class.java),
            wakeWordDetector = mock(WakeWordDetector::class.java),
            gracePeriodSignal = GracePeriodSignal(),
            audioFeedbackRenderer = mock(AudioFeedbackRenderer::class.java),
            templateResolver = SpokenTemplateResolver(emptyMap()),
            currentLocale = { Locale.US },
            translationStage = mock(TranslationStage::class.java),
            collectionAuthority = authority,
            retentionSink = ContributionRetentionSink(),
            segmentObserver = observer,
            context = mock(android.content.Context::class.java),
        ).also { it.scope = scope }
    }

    @Test
    fun `a job finishing during the snapshot does not throw`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine = engine(scope)
            val jobs = RacingList<Job>()
            jobs.add(scope.launch { })
            engine.unfinished = jobs

            // Before the fix this throws ArrayIndexOutOfBoundsException out of the getter.
            val snapshot = engine.unfinishedProcessingJobs

            assertTrue("the snapshot must be a stable value, not a live view", snapshot.size <= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the snapshot still reports a job that has not finished`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine = engine(scope)
            val held = scope.launch { kotlinx.coroutines.awaitCancellation() }
            val jobs = RacingList<Job>()
            jobs.add(held)
            engine.unfinished = jobs

            // The atomic snapshot must not become "always empty": that would report quiescence
            // while work is still running, which is the opposite failure and worse than a throw.
            assertEquals(listOf(held), engine.unfinishedProcessingJobs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a stop whose snapshot races a completion still settles`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val engine = engine(scope)
            val finished = scope.launch { }
            finished.join()
            val jobs = RacingList<Job>()
            jobs.add(finished)
            engine.unfinished = jobs

            // The consequence, not just the absence of a throw: quiescence takes its snapshot
            // through this getter, and a throw here becomes StopFailed/a held session rather than
            // the clean stop it actually was.
            val verdict = EngineQuiescence.settle(
                stop = { },
                unfinished = { engine.unfinishedProcessingJobs },
                join = { it.join() },
                owned = { setOf(finished) },
                completionCause = { null },
            )

            assertEquals(EngineQuiescence.Verdict.Settled, verdict)
        } finally {
            scope.cancel()
        }
    }
}
