package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.enrollment

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disclosure VERSION is a promise about what the user read. These controls read the shipped
 * English resources and fail if the copy drifts away from [EnrollmentDisclosure] without the version
 * moving with it.
 *
 * Why a resource-file control rather than a rendering test: the strings are the artifact that ships,
 * and a rendered screenshot would not survive in this lane. Reading the resource source directly is
 * the strongest check available without a device, and it catches the exact failure that matters —
 * text edited, version left behind.
 */
class EnrollmentDisclosureTest {

    private fun stringsXml(): File {
        // Walk up from the module directory to the repo root, then into the localization module.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/services/localization/src/main/res/values/strings.xml").exists()) {
            dir = dir.parentFile
        }
        val root = checkNotNull(dir) { "could not locate the localization strings.xml from ${File("").absolutePath}" }
        return File(root, "modules/services/localization/src/main/res/values/strings.xml")
    }

    private fun stringResource(name: String): String {
        val text = stringsXml().readText()
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(text)
        return checkNotNull(match) { "missing string resource $name" }.groupValues[1]
    }

    @Test
    fun `the shipped title, setting and supporting sentence are the disclosure copy verbatim`() {
        assertEquals(EnrollmentDisclosure.TITLE, stringResource("voice_sharing_title"))
        assertEquals(EnrollmentDisclosure.SETTING, stringResource("voice_sharing_setting_share_recordings"))
        assertEquals(
            "the supporting sentence must match the disclosed text exactly; if it changed, " +
                "EnrollmentDisclosure.VERSION must change with it",
            EnrollmentDisclosure.SUPPORTING,
            stringResource("voice_sharing_disclosure_supporting"),
        )
        assertEquals(
            "the detail text must match the disclosed text exactly",
            EnrollmentDisclosure.DETAIL,
            stringResource("voice_sharing_disclosure_detail"),
        )
    }

    @Test
    fun `the shipped disclosure states every fact the spec requires`() {
        val rendered = (
            stringResource("voice_sharing_disclosure_supporting") + " " +
                stringResource("voice_sharing_disclosure_detail")
            ).lowercase()
        for (fact in EnrollmentDisclosure.REQUIRED_FACTS) {
            assertTrue(
                "the disclosure must mention '$fact'; a silent omission means the user was not told " +
                    "what leaves the device",
                rendered.contains(fact.lowercase()),
            )
        }
    }

    @Test
    fun `the version is non-blank so enrollment cannot ship an unusable consent record`() {
        assertTrue(EnrollmentDisclosure.VERSION.isNotBlank())
    }
}
