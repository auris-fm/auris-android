package au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics

import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Event
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Kind
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Reason
import au.com.shiftyjelly.pocketcasts.voicecontrol.diagnostics.VoiceLifecycleDiagnostics.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceLifecycleOutputTest {
    @Test
    fun `exported records are bounded and reject non UUID identity payloads`() {
        LifecycleLogCapture().use { log ->
            VoiceLifecycleDiagnostics.record(
                Event(
                    Kind.Turn,
                    Reason.TurnStarted,
                    Source.Cloud,
                    owner = 10,
                    requestId = "Bearer PRIVATE TOKEN\nPRIVATE UTTERANCE".repeat(100),
                ),
            )
            VoiceLifecycleDiagnostics.record(
                Event(
                    Kind.Turn,
                    Reason.TurnStarted,
                    Source.Cloud,
                    owner = 11,
                    requestId = "5BA4AD9C-AC04-4B07-AF87-D034DE6FAE0F",
                ),
            )
            val lines = log.lines()
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.length < 1_000 && it.contains("elapsed_ms=") && it.contains("unix_ms=") && it.contains("pid=") && it.contains("dropped_before=") })
            assertTrue(lines[0].contains("request_id=invalid"))
            assertTrue(lines[1].contains("request_id=5ba4ad9c-ac04-4b07-af87-d034de6fae0f"))
            assertFalse(lines.any { it.contains("PRIVATE") || it.contains("Bearer") })
        }
    }

    @Test
    fun `a blocked writer has a bounded queue and reports discarded records`() {
        LifecycleLogCapture().use { log ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            val tree = object : timber.log.Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (tag == "VoiceLifecycle" && first.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
            timber.log.Timber.plant(tree)
            try {
                VoiceLifecycleDiagnostics.record(Event(Kind.Turn, Reason.TurnStarted, Source.Cloud))
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                repeat(200) { VoiceLifecycleDiagnostics.record(Event(Kind.Turn, Reason.TurnEnded, Source.Cloud, owner = it.toLong())) }
                au.com.shiftyjelly.pocketcasts.voicecontrol.gate.signals.GracePeriodSignal().onAppBackgrounded()
            } finally {
                release.countDown()
                timber.log.Timber.uproot(tree)
            }
            val lines = log.lines()
            assertTrue("the stalled writer cannot retain all 202 records", lines.size <= 130)
            assertTrue(lines.any { Regex("dropped_before=([1-9][0-9]*)").containsMatchIn(it) })
            assertTrue("the latest actual window decision survives", lines.any { it.contains("reason=Backgrounded") })
        }
    }

    @Test
    fun `unrecognized rule names use a bit rather than logging their payload`() {
        assertEquals(1 shl 15, VoiceLifecycleDiagnostics.ruleMask(setOf("PRIVATE UTTERANCE")))
        assertEquals((1 shl 8) or (1 shl 9), VoiceLifecycleDiagnostics.ruleMask(setOf("playback_context", "grace_period_active")))
    }
}
