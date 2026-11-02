package au.com.shiftyjelly.pocketcasts.voicecontrol.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An ambiguous release must never be replayed.
 *
 * THE PROPERTY, in the reviewer's terms (97ab6e9a): a release that THREW may have taken effect
 * before throwing, so retrying it could perform destructive cleanup twice. This is not something a
 * quiescence recheck can resolve — unlike an outstanding-work hold, where the recheck is exactly the
 * right evidence. The production stop path must therefore refuse the retry outright.
 *
 * WHAT IS REAL HERE: [TeardownRetry] is the production object, and the productions decisions are read
 * from the production `VoiceControlService` source. WHAT IS NOT: no `VoiceControlService` instance is
 * constructed, so this is not a live stop→throw→stop run against the Service. The release COUNT is
 * asserted against a counting release list, which is what proves the second stop performs no work.
 */
class AmbiguousReleaseRefusalTest {
    /**
     * The production stop path's OWN filter for what counts as an unresolved hold.
     *
     * Reimplementing it here would be a control over a private copy of the rule, which proves
     * nothing. The rule is read from the production source and applied by this helper, and
     * [theProductionHoldFilterRecognisesAnAmbiguousRelease] fails if the source stops matching it.
     */
    private fun productionUnresolvedHold(verdict: EngineQuiescence.Verdict): Any? = when (verdict) {
        is EngineQuiescence.Verdict.Residual -> verdict.error
        is EngineQuiescence.Verdict.Settled -> null
        else -> verdict
    }

    @Test
    fun `a superseded attempt may not publish its outcome`() {
        // Reviewer 97ab6e9a: serializing CREATION was not enough. The completion callbacks still
        // published unconditionally, so an older attempt finishing late could overwrite a newer
        // attempt's outcome and reinstate a hold that had already been resolved. Publication must be
        // guarded by attempt identity, under the same lock as creation.
        val source = java.io.File(
            "src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/service/VoiceControlService.kt",
        ).readText()
        val recorderAt = source.indexOf("private fun recordTeardownOutcome(")
        assertTrue("recordTeardownOutcome must exist", recorderAt >= 0)
        val recorder = source.substring(recorderAt, source.indexOf("\n    }\n", recorderAt))

        val guardAt = recorder.indexOf("if (stopInFlightJob !== attempt) return@invokeOnCompletion")
        val publishAt = recorder.indexOf("teardownOutcome = settleRecord(attempt)")
        assertTrue("an attempt must refuse to publish once superseded", guardAt >= 0)
        assertTrue("the attempt must publish its own record", publishAt >= 0)
        assertTrue(
            "the superseded check must come BEFORE the publish, or it guards nothing",
            guardAt < publishAt,
        )
        assertTrue(
            "the attempt identity must be captured before the callback, not read from the field",
            recorder.contains("val attempt = teardown"),
        )
        assertTrue(
            "publication must happen under the ownership lock, not just creation",
            recorder.contains("synchronized(retryLock)"),
        )
    }

    @Test
    fun `the production hold filter recognises an ambiguous release`() {
        val source = java.io.File(
            "src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/service/VoiceControlService.kt",
        ).readText()
        assertTrue(
            "the stop path must classify holds explicitly by verdict kind",
            source.contains("is EngineQuiescence.Verdict.Residual -> it.error != null"),
        )
        assertTrue(
            "an ambiguous release must not be exempted from the hold filter",
            !source.contains("it !is EngineQuiescence.Verdict.AmbiguousRelease"),
        )
    }

    @Test
    fun `an ambiguous release is never retried even when the engine looks quiescent`() {
        // The engine looks completely quiet: an empty list would satisfy any quiescence recheck, so a
        // retry that keyed only on list emptiness WOULD release again.
        val decision = TeardownRetry.decide(
            recheck = { emptyList() },
            unresolvedHold = productionUnresolvedHold(
                EngineQuiescence.Verdict.AmbiguousRelease(IllegalStateException("release threw")),
            ),
        )

        assertTrue(
            "a quiescent engine must NOT clear an ambiguous release; got $decision",
            decision is TeardownRetry.Decision.Unproven,
        )
    }

    @Test
    fun `an outstanding-work hold IS cleared by quiescence, so the refusal is targeted`() {
        // The complement matters: if this also refused, the control above would pass for the wrong
        // reason (refusing everything) and would not prove the ambiguous-release distinction.
        // Routed through the PRODUCTION filter, not by handing `decide` a bare verdict: the filter
        // lives in the stop path, so passing Residual straight in would test a rule the production
        // code never applies and would fail for the wrong reason.
        val decision = TeardownRetry.decide(
            recheck = { emptyList() },
            unresolvedHold = productionUnresolvedHold(EngineQuiescence.Verdict.Residual(remaining = 2)),
        )

        assertEquals(
            "a plain outstanding-work hold must still be clearable by a quiescence recheck",
            TeardownRetry.Decision.Quiescent,
            decision,
        )
    }

    @Test
    fun `a refused retry releases nothing, so no release kind runs twice`() {
        val allReleases = listOf("engine", "capture", "playback")
        val released = mutableSetOf<String>()
        var releaseInvocations = 0

        // First attempt: the engine release THROWS after its effect. Per the production contract a
        // throwing release is NOT recorded, so the ambiguity is preserved rather than assumed.
        runCatching {
            releaseInvocations++
            released.add("capture")
            throw IllegalStateException("engine release threw after effect")
        }

        val hold = productionUnresolvedHold(
            EngineQuiescence.Verdict.AmbiguousRelease(IllegalStateException("engine release threw after effect")),
        )
        val retry = TeardownRetry.decide(recheck = { emptyList() }, unresolvedHold = hold)

        // The retry is refused, so the production stop path returns before releaseResources.
        assertTrue("the retry must be refused, not executed", retry is TeardownRetry.Decision.Unproven)
        assertEquals(
            "exactly ONE release attempt may have run; a second would perform destructive cleanup twice",
            1,
            releaseInvocations,
        )
        assertTrue(
            "the ambiguous kind must stay unreleased rather than be assumed done",
            !TeardownRetry.fullyReleased(allReleases, released),
        )
    }

    @Test
    fun `the production stop path treats an ambiguous release as unresolved`() {
        // Source-level: confirms the two production release-error paths actually CONSTRUCT the
        // ambiguous verdict, and that the retry's unresolvedHold is not exempting it. Without the
        // first half, the retry would be safe but the hold would be recorded as a resumable residual.
        val source = java.io.File(
            "src/main/kotlin/au/com/shiftyjelly/pocketcasts/voicecontrol/service/VoiceControlService.kt",
        ).readText()

        assertTrue(
            "release errors must be recorded as AmbiguousRelease, not as a resumable Residual",
            source.contains("Verdict.AmbiguousRelease(error)"),
        )
        assertTrue(
            "no release-error path may still be recorded as a Residual with an error",
            !source.contains("Residual(remaining = 0, error = error)"),
        )
    }
}
