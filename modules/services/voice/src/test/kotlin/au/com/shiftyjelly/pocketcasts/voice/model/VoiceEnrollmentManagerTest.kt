package au.com.shiftyjelly.pocketcasts.voice.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voice.audio.PcmAudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceEnrollmentManagerTest {
    private lateinit var store: SpeakerVerificationStore
    private lateinit var embedder: SpeakerEmbedder
    private lateinit var verifier: SpeakerVerifier
    private lateinit var manager: VoiceEnrollmentManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        store = SpeakerVerificationStore(ctx)
        store.clear()
        embedder = SpeakerEmbedder(ctx)
        embedder.load()
        verifier = SpeakerVerifier()
        manager = VoiceEnrollmentManager(store, embedder, verifier)
    }

    @Test fun `initial state is NotEnrolled`() {
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
    }

    @Test fun `enroll with 3 utterances saves voiceprint`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(store.isEnrolled())
        assertTrue(manager.state.value is VoiceEnrollmentState.Enrolled)
    }

    @Test fun `clear resets everything`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(store.isEnrolled())
        manager.clear()
        assertEquals(VoiceEnrollmentState.NotEnrolled, manager.state.value)
        assertTrue(!store.isEnrolled())
    }

    @Test fun `verify matching audio returns true`() {
        manager.enroll(List(3) { makeClip() })
        assertTrue(manager.verify(makeClip()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty list`() { manager.enroll(emptyList()) }

    private fun makeClip(): VoiceUtteranceClip {
        val s = ShortArray(16000)
        return VoiceUtteranceClip.fromFrames(listOf(PcmAudioFrame(s, 16000)))
    }
}
