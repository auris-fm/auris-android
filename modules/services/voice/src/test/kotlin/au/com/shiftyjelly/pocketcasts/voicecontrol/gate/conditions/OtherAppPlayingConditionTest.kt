@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package au.com.shiftyjelly.pocketcasts.voicecontrol.gate.conditions

import au.com.shiftyjelly.pocketcasts.voicecontrol.feedback.AudioFeedbackRenderer
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleGroup
import au.com.shiftyjelly.pocketcasts.voicecontrol.gate.VoiceControlRuleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherAppPlayingConditionTest {

    @Test
    fun `a successor's unresolved observation does not inherit the previous cue's timeout`() {
        // @spec's specific check. Cue A is unresolved long enough to consume most of its hold; a SECOND
        // cue then becomes unresolved. If the anchor were kept across cues, B would inherit A's elapsed
        // time and expire almost immediately -- a hold that never actually covered B.
        var now = 0L
        // CUE A PUBLISHED AT 0. Its hold runs from that instant, not from this consumer's first poll.
        val observation = MutableStateFlow<AudioFeedbackRenderer.PublishedObservation?>(
            AudioFeedbackRenderer.PublishedObservation(
                cueId = 1L,
                observation = AudioFeedbackRenderer.CueObservation.Unknown,
                publishedAtMs = 0L,
            ),
        )
        val condition = OtherAppPlayingCondition(
            hostIsPlaying = MutableStateFlow(false),
            hasEmittedAudio = MutableStateFlow(true),
            lastEmittedAtMs = MutableStateFlow(0L),
            lastCueObservation = observation,
            nowMs = { now },
            transitionWindowMs = 5_000,
        )

        // CUE A: unresolved for most of the hold.
        condition.otherAppPlaying(isMusicActive = true)
        now = 25_000L
        assertFalse(condition.otherAppPlaying(isMusicActive = true))

        // CUE B PUBLISHES ITS OWN UNKNOWN WITH A DISTINCT IDENTITY. THE CONSUMER DOES NOT POLL AGAIN
        // UNTIL LONG AFTER THAT PUBLICATION -- which is what makes this case discriminate: a hold
        // anchored to the consumer's first sight would start counting at 40_000, while one anchored to
        // the RENDERER'S instant has already expired.
        now = 26_000L
        observation.value = AudioFeedbackRenderer.PublishedObservation(
            cueId = 2L,
            observation = AudioFeedbackRenderer.CueObservation.Unknown,
            publishedAtMs = 26_000L,
        )

        // THE CONSUMER'S FIRST SIGHT OF B IS 40_000, but B's observation is 14_000 ms old and its hold is
        // 30_000 -- still within it, so the attribution remains ours. Had B inherited A's anchor (0),
        // it would be 40_000 old and would wrongly expire here.
        now = 40_000L
        assertFalse(
            "the hold runs from the renderer's publication, so a late first poll does not expire it early",
            condition.otherAppPlaying(isMusicActive = true),
        )

        // AND IT DOES EXPIRE, from its OWN publication: 26_000 + 30_000 = 56_000.
        now = 57_000L
        assertTrue(
            "the successor's hold expires from ITS OWN publication instant, and the app listens again",
            condition.otherAppPlaying(isMusicActive = true),
        )
    }

    @Test
    fun `an unresolved observation does NOT hold the attribution open forever`() {
        // @reviewer's second finding: Unknown bypassed the window with NO TIME LIMIT, so after the cue
        // ended every later active-music reading stayed attributed to us. A hold is a BOUND, not a state
        // change of ownership.
        var now = 0L
        val observation = MutableStateFlow<AudioFeedbackRenderer.PublishedObservation?>(
            AudioFeedbackRenderer.PublishedObservation(
                cueId = 1L,
                observation = AudioFeedbackRenderer.CueObservation.Unknown,
                publishedAtMs = 0L,
            ),
        )
        val condition = OtherAppPlayingCondition(
            hostIsPlaying = MutableStateFlow(false),
            hasEmittedAudio = MutableStateFlow(true),
            lastEmittedAtMs = MutableStateFlow(0L),
            lastCueObservation = observation,
            nowMs = { now },
            transitionWindowMs = 5_000,
        )

        // THE HOLD IS ACTIVE while the observation is fresh.
        assertFalse(
            "an unresolved observation holds our own audio open",
            condition.otherAppPlaying(isMusicActive = true),
        )

        // LONG AFTER, the hold EXPIRES and foreign audio is a foreign app again -- otherwise the app
        // would never listen again for the rest of the process.
        now = 10 * 60_000L
        assertTrue(
            "the hold must EXPIRE: an unresolved cue cannot own all later audio indefinitely",
            condition.otherAppPlaying(isMusicActive = true),
        )
    }

    @Test
    fun `an unresolved cue observation holds our own audio open past the window`() {
        // @spec's 96a3831c: the renderer's typed per-cue observation must have a PRODUCTION consumer.
        // Beyond the bounded self-emission window, an UNKNOWN observation means we could not establish
        // that our cue stopped sounding -- so its audio is still OURS, and reading expiry as "ended"
        // would hand it to a foreign app and cut the microphone mid-cue.
        assertFalse(
            "an unresolved cue past the window is still our own audio, not a foreign app",
            otherAppPlaying(
                isMusicActive = true,
                hostCurrentlyPlaying = false,
                msSinceHostPlaying = 10_000,
                msSinceSelfEmitted = 10_000,
                transitionWindowMs = 5_000,
                selfEmissionUnresolved = true,
            ),
        )
        // CONTROL: the same inputs with a RESOLVED observation are a foreign app, so the case is decided
        // by the observation and not by the timings.
        assertTrue(
            otherAppPlaying(
                isMusicActive = true,
                hostCurrentlyPlaying = false,
                msSinceHostPlaying = 10_000,
                msSinceSelfEmitted = 10_000,
                transitionWindowMs = 5_000,
                selfEmissionUnresolved = false,
            ),
        )
    }

    @Test
    fun `initial state is unknown`() {
        val condition = OtherAppPlayingCondition()
        val state = condition.state.value
        assertTrue(state is VoiceControlRuleState.Unknown)
        assertEquals("initializing", (state as VoiceControlRuleState.Unknown).reason)
    }

    @Test
    fun `has correct id and group`() {
        val condition = OtherAppPlayingCondition()
        assertEquals(VoiceControlRuleGroup.Conflicts, condition.group)
        assertEquals("other_app_playing", condition.id)
    }

    @Test
    fun `blocks when other app is playing after debounce`() = runTest {
        val condition = OtherAppPlayingCondition(debounceMs = 500, scope = backgroundScope)
        assertEquals(VoiceControlRuleGroup.Conflicts, condition.group)
        assertEquals("other_app_playing", condition.id)

        condition.update(otherAppPlaying = true)
        // Debounce is 500ms; wait past it
        delay(600)

        val state = condition.state.value
        assertTrue(state is VoiceControlRuleState.Blocked)
        assertEquals("other_app_playing", (state as VoiceControlRuleState.Blocked).reason)
    }

    @Test
    fun `allowed when no other app is playing`() = runTest {
        val condition = OtherAppPlayingCondition(scope = backgroundScope)

        condition.update(otherAppPlaying = false)

        assertTrue(condition.state.value is VoiceControlRuleState.Allowed)
    }

    @Test
    fun `debounces transient flickers`() = runTest {
        val condition = OtherAppPlayingCondition(debounceMs = 500, scope = backgroundScope)

        // A quick true->false->true flicker within <500ms should not emit Blocked
        condition.update(otherAppPlaying = true)
        delay(200)
        condition.update(otherAppPlaying = false)
        delay(200)
        condition.update(otherAppPlaying = true)
        delay(200)

        // Total elapsed: 600ms, but latest update(true) was only 200ms ago
        // Debounce (500ms) should NOT have fired yet
        assertFalse("State should not be Blocked during debounce window", condition.state.value is VoiceControlRuleState.Blocked)

        // Wait for the debounce to fire
        delay(300)

        // After full debounce the condition should stabilise to Blocked
        val state = condition.state.value
        assertTrue(state is VoiceControlRuleState.Blocked)
        assertEquals("other_app_playing", (state as VoiceControlRuleState.Blocked).reason)
    }

    @Test
    fun `evaluate returns blocked when other app is playing`() {
        val condition = OtherAppPlayingCondition()
        val state = condition.evaluate(otherAppPlaying = true)
        assertTrue(state is VoiceControlRuleState.Blocked)
        assertEquals("other_app_playing", (state as VoiceControlRuleState.Blocked).reason)
    }

    @Test
    fun `evaluate returns allowed when no other app is playing`() {
        val condition = OtherAppPlayingCondition()
        val state = condition.evaluate(otherAppPlaying = false)
        assertTrue(state is VoiceControlRuleState.Allowed)
    }

    @Test
    fun `evaluate with null AudioManager returns allowed (safe default)`() {
        val condition = OtherAppPlayingCondition(audioManager = null)
        val state = condition.evaluate()
        assertTrue(state is VoiceControlRuleState.Allowed)
    }

    @Test
    fun `host app playback is not treated as other app playing`() {
        // When the host is playing audio, the condition should allow
        // (per spec: Auris must own the audio output).
        // The evaluate(Boolean) path takes a pre-computed boolean —
        // passing false means "no other app" → Allowed.
        val condition = OtherAppPlayingCondition()
        val state = condition.evaluate(otherAppPlaying = false)
        assertTrue(state is VoiceControlRuleState.Allowed)
    }

    @Test
    fun `host own audio while playing is not other app`() {
        // Host is actively playing -> owns the audio session -> not another app.
        assertFalse(otherAppPlaying(isMusicActive = true, hostCurrentlyPlaying = true, msSinceHostPlaying = 0, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }

    @Test
    fun `other app audio when host does not own it is other app`() {
        // Host not playing and not recently played -> another app owns the active audio.
        assertTrue(otherAppPlaying(isMusicActive = true, hostCurrentlyPlaying = false, msSinceHostPlaying = 10_000, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }

    @Test
    fun `no active audio is never other app`() {
        assertFalse(otherAppPlaying(isMusicActive = false, hostCurrentlyPlaying = false, msSinceHostPlaying = 0, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
        assertFalse(otherAppPlaying(isMusicActive = false, hostCurrentlyPlaying = true, msSinceHostPlaying = 0, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }

    @Test
    fun `host owns audio during play-pause transition window`() {
        // The reported false positive: host was playing within the last few seconds but
        // isPlaying momentarily flickered false. The transition window attributes the
        // still-active audio session to the host, not "another app".
        assertFalse(otherAppPlaying(isMusicActive = true, hostCurrentlyPlaying = false, msSinceHostPlaying = 2_000, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }

    @Test
    fun `our own earcon is not a foreign app`() {
        // Earcons ride the media stream and are 0.15-0.4s long, so a 1 Hz poll lands inside one.
        assertFalse(
            otherAppPlaying(
                isMusicActive = true,
                hostCurrentlyPlaying = false,
                msSinceHostPlaying = 60_000,
                msSinceSelfEmitted = 200,
                transitionWindowMs = 5000,
            ),
        )
    }

    @Test
    fun `our own audio long past does not mask a genuinely foreign app`() {
        assertTrue(
            otherAppPlaying(
                isMusicActive = true,
                hostCurrentlyPlaying = false,
                msSinceHostPlaying = 60_000,
                msSinceSelfEmitted = 60_000,
                transitionWindowMs = 5000,
            ),
        )
    }

    @Test
    fun `the condition reads its own emitted audio as host audio`() = runTest {
        val condition = OtherAppPlayingCondition(
            hostIsPlaying = MutableStateFlow(false),
            hasEmittedAudio = MutableStateFlow(true),
            lastEmittedAtMs = MutableStateFlow(1_000L),
            nowMs = { 1_200L },
            scope = backgroundScope,
        )

        assertFalse(condition.otherAppPlaying(isMusicActive = true))
    }

    @Test
    fun `host paused beyond window does not own audio (no false negative)`() {
        // Host holds a paused, loaded episode but did not play within the window; a
        // genuinely different app playing is correctly blocked (avoids the open-mic-
        // over-background-music false negative).
        assertTrue(otherAppPlaying(isMusicActive = true, hostCurrentlyPlaying = false, msSinceHostPlaying = 60_000, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }

    @Test
    fun `host never played does not own audio`() {
        // Long.MIN_VALUE sentinel -> msSinceHostPlaying is huge -> not host-owned.
        assertTrue(otherAppPlaying(isMusicActive = true, hostCurrentlyPlaying = false, msSinceHostPlaying = Long.MIN_VALUE, msSinceSelfEmitted = -1, transitionWindowMs = 5000))
    }
}
