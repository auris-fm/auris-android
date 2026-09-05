package au.com.shiftyjelly.pocketcasts.voicecontrol.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelManagerLfmTest {
    @get:Rule val tempDir = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun parseLfmManifest_requiresExactlyGgufClassifierAndLabelMap() {
        val manifest =
            """
            {
              "version": "2026-06-21-143005",
              "source_commit": "abc123",
              "assets": {
                "model.gguf": {
                  "bytes": 5,
                  "sha256": "gguf-sha",
                  "content_type": "application/octet-stream",
                  "url": "https://download.auris.fm/function-call/2026-06-21-143005/model.gguf"
                },
                "classifier.bin": {
                  "bytes": 5,
                  "sha256": "cls-sha",
                  "content_type": "application/octet-stream",
                  "url": "https://download.auris.fm/function-call/2026-06-21-143005/classifier.bin"
                },
                "label_map.json": {
                  "bytes": 5,
                  "sha256": "map-sha",
                  "content_type": "application/json",
                  "url": "https://download.auris.fm/function-call/2026-06-21-143005/label_map.json"
                }
              }
            }
            """.trimIndent()

        val release = parseLfmManifest(manifest)

        assertEquals("2026-06-21-143005", release.version)
        assertEquals(RouterInputFormat.EnglishV1, release.routerInputFormat)
        assertNull(release.quant)
        assertEquals(
            listOf("model.gguf", "classifier.bin", "label_map.json"),
            release.requiredAssets.map { it.name },
        )
    }

    @Test
    fun parseLfmManifest_missingRouterInputFormatDefaultsToEnglishV1() {
        val release = parseLfmManifest(manifestFor(ggufBytes = 5, ggufSha = "a", classifierBytes = 5, classifierSha = "b", labelMapBytes = 5, labelMapSha = "c"))
        assertEquals(RouterInputFormat.EnglishV1, release.routerInputFormat)
    }

    @Test
    fun parseLfmManifest_explicitEnglishV1AndQuant() {
        val release = parseLfmManifest(
            manifestFor(
                ggufBytes = 5,
                ggufSha = "a",
                classifierBytes = 5,
                classifierSha = "b",
                labelMapBytes = 5,
                labelMapSha = "c",
                routerInputFormat = "english_v1",
                quant = "q8_0",
            ),
        )
        assertEquals(RouterInputFormat.EnglishV1, release.routerInputFormat)
        assertEquals("q8_0", release.quant)
    }

    @Test
    fun parseLfmManifest_unknownRouterInputFormatIsTyped() {
        val release = parseLfmManifest(
            manifestFor(
                ggufBytes = 5,
                ggufSha = "a",
                classifierBytes = 5,
                classifierSha = "b",
                labelMapBytes = 5,
                labelMapSha = "c",
                routerInputFormat = "future_v9",
            ),
        )
        assertEquals(RouterInputFormat.Unknown("future_v9"), release.routerInputFormat)
        assertFalse(release.routerInputFormat.isReadyForInference)
    }

    @Test
    fun parseLfmManifest_sourceAndDualFormatsAreKnownButNotReady() {
        assertEquals(
            RouterInputFormat.SourceV1,
            parseLfmManifest(
                manifestFor(5, "a", 5, "b", 5, "c", routerInputFormat = "source_v1"),
            ).routerInputFormat,
        )
        assertFalse(RouterInputFormat.SourceV1.isReadyForInference)
        assertFalse(RouterInputFormat.DualV1.isReadyForInference)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseLfmManifest_rejectsFunctionGemmaManifest() {
        parseLfmManifest(
            """
            {
              "version": "2026-06-21-143005",
              "assets": {
                "model.litertlm": {
                  "bytes": 5,
                  "sha256": "model-sha",
                  "url": "https://download.auris.fm/function-call/model.litertlm"
                },
                "model.litertlm.xnnpack_cache_123": {
                  "bytes": 5,
                  "sha256": "cache-sha",
                  "url": "https://download.auris.fm/function-call/model.litertlm.xnnpack_cache_123"
                }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun lfmIsNotReadyWhenDownloadedAssetIsPartial() {
        val modelDir = File(tempDir.root, "function-call").apply { mkdirs() }
        File(modelDir, "model.gguf").writeText("gguf")
        File(modelDir, "classifier.bin").writeText("cls")
        File(modelDir, "label_map.json").writeText("bad")
        File(modelDir, "manifest.json").writeText(
            manifestFor(
                ggufBytes = 4,
                ggufSha = sha256("gguf"),
                classifierBytes = 3,
                classifierSha = sha256("cls"),
                labelMapBytes = 5,
                labelMapSha = sha256("label"),
            ),
        )
        val manager = ModelManager(context).apply { filesDir = tempDir.root }

        assertFalse(manager.isLfmModelReady())
    }

    @Test
    fun lfmIsReadyWhenRequiredDownloadedAssetsMatchManifest() {
        val modelDir = File(tempDir.root, "function-call").apply { mkdirs() }
        File(modelDir, "model.gguf").writeText("gguf")
        File(modelDir, "classifier.bin").writeText("cls")
        File(modelDir, "label_map.json").writeText("label")
        File(modelDir, "manifest.json").writeText(
            manifestFor(
                ggufBytes = 4,
                ggufSha = sha256("gguf"),
                classifierBytes = 3,
                classifierSha = sha256("cls"),
                labelMapBytes = 5,
                labelMapSha = sha256("label"),
            ),
        )
        val manager = ModelManager(context).apply { filesDir = tempDir.root }

        assertTrue(manager.isLfmModelReady())
    }

    @Test
    fun lfmReleaseVersionIsReadFromInstalledManifest() {
        val modelDir = File(tempDir.root, "function-call").apply { mkdirs() }
        File(modelDir, "manifest.json").writeText(
            manifestFor(
                ggufBytes = 4,
                ggufSha = sha256("gguf"),
                classifierBytes = 3,
                classifierSha = sha256("cls"),
                labelMapBytes = 5,
                labelMapSha = sha256("label"),
            ),
        )
        val manager = ModelManager(context).apply { filesDir = tempDir.root }

        assertEquals("2026-06-21-143005", manager.lfmReleaseVersion())
    }

    @Test
    fun lfmIsNotReadyWhenRouterInputFormatIsUnknown() {
        val modelDir = File(tempDir.root, "function-call").apply { mkdirs() }
        File(modelDir, "model.gguf").writeText("gguf")
        File(modelDir, "classifier.bin").writeText("cls")
        File(modelDir, "label_map.json").writeText("label")
        File(modelDir, "manifest.json").writeText(
            manifestFor(
                ggufBytes = 4,
                ggufSha = sha256("gguf"),
                classifierBytes = 3,
                classifierSha = sha256("cls"),
                labelMapBytes = 5,
                labelMapSha = sha256("label"),
                routerInputFormat = "future_v9",
            ),
        )
        val manager = ModelManager(context).apply { filesDir = tempDir.root }

        assertFalse(manager.isLfmModelReady())
        assertEquals(RouterInputFormat.Unknown("future_v9"), manager.lfmRouterInputFormat())
    }

    @Test
    fun lfmReleaseExposesParsedFormatAndQuant() {
        val modelDir = File(tempDir.root, "function-call").apply { mkdirs() }
        File(modelDir, "manifest.json").writeText(
            manifestFor(
                ggufBytes = 4,
                ggufSha = sha256("gguf"),
                classifierBytes = 3,
                classifierSha = sha256("cls"),
                labelMapBytes = 5,
                labelMapSha = sha256("label"),
                routerInputFormat = "english_v1",
                quant = "q8_0",
            ),
        )
        val manager = ModelManager(context).apply { filesDir = tempDir.root }

        assertEquals("2026-06-21-143005", manager.lfmReleaseVersion())
        assertEquals(RouterInputFormat.EnglishV1, manager.lfmRouterInputFormat())
        assertEquals("q8_0", manager.lfmRelease()?.quant)
    }

    private fun manifestFor(
        ggufBytes: Int,
        ggufSha: String,
        classifierBytes: Int,
        classifierSha: String,
        labelMapBytes: Int,
        labelMapSha: String,
        routerInputFormat: String? = null,
        quant: String? = null,
    ): String {
        val formatLine = routerInputFormat?.let { """"router_input_format": "$it",""" } ?: ""
        val quantLine = quant?.let { """"quant": "$it",""" } ?: ""
        return """
        {
          "version": "2026-06-21-143005",
          "source_commit": "abc123",
          $quantLine
          $formatLine
          "assets": {
            "model.gguf": {
              "bytes": $ggufBytes,
              "sha256": "$ggufSha",
              "content_type": "application/octet-stream",
              "url": "https://example.test/model.gguf"
            },
            "classifier.bin": {
              "bytes": $classifierBytes,
              "sha256": "$classifierSha",
              "content_type": "application/octet-stream",
              "url": "https://example.test/classifier.bin"
            },
            "label_map.json": {
              "bytes": $labelMapBytes,
              "sha256": "$labelMapSha",
              "content_type": "application/json",
              "url": "https://example.test/label_map.json"
            }
          }
        }
        """.trimIndent()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
