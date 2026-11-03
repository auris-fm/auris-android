package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.policy

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy-isolation control for the named secondary Firebase app (spec 51500e55, correction
 * 172783bb): the Voice Sharing policy must read project `auris-fm` through its OWN client
 * configuration, while the default app keeps serving every unrelated consumer.
 *
 * The JVM cannot initialize FirebaseApp, so these checks read the shipped artifacts directly:
 * the raw resource the module parses, and the variant google-services.json that configures the
 * default app. Asserting the two differ is the isolation claim; asserting the resource's
 * identity is the "this is the dedicated project" claim.
 */
class VoiceSharingFirebaseConfigResourceTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/services/localization/src/main/res/values/strings.xml").exists()) {
            dir = dir.parentFile
        }
        return checkNotNull(dir) { "could not locate the repo root" }
    }

    private fun voiceSharingConfig(): JSONObject {
        val file = File(
            repoRoot(),
            "modules/services/voice/src/main/res/raw/voice_sharing_firebase_config.json",
        )
        return JSONObject(file.readText())
    }

    @Test
    fun `the secondary config is the dedicated auris-fm project`() {
        val json = voiceSharingConfig()
        assertEquals("auris-fm", json.getJSONObject("project_info").getString("project_id"))
        val client = json.getJSONArray("client").getJSONObject(0)
        assertEquals("fm.auris.debug", client.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name"))
        assertTrue(
            "mobilesdk_app_id must be present: the module builds FirebaseOptions from it",
            client.getJSONObject("client_info").has("mobilesdk_app_id"),
        )
        assertTrue(
            "an api key must be present: FirebaseOptions requires one",
            client.getJSONArray("api_key").getJSONObject(0).has("current_key"),
        )
    }

    @Test
    fun `the secondary config differs from the default app's google-services config`() {
        val secondary = voiceSharingConfig()
        val default = JSONObject(
            File(repoRoot(), "app/src/debug/google-services.json").readText(),
        )
        val secondaryAppId = secondary.getJSONArray("client").getJSONObject(0)
            .getJSONObject("client_info").getString("mobilesdk_app_id")
        val defaultAppId = default.getJSONArray("client").getJSONObject(0)
            .getJSONObject("client_info").getString("mobilesdk_app_id")
        assertNotEquals(
            "the secondary app must not be the default app's registration; sharing it would " +
                "move unrelated consumers onto the Voice Sharing project",
            defaultAppId,
            secondaryAppId,
        )
        assertNotEquals(
            "the secondary project must not be the default project",
            default.getJSONObject("project_info").getString("project_id"),
            secondary.getJSONObject("project_info").getString("project_id"),
        )
    }
}
