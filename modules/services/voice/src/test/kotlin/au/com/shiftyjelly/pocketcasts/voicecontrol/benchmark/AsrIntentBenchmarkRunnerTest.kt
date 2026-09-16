package au.com.shiftyjelly.pocketcasts.voicecontrol.benchmark

import androidx.test.core.app.ApplicationProvider
import au.com.shiftyjelly.pocketcasts.voicecontrol.asr.TranslationStage
import au.com.shiftyjelly.pocketcasts.voicecontrol.intent.lfm.RouterStageDiagnostic
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.TranslationKind
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizeResult
import au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognizer
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the Item 21 device latency driver: utterance parsing, iteration
 * discipline (warm-up excluded from measured stats), variant input shapes,
 * and the median/p95 aggregation reported in the decision pack.
 */
@RunWith(RobolectricTestRunner::class)
class AsrIntentBenchmarkRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTranslation : TranslationStage {
        override suspend fun ensureReady(sourceLanguage: String): Result<Unit> = Result.success(Unit)
        override suspend fun translate(text: String, sourceLanguage: String): Result<String> = Result.success("en:$text")
    }

    private class FakeRecognizer : VoiceRecognizer {
        var calls = 0
        var lastInput: au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput? = null
        private val base = 10L

        override suspend fun ensureReady(): Result<Unit> = Result.success(Unit)
        override suspend fun recognize(
            input: au.com.shiftyjelly.pocketcasts.voicecontrol.model.IntentRoutingInput,
            context: au.com.shiftyjelly.pocketcasts.voicecontrol.model.VoiceRecognitionContext,
        ): VoiceRecognizeResult {
            calls += 1
            lastInput = input
            val t = base + calls
            val diagnostic = RouterStageDiagnostic(
                modelRelease = "dual_v1-test",
                quant = null,
                inputFormat = "dual_v1",
                sourceLanguage = input.sourceLanguage,
                translationKind = input.translationKind.wireName,
                classifierLabel = "playback:seek_relative",
                finalOutcome = RouterStageDiagnostic.OUTCOME_INTENT,
                failedStage = null,
                reason = null,
                stageLatencyMs = mapOf(
                    RouterStageDiagnostic.STAGE_TOKENIZE to 1L,
                    RouterStageDiagnostic.STAGE_CLASSIFY to t,
                    RouterStageDiagnostic.STAGE_GENERATE to t * 2,
                ),
                totalLatencyMs = t * 3 + 1,
            )
            return VoiceRecognizeResult(intent = null, diagnostic = diagnostic)
        }

        override fun release() = Unit
    }

    private class FakeInstaller : BenchmarkModelInstaller {
        var installed: File? = null
        override suspend fun install(sourceDir: File) {
            installed = sourceDir
        }
        override fun currentFormat(): String = "dual_v1"
        override fun currentRelease(): String = "dual_v1-test"
    }

    private fun runner(recognizer: FakeRecognizer, installer: FakeInstaller) = AsrIntentBenchmarkRunner(
        translationStage = FakeTranslation(),
        voiceRecognizer = recognizer,
        modelManager = unusedModelManager(),
    ).apply { modelInstaller = installer }

    private fun unusedModelManager(): au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager = au.com.shiftyjelly.pocketcasts.voicecontrol.model.ModelManager(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `parses jsonl utterances with defaults`() = runTest {
        val runner = runner(FakeRecognizer(), FakeInstaller())
        val f = tmp.newFile("utterances.jsonl")
        f.writeText(
            """
            {"case_id":"c1","language":"zh","text":"快进两分钟"}
            {"language":"en","text":"play"}
            """.trimIndent(),
        )
        val list = runner.loadUtterances(f)
        assertEquals(2, list.size)
        assertEquals("c1", list[0].caseId)
        assertEquals("zh", list[0].language)
        assertEquals("case_1", list[1].caseId)
        assertEquals("play", list[1].text)
    }

    @Test
    fun `runVariant honors warmup vs measured iteration discipline`() = runTest {
        val recognizer = FakeRecognizer()
        val installer = FakeInstaller()
        val runner = runner(recognizer, installer)
        val report = runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_B,
            modelSourceDir = tmp.newFolder("dual_v1"),
            utterances = listOf(AsrIntentBenchmarkRunner.Utterance("c1", "zh", "快进两分钟")),
            warmupIterations = 2,
            measuredIterations = 5,
        )
        // 1 warm-up + 1 measured utterance-pass per iteration = 7 recognize calls total.
        assertEquals(7, recognizer.calls)
        assertEquals(2, report.warmupIterations)
        assertEquals(5, report.measuredIterations)
        val case = report.cases.single()
        assertEquals(5, case.totalMs.size)
        assertEquals(5, case.translateMs.size)
        assertEquals(5, case.stageLatencyMs[RouterStageDiagnostic.STAGE_CLASSIFY]!!.size)
        assertTrue(case.totalMs.all { it > 0 })
    }

    @Test
    fun `variant b carries native and english transcripts with platform translation`() = runTest {
        val recognizer = FakeRecognizer()
        val runner = runner(recognizer, FakeInstaller())
        runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_B,
            modelSourceDir = tmp.newFolder("dual_v1"),
            utterances = listOf(AsrIntentBenchmarkRunner.Utterance("c1", "zh", "暂停")),
            warmupIterations = 0,
            measuredIterations = 1,
        )
        val input = recognizer.lastInput!!
        assertEquals("暂停", input.sourceTranscript)
        assertEquals("en:暂停", input.routerTranscript)
        assertEquals(TranslationKind.PLATFORM, input.translationKind)
        assertEquals("zh", input.sourceLanguage)
    }

    @Test
    fun `variant a router transcript is the english translation`() = runTest {
        val recognizer = FakeRecognizer()
        val runner = runner(recognizer, FakeInstaller())
        runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_A,
            modelSourceDir = tmp.newFolder("english_v1"),
            utterances = listOf(AsrIntentBenchmarkRunner.Utterance("c1", "zh", "暂停")),
            warmupIterations = 0,
            measuredIterations = 1,
        )
        val input = recognizer.lastInput!!
        assertEquals("en:暂停", input.routerTranscript)
    }

    @Test
    fun `median and p95 aggregation`() {
        assertEquals(3L, AsrIntentBenchmarkRunner.median(listOf(1, 2, 3, 4, 5)))
        assertEquals(3L, AsrIntentBenchmarkRunner.median(listOf(2, 4))) // even → midpoint
        assertEquals(10L, AsrIntentBenchmarkRunner.percentile95(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))) // nearest-rank: ceil(9.5)=10th
        assertEquals(10L, AsrIntentBenchmarkRunner.percentile95(listOf(10)))
    }

    @Test
    fun `skipCaseIds resumes past already-measured cases`() = runTest {
        val recognizer = FakeRecognizer()
        val runner = runner(recognizer, FakeInstaller())
        val utterances = listOf(
            AsrIntentBenchmarkRunner.Utterance("c1", "zh", "快进两分钟"),
            AsrIntentBenchmarkRunner.Utterance("c2", "en", "play"),
        )
        val report = runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_B,
            modelSourceDir = tmp.newFolder("dual_v1"),
            utterances = utterances,
            warmupIterations = 0,
            measuredIterations = 1,
            skipCaseIds = setOf("c1"),
        )
        assertEquals(listOf("c2"), report.cases.map { it.caseId })
        assertEquals(1, recognizer.calls)
    }

    @Test
    fun `onCaseResult fires once per measured case`() = runTest {
        val recognizer = FakeRecognizer()
        val runner = runner(recognizer, FakeInstaller())
        val seen = mutableListOf<String>()
        runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_B,
            modelSourceDir = tmp.newFolder("dual_v1"),
            utterances = listOf(
                AsrIntentBenchmarkRunner.Utterance("c1", "en", "play"),
                AsrIntentBenchmarkRunner.Utterance("c2", "en", "pause"),
            ),
            warmupIterations = 0,
            measuredIterations = 1,
            onCaseResult = { case, _, _ -> seen += case.caseId },
        )
        assertEquals(listOf("c1", "c2"), seen)
    }

    @Test
    fun `installer receives the variant model source dir`() = runTest {
        val dir = tmp.newFolder("dual_v1")
        val installer = FakeInstaller()
        val runner = runner(FakeRecognizer(), installer)
        runner.runVariant(
            variant = AsrIntentBenchmarkRunner.VARIANT_B,
            modelSourceDir = dir,
            utterances = listOf(AsrIntentBenchmarkRunner.Utterance("c1", "en", "play")),
            warmupIterations = 0,
            measuredIterations = 1,
        )
        assertEquals(dir, installer.installed)
    }
}
