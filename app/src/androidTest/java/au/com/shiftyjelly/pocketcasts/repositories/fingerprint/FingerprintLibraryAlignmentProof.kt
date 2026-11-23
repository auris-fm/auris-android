package au.com.shiftyjelly.pocketcasts.repositories.fingerprint

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.fingerprint_uniffi.CheckpointMatcher
import uniffi.fingerprint_uniffi.StreamingWindowedFingerprinter

/**
 * On-device proof that the library shipped in this app produces the values the server's generator
 * produces, and that its matcher ranks them the way the acceptance rule was written against.
 *
 * The JVM suite cannot load the native library at all, so the rule is tested there over captured
 * ranked lists. This runs the real thing once, on an emulator, against the same fixtures. It is
 * evidence for a change, not a CI gate: it needs staged clips and a device.
 *
 * Stage the fixture directory into `filesDir/fingerprint_alignment/` before running:
 *   adb push <fixtures>/. /data/data/fm.auris/files/fingerprint_alignment/
 * Results are written to `fingerprint_alignment/device_result.json` for pulling back.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintLibraryAlignmentProof {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.filesDir, DIR_NAME)
        assertTrue(
            "fixtures not staged — adb push them into filesDir/$DIR_NAME/",
            dir.isDirectory && dir.listFiles()?.any { it.extension == "f32" } == true,
        )
    }

    /** Every value of every window, position by position, against the fixture the server also uses. */
    @Test
    fun theLibraryProducesTheFixturesValues() {
        val clips = dir.listFiles { f -> f.extension == "json" && f.name.startsWith("clip-") }!!
            .sortedBy { it.name }
        assertTrue("no clip fixtures staged", clips.isNotEmpty())

        val report = mutableListOf<JSONObject>()
        var compared = 0
        for (clipFile in clips) {
            val expected = JSONObject(clipFile.readText())
            val rate = expected.getInt("sample_rate")
            val channels = expected.getInt("channels")
            val audio = File(dir, expected.getString("audio"))
            assertEquals(
                "${clipFile.name}: the staged audio is not the fixture's audio",
                expected.getString("audio_sha256"),
                sha256(audio.readBytes()),
            )

            val samples = readFloatsLE(audio)
            val windows = fingerprint(samples, rate, channels)
            var clipCompared = 0
            val expectedWindows = expected.getJSONArray("windows")
            assertEquals("${clipFile.name}: window count", expectedWindows.length(), windows.size)

            for (i in 0 until expectedWindows.length()) {
                val want = expectedWindows.getJSONObject(i)
                val got = windows[i]
                assertEquals("${clipFile.name} window $i: timestamp", want.getInt("timestamp_ms").toUInt(), got.timestampMs)
                val values = want.getJSONArray("values")
                assertEquals("${clipFile.name} window $i: value count", values.length(), got.hashes.size)
                for (j in 0 until values.length()) {
                    // Position by position: these are a time-ordered sequence, never sorted.
                    assertEquals(
                        "${clipFile.name} window $i value $j",
                        values.getLong(j).toUInt(),
                        got.hashes[j],
                    )
                    compared++
                    clipCompared++
                }
            }
            report.add(
                JSONObject()
                    .put("clip", clipFile.nameWithoutExtension)
                    .put("sample_rate", rate)
                    .put("channels", channels)
                    .put("windows", windows.size)
                    .put("values_compared", clipCompared),
            )
            Log.i(TAG, "${clipFile.nameWithoutExtension}: ${windows.size} windows match at $rate Hz / $channels ch")
        }

        writeReport("values", JSONObject().put("clips", report).put("values_compared", compared))
        Log.i(TAG, "$compared values compared across ${clips.size} clips, all identical")
    }

    /**
     * The real matcher, on the real reference, reproduces the ranked lists the acceptance rule was
     * written against — and the rule gives each of them the captured verdict.
     *
     * This is the half that closes the gap between the JVM suite's captured lists and the library
     * this app actually ships. The inputs are staged beside the clips; they are not committed.
     */
    @Test
    fun theRealMatcherReproducesTheCapturedLists() {
        val inputsFile = File(dir, "matcher-top6-inputs.json")
        val casesFile = File(dir, "matcher-top6-cases.json")
        assertTrue("stage matcher-top6-inputs.json beside the clips", inputsFile.isFile)
        assertTrue("stage matcher-top6-cases.json beside the clips", casesFile.isFile)

        val inputs = JSONObject(inputsFile.readText())
        val reference = inputs.getJSONArray("reference")
        val inputCases = inputs.getJSONArray("cases")
        val expectedCases = JSONObject(casesFile.readText()).getJSONArray("cases")
        assertEquals("the two files must describe the same cases", inputCases.length(), expectedCases.length())

        val report = mutableListOf<JSONObject>()
        for (i in 0 until inputCases.length()) {
            val input = inputCases.getJSONObject(i)
            val expected = expectedCases.getJSONObject(i)
            val name = input.getString("name")
            assertEquals("$name: case order differs between the files", name, expected.getString("name"))

            val count = input.getInt("reference_checkpoints")
            val query = uintValues(input.getJSONArray("query_values"))
            val want = expected.getJSONArray("candidates")

            CheckpointMatcher().use { matcher ->
                for (c in 0 until count) {
                    val checkpoint = reference.getJSONObject(c)
                    matcher.add(
                        checkpoint.getDouble("timestamp_s").toFloat(),
                        uintValues(checkpoint.getJSONArray("values")),
                        WINDOW_SECONDS,
                    )
                }
                val ranked = matcher.findTopMatches(query, FingerprintConstants.REFERENCE_CANDIDATES.toUInt())

                assertEquals("$name: candidate count", want.length(), ranked.size)
                for (j in 0 until want.length()) {
                    val w = want.getJSONObject(j)
                    assertEquals("$name candidate $j: timestamp", w.getDouble("timestamp").toFloat(), ranked[j].timestamp, 0f)
                    assertEquals("$name candidate $j: score", w.getDouble("score").toFloat(), ranked[j].score, 0f)
                }

                // And the rule, on the library's live output rather than a captured copy of it.
                val verdict = ReferenceAcceptance.accept(ranked)
                assertEquals("$name: acceptance", expected.getBoolean("accept"), verdict != null)
                if (verdict != null) {
                    assertEquals("$name: accepted place", ranked.first().timestamp.toDouble(), verdict.referenceTimeSeconds, 0.0)
                }
                report.add(
                    JSONObject()
                        .put("name", name)
                        .put("reference_checkpoints", count)
                        .put("candidates", ranked.size)
                        .put("accepted", verdict != null)
                        .put("reason", expected.getString("reason")),
                )
            }
        }

        writeReport("ranked", JSONObject().put("cases", report))
        Log.i(TAG, "${inputCases.length()} ranked lists reproduced exactly, and every verdict agrees")
    }

    private fun uintValues(array: org.json.JSONArray): List<UInt> {
        val n = array.length()
        return (0 until n).map { i -> array.getLong(i).toUInt() }
    }

    private fun fingerprint(samples: FloatArray, rate: Int, channels: Int) = StreamingWindowedFingerprinter(
        rate.toUInt(),
        channels.toUShort(),
        FingerprintConstants.WINDOW_DURATION_MS.toUInt(),
        FingerprintConstants.WINDOW_INTERVAL_MS.toUInt(),
    ).use { streamer ->
        val out = mutableListOf<uniffi.fingerprint_uniffi.WindowedFingerprint>()
        // Pushed in blocks, as playback delivers them, rather than one call for the whole clip.
        val blockFrames = 4096
        val blockSamples = blockFrames * channels
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + blockSamples, samples.size)
            out += streamer.pushSamplesF32(samples.copyOfRange(offset, end).toList(), channels.toUShort())
            offset = end
        }
        out += streamer.flush()
        out
    }

    private fun readFloatsLE(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buffer.remaining() / 4) { buffer.getFloat() }
    }

    private fun writeReport(name: String, body: JSONObject) {
        File(dir, "device_result_$name.json").writeText(body.toString(2))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "FingerprintAlignment"
        const val DIR_NAME = "fingerprint_alignment"
        const val WINDOW_SECONDS = 8f
    }
}
