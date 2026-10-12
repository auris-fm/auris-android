package au.com.shiftyjelly.pocketcasts.voicecontrol.playback

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * The REAL sink's resolution of the schema's signed absolute position. This is the only place the
 * negative-from-the-end rule can be exercised: a fake sink that resolves the offset itself tests its
 * own arithmetic, and cannot fail when production stops resolving.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackManagerPlaybackSinkTest {

    private fun sink(playbackManager: PlaybackManager): PlaybackManagerPlaybackSink = PlaybackManagerPlaybackSink(
        playbackManager,
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `a seek bound to another episode is refused AT THE SINK, not by its caller`() = runTest {
        // THE BOUNDARY IS THE PRODUCTION SINK, so the case must drive IT rather than a fake. This is the
        // same principle as the signed-position case above: a caller-side check can be overtaken by a
        // selection change between the check and the mutation, and the caller's mutex cannot exclude a
        // change made anywhere else in the app. Only the owner of what is playing can decide "which
        // episode is selected" and "seek it" as ONE step.
        //
        // A fake sink that models the refusal cannot fail when the PRODUCTION check is removed -- it
        // would only be testing its own copy -- which is exactly why this case lives here.
        val manager = mock<PlaybackManager>()
        // The OWNER's operation decides, so the mock must answer it: this case is about the adapter
        // consulting the owner, not about the owner's own behaviour.
        org.mockito.kotlin.whenever(
            manager.seekIfPlayingCapturing(org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenReturn(null)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-B",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertEquals(
            "a seek bound to another episode is REFUSED, and the refusal is reported as one",
            EpisodeOperationResult.Refused,
            result,
        )
        verify(manager, org.mockito.kotlin.never()).seekToTimeMsSuspend(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `the REAL manager refuses a seek the player is RE-POINTED during`() = probeActualManagerReuse(reuse = true)

    @Test
    fun `the REAL manager seeks a target that stays on its episode`() = probeActualManagerReuse(reuse = false)

    @Test
    fun `the REAL manager refuses a re-pointing that does NOT advance the incarnation counter`() = runTest {
        // @reviewer's remaining gap: only the paused-load branch advances the counter, while the playing
        // and queue-selection reconfiguration paths need not. A seek relying on the COUNTER alone would
        // therefore miss a re-pointing made by a path that does not advance it.
        //
        // This case re-points the player WITHOUT touching the counter at all, so the only thing that can
        // refuse it is the seek reading the PLAYER'S OWN episode after its suspending call.
        val manager = mock<PlaybackManager>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var configured = "episode-A"
        val target = object : au.com.shiftyjelly.pocketcasts.repositories.playback.Player by mock() {
            // THE SUBSTITUTED PLAYER IS A REAL ENFORCER, as LocalPlayer and CastPlayer are. Without this
            // the manager refuses the bound call as unsupported and the case would test that refusal
            // instead of the re-pointing it is about.
            override val enforcesEpisodeBoundSeek: Boolean get() = true
            override val episodeUuid: String? get() = configured
            override suspend fun seekToTimeMs(positionMs: Int) {
                entered.complete(Unit)
                proceed.await()
            }

            override suspend fun seekToTimeMsIfEpisode(
                positionMs: Int,
                expectedEpisodeUuid: String,
            ): au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome {
                entered.complete(Unit)
                proceed.await()
                // The player's own mutation point: re-pointed means REFUSED, with no seek performed.
                if (configured != expectedEpisodeUuid) {
                    return au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome.Refused
                }
                return au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome
                    .accepted(0L)
            }
        }
        org.mockito.kotlin.whenever(manager.player).thenReturn(target)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )
        org.mockito.kotlin.whenever(manager.playbackStateRelay)
            .thenReturn(com.jakewharton.rxrelay2.BehaviorRelay.createDefault(PlaybackState()))
        PlaybackManager::class.java.getDeclaredField("selectedPlayerLock")
            .apply { isAccessible = true }
            .set(manager, Any())
        val method = PlaybackManager::class.java.declaredMethods
            .single { it.name == "seekToTimeMsInternalIfEpisode" }
            .apply { isAccessible = true }

        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val operation = async {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                    method.invoke(manager, "episode-A", 100_000, continuation)
                }
            }
            runCurrent()
            assertTrue("the seek reached the player", entered.isCompleted)
            // RE-POINTED WITHOUT ADVANCING THE COUNTER: the only observation that can catch this is the
            // player's own episode, read after the call.
            configured = "episode-B"
            proceed.complete(Unit)
            runCurrent()
            val accepted = operation.await()

            assertFalse(
                "a re-pointing that advanced no counter must still be refused, because the seek reads " +
                    "the PLAYER rather than only the counter",
                accepted,
            )
        } finally {
            // COMPLETE THE GATE AND LET IT SETTLE BEFORE RESETTING MAIN. The seek is suspended on this
            // gate; releasing it after  lets the coroutine resume on a dispatcher the test no
            // longer owns, and the resulting failure surfaces as an UNCAUGHT exception in a LATER test --
            // a leak whose symptom is nowhere near its cause.
            proceed.complete(Unit)
            runCurrent()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `an ACCEPTED operation with an UNAVAILABLE position is still accepted`() = runTest {
        // @spec's distinction, and the defect it caught in my previous version: a successful load of a new
        // episode has NO pre-action position on the receiver -- the receiver is still on the old one --
        // but the load is ACCEPTED. Deriving acceptance from the position's presence would REJECT an
        // operation that is about to succeed. The two are separate facts.
        val manager = mock<PlaybackManager>()
        val target = object : au.com.shiftyjelly.pocketcasts.repositories.playback.Player by mock() {
            override val enforcesEpisodeBoundSeek: Boolean get() = true
            override val episodeUuid: String? get() = "episode-A"
            override suspend fun seekToTimeMsIfEpisode(
                positionMs: Int,
                expectedEpisodeUuid: String,
            ): au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome = au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome
                .accepted(positionMs = null)
        }
        org.mockito.kotlin.whenever(manager.player).thenReturn(target)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )
        org.mockito.kotlin.whenever(manager.playbackStateRelay)
            .thenReturn(com.jakewharton.rxrelay2.BehaviorRelay.createDefault(PlaybackState()))
        PlaybackManager::class.java.getDeclaredField("selectedPlayerLock")
            .apply { isAccessible = true }
            .set(manager, Any())
        val method = PlaybackManager::class.java.declaredMethods
            .single { it.name == "seekToTimeMsInternalIfEpisode" }
            .apply { isAccessible = true }

        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val accepted = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                method.invoke(manager, "episode-A", 100_000, continuation)
            }
            runCurrent()

            assertTrue(
                "an ACCEPTED operation must be accepted even when its pre-action position is " +
                    "UNAVAILABLE: absence of provenance is not refusal",
                accepted,
            )
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `the REAL manager REFUSES a bound seek on a player that cannot enforce the binding`() = runTest {
        // A player without the enforcement reports `enforcesEpisodeBoundSeek = false`, so the default
        // check-then-delegate would carry the very gap the contract exists to close. The bound call must
        // be UNAVAILABLE for it rather than FALLING BACK to an unrestricted seek -- which is what
        // "refuse unsupported bound execution rather than fall back" means.
        val manager = mock<PlaybackManager>()
        var reached = false
        val target = object : au.com.shiftyjelly.pocketcasts.repositories.playback.Player by mock() {
            override val episodeUuid: String? get() = "episode-A"
            override suspend fun seekToTimeMs(positionMs: Int) {
                reached = true
            }
        }
        org.mockito.kotlin.whenever(manager.player).thenReturn(target)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )
        org.mockito.kotlin.whenever(manager.playbackStateRelay)
            .thenReturn(com.jakewharton.rxrelay2.BehaviorRelay.createDefault(PlaybackState()))
        PlaybackManager::class.java.getDeclaredField("selectedPlayerLock")
            .apply { isAccessible = true }
            .set(manager, Any())
        val method = PlaybackManager::class.java.declaredMethods
            .single { it.name == "seekToTimeMsInternalIfEpisode" }
            .apply { isAccessible = true }

        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val accepted = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                method.invoke(manager, "episode-A", 100_000, continuation)
            }
            runCurrent()

            assertFalse(
                "an unsupported player must make the bound call UNAVAILABLE, not seek unrestricted",
                accepted,
            )
            assertFalse("and no seek may be performed (reached=$reached)", reached)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    /**
     * Executes the ACTUAL private manager seek against a substituted player whose `seekToTimeMs`
     * suspends, then re-points that player at another episode WHILE THE SEEK IS IN FLIGHT.
     *
     * This is the only shape that can observe the defect: a monitor cannot span a suspension, so the
     * check must happen where the change is visible -- after the suspending call returns. Every
     * adapter-level fixture stubs the owner and therefore cannot see this at all.
     *
     * @reviewer's control, adopted; it failed here until the post-seek episode read was added.
     */
    private fun probeActualManagerReuse(reuse: Boolean) = runTest {
        val manager = mock<PlaybackManager>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var configured = "episode-A"
        var actedOn: String? = null
        val target = object : au.com.shiftyjelly.pocketcasts.repositories.playback.Player by mock() {
            // THE SUBSTITUTED PLAYER IS A REAL ENFORCER, as LocalPlayer and CastPlayer are. Without this
            // the manager refuses the bound call as unsupported and the case would test that refusal
            // instead of the re-pointing it is about.
            override val enforcesEpisodeBoundSeek: Boolean get() = true
            override val episodeUuid: String? get() = configured
            override suspend fun seekToTimeMs(positionMs: Int) {
                entered.complete(Unit)
                proceed.await()
                actedOn = configured
            }

            override suspend fun seekToTimeMsIfEpisode(
                positionMs: Int,
                expectedEpisodeUuid: String,
            ): au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome {
                // THE PLAYER'S OWN MUTATION POINT: it records the seek only while it still addresses the
                // episode it was asked about, which is what makes a re-pointing REFUSED rather than
                // merely detected after the damage. A non-null result is the acceptance AND the
                // pre-action position the player saw at its own mutation.
                entered.complete(Unit)
                proceed.await()
                if (configured != expectedEpisodeUuid) {
                    return au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome.Refused
                }
                actedOn = configured
                return au.com.shiftyjelly.pocketcasts.repositories.playback.Player.EpisodeSeekOutcome
                    .accepted(0L)
            }
        }
        org.mockito.kotlin.whenever(manager.player).thenReturn(target)
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )
        org.mockito.kotlin.whenever(manager.playbackStateRelay)
            .thenReturn(com.jakewharton.rxrelay2.BehaviorRelay.createDefault(PlaybackState()))
        PlaybackManager::class.java.getDeclaredField("selectedPlayerLock")
            .apply { isAccessible = true }
            .set(manager, Any())
        val method = PlaybackManager::class.java.declaredMethods
            .single { it.name == "seekToTimeMsInternalIfEpisode" }
            .apply { isAccessible = true }

        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val operation = async {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                    method.invoke(manager, "episode-A", 100_000, continuation)
                }
            }
            runCurrent()
            assertTrue(
                "the real manager must reach the captured player's suspending seek",
                entered.isCompleted,
            )
            // THE RE-POINTING LANDS WHILE THE SEEK IS SUSPENDED: the in-flight observation.
            if (reuse) configured = "episode-B"
            proceed.complete(Unit)
            runCurrent()
            val accepted = operation.await()

            if (reuse) {
                assertFalse(
                    "the manager must REFUSE a seek that ran against a player re-pointed to " +
                        "another episode (actedOn=$actedOn)",
                    accepted && actedOn == "episode-B",
                )
            } else {
                assertTrue(
                    "control: an unchanged target's seek runs and is accepted (actedOn=$actedOn)",
                    accepted && actedOn == "episode-A",
                )
            }
        } finally {
            // COMPLETE THE GATE AND LET IT SETTLE BEFORE RESETTING MAIN. The seek is suspended on this
            // gate; releasing it after  lets the coroutine resume on a dispatcher the test no
            // longer owns, and the resulting failure surfaces as an UNCAUGHT exception in a LATER test --
            // a leak whose symptom is nowhere near its cause.
            proceed.complete(Unit)
            runCurrent()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `a replacement landing WHILE the owner's Main dispatch is queued is refused`() = runTest {
        // @spec's controlled case. `seekToTimeMsInternalIfEpisode` suspends to publish the media-session
        // position BEFORE the player mutation, and the replacement is made to land in that suspension --
        // while the Main dispatch it is waiting on is still queued, not merely "before the call".
        //
        // The operation must be refused, and it must NOT touch the replacement player: a seek that
        // reaches `player.seekToTimeMs` after the replacement has moved the player is exactly the
        // cross-episode mutation this boundary exists to prevent.
        val replaced = java.util.concurrent.atomic.AtomicBoolean(false)
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenAnswer {
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = if (replaced.get()) "episode-B" else "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            )
        }
        org.mockito.kotlin.whenever(
            manager.seekIfPlayingCapturing(org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenAnswer {
            // THE REPLACEMENT LANDS AT THE OWNER'S DECIDING STEP. The owner is where the decision is
            // made, so it is where the replacement must be observable; the payload it returns is the
            // decision it actually reached.
            replaced.set(true)
            manager.getCurrentEpisode()
                ?.takeIf { it.uuid == "episode-A" }
                ?.let { PlaybackManager.ScopedMutation(it.uuid, it.playedUpToMs) }
        }

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertTrue(
            "a replacement during the owner's suspension must be refused (result=$result)",
            result is EpisodeOperationResult.Refused,
        )
        org.mockito.kotlin.verify(manager, org.mockito.kotlin.never()).seekToTimeMsSuspend(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `the episode is re-decided AT the mutation, so a change across the suspension is refused`() = runTest {
        // @reviewer's finding: `seekToTimeMsInternal` reads the episode, then SUSPENDS in
        // `withContext(Dispatchers.Main)` before the player mutation -- and a replacement can land in
        // that suspension. A read taken before the suspensions therefore does not exclude a change
        // ACROSS them.
        //
        // The owner's episode-scoped seek re-reads the selection immediately before `player.seekToTimeMs`,
        // where nothing can await in between. This drives that: the manager reports episode A on the
        // FIRST consultation and B on the SECOND, which is exactly a replacement landing during the
        // step, and the seek must then be refused.
        // THE REPLACEMENT LANDS BEFORE THE OWNER DECIDES. The adapter may read the selection for its
        // own early refusal; the OWNER'S ANSWER is what must observe the replacement, because the
        // owner is where the deciding read happens.
        val replaced = java.util.concurrent.atomic.AtomicBoolean(false)
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenAnswer {
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = if (replaced.get()) "episode-B" else "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            )
        }
        org.mockito.kotlin.whenever(
            manager.seekIfPlayingCapturing(org.mockito.kotlin.any(), org.mockito.kotlin.any()),
        ).thenAnswer {
            // THE OWNER'S DECIDING STEP: the replacement has landed by now, so it observes B and
            // refuses. Returning A's payload would be the defect itself -- an operation performed on
            // an episode that is no longer selected.
            replaced.set(true)
            manager.getCurrentEpisode()
                ?.takeIf { it.uuid == "episode-A" }
                ?.let { PlaybackManager.ScopedMutation(it.uuid, it.playedUpToMs) }
        }

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertTrue(
            "a selection change across the owner's suspension must be REFUSED, not performed " +
                "(result=$result)",
            result is EpisodeOperationResult.Refused,
        )
    }

    @Test
    fun `the owner's answer is AWAITED, not read from a flag written inside a launch`() = runTest {
        // @reviewer's source finding: `seekIfPlayingToTimeMs` used to SET A FLAG INSIDE A LAUNCHED
        // COROUTINE and return it immediately -- so when dispatch queued the coroutine, the caller read
        // the flag's INITIAL value and reported a REFUSAL for a seek that then performed. The outcome is
        // the owner's decision, so the caller must WAIT for it.
        //
        // The owner here answers on a delay, which is exactly the shape that used to be misreported: a
        // launch-and-return would answer false before this completed.
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.seekIfPlayingCapturing(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenAnswer {
                // A NON-SUSPENDING delay: the point is that the answer arrives AFTER the call returns,
                // which is what a launch-and-read-a-flag cannot report.
                Thread.sleep(50)
                PlaybackManager.ScopedMutation("episode-A", 0)
            }
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        assertEquals(
            "an operation the owner ACCEPTED must be reported as accepted, even though the owner " +
                "answered asynchronously",
            true,
            result is EpisodeOperationResult.Accepted && result.episodeId == "episode-A",
        )
    }

    @Test
    fun `a seek bound to the episode now playing still reaches the player`() = runTest {
        // The positive control: the binding must not refuse the ordinary case, or the check above would
        // be satisfied by refusing everything.
        val manager = mock<PlaybackManager>()
        org.mockito.kotlin.whenever(manager.getCurrentEpisode()).thenReturn(
            au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode(
                uuid = "episode-A",
                podcastUuid = "podcast-1",
                publishedDate = java.util.Date(),
            ),
        )

        org.mockito.kotlin.whenever(manager.seekIfPlayingCapturing(org.mockito.kotlin.any(), org.mockito.kotlin.any()))
            .thenReturn(PlaybackManager.ScopedMutation("episode-A", 100_000))

        val result = sink(manager).seekTo(
            positionSeconds = 100,
            episodeDurationSeconds = 1800,
            expectedEpisodeId = "episode-A",
        )

        // THE OWNING OPERATION IS WHAT MUST BE CALLED. `seekIfPlayingToTimeMs` re-reads the selection
        // inside the operation, immediately before seeking, so it is the only place the check and the
        // mutation are one step. A position-only `seekToTimeMsSuspend` here would mean the adapter had
        // checked and then seeked separately -- the race this delegation exists to remove.
        val positionMs = argumentCaptor<Int>()
        verify(manager).seekIfPlayingCapturing(
            org.mockito.kotlin.eq("episode-A"),
            positionMs.capture(),
        )
        assertEquals(100_000, positionMs.firstValue)
        assertTrue("accepted, reporting the episode it was bound to", result is EpisodeOperationResult.Accepted)
        verify(manager, org.mockito.kotlin.never()).seekToTimeMsSuspend(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `a negative position resolves as an offset back from the episode end`() = runTest {
        // voice-intents: "non-negative values are offsets from the beginning, 0 means beginning, and
        // negative values are offsets back from the episode end." -30 s against a 1800 s episode is
        // 1770 s, not 0: clamping would issue a different instruction than the one given.
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = -30, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(1_770_000, positionMs.firstValue)
    }

    @Test
    fun `a non-negative position is used as given`() = runTest {
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = 300, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(300_000, positionMs.firstValue)
    }

    @Test
    fun `an offset beyond the episode start clamps to the beginning, not below it`() = runTest {
        // A negative beyond the episode's length has no position before the beginning, so the
        // resolved value is the beginning rather than a negative sink position.
        val manager = mock<PlaybackManager>()
        sink(manager).seekTo(positionSeconds = -5000, episodeDurationSeconds = 1800)

        val positionMs = argumentCaptor<Int>()
        verify(manager).seekToTimeMsSuspend(positionMs.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(0, positionMs.firstValue)
    }
}
